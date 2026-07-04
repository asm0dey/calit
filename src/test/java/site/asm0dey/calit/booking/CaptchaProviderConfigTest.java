package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CaptchaProviderConfigTest {

    @Test
    void explicitProviderWins() {
        assertEquals("altcha", CaptchaProviderConfig.resolve("altcha", true));
        assertEquals("turnstile", CaptchaProviderConfig.resolve("turnstile", false));
        assertEquals("none", CaptchaProviderConfig.resolve("none", true));
    }

    @Test
    void blankExplicitFallsBackToTurnstileFlag() {
        assertEquals("turnstile", CaptchaProviderConfig.resolve("", true));
        assertEquals("turnstile", CaptchaProviderConfig.resolve(null, true));
        assertEquals("none", CaptchaProviderConfig.resolve(null, false));
    }

    @Test
    void caseAndWhitespaceTolerant() {
        assertEquals("altcha", CaptchaProviderConfig.resolve("  ALTCHA ", false));
    }

    @Test
    void invalidProviderThrows() {
        assertThrows(IllegalArgumentException.class, () -> CaptchaProviderConfig.resolve("recaptcha", false));
    }
}
