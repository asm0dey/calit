package site.asm0dey.calit.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The SIGN-IN OAuth flow, kept separate from the calendar-connect flow in GoogleTokenService so
 * the working calendar integration is untouched. Builds a consent URL with a login-purpose,
 * HMAC-signed, stateless state (no ownerId — the user is not yet known), and exchanges the
 * callback code for the id_token identity. The network call is an overridable seam for tests.
 */
@ApplicationScoped
public class GoogleLoginService {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String PURPOSE = "login"; // distinguishes these states from calendar states
    // Sign-in needs identity only — NOT the calendar scope used by the connect flow.
    private static final String LOGIN_SCOPE = "openid email";

    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    protected final GoogleOAuthConfig config;

    @Inject
    public GoogleLoginService(GoogleOAuthConfig config) {
        this.config = config;
    }

    public String buildConsentUrl(Instant now) {
        return AUTH_ENDPOINT + "?"
                + "client_id=" + enc(config.oauth().clientId())
                + "&redirect_uri=" + enc(config.oauth().loginRedirectUri())
                + "&response_type=code"
                + "&scope=" + enc(LOGIN_SCOPE)
                + "&prompt=select_account"
                + "&state=" + enc(issueLoginState(now));
    }

    /** Mint a signed, time-stamped, login-purpose state. Stateless: nothing stored. */
    public String issueLoginState(Instant now) {
        String payload = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                + ":" + PURPOSE + ":" + now.getEpochSecond();
        return payload + "." + b64(hmac(payload));
    }

    /** True when {@code state} is a valid, unexpired, login-purpose state. */
    public boolean validateLoginState(String state, Instant now) {
        if (state == null || state.isBlank()) return false;
        int dot = state.lastIndexOf('.');
        if (dot <= 0) return false;
        String payload = state.substring(0, dot);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(state.substring(dot + 1));
        } catch (IllegalArgumentException _) {
            return false;
        }
        if (!MessageDigest.isEqual(expected, actual)) return false;

        // payload = b64(nonce) ":" PURPOSE ":" issuedAtEpochSec
        int lastColon = payload.lastIndexOf(':');
        int prevColon = lastColon <= 0 ? -1 : payload.lastIndexOf(':', lastColon - 1);
        if (prevColon <= 0) return false;
        if (!PURPOSE.equals(payload.substring(prevColon + 1, lastColon))) return false;
        try {
            Instant issued = Instant.ofEpochSecond(Long.parseLong(payload.substring(lastColon + 1)));
            return !issued.isAfter(now) && !issued.plus(STATE_TTL).isBefore(now);
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /**
     * Exchange the callback code for the Google identity. Overridable so tests skip the network.
     *
     * @param code the authorization code received from Google's redirect callback
     * @param now reserved for future id_token exp/iat validation; not used yet.
     */
    public GoogleIdentity exchangeForIdentity(String code, Instant now) {
        return requestIdentity(code);
    }

    protected GoogleIdentity requestIdentity(String code) {
        NetHttpTransport transport = new NetHttpTransport();
        GsonFactory json = GsonFactory.getDefaultInstance();
        try {
            var resp = new GoogleAuthorizationCodeTokenRequest(
                    transport, json, TOKEN_ENDPOINT,
                    config.oauth().clientId(), config.oauth().clientSecret(),
                    code, config.oauth().loginRedirectUri())
                    .execute();
            String idToken = resp.getIdToken();
            if (idToken == null) {
                throw new IllegalStateException("Google response missing id_token; check the openid scope.");
            }
            // The id_token came directly from Google's token endpoint over TLS in this request,
            // so we read its sub/email claims without re-verifying the signature over the network.
            GoogleIdToken.Payload p = GoogleIdToken.parse(json, idToken).getPayload();
            boolean verified = Boolean.TRUE.equals(p.getEmailVerified());
            return new GoogleIdentity(p.getSubject(), p.getEmail(), verified);
        } catch (IOException e) {
            throw new IllegalStateException("Google token request failed", e);
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    config.oauth().stateSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign login state", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
