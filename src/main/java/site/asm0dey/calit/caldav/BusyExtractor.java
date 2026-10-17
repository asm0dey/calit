package site.asm0dey.calit.caldav;

import module java.base;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateListProperty;
import net.fortuna.ical4j.model.property.DateProperty;
import org.jboss.logging.Logger;
import site.asm0dey.calit.calendar.BusyInterval;
import site.asm0dey.calit.calendar.BusyIntervals;

/**
 * Turns raw CalDAV {@code calendar-data} into busy time, expanding recurrences client-side.
 */
public final class BusyExtractor {
    private static final Logger LOG = Logger.getLogger(BusyExtractor.class);
    // expansion runs on a window padded by this on each side so zone/day boundaries can't clip an instance
    private static final Duration PAD = Duration.ofDays(2);

    private BusyExtractor() {
    }

    public static List<BusyInterval> busy(List<String> calendarData, Instant from, Instant to, ZoneId ownerZone) {
        var out = new ArrayList<BusyInterval>();
        for (var data : calendarData) {
            List<VEvent> events;
            try {
                events = new CalendarBuilder().build(new StringReader(data)).getComponents(Component.VEVENT);
            } catch (IOException | ParserException | RuntimeException e) {
                // shortcut: one unparseable object is skipped, not fatal; its time is not blocked
                LOG.warnf("Skipping unparseable CalDAV object: %s", e.getMessage());
                continue;
            }
            // RECURRENCE-ID overrides replace the master's instance at that start
            var overridden = events
                .stream()
                .flatMap(e -> e.<DateProperty<Temporal>>getProperty(Property.RECURRENCE_ID).stream())
                .map(p -> toInstant(p.getDate(), ownerZone))
                .collect(Collectors.toSet());
            for (var event : events) {
                if (ignored(event)) {
                    continue;
                }
                var isMaster = event.getProperty(Property.RECURRENCE_ID).isEmpty();
                var excluded = new HashSet<Instant>();
                if (isMaster) {
                    excluded.addAll(overridden);
                    event
                        .<DateListProperty<Temporal>>getProperties(Property.EXDATE)
                        .forEach(p -> p
                            .getDates()
                            .forEach(d -> excluded.add(toInstant(d, ownerZone))));
                }
                for (var period : expand(event, from, to, ownerZone)) {
                    var start = toInstant(period.getStart(), ownerZone);
                    var end = toInstant(period.getEnd(), ownerZone);
                    if (!excluded.contains(start) && start.isBefore(to) && end.isAfter(from)) {
                        out.add(new BusyInterval(start, end));
                    }
                }
            }
        }
        return BusyIntervals.merge(out);
    }

    private static boolean ignored(VEvent e) {
        return e.getProperty(Property.TRANSP).map(p -> "TRANSPARENT".equalsIgnoreCase(p.getValue())).orElse(false)
                || e.getProperty(Property.STATUS).map(p -> "CANCELLED".equalsIgnoreCase(p.getValue())).orElse(false);
    }

    /**
     * Expands on a window of the same temporal type as DTSTART: ical4j compares like with like.
     */
    private static Set<Period<Temporal>> expand(VEvent event, Instant from, Instant to, ZoneId ownerZone) {
        Optional<DateProperty<Temporal>> dtStart = event.getProperty(Property.DTSTART);
        if (dtStart.isEmpty()) {
            return Set.of();
        }
        var seed = dtStart.get().getDate();
        var lo = from.minus(PAD);
        var hi = to.plus(PAD);
        Period<?> window = switch (seed) {
            case LocalDate _ -> new Period<>(lo.atZone(ownerZone).toLocalDate(), hi.atZone(ownerZone).toLocalDate());
            case LocalDateTime _ -> new Period<>(
                    lo.atZone(ownerZone).toLocalDateTime(),
                    hi.atZone(ownerZone).toLocalDateTime()
            );
            case ZonedDateTime z -> new Period<>(lo.atZone(z.getZone()), hi.atZone(z.getZone()));
            case OffsetDateTime o -> new Period<>(lo.atOffset(o.getOffset()), hi.atOffset(o.getOffset()));
            default -> new Period<>(lo, hi);
        };
        return event.calculateRecurrenceSet(window);
    }

    private static Instant toInstant(Temporal t, ZoneId ownerZone) {
        return switch (t) {
            case Instant i -> i;
            case ZonedDateTime z -> z.toInstant();
            case OffsetDateTime o -> o.toInstant();
            // floating times and all-day dates are the owner's wall clock
            case LocalDateTime l -> l.atZone(ownerZone).toInstant();
            case LocalDate d -> d.atStartOfDay(ownerZone).toInstant();
            default -> Instant.from(t);
        };
    }
}
