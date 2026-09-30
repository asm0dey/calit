package site.asm0dey.calit.i18n;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (no CDI, no Quarkus) tests for the pure static helpers in {@link AppLocales}.
 * All methods under test receive an explicit {@code List<Locale>} — no Arc involved.
 *
 * For auto-discovery assertions (supported() == [en, de]) see {@link AppLocalesDiscoveryTest}.
 */
class AppLocalesTest {
    private static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.GERMAN);

    @Test
    void picksExactSupported() {
        assertThat(AppLocales.pick("de", LOCALES)).isEqualTo(Locale.GERMAN);
    }

    @Test
    void picksByLanguageIgnoringRegion() {
        assertThat(AppLocales.pick("de-AT", LOCALES)).isEqualTo(Locale.GERMAN);
    }

    @Test
    void unsupportedFallsBackToDefault() {
        assertThat(AppLocales.pick("fr", LOCALES)).isEqualTo(AppLocales.DEFAULT);
    }

    @Test
    void nullOrBlankIsDefault() {
        assertThat(AppLocales.pick(null, LOCALES)).isEqualTo(AppLocales.DEFAULT);
        assertThat(AppLocales.pick("  ", LOCALES)).isEqualTo(AppLocales.DEFAULT);
    }

    @Test
    void acceptLanguagePicksBestSupported() {
        assertThat(AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9,de;q=0.8,en;q=0.7", LOCALES)).isEqualTo(Locale.GERMAN);
    }

    @Test
    void acceptLanguageNoneSupportedIsDefault() {
        assertThat(AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9", LOCALES)).isEqualTo(AppLocales.DEFAULT);
    }

    @Test
    void acceptLanguageNullIsDefault() {
        assertThat(AppLocales.fromAcceptLanguage(null, LOCALES)).isEqualTo(AppLocales.DEFAULT);
    }

    @Test
    void isSupportedReturnsTrueForKnown() {
        assertThat(AppLocales.isSupported("de", LOCALES)).isTrue();
        assertThat(AppLocales.isSupported("en", LOCALES)).isTrue();
    }

    @Test
    void isSupportedReturnsFalseForUnknown() {
        assertThat(AppLocales.isSupported("fr", LOCALES)).isFalse();
        assertThat(AppLocales.isSupported(null, LOCALES)).isFalse();
    }

    @Test
    void labelForDeIssDeutsch() {
        assertThat(AppLocales.labelFor("de")).isEqualTo("Deutsch");
    }

    @Test
    void labelForEnIsEnglish() {
        assertThat(AppLocales.labelFor("en")).isEqualTo("English");
    }

    @Test
    void labelForFrIsFrancais() {
        // JDK returns "français" with lowercase f; we capitalize to "Français"
        assertThat(AppLocales.labelFor("fr")).isEqualTo("Français");
    }
}
