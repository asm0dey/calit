package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingInviteService;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.CaptchaVerifier;
import site.asm0dey.calit.booking.SlotPick;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The manage page of a Booking in a set: siblings with their own links, "Cancel all", and the Owner's "k of N" tag.
 */
@QuarkusTest
class ManageSetTest {
    private static final String SLUG = "zz-hidden-slug";
    private static final String EMAIL = "sam@x.com";
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate MONDAY =
            LocalDate.now(AMS).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    @Inject
    BookingService bookingService;
    @Inject
    BookingInviteService invites;
    @Inject
    MockMailbox mailbox;
    @InjectMock
    CalendarPort calendarPort;
    @InjectMock
    CaptchaVerifier captchaVerifier;
    private Long typeId;

    @BeforeEach
    void seed() {
        mailbox.clear();
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        typeId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                MultiHostFixtures.settings(1L, "pasha");
                MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
                MeetingType t = MultiHostFixtures.meetingType(1L, SLUG, 30);
                // the fixture names the type after its slug; a name must not trip the slug check
                t.name = "Coaching";
                t.multiSlotMin = 2;
                t.multiSlotMax = 3;
                t.multiSlotSameDay = true;
                return t.id;
            });
    }

    private List<Booking> bookSet(String inviteToken) {
        var picks =
                Stream
            .of(10, 11, 12)
            .map(h -> new SlotPick(MONDAY.atTime(h, 0).atZone(AMS).toInstant(), 30))
            .toList();
        return bookingService.bookSet(
                1L,
                SLUG,
                picks,
                "Sam",
                EMAIL,
                Map.of(),
                "tok",
                null,
                "",
                "en",
                List.of(),
                inviteToken
        );
    }

    private static String manage(String token) {
        return given().when().get("/booking/" + token + "/manage").then().statusCode(200).extract().asString();
    }

    @Test
    void manageListsSiblingsWithLinks() {
        var rows = bookSet(null);
        bookingService.cancel(rows.getFirst().manageToken);

        var body = manage(rows.get(1).manageToken);

        assertThat(body)
            .contains("href=\"/booking/" + rows.getFirst().manageToken + "/manage\"")
            .contains("href=\"/booking/" + rows.getLast().manageToken + "/manage\"")
            .doesNotContain("href=\"/booking/" + rows.get(1).manageToken + "/manage\"")
            .contains("1 of 3", "2 of 3", "3 of 3", "this one", "Cancel all 2 sessions")
            .contains("<form method=\"post\" action=\"/booking/" + rows.get(1).manageToken + "/cancel-all\">");
    }

    @Test
    void manageOfLoneBookingHasNoSiblings() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> MeetingType.<MeetingType>findById(typeId).multiSlotMin = 1);
        var only = bookingService
            .bookSet(
                    1L,
                    SLUG,
                    List.of(new SlotPick(MONDAY.atTime(10, 0).atZone(AMS).toInstant(), 30)),
                    "Sam",
                    EMAIL,
                    Map.of(),
                    "tok",
                    null,
                    "",
                    "en",
                    List.of(),
                    null
            )
            .getFirst();

        assertThat(manage(only.manageToken)).doesNotContain("Cancel all", "1 of ");
    }

    @Test
    void cancelAllHiddenWhenNothingHeld() {
        var rows = bookSet(null);
        rows.forEach(b -> bookingService.cancel(b.manageToken));

        assertThat(manage(rows.getFirst().manageToken)).contains("2 of 3").doesNotContain("Cancel all");
    }

    @Test
    void postCancelAllCancelsTheSet() {
        var rows = bookSet(null);

        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/booking/" + rows.getFirst().manageToken + "/cancel-all")
            .then()
            .statusCode(200)
            .body(containsString("Your booking is cancelled"));

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail", EMAIL)))
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CANCELLED);

        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/booking/" + rows.getFirst().manageToken + "/cancel-all")
            .then()
            .statusCode(200)
            .body(containsString("Nothing left to cancel"));
    }

    @Test
    void ownerCancelAllFromOwnerManagePage() {
        var rows = bookSet(null);
        var cookie = FormAuth.login();

        given()
            .cookie("quarkus-credential", cookie)
            .when()
            .get("/me/bookings/" + rows.getFirst().id + "/manage")
            .then()
            .statusCode(200)
            .body(containsString("action=\"/me/bookings/" + rows.getFirst().id + "/cancel-all\""))
            .body(containsString("Cancel all 3 sessions"));
        given()
            .cookie("quarkus-credential", cookie)
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/bookings/" + rows.getFirst().id + "/cancel-all")
            .then()
            .statusCode(200);

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail", EMAIL)))
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CANCELLED);
    }

    @Test
    void dashboardTagsKOfN() {
        bookSet(null);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me")
            .then()
            .statusCode(200)
            .body(containsString("1 of 3"))
            .body(containsString("2 of 3"))
            .body(containsString("3 of 3"));
    }

    @Test
    void manageViaInviteBookingHasNoSlug() {
        var token = invites.issue(1L, typeId, Instant.now());
        var rows = bookSet(token);

        assertThat(manage(rows.get(1).manageToken)).contains("Cancel all 3 sessions").doesNotContain(SLUG);
        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/booking/" + rows.get(1).manageToken + "/cancel-all")
            .then()
            .statusCode(200)
            .body(not(containsString(SLUG)));
        assertThat(Stream
            .of(EMAIL, "pasha@x.com")
            .flatMap(to -> mailbox.getMailsSentTo(to).stream()))
            .isNotEmpty()
            .extracting(Mail::getHtml)
            .allSatisfy(html -> assertThat(html).doesNotContain(SLUG));
    }
}
