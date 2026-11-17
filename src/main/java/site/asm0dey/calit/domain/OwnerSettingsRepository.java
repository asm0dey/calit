package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OwnerSettingsRepository implements PanacheRepositoryBase<OwnerSettings, Long> {
    public OwnerSettings forOwner(Long ownerId) {
        return OwnerSettings.forOwner(ownerId);
    }
}
