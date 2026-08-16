package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCredential;

/** The event address columns round-trip, and a row without one reports a null ref. */
@QuarkusTest
class BookingCalendarAddressTest {

    @Test
    @TestTransaction
    void storesAndReadsBackTheEventAddress() {
        // google_credential_id carries a real FK to google_credential(id); seed a row so the FK holds.
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt";
        cred.googleSub = "sub-address-test";
        cred.persist();

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
