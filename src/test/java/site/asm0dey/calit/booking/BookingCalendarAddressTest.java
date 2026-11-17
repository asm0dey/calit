package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * The event address columns round-trip, and a row without one reports a null ref.
 */
@QuarkusTest
class BookingCalendarAddressTest {
    @Test
    @TestTransaction
    void storesAndReadsBackTheEventAddress() {
        GoogleCredential cred = seedCredential("sub-address-test");

        Booking b = seed();
        b.eventId = "evt-1";
        b.calendarRemoteId = "work@example.com";
        b.calendarAccountId = cred.id;
        b.persistAndFlush();

        Booking loaded = Booking.findById(b.id);
        assertThat(loaded.calendarRef().remoteCalendarId()).isEqualTo("work@example.com");
        assertThat(loaded.calendarRef().accountId()).isEqualTo(cred.id);
    }

    @Test
    @TestTransaction
    void preMigrationRowHasNoRef() {
        Booking b = seed();
        b.persistAndFlush();

        assertThat(Booking.<Booking>findById(b.id).calendarRef()).isNull();
    }

    /**
     * Seed a real GoogleCredential row so a booking's google_credential_id FK holds.
     */
    private static GoogleCredential seedCredential(String sub) {
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt";
        cred.googleSub = sub;
        cred.persist();
        return cred;
    }

    /**
     * Minimal valid booking row for owner 1 (the always-present admin), on a type it also owns.
     */
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
