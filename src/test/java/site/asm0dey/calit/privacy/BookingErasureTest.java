package site.asm0dey.calit.privacy;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.scheduler.Reminder;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class BookingErasureTest {
    /**
     * The seeded admin owner — DatabaseResetCallback guarantees id 1.
     */
    private static final Long OWNER = ErasureFixtures.OWNER;
    @Inject
    PrivacyService privacy;

    /**
     * A second host for group-booking rows — booking.owner_id's no-overlap constraint is per-owner.
     */
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

        Booking b = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id));
        assertThat(b.inviteeName).isEmpty();
        assertThat(b.inviteeEmail).as("invitee_email is NOT NULL, so erasure blanks it").isEmpty();
        assertThat(b.answers).isEmpty();
        assertThat(b.meetLink).isNull();
        assertThat(b.title).isNull();
        assertThat(b.description).isNull();
        assertThat(b.erasedAt).isNotNull();
        assertThat(b.status).as("the slot record survives erasure").isEqualTo(BookingStatus.CONFIRMED);
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
            assertThat(BookingGuest.count("bookingId", id)).isZero();
            assertThat(Reminder.count("bookingId = ?1 and sentAt is null", id)).isZero();
            assertThat(Reminder.count("bookingId = ?1 and sentAt is not null", id))
                .as("a sent reminder carries no personal data and must survive erasure")
                .isOne();
            assertThat(EmailOutbox.count("bookingId", id)).isZero();
        });
    }

    @Test
    void anonymiseIsIdempotent() {
        var id = ErasureFixtures.seedPastBookingId();
        privacy.anonymise(id);
        Instant first = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id).erasedAt);
        privacy.anonymise(id);
        Instant second = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id).erasedAt);
        assertThat(second).as("a second erasure must not restamp the row").isEqualTo(first);
    }

    @Test
    void anonymiseManyErasesAllAndReportsTheCount() {
        var a = ErasureFixtures.seedPastBookingId();
        var b = ErasureFixtures.seedPastBookingId();

        int count = privacy.anonymise(List.of(a, b));
        assertThat(count).isEqualTo(2);

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(Booking.<Booking>findById(a).erasedAt).isNotNull();
            assertThat(Booking.<Booking>findById(b).erasedAt).isNotNull();
        });

        assertThat(privacy.anonymise(List.of(a, b))).as("already-erased ids are not re-counted").isZero();
    }

    @Test
    void anonymiseErasesEveryRowInAGroupBooking() {
        var groupId = seedGroupBooking();
        Long anyRowId = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.group(groupId).get(0).id);

        privacy.anonymise(anyRowId);

        List<Booking> rows = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.group(groupId));
        assertThat(rows).as("both host rows for the group must still exist").hasSize(2);
        for (Booking row : rows) {
            assertThat(row.inviteeName).as("owner " + row.ownerId + "'s row must be blanked too").isEmpty();
            assertThat(row.inviteeEmail).isEmpty();
            assertThat(row.answers).isEmpty();
            assertThat(row.erasedAt).isNotNull();
        }
    }

    /**
     * Retention measures each host's row against that host's own window, so a group can be partly
     * erased. The erased row's token must still reach the co-host copy: export reads from it and
     * erasure finishes the job; only a fully erased group 404s.
     */
    @Test
    void aPartlyErasedGroupIsStillExportableAndErasableThroughTheErasedRowsToken() {
        var groupId = seedGroupBooking();
        List<Booking> rows = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.group(groupId));
        Booking erasedRow = rows.get(0);
        // as a retention sweep would: this row only
        privacy.anonymise(List.of(erasedRow.id));

        String exported = privacy.exportBooking(erasedRow.manageToken);
        assertThat(exported).as("the co-host row still holds the invitee's data: " + exported).contains("Dana Vogel");

        privacy.eraseByManageToken(erasedRow.manageToken);

        QuarkusTransaction
            .requiringNew()
            .run(() -> Booking
                .<Booking>group(groupId)
                .forEach(r -> assertThat(r.isErased()).as("row " + r.id + " must be erased").isTrue()));
        assertThatExceptionOfType(NotFoundException.class).isThrownBy(() -> privacy.exportBooking(erasedRow.manageToken));
        assertThatExceptionOfType(NotFoundException.class).isThrownBy(() -> privacy.eraseByManageToken(
                erasedRow.manageToken
        ));
        String coHostManageToken = rows.get(1).manageToken;
        assertThatExceptionOfType(NotFoundException.class).isThrownBy(() -> privacy.exportBooking(coHostManageToken));
    }

    @Test
    void erasingAPastBookingDoesNotTouchGoogle() {
        String token = ErasureFixtures.seedPastBooking();

        ErasureReport report = privacy.eraseByManageToken(token);
        assertThat(report.google())
            .as("no Google event id on this row, and Google is disabled in %test")
            .isEqualTo(ErasureReport.GoogleOutcome.NOT_APPLICABLE);
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
        assertThat(report.google())
            .as("a stored event id with no connected Google account cannot be deleted")
            .isEqualTo(ErasureReport.GoogleOutcome.UNREACHABLE);
    }
}
