package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

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

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private Instant nextMonday(int hour) {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(hour, 0).atZone(AMS).toInstant();
    }

    /**
     * A second, multi-host type owned by {@code OWNER} (id 1, already seeded by {@link #seedSettings()})
     * plus one ACCEPTED co-host — mirrors {@code GroupCancelRescheduleTest#groupType}. Skips re-seeding
     * {@code OWNER}'s own {@code OwnerSettings} (already done by {@link #seedSettings()}; a second insert
     * for the same owner id would violate its uniqueness) and adds a non-default {@link MeetingTypeDuration}
     * row so a booking can be made at a length other than the type's default 60.
     */
    private MeetingType groupType() {
        AppUser cohost = MultiHostFixtures.enabledUser("cohost");
        MultiHostFixtures.settings(cohost.id, "cohost");
        MultiHostFixtures.rule(OWNER, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(cohost.id, DayOfWeek.MONDAY, 9, 17);
        MeetingType t = MultiHostFixtures.acceptedTwoHostType(OWNER, cohost.id, "resched-len-group", 60, false);
        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = t.id;
        d.durationMinutes = 90;
        d.persist();
        return t;
    }

    @Test
    @TestTransaction
    void groupRescheduleKeepsEveryRowAtTheBookedLength() {
        MeetingType type = groupType();

        Booking lead = bookingService.book(
                OWNER, type.slug, nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", null, "", "en", List.of(), 90);
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size(), "both hosts get a row");
        rows.forEach(r -> assertEquals(
                90, Duration.between(r.startUtc, r.endUtc).toMinutes(), "booked at the chosen non-default length"));

        Booking freshLead = Booking.leadOfGroup(lead.groupId, OWNER);
        bookingService.reschedule(freshLead.manageToken, nextMonday(13));

        // Assert EVERY row, not just the one whose manageToken drove the reschedule -- taking the
        // length from the wrong row is exactly the bug this path could hide.
        Booking.<Booking>group(lead.groupId)
                .forEach(r -> assertEquals(
                        90,
                        Duration.between(r.startUtc, r.endUtc).toMinutes(),
                        "group reschedule moves every row; it must never resize any of them"));
    }
}
