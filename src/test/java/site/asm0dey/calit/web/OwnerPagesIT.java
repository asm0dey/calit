package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.ItBrowser;

/**
 * Native smoke for the signed-in owner surface: every {@code /me} page renders through Qute and its message
 * bundles (the #342 failure was a 500 on these in the native image only), the owner export serialises to
 * JSON, and the DB-backed OG card draws. Scenarios from {@code AdminAuthTest}, {@code MeOwnerFilterTest},
 * {@code AdminNavTest}, {@code OwnerExportTest} and {@code OgImageResourceTest}, over HTTP only.
 */
@QuarkusIntegrationTest
@Tag("integration")
class OwnerPagesIT {
    private static final List<String> PAGES = List.of(
            "/me",
            "/me/meeting-types",
            "/me/availability",
            "/me/date-overrides",
            "/me/booking-fields",
            "/me/calendars",
            "/me/pending",
            "/me/shared",
            "/me/shared/requests",
            "/me/settings",
            "/me/settings/delete"
    );
    private final ItBrowser browser = new ItBrowser();

    @Test
    void everyOwnerPageRenders() {
        browser.signUpOwner("pagesit", ZoneId.of("Europe/Berlin"));
        for (var page : PAGES) {
            assertThat(browser.get(page).statusCode()).as(page).isEqualTo(200);
        }
        assertThat(browser.get("/me").asString()).contains("Dashboard");
    }

    @Test
    void ownerExportIsJson() {
        browser.signUpOwner("exportit", ZoneId.of("Europe/Berlin"));
        var export = browser.get("/me/export");
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.contentType()).contains("application/json");
        assertThat(export.jsonPath().getMap("$")).isNotEmpty();
    }

    @Test
    void ownerCardRendersFromTheDatabase() {
        var owner = browser.signUpOwner("cardit", ZoneId.of("Europe/Berlin"));
        byte[] product = browser.get("/og.png").asByteArray();
        byte[] card = browser.get("/og/" + owner.username() + ".png").asByteArray();
        assertThat(Arrays.copyOf(product, 4)).containsExactly(OgImageResourceIT.PNG_MAGIC);
        assertThat(Arrays.copyOf(card, 4)).containsExactly(OgImageResourceIT.PNG_MAGIC);
        // a silent fallback to the product card would still be a PNG
        assertThat(card).isNotEqualTo(product);
    }
}
