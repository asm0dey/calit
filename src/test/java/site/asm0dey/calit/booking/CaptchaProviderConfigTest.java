package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;
import org.junit.jupiter.api.Test;

class CaptchaProviderConfigTest {
    @Test
    void explicitProviderWins() {
        assertThat(CaptchaProviderConfig.resolve("altcha", true)).isEqualTo("altcha");
        assertThat(CaptchaProviderConfig.resolve("turnstile", false)).isEqualTo("turnstile");
        assertThat(CaptchaProviderConfig.resolve("none", true)).isEqualTo("none");
    }

    @Test
    void blankExplicitFallsBackToTurnstileFlag() {
        assertThat(CaptchaProviderConfig.resolve("", true)).isEqualTo("turnstile");
        assertThat(CaptchaProviderConfig.resolve(null, true)).isEqualTo("turnstile");
        assertThat(CaptchaProviderConfig.resolve(null, false)).isEqualTo("none");
    }

    @Test
    void caseAndWhitespaceTolerant() {
        assertThat(CaptchaProviderConfig.resolve("  ALTCHA ", false)).isEqualTo("altcha");
    }

    @Test
    void invalidProviderThrows() {
        assertThatThrownBy(() -> CaptchaProviderConfig.resolve("recaptcha", false))
            .asInstanceOf(throwable(IllegalArgumentException.class));
    }
}
