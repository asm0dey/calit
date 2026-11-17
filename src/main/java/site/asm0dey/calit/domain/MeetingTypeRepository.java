package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MeetingTypeRepository implements PanacheRepositoryBase<MeetingType, Long> {
    public MeetingType findBySlug(Long ownerId, String slug) {
        return MeetingType.findBySlug(ownerId, slug);
    }

    /**
     * True when this owner already has a *different* type using this slug.
     */
    public boolean slugUsedByOwner(Long ownerId, String slug, Long excludeTypeId) {
        if (excludeTypeId == null) {
            return count("ownerId = ?1 and slug = ?2", ownerId, slug) > 0;
        }
        return count("ownerId = ?1 and slug = ?2 and id <> ?3", ownerId, slug, excludeTypeId) > 0;
    }
}
