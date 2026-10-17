package site.asm0dey.calit.calendar;

import module java.base;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The {@link CalendarPort} everything injects: busy time from every connected provider, each Mirror event write on
 * the provider {@link MirrorProvider} picks. Tests replace it with {@code @InjectMock CalendarPort}.
 */
@ApplicationScoped
public class RoutingCalendarPort implements CalendarPort {
    private final Map<CalendarProvider, ProviderCalendarPort> ports = new EnumMap<>(CalendarProvider.class);

    public RoutingCalendarPort(@All List<ProviderCalendarPort> ports) {
        for (ProviderCalendarPort p : ports) {
            CalendarProvider provider = p.provider();
            if (provider == null) {
                throw new IllegalStateException(p + " claims no CalendarProvider");
            }
            if (this.ports.putIfAbsent(provider, p) != null) {
                throw new IllegalStateException("Two calendar ports claim " + provider);
            }
        }
    }

    /**
     * The providers this router can reach.
     */
    Set<CalendarProvider> providers() {
        return ports.keySet();
    }

    private CalendarPort port(Long ownerId, CalendarRef ref) {
        return ports.get(MirrorProvider.of(ownerId, ref));
    }

    @Override
    public boolean isConnected(Long ownerId) {
        return ports
            .values()
            .stream()
            .anyMatch(p -> p.isConnected(ownerId));
    }

    /**
     * Fails closed: a throw from either provider propagates rather than hiding its busy time.
     */
    @Override
    public List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to) {
        var all = new ArrayList<BusyInterval>();
        for (CalendarPort p : ports.values()) {
            if (p.isConnected(ownerId)) {
                all.addAll(p.freeBusy(ownerId, from, to));
            }
        }
        return BusyIntervals.merge(all);
    }

    @Override
    public CreatedEvent createEvent(
            Long ownerId,
            CalendarRef target,
            String uid,
            String summary,
            String description,
            Instant start,
            Instant end,
            List<String> attendeeEmails,
            boolean createMeetLink,
            String locationText
    ) {
        return port(ownerId, target)
            .createEvent(
                    ownerId,
                    target,
                    uid,
                    summary,
                    description,
                    start,
                    end,
                    attendeeEmails,
                    createMeetLink,
                    locationText
            );
    }

    @Override
    public void updateEvent(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            Instant start,
            Instant end,
            List<String> attendeeEmails
    ) {
        port(ownerId, ref).updateEvent(ownerId, ref, eventId, start, end, attendeeEmails);
    }

    @Override
    public void updateEventDetails(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            String summary,
            String description,
            List<String> attendeeEmails
    ) {
        port(ownerId, ref).updateEventDetails(ownerId, ref, eventId, summary, description, attendeeEmails);
    }

    @Override
    public void deleteEvent(Long ownerId, CalendarRef ref, String eventId) {
        port(ownerId, ref).deleteEvent(ownerId, ref, eventId);
    }
}
