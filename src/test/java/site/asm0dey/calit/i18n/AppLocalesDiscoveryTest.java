package site.asm0dey.calit.i18n;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link AppLocales#supported()} auto-discovers the correct set of locales
 * from the Quarkus-generated {@code @Localized} message-bundle beans (Arc CDI required).
 *
 * Expected: [en, de] — the two bundles present in src/main/resources/messages/.
 * Adding msg_fr.properties + adm_fr.properties would make supported() return [en, de, fr]
 * with zero further changes.
 */
@QuarkusTest
class AppLocalesDiscoveryTest {

    @Test
    void supportedContainsEnglishAndGerman() {
        List<Locale> supported = AppLocales.supported();
        // Default (en) must be first
        assertEquals(Locale.ENGLISH, supported.get(0), "Default locale must be first");
        assertTrue(supported.contains(Locale.GERMAN), "German must be discovered from msg_de.properties");
        assertEquals(2, supported.size(), "Exactly two locales expected: en + de");
    }

    @Test
    void labelForDeIsDeutsch() {
        assertEquals("Deutsch", AppLocales.labelFor("de"));
    }

    @Test
    void labelForEnIsEnglish() {
        assertEquals("English", AppLocales.labelFor("en"));
    }

    @Test
    void supportedMatchesBundleBeans() {
        // Pick and isSupported round-trip through the live discovered list
        assertEquals(Locale.GERMAN, AppLocales.pick("de"));
        assertTrue(AppLocales.isSupported("de"));
        assertFalse(AppLocales.isSupported("fr"));
    }
}
