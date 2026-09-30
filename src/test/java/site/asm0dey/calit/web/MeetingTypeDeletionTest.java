package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * {@code booking.meeting_type_id} cascades (V34), so deleting a meeting type deletes its bookings —
 * every host's row of a group booking included. The delete route therefore refuses while any
 * upcoming held booking of the type exists, whoever owns the row.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"user", "admin"})
class MeetingTypeDeletionTest {
    private static final String REFUSAL = "still has upcoming bookings";

    private static Long seedType() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> MultiHostFixtures.meetingType(1L, "doomed-" + UUID.randomUUID(), 30).id);
    }

    private static Long seedBooking(Long ownerId, Long typeId, long startDaysFromNow, BookingStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var b = new Booking();
            b.ownerId = ownerId;
            b.meetingTypeId = typeId;
            b.inviteeName = "Ivy";
            b.inviteeEmail = "ivy@example.com";
            b.startUtc = Instant.now().plus(startDaysFromNow, ChronoUnit.DAYS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = status;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private static void postDelete(Long typeId, boolean expectRefusal) {
        var response = given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/meeting-types/" + typeId + "/delete")
            .then()
            .statusCode(200);
        if (expectRefusal) {
            response.body(containsString(REFUSAL));
        }
    }

    private static MeetingType type(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> MeetingType.findById(id));
    }

    private static Booking booking(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.findById(id));
    }

    @Test
    void anUpcomingBookingBlocksTheDelete() {
        var typeId = seedType();
        var bookingId = seedBooking(1L, typeId, 5, BookingStatus.CONFIRMED);

        postDelete(typeId, true);

        assertThat(type(typeId)).as("the meeting type must survive a refused delete").isNotNull();
        assertThat(booking(bookingId)).as("the upcoming booking must survive a refused delete").isNotNull();
    }

    @Test
    void onlyPastOrCancelledBookingsLetTheDeleteThroughAndCascade() {
        var typeId = seedType();
        var past = seedBooking(1L, typeId, -5, BookingStatus.CONFIRMED);
        var cancelledUpcoming = seedBooking(1L, typeId, 5, BookingStatus.CANCELLED);

        postDelete(typeId, false);

        assertThat(type(typeId)).as("the meeting type is deleted").isNull();
        assertThat(booking(past)).as("its past booking goes with it").isNull();
        assertThat(booking(cancelledUpcoming)).as("a cancelled upcoming booking does not block and goes too").isNull();
    }

    @Test
    void anUpcomingCohostRowBlocksTheDelete() {
        var typeId = seedType();
        Long cohostId = QuarkusTransaction
            .requiringNew()
            .call(() -> MultiHostFixtures.enabledUser("typedel-cohost").id);
        var cohostRow = seedBooking(cohostId, typeId, 5, BookingStatus.PENDING);

        postDelete(typeId, true);

        assertThat(type(typeId)).isNotNull();
        assertThat(booking(cohostRow)).as("another host's upcoming row must not be cascaded away").isNotNull();
        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> AppUser.count("id", cohostId))).isOne();
    }
}
