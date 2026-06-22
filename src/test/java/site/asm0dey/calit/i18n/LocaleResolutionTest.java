package site.asm0dey.calit.i18n;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LocaleResolutionTest {

    @Test
    void publicCookieDeRendersGerman() {
        given().cookie("calit_lang", "de").when().get("/__i18n_probe")
                .then().statusCode(200)
                .body(containsString("lang=de")).body(containsString("cancel=Abbrechen"));
    }

    @Test
    void publicAcceptLanguageDe() {
        given().header("Accept-Language", "de").when().get("/__i18n_probe")
                .then().statusCode(200)
                .body(containsString("cancel=Abbrechen")).body(containsString("lang=de"));
    }

    @Test
    void unsupportedFallsToEnglish() {
        given().header("Accept-Language", "fr").when().get("/__i18n_probe")
                .then().statusCode(200)
                .body(containsString("cancel=Cancel")).body(containsString("lang=en"));
    }
}
