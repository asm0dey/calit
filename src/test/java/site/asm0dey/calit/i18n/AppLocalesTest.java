package site.asm0dey.calit.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit (no CDI, no Quarkus) tests for the pure static helpers in {@link AppLocales}.
 * All methods under test receive an explicit {@code List<Locale>} — no Arc involved.
 *
 * For auto-discovery assertions (supported() == [en, de]) see {@link AppLocalesDiscoveryTest}.
 */
class AppLocalesTest {

    private static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.GERMAN);

    @Test void picksExactSupported() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de", LOCALES));
    }

    @Test void picksByLanguageIgnoringRegion() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de-AT", LOCALES));
    }

    @Test void unsupportedFallsBackToDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.pick("fr", LOCALES));
    }

    @Test void nullOrBlankIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.pick(null, LOCALES));
        assertEquals(AppLocales.DEFAULT, AppLocales.pick("  ", LOCALES));
    }

    @Test void acceptLanguagePicksBestSupported() {
        assertEquals(Locale.GERMAN,
                AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9,de;q=0.8,en;q=0.7", LOCALES));
    }

    @Test void acceptLanguageNoneSupportedIsDefault() {
        assertEquals(AppLocales.DEFAULT,
                AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9", LOCALES));
    }

    @Test void acceptLanguageNullIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.fromAcceptLanguage(null, LOCALES));
    }

    @Test void isSupportedReturnsTrueForKnown() {
        assertTrue(AppLocales.isSupported("de", LOCALES));
        assertTrue(AppLocales.isSupported("en", LOCALES));
    }

    @Test void isSupportedReturnsFalseForUnknown() {
        assertFalse(AppLocales.isSupported("fr", LOCALES));
        assertFalse(AppLocales.isSupported(null, LOCALES));
    }

    @Test void labelForDeIssDeutsch() {
        assertEquals("Deutsch", AppLocales.labelFor("de"));
    }

    @Test void labelForEnIsEnglish() {
        assertEquals("English", AppLocales.labelFor("en"));
    }

    @Test void labelForFrIsFrancais() {
        // JDK returns "français" with lowercase f; we capitalize to "Français"
        assertEquals("Français", AppLocales.labelFor("fr"));
    }
}
