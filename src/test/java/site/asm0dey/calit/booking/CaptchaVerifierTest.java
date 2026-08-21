package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.altcha.altcha.v1.Altcha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs in the default (no-profile) Quarkus boot: spy the config bean and force the altcha provider +
 * hmac key per-test instead of a @TestProfile restart.
 */
@QuarkusTest
class CaptchaVerifierTest {

    static final String KEY = "test-hmac-secret";

    @Inject
    CaptchaVerifier verifier;

    @InjectSpy
    CaptchaProviderConfig providerConfig;

    @BeforeEach
    void enableAltcha() {
        when(providerConfig.provider()).thenReturn("altcha");
        when(providerConfig.altchaHmacKey()).thenReturn(Optional.of(KEY));
    }

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
        // Corrupt the signature inside the payload, NOT the base64 tail. The encoded payload ends in
        // "=" padding whose low bits decode to nothing, so flipping its last character leaves the
        // decoded bytes unchanged roughly half the time -- the payload then verifies correctly and
        // the test silently asserts nothing. Which case you land in depends on the random challenge,
        // so it failed at random on any branch until this was fixed.
        var json = new String(Base64.getDecoder().decode(validPayload()), StandardCharsets.UTF_8);
        var marker = "\"signature\":\"";
        var at = json.indexOf(marker) + marker.length();
        var tampered = json.substring(0, at) + (json.charAt(at) == '0' ? '1' : '0') + json.substring(at + 1);
        assertNotEquals(json, tampered, "the tamper must actually change the payload");

        var bad = Base64.getEncoder().encodeToString(tampered.getBytes(StandardCharsets.UTF_8));
        assertThrows(AbuseException.class, () -> verifier.verify(null, bad));
    }
}
