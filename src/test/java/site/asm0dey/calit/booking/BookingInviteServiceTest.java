package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.LoginTicketService;

/**
 * Invites are committed rows consumed inside {@code book()}'s transaction, so these tests commit
 * their own data ({@link QuarkusTransaction}) instead of rolling back with {@code @TestTransaction}.
 */
@QuarkusTest
class BookingInviteServiceTest {
    @Inject
    BookingInviteService invites;
    @Inject
    BookingService bookingService;
    @InjectMock
    CalendarPort calendarPort;
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();
    private static final Instant SLOT_10 = DAY.atTime(10, 0).atZone(ZONE).toInstant();

    @Test
    void issueStoresOnlyTheHash() {
        seed("a");
        // timestamptz keeps microseconds; a finer Instant would not round-trip
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var token = invites.issue(1L, typeId("a"), now);

        BookingInvite row =
                QuarkusTransaction
            .requiringNew()
            .call(() -> BookingInvite.<BookingInvite>findAll().firstResult());
        assertThat(row.tokenHash).isNotEqualTo(token).isEqualTo(LoginTicketService.sha256Hex(token));
        assertThat(row.expiresAt).isEqualTo(now.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void findRefusesExpiredAndForeignOwner() {
        seed("a");
        var now = Instant.now();
        var token = invites.issue(1L, typeId("a"), now);

        assertThat(invites.find(1L, token, now)).isNotNull();
        assertThat(invites.find(1L, token, now.plus(31, ChronoUnit.DAYS))).isNull();
        assertThat(invites.find(otherOwnerId(), token, now)).isNull();
    }

    @Test
    void issueDeletesThatOwnersExpiredRows() {
        seed("a");
        var other = otherOwnerId();
        var typeId = typeId("a");
        var now = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> {
            expiredRow(1L, typeId, "mine", now);
            var otherType = new MeetingType();
            otherType.ownerId = other;
            otherType.name = "o";
            otherType.slug = "o";
            otherType.durationMinutes = 60;
            otherType.persist();
            expiredRow(other, otherType.id, "theirs", now);
        });

        invites.issue(1L, typeId, now);

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> BookingInvite.<BookingInvite>listAll()))
            .extracting(i -> i.tokenHash)
            .doesNotContain("mine")
            .contains("theirs");
    }

    @Test
    void bookingConsumesInvite() {
        seed("a");
        var token = invites.issue(1L, typeId("a"), Instant.now());

        var booking = book("a", SLOT_09, token);

        assertThat(booking.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(invites.find(1L, token, Instant.now())).isNull();
    }

    @Test
    void secondBookingIsRefused() {
        seed("a");
        var token = invites.issue(1L, typeId("a"), Instant.now());
        book("a", SLOT_09, token);

        assertThatThrownBy(() -> book("a", SLOT_10, token)).isInstanceOf(InvalidInviteException.class);
    }

    @Test
    void concurrentBookingsHaveOneWinner() throws Exception {
        seed("a");
        var token = invites.issue(1L, typeId("a"), Instant.now());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return book("a", SLOT_09, token);
            });
            var second = pool.submit(() -> {
                start.await();
                return book("a", SLOT_10, token);
            });
            start.countDown();
            var outcomes = new ArrayList<Object>();
            for (var f : List.of(first, second)) {
                try {
                    outcomes.add(f.get());
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            assertThat(outcomes).filteredOn(Booking.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(InvalidInviteException.class::isInstance).hasSize(1);
        }
    }

    @Test
    void failedBookingLeavesInviteUsable() {
        seed("a");
        var token = invites.issue(1L, typeId("a"), Instant.now());
        // 03:00 local is outside the 09:00-11:00 availability window
        var unavailable = DAY.atTime(3, 0).atZone(ZONE).toInstant();

        assertThatThrownBy(() -> book("a", unavailable, token)).isInstanceOf(BookingConflictException.class);
        assertThat(invites.find(1L, token, Instant.now())).isNotNull();
    }

    @Test
    void failureAfterConsumeRollsBackConsumeAndBooking() {
        seed("a");
        var token = invites.issue(1L, typeId("a"), Instant.now());
        // Slot check passes and the invite is consumed; the calendar write then fails, so the
        // @Transactional boundary must undo both the booking row and the consume.
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.createEvent(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenThrow(new IllegalStateException("calendar down"));

        assertThatThrownBy(() -> book("a", SLOT_09, token)).isInstanceOf(RuntimeException.class);

        assertThat(invites.find(1L, token, Instant.now())).isNotNull();
        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.count("meetingTypeId", typeId("a")))).isZero();
    }

    @Test
    void cancelDoesNotReviveInvite() {
        seed("a");
        var token = invites.issue(1L, typeId("a"), Instant.now());
        var booking = book("a", SLOT_09, token);

        bookingService.cancel(booking.manageToken);

        assertThat(invites.find(1L, token, Instant.now())).isNull();
        assertThatThrownBy(() -> book("a", SLOT_10, token)).isInstanceOf(InvalidInviteException.class);
    }

    @Test
    void expiredInviteRefusedAtBooking() {
        seed("a");
        var typeId = typeId("a");
        QuarkusTransaction.requiringNew().run(() -> {
            var row = expiredRow(1L, typeId, LoginTicketService.sha256Hex("old"), Instant.now());
            row.expiresAt = Instant.now().minusSeconds(60);
        });

        assertThatThrownBy(() -> book("a", SLOT_09, "old")).isInstanceOf(InvalidInviteException.class);
    }

    @Test
    void inviteForAnotherTypeRefused() {
        seed("a");
        seed("b");
        var token = invites.issue(1L, typeId("a"), Instant.now());

        assertThatThrownBy(() -> book("b", SLOT_09, token)).isInstanceOf(InvalidInviteException.class);
        assertThat(invites.find(1L, token, Instant.now())).isNotNull();
    }

    @Test
    void nullInviteBooksAsToday() {
        seed("a");

        var booking = bookingService.book(
                1L,
                "a",
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "",
                "en",
                List.of(),
                60
        );

        assertThat(booking.status).isEqualTo(BookingStatus.CONFIRMED);
    }

    private Booking book(String slug, Instant start, String token) {
        return bookingService.book(
                1L,
                slug,
                start,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "",
                "en",
                List.of(),
                60,
                token
        );
    }

    private static BookingInvite expiredRow(Long ownerId, Long typeId, String hash, Instant now) {
        var row = new BookingInvite();
        row.ownerId = ownerId;
        row.meetingTypeId = typeId;
        row.tokenHash = hash;
        row.expiresAt = now.minusSeconds(1);
        row.persist();
        return row;
    }

    private Long typeId(String slug) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> MeetingType.findBySlug(1L, slug).id);
    }

    private Long otherOwnerId() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var u = new AppUser();
            u.username = "invite-other";
            u.passwordHash = "x";
            u.roles = "user";
            u.enabled = true;
            u.isAdmin = false;
            u.createdAt = Instant.now();
            u.persist();
            return u.id;
        });
    }

    private void seed(String slug) {
        QuarkusTransaction.requiringNew().run(() -> {
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
            t.name = slug;
            t.slug = slug;
            t.durationMinutes = 60;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.persist();
            if (AvailabilityRule.count("ownerId = 1 and dayOfWeek = ?1", DAY.getDayOfWeek()) == 0) {
                var r = new AvailabilityRule();
                r.ownerId = 1L;
                r.dayOfWeek = DAY.getDayOfWeek();
                r.startTime = LocalTime.of(9, 0);
                r.endTime = LocalTime.of(11, 0);
                r.meetingTypeId = null;
                r.persist();
            }
        });
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }
}
