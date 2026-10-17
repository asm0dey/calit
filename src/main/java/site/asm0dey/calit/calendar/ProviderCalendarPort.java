package site.asm0dey.calit.calendar;

/**
 * A {@link CalendarPort} for one {@link CalendarProvider}. Implementations are {@code @Typed} without
 * {@code CalendarPort}, so {@link RoutingCalendarPort} stays the only {@code CalendarPort} bean and finds them by this
 * type.
 */
public interface ProviderCalendarPort extends CalendarPort {
    CalendarProvider provider();
}
