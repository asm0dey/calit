package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MeetingTypeRepository implements PanacheRepositoryBase<MeetingType, Long> {
    public MeetingType findBySlug(Long ownerId, String slug) {
        return MeetingType.findBySlug(ownerId, slug);
    }

    public boolean slugUsedByOwner(Long ownerId, String slug, Long excludeTypeId) {
        return MeetingType.slugUsedByOwner(ownerId, slug, excludeTypeId);
    }
}
