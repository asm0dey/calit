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
     * {@code googleSub} and {@code accountEmail} are extracted from the id_token during the
     * authorization_code exchange (Task 2); both are null for refresh-token responses.
     */
    public record TokenResponse(String accessToken, String refreshToken, Instant expiry,
                                String googleSub, String accountEmail) {}

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

    /**
     * Exchange the callback {@code code} for tokens and persist this owner's credential for the
     * Google account identified by the id_token's {@code sub}. Reconnecting the same account
     * upserts the existing row (dedupe by {@code (owner_id, sub)}) rather than creating a duplicate.
     */
    @Transactional
    public void exchangeCode(Long ownerId, String code, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        if (resp.googleSub() == null) {
            throw new IllegalStateException("Google id_token missing 'sub'; check the openid scope.");
        }
        GoogleCredential c = GoogleCredential.findByOwnerAndSub(ownerId, resp.googleSub());
        if (c == null) {
            c = new GoogleCredential();
            c.ownerId = ownerId;
            c.googleSub = resp.googleSub();
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        if (resp.accountEmail() != null) {
            c.accountEmail = resp.accountEmail();
        }
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.needsReconnect = false;
        c.persist();
    }

    /**
     * Return a currently-valid access token for a specific {@link GoogleCredential}, refreshing and
     * persisting a new one when the cached token is missing or within the safety margin of expiry.
     *
     * <p>Fail-soft: when the refresh fails (token revoked/expired) the credential is flagged
     * {@code needsReconnect} and persisted before the exception propagates, so the UI can prompt a
     * reconnect for that single account without affecting the owner's other connected accounts.
     *
     * <p>Horizontal scalability: state lives in shared Postgres, never in instance memory. A refresh
     * writes the new access token + expiry (and any rotated refresh token) back to that row in this
     * same transaction so other replicas pick it up on their next read. A concurrent refresh from
     * another replica is safe — last write wins and either fresh token is valid until its own
     * expiry — so no distributed lock is needed.
     */
    @Transactional
    public String validAccessToken(GoogleCredential c, Instant now) {
        if (!c.isAccessTokenExpired(now)) {
            return c.accessToken;
        }
        try {
            TokenResponse resp = requestToken("refresh_token", c.refreshToken, now);
            c.accessToken = resp.accessToken();
            c.accessTokenExpiry = resp.expiry();
            // Google only returns a new refresh token if it rotated the old one; persist it when present.
            if (resp.refreshToken() != null) {
                c.refreshToken = resp.refreshToken();
            }
            c.needsReconnect = false;
            // Write the refreshed token back to the shared row so every replica benefits; no node-local cache.
            c.persist();
            return c.accessToken;
        } catch (RuntimeException ex) {
            // The failing refresh rolls back THIS transaction; persist the flag in a separate,
            // committed transaction so "needs reconnect" survives the rollback and reaches the UI.
            Long credId = c.id;
            if (credId != null) {
                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                    GoogleCredential fresh = GoogleCredential.findById(credId);
                    if (fresh != null) {
                        fresh.needsReconnect = true;
                        fresh.persist();
                    }
                });
            }
            throw ex;
        }
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
                // The id_token came directly from Google's token endpoint over TLS, so we read its
                // sub/email claims without re-verifying the signature over the network.
                String sub = null, email = null;
                String idToken = resp.getIdToken();
                if (idToken != null) {
                    var payload = com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
                            .parse(json, idToken).getPayload();
                    sub = payload.getSubject();
                    email = payload.getEmail();
                }
                return new TokenResponse(resp.getAccessToken(), resp.getRefreshToken(),
                        now.plusSeconds(resp.getExpiresInSeconds()), sub, email);
            }
            var resp = new GoogleRefreshTokenRequest(
                    transport, json, codeOrRefreshToken,
                    config.oauth().clientId(), config.oauth().clientSecret())
                    .execute();
            return new TokenResponse(resp.getAccessToken(), resp.getRefreshToken(),
                    now.plusSeconds(resp.getExpiresInSeconds()), null, null);
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
