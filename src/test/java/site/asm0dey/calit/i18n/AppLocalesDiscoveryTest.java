package site.asm0dey.calit.i18n;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AppLocales#supported()} auto-discovers the correct set of locales
 * from the Quarkus-generated {@code @Localized} message-bundle beans (Arc CDI required).
 *
 * Expected: [en, de, he] — the three bundles present in src/main/resources/messages/.
 * Adding msg_fr.properties + adm_fr.properties would make supported() return [en, de, he, fr]
 * with zero further changes.
 */
@QuarkusTest
class AppLocalesDiscoveryTest {
    @Test
    void supportedContainsEnglishGermanAndHebrew() {
        List<Locale> supported = AppLocales.supported();
        // Default (en) must be first
        assertThat(supported)
            .as("Exactly three locales expected: en + de + he")
            .hasSize(3)
            .as("German and Hebrew must be discovered from msg_de/msg_he.properties")
            .contains(Locale.GERMAN, Locale.forLanguageTag("he"))
            .first()
            .as("Default locale must be first")
            .isEqualTo(Locale.ENGLISH);
    }

    @Test
    void labelForDeIsDeutsch() {
        assertThat(AppLocales.labelFor("de")).isEqualTo("Deutsch");
    }

    @Test
    void labelForEnIsEnglish() {
        assertThat(AppLocales.labelFor("en")).isEqualTo("English");
    }

    @Test
    void labelForHeIsHebrewEndonym() {
        assertThat(AppLocales.labelFor("he")).isEqualTo("עברית");
    }

    @Test
    void supportedMatchesBundleBeans() {
        // Pick and isSupported round-trip through the live discovered list
        assertThat(AppLocales.pick("de")).isEqualTo(Locale.GERMAN);
        assertThat(AppLocales.isSupported("de")).isTrue();
        assertThat(AppLocales.isSupported("he")).isTrue();
        assertThat(AppLocales.isSupported("fr")).isFalse();
    }
}
