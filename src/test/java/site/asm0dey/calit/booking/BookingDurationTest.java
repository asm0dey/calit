package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class BookingDurationTest {

    private static final Long OWNER = 1L;

    @Inject
    BookingService bookingService;

    // DatabaseResetCallback truncates owner_settings per test but does not reseed it; hostFreeSlots
    // / assertSlotAvailable read OwnerSettings.forOwner(ownerId).timezone unguarded, so any test that
    // actually generates slots for an owner needs that owner's row seeded first.
    @Transactional
    @BeforeEach
    void seedSettings() {
        OwnerSettings s = OwnerSettings.forOwner(OWNER);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = OWNER;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    @Transactional
    MeetingType seed(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.horizonDays = 60;
        t.persist();
        for (int len : new int[] {60, 120}) {
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = len;
            d.persist();
        }
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = OWNER;
            r.meetingTypeId = t.id;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.persist();
        }
        return t;
    }

    @Test
    void bookingAtAChosenLengthSetsTheEndAccordingly() {
        MeetingType t = seed("dur-book");
        var slot = bookingService
                .availableSlots(
                        t, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.util.Set.of(), 120)
                .getFirst();

        // 12-arg order: ownerId, slug, startUtc, name, email, answers, turnstileToken,
        // altchaSolution, honeypot, locale, guestEmails, durationMinutes.
        Booking b = bookingService.book(
                OWNER,
                t.slug,
                slot.start().toInstant(),
                "Ada",
                "ada@example.test",
                Map.of(),
                null,
                null,
                null,
                "en",
                List.of(),
                120);

        assertEquals(120, Duration.between(b.startUtc, b.endUtc).toMinutes());
    }

    @Test
    void aLengthOutsideTheAllowedSetIsRejected() {
        MeetingType t = seed("dur-reject");
        var slot = bookingService
                .availableSlots(
                        t, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.util.Set.of(), 30)
                .getFirst();

        long before = Booking.count();
        assertThrows(
                BookingConflictException.class,
                () -> bookingService.book(
                        OWNER,
                        t.slug,
                        slot.start().toInstant(),
                        "Ada",
                        "ada@example.test",
                        Map.of(),
                        null,
                        null,
                        null,
                        "en",
                        List.of(),
                        45));
        assertEquals(before, Booking.count(), "a rejected duration must write no row");
    }

    @Test
    void theDefaultingOverloadStillBooksTheTypesOwnLength() {
        MeetingType t = seed("dur-default");
        var slot = bookingService
                .availableSlots(
                        t, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.util.Set.of())
                .getFirst();

        Booking b = bookingService.book(
                OWNER,
                t.slug,
                slot.start().toInstant(),
                "Ada",
                "ada@example.test",
                Map.of(),
                null,
                null,
                null,
                "en",
                List.of());

        assertEquals(30, Duration.between(b.startUtc, b.endUtc).toMinutes());
    }
}
