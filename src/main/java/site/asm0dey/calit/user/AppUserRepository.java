package site.asm0dey.calit.user;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AppUserRepository implements PanacheRepositoryBase<AppUser, Long> {
    /**
     * Each listed user's {@code enabled} flag; ids with no user are absent.
     */
    public Map<Long, Boolean> enabledByIds(Collection<Long> ids) {
        return ids.isEmpty()
                ? Map.of()
                : list("id in ?1", ids).stream().collect(Collectors.toMap(u -> u.id, u -> u.enabled));
    }
}
