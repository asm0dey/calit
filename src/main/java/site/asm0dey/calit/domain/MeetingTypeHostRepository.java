package site.asm0dey.calit.domain;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MeetingTypeHostRepository implements PanacheRepositoryBase<MeetingTypeHost, Long> {
    public List<MeetingTypeHost> forType(Long meetingTypeId) {
        return MeetingTypeHost.forType(meetingTypeId);
    }

    public List<MeetingTypeHost> acceptedForType(Long meetingTypeId) {
        return MeetingTypeHost.acceptedForType(meetingTypeId);
    }

    public MeetingTypeHost findHost(Long meetingTypeId, Long ownerId) {
        return MeetingTypeHost.find(meetingTypeId, ownerId);
    }

    public List<MeetingTypeHost> cohostedTypesFor(Long ownerId) {
        return MeetingTypeHost.cohostedTypesFor(ownerId);
    }

    public boolean isMultiHost(Long meetingTypeId) {
        return MeetingTypeHost.isMultiHost(meetingTypeId);
    }

    /**
     * Every host row of the type, creator included.
     */
    public long countForType(Long meetingTypeId) {
        return count("meetingTypeId", meetingTypeId);
    }

    public long countCohosts(Long meetingTypeId) {
        return count("meetingTypeId = ?1 and role = ?2", meetingTypeId, MeetingTypeHost.COHOST);
    }

    public long deleteHost(Long meetingTypeId, Long ownerId) {
        return delete("meetingTypeId = ?1 and ownerId = ?2", meetingTypeId, ownerId);
    }

    public long deleteCreatorRow(Long meetingTypeId) {
        return delete("meetingTypeId = ?1 and role = ?2", meetingTypeId, MeetingTypeHost.CREATOR);
    }
}
