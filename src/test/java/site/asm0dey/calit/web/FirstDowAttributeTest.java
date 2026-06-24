package site.asm0dey.calit.web;

import org.junit.jupiter.api.Test;
import site.asm0dey.calit.i18n.AppLocales;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the language -> first-weekday-column (JS Date.getDay() index) policy. */
class FirstDowAttributeTest {

    @Test
    void hebrewStartsSunday() {
        assertEquals(0, AppLocales.firstDayOfWeekIndex(Locale.forLanguageTag("he")));
    }

    @Test
    void germanStartsMonday() {
        assertEquals(1, AppLocales.firstDayOfWeekIndex(Locale.forLanguageTag("de")));
    }

    @Test
    void englishStartsMonday() {
        assertEquals(1, AppLocales.firstDayOfWeekIndex(Locale.ENGLISH));
    }
}
