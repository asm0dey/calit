package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class RescheduleLengthTest {

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
        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = t.id;
        d.durationMinutes = 120;
        d.persist();
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
    void reschedulingA120MinuteBookingKeepsIt120() {
        MeetingType t = seed("resched-len");
        var slots = bookingService.availableSlots(
                t, LocalDate.now(), LocalDate.now().plusDays(7), Set.of(), 120);

        Booking b = bookingService.book(
                OWNER,
                t.slug,
                slots.getFirst().start().toInstant(),
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

        var target = slots.stream()
                .filter(s -> !s.start().toInstant().equals(b.startUtc))
                .findFirst()
                .orElseThrow();
        bookingService.reschedule(b.manageToken, target.start().toInstant());

        Booking moved = Booking.findById(b.id);
        assertEquals(
                120,
                Duration.between(moved.startUtc, moved.endUtc).toMinutes(),
                "reschedule moves a booking; it must never resize it");
    }
}
