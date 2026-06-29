package site.asm0dey.calit.i18n;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Locale;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminMessagesHebrewTest {

    @Inject
    AdminMessageResolver admin;

    @Test
    void hebrewAdminStringsResolveAndDifferFromEnglish() {
        String he = admin.forLocale(Locale.forLanguageTag("he")).adm_nav_dashboard();
        String en = admin.forLocale(Locale.ENGLISH).adm_nav_dashboard();
        assertFalse(he.isBlank(), "Hebrew admin nav label must not be blank");
        assertNotEquals(en, he, "Hebrew admin label must differ from English");
        assertEquals("לוח בקרה", he);
    }
}
