package com.calit.i18n;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class AppLocalesTest {
    @Test void picksExactSupported() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de"));
    }
    @Test void picksByLanguageIgnoringRegion() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de-AT"));
    }
    @Test void unsupportedFallsBackToDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.pick("fr"));
    }
    @Test void nullOrBlankIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.pick(null));
        assertEquals(AppLocales.DEFAULT, AppLocales.pick("  "));
    }
    @Test void acceptLanguagePicksBestSupported() {
        assertEquals(Locale.GERMAN, AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9,de;q=0.8,en;q=0.7"));
    }
    @Test void acceptLanguageNoneSupportedIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9"));
    }
    @Test void acceptLanguageNullIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.fromAcceptLanguage(null));
    }
}
