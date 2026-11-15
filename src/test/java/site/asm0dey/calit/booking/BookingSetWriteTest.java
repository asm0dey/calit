package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.BookingRequested;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * {@code bookSet} runs in its own transaction and must roll back as a whole, so these tests commit
 * their seed data and read results back in fresh transactions instead of using {@code @TestTransaction}.
 */
@QuarkusTest
class BookingSetWriteTest {
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate MONDAY = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    private static final String EMAIL = "sam@x.com";
    @Inject
    BookingService bookingService;
    @Inject
    BookingInviteService invites;
    @InjectMock
    CalendarPort calendarPort;
    @InjectMock
    CaptchaVerifier captchaVerifier;

    /**
     * Collects fired booking events; synchronous, so it sees them inside the booking transaction.
     */
    @ApplicationScoped
    static class EventLog {
        private static final List<Long> CONFIRMED = new CopyOnWriteArrayList<>();
        private static final List<Long> REQUESTED = new CopyOnWriteArrayList<>();

        void onConfirmed(@Observes BookingConfirmed e) {
            CONFIRMED.add(e.bookingId());
        }

        void onRequested(@Observes BookingRequested e) {
            REQUESTED.add(e.bookingId());
        }

        static List<Long> confirmed() {
            return CONFIRMED;
        }

        static List<Long> requested() {
            return REQUESTED;
        }
    }

    @BeforeEach
    void clearEvents() {
        EventLog.confirmed().clear();
        EventLog.requested().clear();
    }

    private static Instant at(int hour, int minute) {
        return MONDAY.atTime(hour, minute).atZone(AMS).toInstant();
    }

    private static SlotPick pick(int hour, int minute, int minutes) {
        return new SlotPick(at(hour, minute), minutes);
    }

    /**
     * Single-host 30-min type (60 also offered), sets of 2..3 allowed on one day, Monday 09-17.
     */
    private static MeetingType seedType(String slug, boolean approval) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.meetingType(1L, slug, 30);
            t.requiresApproval = approval;
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = true;
            var d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = 60;
            d.persist();
            return t;
        });
    }

    private List<Booking> bookSet(String slug, List<SlotPick> picks) {
        return bookSet(slug, picks, null);
    }

    private List<Booking> bookSet(String slug, List<SlotPick> picks, String invite) {
        return bookingService.bookSet(1L, slug, picks, "Sam", EMAIL, Map.of(), "tok", null, "", "en", List.of(), invite);
    }

    private static List<Booking> rowsFor(String email) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail", email));
    }

    private static void occupy(Instant start, int minutes) {
        QuarkusTransaction.requiringNew().run(() -> {
            var b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = MeetingType.findBySlug(1L, "set").id;
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

    private static CreatedEvent anyCreateEvent(CalendarPort port) {
        return port.createEvent(
                anyLong(),
                any(),
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyList(),
                anyBoolean(),
                any()
        );
    }

    @Test
    void booksThreePicksAsOneSet() {
        seedType("set", false);

        var booked = bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 60), pick(13, 0, 30)));

        assertThat(booked)
            .extracting(b -> b.startUtc)
            .containsExactly(at(10, 0), at(11, 0), at(13, 0));
        var rows = rowsFor(EMAIL);
        assertThat(rows).hasSize(3);
        assertThat(rows)
            .extracting(b -> b.setId)
            .doesNotContainNull()
            .containsOnly(rows.getFirst().setId);
        assertThat(rows)
            .extracting(b -> b.manageToken)
            .doesNotHaveDuplicates();
        assertThat(rows).extracting(BookingService::lengthOf).containsExactlyInAnyOrder(30, 60, 30);
        assertThat(rows)
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CONFIRMED);
    }

    @Test
    void onePickIsPlainBooking() {
        QuarkusTransaction.requiringNew().run(() -> {
            seedType("set", false);
            MeetingType.findBySlug(1L, "set").multiSlotMin = 1;
        });

        var booked = bookSet("set", List.of(pick(10, 0, 30)));

        assertThat(booked).hasSize(1);
        assertThat(rowsFor(EMAIL))
            .singleElement()
            .satisfies(b -> assertThat(b.setId).isNull());
    }

    @Test
    void conflictOnLastPickBooksNothing() {
        seedType("set", false);
        when(calendarPort.isConnected(1L)).thenReturn(true);
        occupy(at(13, 0), 30);

        var picks = List.of(pick(10, 0, 30), pick(11, 0, 30), pick(13, 0, 30));
        assertThatThrownBy(() -> bookSet("set", picks)).isInstanceOf(BookingConflictException.class);

        assertThat(rowsFor(EMAIL)).isEmpty();
        verify(calendarPort, never())
            .createEvent(
                    anyLong(),
                    any(),
                    any(),
                    anyString(),
                    anyString(),
                    any(),
                    any(),
                    anyList(),
                    anyBoolean(),
                    any()
            );
        assertThat(EventLog.confirmed()).isEmpty();
    }

    @Test
    void picksWithinBufferConflict() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                seedType("set", false);
                MeetingType t = MeetingType.findBySlug(1L, "set");
                t.bufferBeforeMinutes = 15;
                t.bufferAfterMinutes = 15;
                // a 10-minute lattice puts 10:40 on offer, so only the buffer can refuse it
                t.slotIntervalMinutes = 10;
            });
        var offered = QuarkusTransaction
            .requiringNew()
            .call(() -> bookingService.availableSlots(MeetingType.findBySlug(1L, "set"), MONDAY, MONDAY));
        assertThat(offered)
            .extracting(s -> s.start().toInstant())
            .contains(at(10, 40));

        var picks = List.of(pick(10, 0, 30), pick(10, 40, 30));
        assertThatThrownBy(() -> bookSet("set", picks)).isInstanceOf(BookingConflictException.class);

        assertThat(rowsFor(EMAIL)).isEmpty();
    }

    @Test
    void inviteConsumedOnceForTheSet() {
        var typeId = seedType("set", false).id;
        var token = invites.issue(1L, typeId, Instant.now());
        var other = invites.issue(1L, typeId, Instant.now());
        occupy(at(14, 0), 30);

        var picks = List.of(pick(10, 0, 30), pick(14, 0, 30));
        assertThatThrownBy(() -> bookSet("set", picks, other)).isInstanceOf(BookingConflictException.class);
        assertThat(invites.find(1L, other, Instant.now())).isNotNull();

        assertThat(bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 30), pick(12, 0, 30)), token)).hasSize(3);
        assertThat(invites.find(1L, token, Instant.now())).isNull();
        assertThat(invites.find(1L, other, Instant.now())).isNotNull();
    }

    @Test
    void captchaVerifiedOnce() {
        seedType("set", false);

        bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 30), pick(12, 0, 30)));

        verify(captchaVerifier, times(1)).verify(any(), any());
    }

    @Test
    void dailyCapCountsSetOnce() {
        seedType("set", false);
        // default cap is 10: 8 earlier bookings today leave room for exactly 2 more
        QuarkusTransaction.requiringNew().run(() -> {
            for (var i = 0; i < 8; i++) {
                var b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = MeetingType.findBySlug(1L, "set").id;
                b.inviteeName = "Sam";
                b.inviteeEmail = EMAIL;
                b.startUtc = at(9, 0).minus(i + 1L, ChronoUnit.DAYS);
                b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CANCELLED;
                b.createdAt = Instant.now();
                b.manageToken = UUID.randomUUID().toString();
                b.persist();
            }
        });

        assertThat(bookSet("set", List.of(pick(9, 0, 30), pick(10, 0, 30), pick(11, 0, 30)))).hasSize(3);
        assertThat(bookSet("set", List.of(pick(12, 0, 30), pick(13, 0, 30)))).hasSize(2);
        var picks = List.of(pick(14, 0, 30), pick(15, 0, 30));
        assertThatThrownBy(() -> bookSet("set", picks)).isInstanceOf(RateLimitException.class);
    }

    @Test
    void coHostedSetWritesRowsPerHost() {
        QuarkusTransaction.requiringNew().run(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            AppUser v = MultiHostFixtures.enabledUser("volodya");
            MultiHostFixtures.settings(v.id, "volodya");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.acceptedTwoHostType(1L, v.id, "set", 30, false);
            t.multiSlotMin = 2;
            t.multiSlotMax = 2;
            t.multiSlotSameDay = true;
        });

        var booked = bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 30)));

        assertThat(booked)
            .extracting(b -> b.ownerId)
            .containsOnly(1L);
        var rows = rowsFor(EMAIL);
        assertThat(rows).hasSize(4);
        assertThat(rows)
            .extracting(b -> b.groupId)
            .doesNotContainNull()
            .hasSize(4)
            .containsOnly(booked.get(0).groupId, booked.get(1).groupId);
        assertThat(booked.get(0).groupId).isNotEqualTo(booked.get(1).groupId);
        assertThat(rows)
            .extracting(b -> b.setId)
            .doesNotContainNull()
            .containsOnly(rows.getFirst().setId);
        assertThat(EventLog.confirmed()).containsExactly(booked.getFirst().id);
    }

    @Test
    void autoSetCreatesMirrorEventPerBookingAfterFlush() {
        seedType("set", false);
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(anyCreateEvent(calendarPort)).thenReturn(new CreatedEvent("evt", null, null, null));

        var booked = bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 30)));

        for (var b : booked) {
            verify(calendarPort, times(1))
                .createEvent(
                        eq(1L),
                        any(),
                        eq(b.manageToken),
                        anyString(),
                        anyString(),
                        eq(b.startUtc),
                        eq(b.endUtc),
                        anyList(),
                        anyBoolean(),
                        any()
                );
        }
        verify(calendarPort, times(2))
            .createEvent(
                    anyLong(),
                    any(),
                    any(),
                    anyString(),
                    anyString(),
                    any(),
                    any(),
                    anyList(),
                    anyBoolean(),
                    any()
            );
        assertThat(rowsFor(EMAIL))
            .extracting(b -> b.eventId)
            .containsOnly("evt");
    }

    @Test
    void failedMirrorEventMidSetDeletesTheEventsAlreadyCreated() {
        var token = invites.issue(1L, seedType("set", false).id, Instant.now());
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(anyCreateEvent(calendarPort))
            .thenReturn(new CreatedEvent("evt-1", null, null, null))
            .thenThrow(new UncheckedIOException(new IOException("calendar down")));

        var picks = List.of(pick(10, 0, 30), pick(11, 0, 30), pick(12, 0, 30));
        assertThatThrownBy(() -> bookSet("set", picks, token)).isInstanceOf(UncheckedIOException.class);

        assertThat(rowsFor(EMAIL)).isEmpty();
        assertThat(invites.find(1L, token, Instant.now())).isNotNull();
        verify(calendarPort, times(1)).deleteEvent(eq(1L), any(), eq("evt-1"));
        verify(calendarPort, times(1)).deleteEvent(any(), any(), any());
        assertThat(EventLog.confirmed()).isEmpty();
    }

    @Test
    void oneRequestedEventForApprovalSet() {
        seedType("set", true);

        bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 30), pick(12, 0, 30)));

        var rows = rowsFor(EMAIL);
        assertThat(rows)
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.PENDING);
        Long lead = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.leadOfSet(rows.getFirst().setId, 1L).id);
        assertThat(EventLog.requested()).containsExactly(lead);
        assertThat(EventLog.confirmed()).isEmpty();
        verify(calendarPort, never())
            .createEvent(
                    anyLong(),
                    any(),
                    any(),
                    anyString(),
                    anyString(),
                    any(),
                    any(),
                    anyList(),
                    anyBoolean(),
                    any()
            );
    }

    @Test
    void oneConfirmedEventForAutoSet() {
        seedType("set", false);

        bookSet("set", List.of(pick(10, 0, 30), pick(11, 0, 30), pick(12, 0, 30)));

        var setId = rowsFor(EMAIL).getFirst().setId;
        Long lead = QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.leadOfSet(setId, 1L).id);
        assertThat(EventLog.confirmed()).containsExactly(lead);
        assertThat(EventLog.requested()).isEmpty();
    }

    @Test
    void setOnTypeWithoutMultiSlotRefused() {
        QuarkusTransaction.requiringNew().run(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MultiHostFixtures.meetingType(1L, "plain", 30);
        });

        var picks = List.of(pick(10, 0, 30), pick(11, 0, 30));
        assertThatThrownBy(() -> bookSet("plain", picks)).isInstanceOf(BookingValidationException.class);
        assertThat(rowsFor(EMAIL)).isEmpty();
    }

    @Test
    void singleSlotBookRefusedWhenMinAboveOne() {
        seedType("set", false);

        var start = at(10, 0);
        var noAnswers = Map.<String, String>of();
        var noGuests = List.<String>of();
        assertThatThrownBy(() -> bookingService.book(
                1L,
                "set",
                start,
                "Sam",
                EMAIL,
                noAnswers,
                "tok",
                null,
                "",
                "en",
                noGuests,
                30,
                null
        ))
            .isInstanceOf(BookingValidationException.class);
        assertThat(rowsFor(EMAIL)).isEmpty();
    }
}
