package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingInvite;
import site.asm0dey.calit.booking.BookingInviteService;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The public single-use invite URL: {@code /{user}?invite=...}. Invites and bookings are committed
 * rows consumed by the POST, so data is committed via {@link QuarkusTransaction}.
 */
@QuarkusTest
class BookingInviteRoutesTest {
    private static final String SLUG = "zz-hidden-slug";
    private static final String INVALID_TITLE = "This invite is no longer valid";
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();
    private static final Instant SLOT_10 = DAY.atTime(10, 0).atZone(ZONE).toInstant();
    private static final Instant SLOT_03 = DAY.atTime(3, 0).atZone(ZONE).toInstant();
    @Inject
    BookingInviteService invites;
    @InjectMock
    CalendarPort calendarPort;

    @Test
    void getDoesNotConsume() {
        var typeId = seed(SLUG);
        var token = invites.issue(1L, typeId, Instant.now());

        given().when().get("/admin?invite=" + token).then().statusCode(200);
        given().when().get("/admin?invite=" + token).then().statusCode(200);

        post(token, SLOT_09).then().statusCode(200).body(containsString("Sam Invitee")).body(not(containsString(SLUG)));
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void invitePageHidesSlugAndCard() {
        var token = invites.issue(1L, seed(SLUG), Instant.now());

        given()
            .when()
            .get("/admin?invite=" + token)
            .then()
            .statusCode(200)
            .body(containsString("noindex"))
            .body(not(containsString(SLUG)))
            .body(not(containsString("og:")))
            .body(not(containsString("twitter:")));
    }

    @Test
    void landingUnchangedWithoutInvite() {
        seed(SLUG);

        given()
            .when()
            .get("/admin")
            .then()
            .statusCode(200)
            .body(containsString("og:title"))
            .body(containsString("/admin/" + SLUG));
    }

    @Test
    void blankInviteShowsInvalidPage() {
        seed(SLUG);

        invalidPage(given().when().get("/admin?invite="));
    }

    @Test
    void unknownInviteShowsInvalidPage() {
        seed(SLUG);

        invalidPage(given().when().get("/admin?invite=nope"));
    }

    @Test
    void expiredInviteShowsInvalidPage() {
        var typeId = seed(SLUG);
        var token = invites.issue(1L, typeId, Instant.now());
        QuarkusTransaction
            .requiringNew()
            .run(() -> BookingInvite.update("expiresAt = ?1", Instant.now().minusSeconds(5)));

        invalidPage(given().when().get("/admin?invite=" + token));
    }

    @Test
    void usedInviteShowsInvalidPage() {
        var token = invites.issue(1L, seed(SLUG), Instant.now());
        post(token, SLOT_09).then().statusCode(200);

        invalidPage(given().when().get("/admin?invite=" + token));
    }

    @Test
    void inviteUnderAnotherUserIsInvalid() {
        var token = invites.issue(1L, seed(SLUG), Instant.now());
        QuarkusTransaction
            .requiringNew()
            .run(() -> MultiHostFixtures.enabledUser("invite-other"));

        invalidPage(given().when().get("/invite-other?invite=" + token));
    }

    @Test
    void postWithoutInviteIs404() {
        seed(SLUG);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", SLOT_09.toString())
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .when()
            .post("/admin")
            .then()
            .statusCode(404);
        assertThat(bookingCount()).isZero();
    }

    @Test
    void validationBounceKeepsInvite() {
        var typeId = seed(SLUG);
        requireField(typeId);
        var token = invites.issue(1L, typeId, Instant.now());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", SLOT_09.toString())
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .when()
            .post("/admin?invite=" + token)
            .then()
            .statusCode(200)
            .body(containsString("action=\"/admin?invite=" + token + "\""))
            .body(not(containsString(SLUG)));
        assertThat(bookingCount()).isZero();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", SLOT_09.toString())
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .formParam("answers.company", "Acme")
            .when()
            .post("/admin?invite=" + token)
            .then()
            .statusCode(200)
            .body(containsString("Sam Invitee"));
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void usedInvitePostShowsInvalidPage() {
        var token = invites.issue(1L, seed(SLUG), Instant.now());
        post(token, SLOT_09).then().statusCode(200);

        invalidPage(post(token, SLOT_10));
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void durationSwitchKeepsInvite() {
        var typeId = seed(SLUG);
        addDurations(typeId, 30);
        var token = invites.issue(1L, typeId, Instant.now());

        given()
            .when()
            .get("/admin?invite=" + token)
            .then()
            .statusCode(200)
            .body(containsString("href=\"/admin?invite=" + token + "&amp;duration=30\""))
            .body(not(containsString(SLUG)));
        given()
            .when()
            .get("/admin?invite=" + token + "&duration=30")
            .then()
            .statusCode(200)
            .body(containsString("name=\"durationMinutes\" value=\"30\""));
    }

    @Test
    void multiHostPendingShowsHostPendingPage() {
        var typeId = seed(SLUG);
        var token = invites.issue(1L, typeId, Instant.now());
        QuarkusTransaction.requiringNew().run(() -> {
            var cohost = MultiHostFixtures.enabledUser("invite-cohost");
            MeetingTypeHost.of(typeId, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
            MeetingTypeHost.of(typeId, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING).persist();
        });

        given().when().get("/admin?invite=" + token).then().statusCode(200).body(containsString("CALIT_HOST_PENDING"));
        assertThat(invites.find(1L, token, Instant.now())).isNotNull();
    }

    @Test
    void conflictBounceDoesNotLeakSlug() {
        var token = invites.issue(1L, seed(SLUG), Instant.now());
        // 03:00 local is outside the 09:00-11:00 availability window
        post(token, SLOT_03)
            .then()
            .statusCode(200)
            .body(containsString("That time is no longer available. Please choose another."))
            .body(not(containsString(SLUG)));
        assertThat(invites.find(1L, token, Instant.now())).isNotNull();
        assertThat(bookingCount()).isZero();
    }

    @Test
    void deactivatedTypeIsNotFoundWithoutSlug() {
        var typeId = seed(SLUG);
        var token = invites.issue(1L, typeId, Instant.now());
        QuarkusTransaction
            .requiringNew()
            .run(() -> MeetingType.update("active = false where id = ?1", typeId));

        given().when().get("/admin?invite=" + token).then().statusCode(404).body(not(containsString(SLUG)));
    }

    @Test
    void slugRouteStillBooks() {
        seed(SLUG);

        given()
            .when()
            .get("/admin/" + SLUG)
            .then()
            .statusCode(200)
            .body(containsString("action=\"/admin/" + SLUG + "\""))
            .body(containsString("og:title"));
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", SLOT_09.toString())
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .when()
            .post("/admin/" + SLUG)
            .then()
            .statusCode(200)
            .body(containsString("Sam Invitee"));
        assertThat(bookingCount()).isEqualTo(1);
    }

    private static io.restassured.response.Response post(String token, Instant start) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", start.toString())
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .when()
            .post("/admin?invite=" + token);
    }

    private static void invalidPage(io.restassured.response.Response r) {
        r
            .then()
            .statusCode(200)
            .body(containsString(INVALID_TITLE))
            .body(not(containsString(SLUG)))
            .body(not(containsString("og:")))
            .body(not(containsString("booking-grid")));
    }

    private static long bookingCount() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.count());
    }

    private static void requireField(Long typeId) {
        QuarkusTransaction.requiringNew().run(() -> {
            var f = new site.asm0dey.calit.domain.BookingField();
            f.ownerId = 1L;
            f.meetingTypeId = typeId;
            f.fieldKey = "company";
            f.label = "Company";
            f.type = site.asm0dey.calit.domain.BookingField.FieldType.SHORT_TEXT;
            f.required = true;
            f.position = 0;
            f.persist();
        });
    }

    private static void addDurations(Long typeId, int... lengths) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (int len : lengths) {
                var d = new MeetingTypeDuration();
                d.meetingTypeId = typeId;
                d.durationMinutes = len;
                d.persist();
            }
        });
    }

    private Long seed(String slug) {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        return QuarkusTransaction.requiringNew().call(() -> {
            var s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();

            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Invite Type";
            t.slug = slug;
            t.durationMinutes = 60;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.persist();
            var r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = DAY.getDayOfWeek();
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(11, 0);
            r.meetingTypeId = null;
            r.persist();
            return t.id;
        });
    }
}
