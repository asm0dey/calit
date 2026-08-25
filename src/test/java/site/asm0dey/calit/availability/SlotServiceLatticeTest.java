package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

    /** Same as {@link #seedType(String, int, String, Long...)} with the default Creator zone UTC. */
    @Transactional
    MeetingType seedType(String slug, int minutes, Long... hostOwnerIds) {
        return seedType(slug, minutes, "UTC", hostOwnerIds);
    }

    @Transactional
    MeetingType seedType(String slug, int minutes, String creatorZone, Long... hostOwnerIds) {
        // latticeZoneFor reads the CREATOR's OwnerSettings for the phase (ADR-0008), even when the
        // Creator is not one of the Hosts -- not reseeded by DatabaseResetCallback, so every test
        // that calls latticeZoneFor needs this row.
        OwnerSettings creatorSettings = OwnerSettings.forOwner(CREATOR);
        if (creatorSettings == null) {
            creatorSettings = new OwnerSettings();
            creatorSettings.ownerId = CREATOR;
            creatorSettings.ownerName = "Creator";
            creatorSettings.ownerEmail = "creator@example.test";
        }
        creatorSettings.timezone = creatorZone;
        creatorSettings.persist();

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

    private Set<Instant> starts(MeetingType t, Long hostOwnerId, ZoneId latticeZone) {
        return starts(t, hostOwnerId, latticeZone, MONDAY, MONDAY);
    }

    private Set<Instant> starts(MeetingType t, Long hostOwnerId, ZoneId latticeZone, LocalDate from, LocalDate to) {
        return slotService.generateRawSlots(t, hostOwnerId, from, to, latticeZone, t.durationMinutes).stream()
                .map(s -> s.start().toInstant())
                .collect(Collectors.toSet());
    }

    @Test
    void hostsAnHourApartShareALatticeOnAFortyFiveMinuteCadence() {
        var london = seedHost("lattice-london", "Europe/London");
        var berlin = seedHost("lattice-berlin", "Europe/Berlin");
        MeetingType t = seedType("lattice-45", 45, london, berlin);
        ZoneId latticeZone = slotService.latticeZoneFor(t);

        Set<Instant> both = starts(t, london, latticeZone);
        both.retainAll(starts(t, berlin, latticeZone));

        assertFalse(both.isEmpty(), "London and Berlin must share start instants on a 45-minute cadence");
    }

    @Test
    void aQuarterHourOffsetZoneStillShares() {
        var berlin = seedHost("lattice-berlin-2", "Europe/Berlin");
        var kathmandu = seedHost("lattice-kathmandu", "Asia/Kathmandu");
        MeetingType t = seedType("lattice-30", 30, berlin, kathmandu);
        ZoneId latticeZone = slotService.latticeZoneFor(t);

        Set<Instant> both = starts(t, berlin, latticeZone);
        both.retainAll(starts(t, kathmandu, latticeZone));

        assertFalse(both.isEmpty(), "Berlin and Kathmandu must share start instants");
    }

    /**
     * The hostile case: a 4h45 offset against a cadence that divides neither 60 nor 1440, with the
     * two hosts opening at different local hours so the windows only partly overlap.
     *
     * <p>Under the old per-host-midnight anchoring the two combs were 285 minutes apart and
     * 285 mod 29 = 24, so they shared no instant at all and this type offered zero slots forever.
     * With one lattice per type both hosts test the same predicate by construction, and what
     * survives is exactly the comb restricted to the overlap of the two windows.
     */
    @Test
    void aTwentyNineMinuteCadenceStillIntersectsAcrossAFourHourFortyFiveOffset() {
        var berlin = seedHost("lattice-berlin-29", "Europe/Berlin");
        var kathmandu = seedHost("lattice-kathmandu-29", "Asia/Kathmandu");

        MeetingType t = seedType("lattice-29", 29); // no extra lengths -> step falls back to 29
        seedRule(t.id, berlin, LocalTime.of(9, 0), LocalTime.of(17, 0));
        seedRule(t.id, kathmandu, LocalTime.of(11, 0), LocalTime.of(19, 0));
        ZoneId latticeZone = slotService.latticeZoneFor(t);

        Set<Instant> both = starts(t, berlin, latticeZone);
        both.retainAll(starts(t, kathmandu, latticeZone));
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
        var berlinOpen = MONDAY.atTime(9, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant();
        var kathmanduClose =
                MONDAY.atTime(19, 0).atZone(ZoneId.of("Asia/Kathmandu")).toInstant();
        for (Instant s : shared) {
            assertFalse(s.isBefore(berlinOpen), "a shared slot cannot start before Berlin opens");
            assertFalse(
                    s.plusSeconds(29 * 60L).isAfter(kathmanduClose), "a shared slot cannot run past Kathmandu's close");
        }

        // The comb is dense over that overlap: 08:00Z-12:46Z holds at least nine 29-minute starts.
        assertTrue(shared.size() >= 9, "expected the comb to be dense over the ~4h45 overlap, got " + shared.size());
    }

    /**
     * The case the previous (epoch-origin) design failed: a team with no cross-timezone problem at
     * all must keep the round local times it has today. {@code Asia/Kathmandu} was {@code +05:30}
     * until 1986 and {@code +05:45} since, so a lattice anchored to {@code LocalDate.EPOCH} in the
     * Creator's zone comes out 15 minutes off today's clock. A predicate-based lattice reads the
     * zone's rules at each candidate instant instead, so it stays round.
     */
    @Test
    void anAllKathmanduTeamKeepsRoundLocalTimes() {
        var kathmandu1 = seedHost("lattice-round-kathmandu-1", "Asia/Kathmandu");
        var kathmandu2 = seedHost("lattice-round-kathmandu-2", "Asia/Kathmandu");
        MeetingType t = seedType("lattice-round-kathmandu", 30, "Asia/Kathmandu", kathmandu1, kathmandu2);
        ZoneId latticeZone = slotService.latticeZoneFor(t);

        List<LocalTime> local = starts(t, kathmandu1, latticeZone).stream()
                .map(s -> s.atZone(ZoneId.of("Asia/Kathmandu")).toLocalTime())
                .sorted()
                .toList();

        assertFalse(local.isEmpty());
        assertEquals(LocalTime.of(9, 0), local.getFirst(), "an all-Kathmandu team must keep 09:00, not 09:15");
        for (LocalTime lt : local) {
            assertEquals(0, lt.getMinute() % 30, "every start's Kathmandu-local minute must be :00 or :30, got " + lt);
        }
    }

    /**
     * The lattice's phase comes from the CREATOR's zone, not the Host's and not UTC. A Kolkata
     * Creator (a half-hour zone) with a Berlin Host keeps :00/:30 in BOTH zones, because their
     * offsets differ by a multiple of the 30-minute cadence. Swap the Host to Kathmandu (whose
     * offset from Kolkata is only 15 minutes) and the Host sees :15/:45 while Kolkata stays
     * :00/:30 -- proving the predicate is evaluated in the Creator's zone, not the Host's.
     */
    @Test
    void theLatticeIsRoundInTheCreatorsZoneNotTheHosts() {
        var berlin = seedHost("lattice-round-berlin", "Europe/Berlin");
        MeetingType berlinType = seedType("lattice-round-kolkata-berlin", 30, "Asia/Kolkata", berlin);
        ZoneId berlinLatticeZone = slotService.latticeZoneFor(berlinType);

        Set<Instant> berlinStarts = starts(berlinType, berlin, berlinLatticeZone);
        assertFalse(berlinStarts.isEmpty());
        for (Instant s : berlinStarts) {
            assertEquals(
                    0, s.atZone(ZoneId.of("Asia/Kolkata")).getMinute() % 30, "Kolkata-local minute must be :00 or :30");
            assertEquals(
                    0,
                    s.atZone(ZoneId.of("Europe/Berlin")).getMinute() % 30,
                    "Berlin-local minute must also be :00 or :30 -- Kolkata/Berlin differ by a multiple of 30");
        }

        var kathmandu = seedHost("lattice-round-kathmandu-solo", "Asia/Kathmandu");
        MeetingType kathmanduType = seedType("lattice-round-kolkata-kathmandu", 30, "Asia/Kolkata", kathmandu);
        ZoneId kathmanduLatticeZone = slotService.latticeZoneFor(kathmanduType);

        Set<Instant> kathmanduStarts = starts(kathmanduType, kathmandu, kathmanduLatticeZone);
        assertFalse(kathmanduStarts.isEmpty());
        for (Instant s : kathmanduStarts) {
            assertEquals(
                    0,
                    s.atZone(ZoneId.of("Asia/Kolkata")).getMinute() % 30,
                    "Kolkata-local minute must stay :00 or :30");
            var kathmanduMinute = s.atZone(ZoneId.of("Asia/Kathmandu")).getMinute();
            assertTrue(
                    kathmanduMinute == 15 || kathmanduMinute == 45,
                    "Kathmandu-local minute must be :15 or :45 (15-minute offset from Kolkata), got "
                            + kathmanduMinute);
        }
    }

    /**
     * The lattice is a predicate on {@code t}, not an origin derived from the request's own {@code
     * from} date. With a 50-minute cadence (which does not divide 1440), deriving the phase from
     * {@code from} would put the booking page ({@code from = today}) and the submit-time re-check
     * ({@code from = the chosen day}) on different combs whenever they are a different number of
     * days apart -- rejecting a slot the page just rendered.
     */
    @Test
    void theLatticeDoesNotMoveWithTheRequestedRange() {
        var host = seedHost("lattice-range-host", "Europe/Berlin");
        MeetingType t = seedType("lattice-range", 50, host);
        ZoneId latticeZone = slotService.latticeZoneFor(t);

        // Only MONDAY has a seeded rule, so both ranges produce slots on MONDAY alone -- this
        // isolates the phase question from availability differences between the two ranges.
        Set<Instant> wideRange = starts(t, host, latticeZone, MONDAY.minusDays(3), MONDAY.plusDays(3));
        Set<Instant> narrowRange = starts(t, host, latticeZone, MONDAY, MONDAY);

        assertFalse(narrowRange.isEmpty());
        assertEquals(narrowRange, wideRange, "the lattice for MONDAY must not depend on how wide the request range is");
    }

    @Test
    void aNullLatticeZoneKeepsWindowAnchoringForSingleHost() {
        MeetingType t = seedType("lattice-window", 45, CREATOR);
        List<LocalTime> local = slotService.generateRawSlots(t, CREATOR, MONDAY, MONDAY, null, 45).stream()
                .map(s -> s.start().toLocalTime())
                .toList();
        assertEquals(LocalTime.of(9, 0), local.getFirst(), "window-anchored: the first slot IS the window start");
    }
}
