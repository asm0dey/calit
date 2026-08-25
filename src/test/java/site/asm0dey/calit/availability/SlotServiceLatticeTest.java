package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlotServiceLatticeTest {

    private static final Long CREATOR = 1L;
    private static final LocalDate MONDAY = LocalDate.of(2027, 3, 1);

    @Inject
    SlotService slotService;

    @Transactional
    Long seedHost(String username, String timezone) {
        // AppUser.create() stamps roles + createdAt (both NOT NULL, no Java-side default) --
        // building the entity by hand and skipping them fails the insert.
        AppUser u = AppUser.create(username, "x", false);
        u.settingsComplete = true;
        u.persist();
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.timezone = timezone;
        s.ownerName = username;
        // ownerEmail is NOT NULL with no Java-side default.
        s.ownerEmail = username + "@example.test";
        s.persist();
        return u.id;
    }

    @Transactional
    MeetingType seedType(String slug, int minutes, Long... hostOwnerIds) {
        // gridAnchorFor reads the CREATOR's OwnerSettings for the phase (ADR-0008), even when the
        // Creator is not one of the Hosts -- not reseeded by DatabaseResetCallback, so every test
        // that calls gridAnchorFor needs this row.
        if (OwnerSettings.forOwner(CREATOR) == null) {
            OwnerSettings creatorSettings = new OwnerSettings();
            creatorSettings.ownerId = CREATOR;
            creatorSettings.ownerName = "Creator";
            creatorSettings.ownerEmail = "creator@example.test";
            creatorSettings.timezone = "UTC";
            creatorSettings.persist();
        }
        MeetingType t = new MeetingType();
        t.ownerId = CREATOR;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.persist();
        for (Long owner : hostOwnerIds) {
            seedRule(t.id, owner, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
        return t;
    }

    /** One host's Monday window, in that host's OWN local time. */
    @Transactional
    void seedRule(Long meetingTypeId, Long hostOwnerId, LocalTime from, LocalTime to) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = hostOwnerId;
        r.meetingTypeId = meetingTypeId;
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = from;
        r.endTime = to;
        r.persist();
    }

    private Set<Instant> starts(MeetingType t, Long hostOwnerId, Instant anchor) {
        return slotService.generateRawSlots(t, hostOwnerId, MONDAY, MONDAY, anchor, t.durationMinutes).stream()
                .map(s -> s.start().toInstant())
                .collect(Collectors.toSet());
    }

    @Test
    void hostsAnHourApartShareALatticeOnAFortyFiveMinuteCadence() {
        var london = seedHost("lattice-london", "Europe/London");
        var berlin = seedHost("lattice-berlin", "Europe/Berlin");
        MeetingType t = seedType("lattice-45", 45, london, berlin);
        Instant anchor = slotService.gridAnchorFor(t);

        Set<Instant> both = starts(t, london, anchor);
        both.retainAll(starts(t, berlin, anchor));

        assertFalse(both.isEmpty(), "London and Berlin must share start instants on a 45-minute cadence");
    }

    @Test
    void aQuarterHourOffsetZoneStillShares() {
        var berlin = seedHost("lattice-berlin-2", "Europe/Berlin");
        var kathmandu = seedHost("lattice-kathmandu", "Asia/Kathmandu");
        MeetingType t = seedType("lattice-30", 30, berlin, kathmandu);
        Instant anchor = slotService.gridAnchorFor(t);

        Set<Instant> both = starts(t, berlin, anchor);
        both.retainAll(starts(t, kathmandu, anchor));

        assertFalse(both.isEmpty(), "Berlin and Kathmandu must share start instants");
    }

    /**
     * The hostile case: a 4h45 offset against a cadence that divides neither 60 nor 1440, with the
     * two hosts opening at different local hours so the windows only partly overlap.
     *
     * <p>Under the old per-host-midnight anchoring the two combs were 285 minutes apart and
     * 285 mod 29 = 24, so they shared no instant at all and this type offered zero slots forever.
     * With one anchor per type both hosts sit on the same comb by construction, and what survives
     * is exactly the comb restricted to the overlap of the two windows.
     */
    @Test
    void aTwentyNineMinuteCadenceStillIntersectsAcrossAFourHourFortyFiveOffset() {
        var berlin = seedHost("lattice-berlin-29", "Europe/Berlin");
        var kathmandu = seedHost("lattice-kathmandu-29", "Asia/Kathmandu");

        MeetingType t = seedType("lattice-29", 29); // no extra lengths -> step falls back to 29
        seedRule(t.id, berlin, LocalTime.of(9, 0), LocalTime.of(17, 0));
        seedRule(t.id, kathmandu, LocalTime.of(11, 0), LocalTime.of(19, 0));
        Instant anchor = slotService.gridAnchorFor(t);

        Set<Instant> both = starts(t, berlin, anchor);
        both.retainAll(starts(t, kathmandu, anchor));
        List<Instant> shared = both.stream().sorted().toList();

        assertFalse(shared.isEmpty(), "one comb per type must survive a 285-minute offset at a 29-minute cadence");

        // Every surviving start sits on ONE comb: consecutive shared starts are exactly one step apart.
        for (var i = 1; i < shared.size(); i++) {
            assertEquals(
                    29 * 60L,
                    shared.get(i).getEpochSecond() - shared.get(i - 1).getEpochSecond(),
                    "shared starts must be consecutive points of a single 29-minute comb");
        }

        // 2027-03-01 is winter: Berlin is +01:00, Kathmandu +05:45 (no DST there).
        // Berlin's window is 08:00Z-16:00Z, Kathmandu's is 05:15Z-13:15Z, so a slot is bookable by
        // BOTH only from 08:00Z until its 29-minute body ends by 13:15Z.
        var berlinOpen =
                MONDAY.atTime(9, 0).atZone(java.time.ZoneId.of("Europe/Berlin")).toInstant();
        var kathmanduClose = MONDAY.atTime(19, 0)
                .atZone(java.time.ZoneId.of("Asia/Kathmandu"))
                .toInstant();
        for (Instant s : shared) {
            assertFalse(s.isBefore(berlinOpen), "a shared slot cannot start before Berlin opens");
            assertFalse(
                    s.plusSeconds(29 * 60L).isAfter(kathmanduClose), "a shared slot cannot run past Kathmandu's close");
        }

        // The comb is dense over that overlap: 08:00Z-12:46Z holds at least nine 29-minute starts.
        assertTrue(shared.size() >= 9, "expected the comb to be dense over the ~4h45 overlap, got " + shared.size());
    }

    @Test
    void theAnchorSitsOnTheCreatorsLocalMidnight() {
        MeetingType t = seedType("lattice-anchor", 30);
        Instant anchor = slotService.gridAnchorFor(t);
        String creatorZone = OwnerSettings.forOwner(CREATOR).timezone;
        assertEquals(
                LocalTime.MIDNIGHT,
                anchor.atZone(java.time.ZoneId.of(creatorZone)).toLocalTime());
    }

    @Test
    void aNullAnchorKeepsWindowAnchoringForSingleHost() {
        MeetingType t = seedType("lattice-window", 45, CREATOR);
        List<LocalTime> local = slotService.generateRawSlots(t, CREATOR, MONDAY, MONDAY, null, 45).stream()
                .map(s -> s.start().toLocalTime())
                .toList();
        assertEquals(LocalTime.of(9, 0), local.getFirst(), "window-anchored: the first slot IS the window start");
    }
}
