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
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Step 2 of a multi-slot booking: the details form, the set POST, the conflict bounce and the set confirmation.
 */
@QuarkusTest
class BookSetFlowTest {
    private static final String SLUG = "set-flow";
    private static final String EMAIL = "sam@example.com";
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY1 = LocalDate.now(ZONE).plusDays(7);
    private static final LocalDate DAY2 = DAY1.plusDays(1);
    private static final Instant A = DAY1.atTime(9, 0).atZone(ZONE).toInstant();
    private static final Instant B = DAY2.atTime(10, 0).atZone(ZONE).toInstant();
    private static final String PICK_A = A + "/30";
    private static final String PICK_B = B + "/60";
    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void seed() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        QuarkusTransaction.requiringNew().run(() -> {
            var s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = ZONE.getId();
            s.persist();
            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coaching";
            t.slug = SLUG;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 30;
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = false;
            t.persist();
            var d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = 60;
            d.persist();
            for (var day : List.of(DAY1.getDayOfWeek(), DAY2.getDayOfWeek())) {
                var r = new AvailabilityRule();
                r.ownerId = 1L;
                r.dayOfWeek = day;
                r.startTime = LocalTime.of(9, 0);
                r.endTime = LocalTime.of(12, 0);
                r.persist();
            }
        });
    }

    @Test
    void detailsStepCarriesPicksAsHiddenInputs() {
        details(PICK_A, PICK_B)
            .body(containsString("<form method=\"post\" action=\"/admin/" + SLUG + "\""))
            .body(containsString("<input type=\"hidden\" name=\"pick\" value=\"" + PICK_A + "\">"))
            .body(containsString("<input type=\"hidden\" name=\"pick\" value=\"" + PICK_B + "\">"))
            .body(containsString("You&#39;re booking <strong>2 sessions</strong>"))
            .body(containsString("Confirm 2 sessions"))
            .body(containsString("name=\"inviteeEmail\""))
            .body(not(containsString("type=\"checkbox\" name=\"pick\"")));
        QuarkusTransaction
            .requiringNew()
            .run(() -> MeetingType.findBySlug(1L, SLUG).requiresApproval = true);
        details(PICK_A, PICK_B).body(containsString("Request 2 sessions"));
    }

    @Test
    void belowMinStaysOnStep1() {
        details(PICK_A)
            .body(containsString("type=\"checkbox\" name=\"pick\""))
            .body(containsString("<div role=\"alert\" class=\"alert alert-error mb-4\">Pick at least 2.</div>"))
            .body(not(containsString("name=\"inviteeEmail\"")));
    }

    @Test
    void stalePickIsDroppedAndNamedOnDetails() {
        occupy(B, 60);

        details(PICK_A, PICK_B)
            .body(containsString("type=\"checkbox\" name=\"pick\" value=\"" + PICK_A + "\" checked"))
            .body(not(containsString("value=\"" + PICK_B + "\"")))
            .body(containsString("No longer available: " + label(B)))
            .body(not(containsString("name=\"inviteeEmail\"")));
    }

    @Test
    void postBooksSetAndShowsRows() {
        var body = post(PICK_A, PICK_B)
            .then()
            .statusCode(200)
            .body(containsString("You&#39;re booked: <span>2 sessions</span>"))
            .body(containsString("1 of 2"))
            .body(containsString("2 of 2"))
            .extract()
            .asString();
        var tokens = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail = ?1 order by startUtc", EMAIL))
            .stream()
            .map(b -> b.manageToken)
            .toList();
        assertThat(tokens)
            .hasSize(2)
            .allSatisfy(t -> assertThat(body).contains("/booking/" + t + "/manage", "/booking/" + t + "/cancel"));
    }

    @Test
    void singlePickOnMinOneTypeShowsSingleConfirmation() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> MeetingType.findBySlug(1L, SLUG).multiSlotMin = 1);

        post(PICK_A)
            .then()
            .statusCode(200)
            .body(containsString("You&#39;re booked, Sam!"))
            .body(not(containsString("1 of 1")));
    }

    @Test
    void conflictBouncesToStep1KeepingFreePicks() {
        var spec = form(PICK_A, PICK_B);
        occupy(B, 60);

        spec
            .post("/admin/" + SLUG)
            .then()
            .statusCode(200)
            .body(containsString("type=\"checkbox\" name=\"pick\" value=\"" + PICK_A + "\" checked"))
            .body(not(containsString("value=\"" + PICK_B + "\"")))
            .body(containsString("No longer available: " + label(B)))
            .body(not(containsString("name=\"inviteeEmail\"")));
        assertThat(bookingsOf(EMAIL)).isZero();
    }

    @Test
    void validationErrorKeepsPicksOnStep2() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("pick", PICK_A, PICK_B)
            .formParam("inviteeName", "Sam")
            .when()
            .post("/admin/" + SLUG)
            .then()
            .statusCode(200)
            .body(containsString("<div role=\"alert\" class=\"alert alert-error mb-4\">"))
            .body(containsString("<input type=\"hidden\" name=\"pick\" value=\"" + PICK_A + "\">"))
            .body(containsString("<input type=\"hidden\" name=\"pick\" value=\"" + PICK_B + "\">"))
            .body(containsString("name=\"inviteeEmail\""));
        assertThat(bookingsOf(EMAIL)).isZero();
    }

    @Test
    void setIcsDownloadHoldsEverySession() {
        var body = post(PICK_A, PICK_B).then().statusCode(200).extract().asString();
        var lead = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>find("inviteeEmail = ?1 order by startUtc", EMAIL).firstResult().manageToken);

        assertThat(body).contains("href=\"/booking/" + lead + "/invite.ics\"", "Add all to calendar (.ics)");
        var ics = given()
            .when()
            .get("/booking/" + lead + "/invite.ics")
            .then()
            .statusCode(200)
            .extract()
            .asString();
        assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(3);
    }

    private static io.restassured.response.ValidatableResponse details(String... picks) {
        return given()
            .queryParam("pick", (Object[]) picks)
            .queryParam("step", "details")
            .when()
            .get("/admin/" + SLUG)
            .then()
            .statusCode(200);
    }

    private static RequestSpecification form(String... picks) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("pick", (Object[]) picks)
            .formParam("inviteeName", "Sam")
            .formParam("inviteeEmail", EMAIL)
            .when();
    }

    private static io.restassured.response.Response post(String... picks) {
        return form(picks).post("/admin/" + SLUG);
    }

    /**
     * The owner-zone label the error names a dropped pick by.
     */
    private static String label(Instant start) {
        var z = start.atZone(ZONE);
        return z.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
                + " · "
                + z.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static long bookingsOf(String email) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.count("inviteeEmail", email));
    }

    static void occupy(Instant start, int minutes) {
        QuarkusTransaction.requiringNew().run(() -> {
            var b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = MeetingType.findBySlug(1L, SLUG).id;
            b.inviteeName = "Other";
            b.inviteeEmail = "other@x.com";
            b.startUtc = start;
            b.endUtc = start.plus(minutes, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
        });
    }
}
