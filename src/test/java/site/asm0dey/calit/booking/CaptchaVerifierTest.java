package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.altcha.altcha.v1.Altcha;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CaptchaVerifierTest.AltchaOn.class)
class CaptchaVerifierTest {

    static final String KEY = "test-hmac-secret";

    public static class AltchaOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.captcha.provider", "altcha",
                    "calit.captcha.altcha.hmac-key", KEY,
                    "calit.captcha.altcha.max-number", "100000");
        }
    }

    @Inject
    CaptchaVerifier verifier;

    /** Build the exact base64 payload the ALTCHA widget would POST after solving. */
    static String validPayload() throws Exception {
        var opts = new Altcha.ChallengeOptions()
                .algorithm(Altcha.Algorithm.SHA256)
                .maxNumber(100000)
                .hmacKey(KEY);
        Altcha.Challenge ch = Altcha.createChallenge(opts);
        Altcha.Solution sol =
                Altcha.solveChallenge(ch.challenge(), ch.salt(), Altcha.Algorithm.SHA256, ch.maxnumber(), 0);
        // salt is hex + "?expires=...&" — URL-encoded, contains no JSON-special chars.
        String json = "{\"algorithm\":\"" + ch.algorithm() + "\",\"challenge\":\"" + ch.challenge()
                + "\",\"number\":" + sol.number() + ",\"salt\":\"" + ch.salt()
                + "\",\"signature\":\"" + ch.signature() + "\"}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validSolutionPasses() throws Exception {
        var payload = validPayload();
        assertDoesNotThrow(() -> verifier.verify(null, payload));
    }

    @Test
    void missingSolutionThrows() {
        assertThrows(AbuseException.class, () -> verifier.verify(null, null));
        assertThrows(AbuseException.class, () -> verifier.verify(null, "   "));
    }

    @Test
    void tamperedSolutionThrows() throws Exception {
        // Flip the last base64 char to corrupt the signature/number.
        var payload = validPayload();
        var bad = payload.substring(0, payload.length() - 2) + (payload.endsWith("A=") ? "B=" : "A=");
        assertThrows(AbuseException.class, () -> verifier.verify(null, bad));
    }
}
