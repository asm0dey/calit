package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.ItBrowser;

/**
 * Native smoke for the anonymous surface: public pages, static resources and locale bundles render, and an
 * invitee books a slot, which writes through Hibernate, renders the mail templates (mock mailer) and the
 * confirmation, then serves the ICS and the invitee's JSON export. Scenarios from {@code PublicLandingTest},
 * {@code LegalPagesTest}, {@code StaticAssetsTest}, {@code LangQueryParamTest}, {@code BookingPostTest},
 * {@code GuestIcsDownloadTest} and {@code BookingExportTest}, over HTTP only.
 */
@QuarkusIntegrationTest
@Tag("integration")
class PublicBookingIT {
    private static final Pattern SLOT = Pattern.compile("name=\"startUtc\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern MANAGE = Pattern.compile("/booking/([^/\"]+)/manage");

    @Test
    void publicPagesRender() {
        var visitor = new ItBrowser();
        var landing = visitor.get("/");
        assertThat(landing.statusCode()).isEqualTo(200);
        assertThat(landing.asString()).contains("Self-hosted scheduling");
        for (var page : List.of("/privacy", "/terms", "/favicon.svg")) {
            assertThat(visitor.get(page).statusCode()).as(page).isEqualTo(200);
        }
        var css = visitor.get("/calit.css");
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.asByteArray()).isNotEmpty();
    }

    @Test
    void inviteeBooksAndGetsIcsAndExport() {
        var owner = new ItBrowser();
        var username = owner.signUpOwner("bookit", ZoneId.of("Europe/Berlin")).username();
        owner.createMeetingType("book-it");

        var invitee = new ItBrowser();
        assertThat(invitee.get("/" + username).statusCode()).isEqualTo(200);
        var page = invitee.get("/" + username + "/book-it");
        assertThat(page.statusCode()).isEqualTo(200);
        var slot = SLOT.matcher(page.asString());
        assertThat(slot.find()).as("a bookable slot on the page").isTrue();
        var hebrew = invitee.get("/" + username + "/book-it?lang=he");
        assertThat(hebrew.statusCode()).isEqualTo(200);
        assertThat(hebrew.asString()).contains("lang=\"he\"");

        var confirmation = invitee.post(
                "/" + username + "/book-it",
                Map.of(
                        "startUtc",
                        slot.group(1),
                        "inviteeName",
                        "Dana Vogel",
                        "inviteeEmail",
                        "dana@example.com",
                        "website",
                        ""
                )
        );
        assertThat(confirmation.statusCode()).isEqualTo(200);
        var manage = MANAGE.matcher(confirmation.asString());
        assertThat(manage.find()).as("manage link on the confirmation").isTrue();
        var booking = "/booking/" + manage.group(1);

        assertThat(invitee.get(booking + "/manage").statusCode()).isEqualTo(200);
        var ics = invitee.get(booking + "/invite.ics");
        assertThat(ics.statusCode()).isEqualTo(200);
        assertThat(ics.contentType()).contains("text/calendar");
        assertThat(ics.asString()).contains("BEGIN:VCALENDAR");
        var data = invitee.get(booking + "/data");
        assertThat(data.statusCode()).isEqualTo(200);
        assertThat(data.jsonPath().getString("invitee.email")).isEqualTo("dana@example.com");
        assertThat(data.jsonPath().getString("booking.startUtc")).isNotNull();
    }
}
