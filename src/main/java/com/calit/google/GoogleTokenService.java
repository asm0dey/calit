package com.calit.google;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Handles the owner OAuth flow: builds the consent URL, exchanges the auth code for tokens
 * (persisting the offline refresh token once), and always returns a valid access token,
 * refreshing via the refresh token when the cached one has expired.
 */
@ApplicationScoped
public class GoogleTokenService {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** A minted CSRF state is only accepted back within this window. */
    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final String HMAC_ALGO = "HmacSHA256";

    protected final GoogleOAuthConfig config;

    @Inject
    public GoogleTokenService(GoogleOAuthConfig config) {
        this.config = config;
    }

    /** Normalized token data, independent of the Google client types (keeps the test seam clean). */
    public record TokenResponse(String accessToken, String refreshToken, Instant expiry) {}

    /** The Google consent URL the owner is redirected to. Pure string building — no network. */
    public String buildConsentUrl() {
        return buildConsentUrl(Instant.now());
    }

    /**
     * The Google consent URL, carrying a stateless, signed CSRF {@code state}. Pure string building.
     *
     * <p>Horizontal scalability: {@code /connect} and {@code /callback} can be served by different
     * replicas, so the state must be self-describing — no HttpSession. The state is
     * {@code base64url(nonce:issuedAtEpochSec) + "." + base64url(HMAC-SHA256(...))} signed with the
     * shared {@code google.oauth.state-secret}. Any replica validates it at {@code /callback} by
     * recomputing the HMAC and checking the {@link #STATE_TTL} window — no shared mutable state.
     */
    public String buildConsentUrl(Instant now) {
        return AUTH_ENDPOINT + "?"
                + "client_id=" + enc(config.oauth().clientId())
                + "&redirect_uri=" + enc(config.oauth().redirectUri())
                + "&response_type=code"
                + "&scope=" + enc(config.oauth().scope())
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + enc(issueState(now));
    }

    /** Mint a signed, time-stamped state value. Stateless: nothing is stored server-side. */
    public String issueState(Instant now) {
        String payload = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                + ":" + now.getEpochSecond();
        return payload + "." + b64(hmac(payload));
    }

    /**
     * Validate a state returned on the callback: signature must verify and the issue time must be
     * within {@link #STATE_TTL} of {@code now}. No server-side session or lock — any replica can do
     * this using only the shared secret. Returns false for any malformed, forged, or expired value.
     */
    public boolean validateState(String state, Instant now) {
        if (state == null || state.isBlank()) {
            return false;
        }
        int dot = state.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String payload = state.substring(0, dot);
        String sig = state.substring(dot + 1);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            return false;
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        try {
            long issuedAt = Long.parseLong(payload.substring(colon + 1));
            Instant issued = Instant.ofEpochSecond(issuedAt);
            return !issued.isAfter(now) && !issued.plus(STATE_TTL).isBefore(now);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    config.oauth().stateSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign OAuth state", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Exchange the callback {@code code} for tokens and persist this owner's credential. */
    @Transactional
    public void exchangeCode(Long ownerId, String code, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        GoogleCredential c = GoogleCredential.forOwner(ownerId);
        if (c == null) {
            c = new GoogleCredential();
            c.ownerId = ownerId;
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.persist();
    }

    /**
     * Return a currently-valid access token, refreshing and persisting a new one when the
     * cached token is missing or within the safety margin of expiry.
     *
     * <p>Horizontal scalability: state lives in shared Postgres, never in instance memory.
     * Each call re-reads the singleton {@link GoogleCredential}; a refresh writes the new
     * access token + expiry (and any rotated refresh token) back to that row in this same
     * transaction so other replicas pick it up on their next read. A concurrent refresh from
     * another replica is safe — last write wins and either fresh token is valid until its own
     * expiry — so no distributed lock is needed.
     */
    @Transactional
    public String validAccessToken(Long ownerId, Instant now) {
        GoogleCredential c = GoogleCredential.forOwner(ownerId);
        if (c == null) {
            throw new IllegalStateException(
                    "Google is not connected for owner " + ownerId + ". Run /api/google/connect.");
        }
        if (!c.isAccessTokenExpired(now)) {
            return c.accessToken;
        }
        TokenResponse resp = requestToken("refresh_token", c.refreshToken, now);
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        // Google only returns a new refresh token if it rotated the old one; persist it when present.
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        // Write the refreshed token back to the shared row so every replica benefits; no node-local cache.
        c.persist();
        return c.accessToken;
    }

    /**
     * The single network round-trip. Overridable so tests can stub it without touching Google.
     *
     * @param grantType            "authorization_code" or "refresh_token"
     * @param codeOrRefreshToken   the auth code (for exchange) or the refresh token (for refresh)
     */
    protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
        NetHttpTransport transport = new NetHttpTransport();
        GsonFactory json = GsonFactory.getDefaultInstance();
        try {
            if ("authorization_code".equals(grantType)) {
                var resp = new GoogleAuthorizationCodeTokenRequest(
                        transport, json, TOKEN_ENDPOINT,
                        config.oauth().clientId(), config.oauth().clientSecret(),
                        codeOrRefreshToken, config.oauth().redirectUri())
                        .execute();
                return new TokenResponse(
                        resp.getAccessToken(),
                        resp.getRefreshToken(),
                        now.plusSeconds(resp.getExpiresInSeconds()));
            }
            var resp = new GoogleRefreshTokenRequest(
                    transport, json, codeOrRefreshToken,
                    config.oauth().clientId(), config.oauth().clientSecret())
                    .execute();
            return new TokenResponse(
                    resp.getAccessToken(),
                    resp.getRefreshToken(),
                    now.plusSeconds(resp.getExpiresInSeconds()));
        } catch (TokenResponseException e) {
            throw new IllegalStateException("Google token request failed: " + e.getStatusCode()
                    + " " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Google token request I/O error", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
