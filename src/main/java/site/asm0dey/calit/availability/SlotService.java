package site.asm0dey.calit.availability;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import site.asm0dey.calit.domain.*;

@ApplicationScoped
public class SlotService {

    /**
     * Raw bookable windows derived from work hours only.
     * Conflict/busy/buffer subtraction is applied by Plan 3 on top of this output.
     *
     * <p>All availability data (weekly rules, date overrides, and override windows) is batch-loaded
     * up front in exactly three queries, independent of the horizon length, then resolved per day in
     * memory. This avoids an N+1 where each day in {@code [from, to]} would otherwise re-query rules
     * and overrides (~3 queries/day, ~180 over a 60-day horizon).
     */
    public List<TimeSlot> generateRawSlots(MeetingType type, LocalDate from, LocalDate to) {
        return generateRawSlots(type, type.ownerId, from, to);
    }

    /**
     * Same as {@link #generateRawSlots(MeetingType, LocalDate, LocalDate)}, but the grid/duration
     * come from {@code type} while {@link OwnerSettings} and availability are read for {@code
     * hostOwnerId} — lets a co-host's own hours/timezone drive their raw slots for a shared type.
     */
    public List<TimeSlot> generateRawSlots(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to) {
        return generateRawSlots(type, hostOwnerId, from, to, null);
    }

    /**
     * Same as {@link #generateRawSlots(MeetingType, Long, LocalDate, LocalDate)}, plus a nullable
     * {@code latticeZone} used only by the multi-host intersection path (ADR-0008).
     *
     * <p>Null means window-anchored (single-host): the grid's first point is the window start,
     * byte-identical to the historical behavior. A non-null zone (pass {@link
     * #latticeZoneFor(MeetingType)}) means lattice-anchored (multi-host): candidate starts are the
     * instants whose local time-of-day IN THAT ZONE is a whole number of steps past midnight. Two
     * hosts whose window starts differ by a non-multiple of the slot step (e.g. 09:00 vs 09:15 with
     * a 30-min step) would otherwise generate grids that never share a start instant, making the
     * intersection spuriously empty even though the hosts' free time genuinely overlaps. Testing the
     * same predicate against the same instants puts every host on the same {@code step}-minute
     * lattice so they can actually intersect.
     */
    public List<TimeSlot> generateRawSlots(
            MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to, ZoneId latticeZone) {
        return generateRawSlots(type, hostOwnerId, from, to, latticeZone, type.durationMinutes);
    }

    /**
     * Same as {@link #generateRawSlots(MeetingType, Long, LocalDate, LocalDate, ZoneId)} for a chosen
     * length. The grid STEP comes from the type's shortest allowed length, not from
     * {@code durationMinutes}: the lattice of candidate starts must not move when an Invitee switches
     * length (ADR-0003). Only the slot BODY varies.
     */
    public List<TimeSlot> generateRawSlots(
            MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to, ZoneId latticeZone, int durationMinutes) {
        OwnerSettings settings = OwnerSettings.forOwner(hostOwnerId);
        if (settings == null) {
            throw new IllegalStateException("Owner settings not configured for owner " + hostOwnerId
                    + "; set them via /me/settings before generating slots.");
        }
        ZoneId zone = ZoneId.of(OwnerSettings.coerceZone(settings.timezone));
        Availability availability = loadAvailability(type, hostOwnerId, from, to);
        List<TimeSlot> slots = new ArrayList<>();

        // Cadence: an explicit interval wins; otherwise the SHORTEST allowed length, so the lattice
        // stays put when the Invitee switches. Not the chosen length, and not the default.
        int step = (type.slotIntervalMinutes != null && type.slotIntervalMinutes > 0)
                ? type.slotIntervalMinutes
                : MeetingTypeDuration.shortestAllowed(type);
        var duration = durationMinutes;

        for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Window window : availability.windowsFor(date)) {
                var windowStart = date.atTime(window.start()).atZone(zone).toInstant();
                var windowEnd = date.atTime(window.end()).atZone(zone).toInstant();
                var bodySeconds = duration * 60L;
                if (latticeZone == null) {
                    // Window-anchored (single-host): the first slot IS the window start, byte-identical
                    // to the historical behaviour, including per-window anchoring on a multi-window day.
                    for (var s = windowStart;
                            !s.plusSeconds(bodySeconds).isAfter(windowEnd);
                            s = s.plusSeconds(step * 60L)) {
                        slots.add(new TimeSlot(
                                s.atZone(zone), s.plusSeconds(bodySeconds).atZone(zone)));
                    }
                } else {
                    // Lattice-anchored (multi-host): candidate starts are the instants whose local
                    // time-of-day in the CREATOR's zone is a whole number of steps past midnight.
                    var local = windowStart
                            .atZone(latticeZone)
                            .toLocalDateTime()
                            .withSecond(0)
                            .withNano(0);
                    var intoDay = local.getHour() * 60 + local.getMinute();
                    var over = intoDay % step;
                    if (over != 0) {
                        local = local.plusMinutes(step - (long) over);
                    }
                    while (true) {
                        // Step in LOCAL terms and re-resolve. ZonedDateTime.plusMinutes works on the
                        // instant time-line, so it would drift an hour off round after a fall-back.
                        var s = local.atZone(latticeZone).toInstant();
                        if (s.plusSeconds(bodySeconds).isAfter(windowEnd)) {
                            break;
                        }
                        if (!s.isBefore(windowStart)) {
                            slots.add(new TimeSlot(
                                    s.atZone(zone), s.plusSeconds(bodySeconds).atZone(zone)));
                        }
                        local = local.plusMinutes(step);
                    }
                }
            }
        }
        return slots;
    }

    /**
     * The zone whose clock defines this type's lattice of candidate start times: the CREATOR's
     * (ADR-0008). Start times come out round on the clock of whoever defined the meeting type, and
     * every Host tests the same predicate against the same instants, so Hosts cannot disagree about
     * which instants are candidates.
     *
     * <p>The zone's rules are consulted at each candidate instant rather than frozen at some origin
     * date. That is deliberate: {@code Asia/Kathmandu} was {@code +05:30} until 1986 and {@code
     * +05:45} since, so an origin-based lattice would move an all-Kathmandu team off the round local
     * times they have today, to fix a cross-timezone problem they do not have.
     */
    public ZoneId latticeZoneFor(MeetingType type) {
        OwnerSettings creator = OwnerSettings.forOwner(type.ownerId);
        if (creator == null) {
            throw new IllegalStateException("Owner settings not configured for owner " + type.ownerId
                    + "; set them via /me/settings before generating slots.");
        }
        return ZoneId.of(OwnerSettings.coerceZone(creator.timezone));
    }

    /** A bookable [start, end) time-of-day window for one day, from either an override or a weekly rule. */
    record Window(LocalTime start, LocalTime end) {}

    /**
     * Pre-loaded availability for an owner+type over a date range, resolved per day in memory.
     * Mirrors {@link DateOverride#resolve} / the old {@code windowsFor}/{@code rulesFor} semantics
     * exactly, just sourced from maps populated by three up-front queries instead of per-day reads.
     */
    private record Availability(
            Map<LocalDate, DateOverride> typedOverrides,
            Map<LocalDate, DateOverride> globalOverrides,
            Map<DayOfWeek, List<AvailabilityRule>> typedRules,
            Map<DayOfWeek, List<AvailabilityRule>> globalRules) {

        /**
         * The day's bookable windows. A {@link DateOverride} for the date REPLACES weekly hours: its
         * windows are used as-is (empty list => day off => no windows). When no override exists, the
         * weekly rules apply. Override precedence: per-type wins, else this owner's global.
         *
         * <p>Weekly rules are all-or-nothing per type (issue #127): a type that defines ANY rule owns
         * its whole week, so a weekday it leaves blank is CLOSED rather than falling back to the
         * owner's global hours for that weekday. Only a type with no rules at all inherits the global
         * week. The old per-weekday fallback made a Mon/Tue-only type bookable every day the owner's
         * global grid covered.
         */
        List<Window> windowsFor(LocalDate date) {
            DateOverride override = typedOverrides.get(date);
            if (override == null) {
                override = globalOverrides.get(date);
            }
            if (override != null) {
                return override.windows.stream()
                        .map(w -> new Window(w.startTime, w.endTime))
                        .toList();
            }
            var week = typedRules.isEmpty() ? globalRules : typedRules;
            return week.getOrDefault(date.getDayOfWeek(), List.<AvailabilityRule>of()).stream()
                    .map(r -> new Window(r.startTime, r.endTime))
                    .toList();
        }
    }

    /**
     * Three queries (constant in horizon length): all weekly rules for this owner+type, all date
     * overrides in range, and all windows of the overrides actually selected. Split into per-type
     * and this-owner's-global maps so {@link Availability#windowsFor} can resolve each day in memory.
     */
    private Availability loadAvailability(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to) {
        // 1) Rules: this owner's per-type + global, grouped by day-of-week.
        List<AvailabilityRule> rules = AvailabilityRule.list(
                "ownerId = ?1 and (meetingTypeId = ?2 or meetingTypeId is null)", hostOwnerId, type.id);
        Map<DayOfWeek, List<AvailabilityRule>> typedRules = rules.stream()
                .filter(r -> type.id.equals(r.meetingTypeId))
                .collect(Collectors.groupingBy(r -> r.dayOfWeek));
        Map<DayOfWeek, List<AvailabilityRule>> globalRules =
                rules.stream().filter(r -> r.meetingTypeId == null).collect(Collectors.groupingBy(r -> r.dayOfWeek));

        // 2) Overrides in range: per-type and this owner's global, keyed by date (first row wins on
        //    a duplicate date+scope, which the unique index should already prevent).
        List<DateOverride> overrides = DateOverride.list(
                "ownerId = ?1 and (meetingTypeId = ?2 or meetingTypeId is null) "
                        + "and overrideDate >= ?3 and overrideDate <= ?4",
                hostOwnerId,
                type.id,
                from,
                to);
        Map<LocalDate, DateOverride> typedOverrides = new HashMap<>();
        Map<LocalDate, DateOverride> globalOverrides = new HashMap<>();
        for (DateOverride o : overrides) {
            Map<LocalDate, DateOverride> target = type.id.equals(o.meetingTypeId) ? typedOverrides : globalOverrides;
            target.putIfAbsent(o.overrideDate, o);
        }

        // 3) Windows: for every override actually selected (per-type beats global on a given date,
        //    but both scopes can apply on different dates, so load windows for all selected ones).
        //    One query, grouped by override id, preserving start-time ordering. Empty = day off.
        List<DateOverride> selected = new ArrayList<>(typedOverrides.values());
        selected.addAll(globalOverrides.values());
        if (!selected.isEmpty()) {
            List<Long> ids = selected.stream().map(o -> o.id).toList();
            Map<Long, List<DateOverrideWindow>> windowsByOverride =
                    DateOverrideWindow.<DateOverrideWindow>list("dateOverrideId in ?1 order by startTime asc", ids)
                            .stream()
                            .collect(Collectors.groupingBy(w -> w.dateOverrideId));
            for (DateOverride o : selected) {
                o.windows = windowsByOverride.getOrDefault(o.id, List.of());
            }
        }
        return new Availability(typedOverrides, globalOverrides, typedRules, globalRules);
    }
}
