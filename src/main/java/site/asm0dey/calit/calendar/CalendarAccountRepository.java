package site.asm0dey.calit.calendar;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CalendarAccountRepository implements PanacheRepositoryBase<CalendarAccount, Long> {
    public CalendarProvider mirrorProviderOf(Long ownerId, CalendarRef ref) {
        return MirrorProvider.of(ownerId, ref);
    }
}
