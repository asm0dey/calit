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

    /**
     * Normalized token data, independent of the Google client types (keeps the test seam clean).
     * {@code googleSub} is extracted from the id_token during authorization_code exchange (Task 2);
     * it is null for refresh-token responses and for the legacy stub constructor.
     */
    public record TokenResponse(String accessToken, String refreshToken, Instant expiry, String googleSub) {
        /** Convenience constructor for tests and refresh responses that have no id_token. */
        public TokenResponse(String accessToken, String refreshToken, Instant expiry) {
            this(accessToken, refreshToken, expiry, null);
        }
    }

    /**
     * The Google consent URL, carrying a stateless, signed CSRF state bound to {@code ownerId}.
     * Layout: b64(nonce) ":" ownerId ":" issuedAtEpochSec "." b64(HMAC-SHA256(payload)), signed with
     * the shared google.oauth.state-secret. Any replica validates it at /callback by recomputing the
     * HMAC and checking the STATE_TTL window — no HttpSession. Pure string building, no network.
     */
    public String buildConsentUrl(long ownerId, Instant now) {
        return AUTH_ENDPOINT + "?"
                + "client_id=" + enc(config.oauth().clientId())
                + "&redirect_uri=" + enc(config.oauth().redirectUri())
                + "&response_type=code"
                + "&scope=" + enc(config.oauth().scope())
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + enc(issueState(ownerId, now));
    }

    /** Mint a signed, time-stamped state bound to {@code ownerId}. Stateless: nothing stored. */
    public String issueState(long ownerId, Instant now) {
        String payload = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                + ":" + ownerId
                + ":" + now.getEpochSecond();
        return payload + "." + b64(hmac(payload));
    }

    /**
     * Validate a callback state and recover the owner id it was issued for. Signature must verify and
     * the issue time must be within STATE_TTL of {@code now}. Returns {@code null} for any malformed,
     * forged, or expired value. No server-side session — any replica validates with only the secret.
     */
    public Long validateState(String state, Instant now) {
        if (state == null || state.isBlank()) return null;
        int dot = state.lastIndexOf('.');
        if (dot <= 0) return null;
        String payload = state.substring(0, dot);
        String sig = state.substring(dot + 1);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!java.security.MessageDigest.isEqual(expected, actual)) return null;
        // payload = b64(nonce) ":" ownerId ":" issuedAtEpochSec
        // The nonce is base64url (alphabet A-Za-z0-9-_, no ':'), so the last two colons are always
        // the field delimiters — walk them right-to-left.
        int lastColon = payload.lastIndexOf(':');
        if (lastColon <= 0) return null;
        int prevColon = payload.lastIndexOf(':', lastColon - 1);
        if (prevColon <= 0) return null;
        try {
            long ownerId = Long.parseLong(payload.substring(prevColon + 1, lastColon));
            long issuedAt = Long.parseLong(payload.substring(lastColon + 1));
            Instant issued = Instant.ofEpochSecond(issuedAt);
            if (issued.isAfter(now) || issued.plus(STATE_TTL).isBefore(now)) return null;
            return ownerId;
        } catch (NumberFormatException e) {
            return null;
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
        // TODO(Task 2): parse id_token to extract real google_sub + account_email from the response.
        // For now use a stub sub so the NOT NULL constraint is satisfied; Task 2 replaces this with
        // the real sub extracted from the id_token returned during authorization_code exchange.
        String googleSub = resp.googleSub() != null ? resp.googleSub()
                : "legacy-owner-" + ownerId;
        GoogleCredential c = GoogleCredential.findByOwnerAndSub(ownerId, googleSub);
        if (c == null) {
            c = GoogleCredential.forOwner(ownerId);  // fall back for migration compatibility
        }
        if (c == null) {
            c = new GoogleCredential();
            c.ownerId = ownerId;
            c.googleSub = googleSub;
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
