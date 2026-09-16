package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class OwnerExportTest {

    @Test
    void anonymousCannotExport() {
        // Form-auth's challenge for an unauthenticated request to a role-protected /me* path is a
        // 302 redirect to the login page, not a bare 401 (confirmed against this codebase's
        // quarkus.http.auth.form.* config — see ReservedRouteTest, which accepts the same set for
        // plain "/me").
        given().redirects()
                .follow(false)
                .when()
                .get("/me/export")
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void exportIsAJsonAttachmentCoveringTheOwnersSubtree() {
        given().when()
                .get("/me/export")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Content-Disposition", containsString("attachment"))
                .body("exportedAt", notNullValue())
                .body("account", notNullValue())
                .body("settings", notNullValue())
                .body("meetingTypes", notNullValue())
                .body("availability", notNullValue())
                .body("bookings", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void noSecretsLeaveTheBuilding() {
        String body = given().when()
                .get("/me/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("passwordHash") || body.contains("password_hash"),
                "the argon2id hash must never appear in an export");
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("accessToken") || body.contains("refreshToken"),
                "Google OAuth tokens must never appear in an export");
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void notificationChannelUrlsAreRedacted() {
        ChannelFixtures.seedChannel(1L, "ntfy://ntfy.sh/secret-topic");
        given().when()
                .get("/me/export")
                .then()
                .statusCode(200)
                .body(not(containsString("secret-topic")))
                .body("notificationChannels", everyItem(hasKey("url")))
                .body("notificationChannels[0].url", containsString("redacted"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void anotherOwnersDataNeverAppearsInThisExport() {
        seedSecondOwnerBooking();
        given().when().get("/me/export").then().statusCode(200).body(not(containsString("Zzyzx Quibblesworth")));
    }

    /** A second owner with a booking carrying a unique, easy-to-grep invitee name. */
    private static void seedSecondOwnerBooking() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser other = AppUser.create("otherowner-export", "x", false);
            other.settingsComplete = true;
            other.persist();

            MeetingType t = new MeetingType();
            t.ownerId = other.id;
            t.name = "Other owner's type";
            t.slug = "other-owner-export-" + UUID.randomUUID();
            t.durationMinutes = 30;
            t.persist();

            var b = new Booking();
            b.ownerId = other.id;
            b.meetingTypeId = t.id;
            b.inviteeName = "Zzyzx Quibblesworth";
            b.inviteeEmail = "zzyzx@example.com";
            b.startUtc = Instant.now().minus(10, ChronoUnit.DAYS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now().minus(10, ChronoUnit.DAYS);
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
        });
    }
}
