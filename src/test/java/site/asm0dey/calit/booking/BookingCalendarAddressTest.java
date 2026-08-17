package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarRef;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.google.GoogleCredential;

/** The event address columns round-trip, and a row without one reports a null ref. */
@QuarkusTest
class BookingCalendarAddressTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    // Owner tz Europe/Amsterdam; a slot a week out is never in the past. Mirrors BookServiceTest.
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();
    private static final Instant SLOT_10 = DAY.atTime(10, 0).atZone(ZONE).toInstant();

    @Test
    @TestTransaction
    void storesAndReadsBackTheEventAddress() {
        GoogleCredential cred = seedCredential("sub-address-test");

        Booking b = seed();
        b.googleEventId = "evt-1";
        b.googleCalendarId = "work@example.com";
        b.googleCredentialId = cred.id;
        b.persistAndFlush();

        Booking loaded = Booking.findById(b.id);
        assertEquals("work@example.com", loaded.calendarRef().googleCalendarId());
        assertEquals(cred.id, loaded.calendarRef().credentialId());
    }

    @Test
    @TestTransaction
    void preMigrationRowHasNoRef() {
        Booking b = seed();
        b.persistAndFlush();

        assertNull(Booking.<Booking>findById(b.id).calendarRef());
    }

    @Test
    @TestTransaction
    void bookingRecordsTheCalendarTheEventWasCreatedOn() {
        GoogleCredential cred = seedCredential("sub-created-test");

        stubGoogle(new CalendarRef(cred.id, "work@example.com"), "evt-created");

        Booking booked = bookAnySlot("addr-created");

        Booking loaded = Booking.findById(booked.id);
        assertEquals("work@example.com", loaded.googleCalendarId);
        assertEquals(cred.id, loaded.googleCredentialId);
    }

    @Test
    @TestTransaction
    void cancelDeletesOnTheStoredCalendar() {
        GoogleCredential cred = seedCredential("sub-cancel-test");

        CalendarRef ref = new CalendarRef(cred.id, "work@example.com");
        stubGoogle(ref, "evt-cancel");
        Booking booked = bookAnySlot("addr-cancel");

        bookingService.cancel(booked.manageToken, true);

        verify(calendarPort).deleteEvent(eq(booked.ownerId), eq(ref), eq("evt-cancel"));
    }

    @Test
    @TestTransaction
    void cancelOfAPreMigrationRowPassesNoAddress() {
        stubGoogle(null, "evt-old"); // createEvent reports no address, as pre-V26 rows have none
        Booking booked = bookAnySlot("addr-old");

        bookingService.cancel(booked.manageToken, true);

        verify(calendarPort).deleteEvent(eq(booked.ownerId), isNull(), eq("evt-old"));
    }

    @Test
    @TestTransaction
    void rescheduleOfAnApprovalTypeDeletesOnTheStoredCalendar() {
        // Task 4 Step 4: an invitee-initiated reschedule of an approval type re-enters PENDING and
        // deletes the prior Google event. `priorRef` in BookingService.reschedule/applyRescheduleOutcome
        // must be captured BEFORE the re-approval block clears googleCalendarId/googleCredentialId --
        // otherwise the delete would address the wrong (cleared/null) ref.
        GoogleCredential cred = seedCredential("sub-resched-test");
        CalendarRef ref = new CalendarRef(cred.id, "work@example.com");
        stubGoogle(ref, "evt-resched");
        Booking booked = bookAnySlot("addr-resched", true);
        bookingService.approve(booked.id);

        // Invitee-initiated (byOwner defaults false) -> triggers re-approval.
        bookingService.reschedule(booked.manageToken, SLOT_10);

        verify(calendarPort).deleteEvent(eq(booked.ownerId), eq(ref), eq("evt-resched"));

        Booking loaded = Booking.findById(booked.id);
        assertEquals(BookingStatus.PENDING, loaded.status);
        assertNull(loaded.googleEventId);
        assertNull(loaded.googleCalendarId);
        assertNull(loaded.googleCredentialId);
    }

    /** Google connected, no busy time, createEvent returning an event at the given address. */
    private void stubGoogle(CalendarRef address, String eventId) {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent(eventId, null, null, address));
    }

    /** Seed owner settings + a 09:00-11:00 type on DAY, then book the 09:00 slot. */
    private Booking bookAnySlot(String slug) {
        return bookAnySlot(slug, false);
    }

    /** Seed owner settings + a 09:00-11:00 type on DAY, then book the 09:00 slot. */
    private Booking bookAnySlot(String slug, boolean requiresApproval) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = MeetingType.LocationType.GOOGLE_MEET;
        t.requiresApproval = requiresApproval;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();

        return bookingService.book(1L, slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
    }

    /** Seed a real GoogleCredential row so a booking's google_credential_id FK holds. */
    private static GoogleCredential seedCredential(String sub) {
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt";
        cred.googleSub = sub;
        cred.persist();
        return cred;
    }

    /** Minimal valid booking row for owner 1 (the always-present admin), on a type it also owns. */
    private static Booking seed() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "address-seed";
        t.slug = "address-seed-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.persist();

        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = t.id;
        b.inviteeName = "Ada";
        b.inviteeEmail = "ada@example.com";
        b.startUtc = Instant.parse("2026-09-01T10:00:00Z");
        b.endUtc = Instant.parse("2026-09-01T10:30:00Z");
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        return b;
    }
}
