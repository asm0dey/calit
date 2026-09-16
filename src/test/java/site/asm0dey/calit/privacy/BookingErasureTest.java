package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.scheduler.Reminder;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class BookingErasureTest {

    /** The seeded admin owner — DatabaseResetCallback guarantees id 1. */
    private static final Long OWNER = ErasureFixtures.OWNER;

    @Inject
    PrivacyService privacy;

    /** A second host for group-booking rows — booking.owner_id's no-overlap constraint is per-owner. */
    private static Long secondOwnerId() {
        var u = AppUser.create("cohost-" + UUID.randomUUID(), "x", false);
        u.persist();
        return u.id;
    }

    /**
     * Two CONFIRMED rows sharing one {@code groupId}, one per host (mirrors
     * {@code BookingService.bookGroup}: same invitee data, same slot, different {@code owner_id}).
     */
    private UUID seedGroupBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var meetingTypeId = ErasureFixtures.firstMeetingTypeId();
            var secondOwner = secondOwnerId();
            var groupId = UUID.randomUUID();
            var start = Instant.now().minus(30, ChronoUnit.DAYS);
            var end = start.plus(30, ChronoUnit.MINUTES);
            for (Long ownerId : List.of(OWNER, secondOwner)) {
                var b = new Booking();
                b.ownerId = ownerId;
                b.meetingTypeId = meetingTypeId;
                b.inviteeName = "Dana Vogel";
                b.inviteeEmail = "dana@example.com";
                b.answers = new java.util.HashMap<>(Map.of("why", "annual review"));
                b.startUtc = start;
                b.endUtc = end;
                b.status = BookingStatus.CONFIRMED;
                b.createdAt = Instant.now().minus(31, ChronoUnit.DAYS);
                b.manageToken = UUID.randomUUID().toString();
                b.groupId = groupId;
                b.persist();
            }
            return groupId;
        });
    }

    @Test
    void anonymiseBlanksEveryPersonalColumn() {
        var id = ErasureFixtures.seedPastBookingId();
        privacy.anonymise(id);

        Booking b = QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findById(id));
        assertEquals("", b.inviteeName);
        assertEquals("", b.inviteeEmail, "invitee_email is NOT NULL, so erasure blanks it");
        assertTrue(b.answers.isEmpty());
        assertNull(b.meetLink);
        assertNull(b.title);
        assertNull(b.description);
        assertNotNull(b.erasedAt);
        assertEquals(BookingStatus.CONFIRMED, b.status, "the slot record survives erasure");
    }

    @Test
    void anonymiseRemovesGuestsRemindersAndParkedMail() {
        var id = ErasureFixtures.seedPastBookingId();
        // A sent reminder must survive: only the "sentAt is null" filter's target should be removed.
        QuarkusTransaction.requiringNew().run(() -> {
            var sent = new Reminder();
            sent.bookingId = id;
            sent.sendAt = Instant.now().minus(29, ChronoUnit.DAYS);
            sent.kind = Reminder.KIND_REMINDER;
            sent.sentAt = Instant.now().minus(29, ChronoUnit.DAYS).plusSeconds(5);
            sent.persist();
        });

        privacy.anonymise(id);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0L, BookingGuest.count("bookingId", id));
            assertEquals(0L, Reminder.count("bookingId = ?1 and sentAt is null", id));
            assertEquals(
                    1L,
                    Reminder.count("bookingId = ?1 and sentAt is not null", id),
                    "a sent reminder carries no personal data and must survive erasure");
            assertEquals(0L, EmailOutbox.count("bookingId", id));
        });
    }

    @Test
    void anonymiseIsIdempotent() {
        var id = ErasureFixtures.seedPastBookingId();
        privacy.anonymise(id);
        Instant first = QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findById(id).erasedAt);
        privacy.anonymise(id);
        Instant second = QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findById(id).erasedAt);
        assertEquals(first, second, "a second erasure must not restamp the row");
    }

    @Test
    void anonymiseManyErasesAllAndReportsTheCount() {
        var a = ErasureFixtures.seedPastBookingId();
        var b = ErasureFixtures.seedPastBookingId();

        int count = privacy.anonymise(List.of(a, b));
        assertEquals(2, count);

        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(Booking.<Booking>findById(a).erasedAt);
            assertNotNull(Booking.<Booking>findById(b).erasedAt);
        });

        assertEquals(0, privacy.anonymise(List.of(a, b)), "already-erased ids are not re-counted");
    }

    @Test
    void anonymiseErasesEveryRowInAGroupBooking() {
        var groupId = seedGroupBooking();
        Long anyRowId = QuarkusTransaction.requiringNew()
                .call(() -> Booking.group(groupId).get(0).id);

        privacy.anonymise(anyRowId);

        List<Booking> rows = QuarkusTransaction.requiringNew().call(() -> Booking.group(groupId));
        assertEquals(2, rows.size(), "both host rows for the group must still exist");
        for (Booking row : rows) {
            assertEquals("", row.inviteeName, "owner " + row.ownerId + "'s row must be blanked too");
            assertEquals("", row.inviteeEmail);
            assertTrue(row.answers.isEmpty());
            assertNotNull(row.erasedAt);
        }
    }

    @Test
    void erasingAPastBookingDoesNotTouchGoogle() {
        String token = ErasureFixtures.seedPastBooking();

        ErasureReport report = privacy.eraseByManageToken(token);
        assertEquals(
                ErasureReport.GoogleOutcome.NOT_APPLICABLE,
                report.google(),
                "no Google event id on this row, and Google is disabled in %test");
    }

    @Test
    void erasingAPastBookingWithAStoredGoogleEventAttemptsBestEffortDelete() {
        var id = ErasureFixtures.seedPastBookingId();
        String token = QuarkusTransaction.requiringNew().call(() -> {
            Booking b = Booking.<Booking>findById(id);
            b.googleEventId = "evt-123";
            return b.manageToken;
        });

        ErasureReport report = privacy.eraseByManageToken(token);

        // No GoogleCredential row is seeded in %test, so CalendarPort.isConnected(...) is false and
        // the best-effort delete is skipped without being attempted -- this is the "Google is not
        // connected" branch of the ruling, not "the call threw". Both land on UNREACHABLE; this
        // assertion pins the disconnected case specifically.
        assertEquals(
                ErasureReport.GoogleOutcome.UNREACHABLE,
                report.google(),
                "a stored event id with no connected Google account cannot be deleted");
    }
}
