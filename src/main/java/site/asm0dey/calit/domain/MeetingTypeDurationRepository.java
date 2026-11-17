package site.asm0dey.calit.domain;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MeetingTypeDurationRepository
        implements PanacheRepositoryBase<MeetingTypeDuration, MeetingTypeDuration.Key>
{
    public List<Integer> allowedDurations(MeetingType type) {
        return MeetingTypeDuration.allowedDurations(type);
    }

    public boolean isAllowed(MeetingType type, int durationMinutes) {
        return MeetingTypeDuration.isAllowed(type, durationMinutes);
    }

    public MeetingTypeDuration findRow(Long meetingTypeId, int durationMinutes) {
        return MeetingTypeDuration.findRow(meetingTypeId, durationMinutes);
    }
}
