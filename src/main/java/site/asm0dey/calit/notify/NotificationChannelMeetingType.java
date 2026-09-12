package site.asm0dey.calit.notify;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A per-meeting-type routing OVERRIDE. No rows for a (host, type) pair means that host inherits all
 * of their own channels; the "belonging to THIS host" half is applied by {@link ChannelRouter}, so
 * one host narrowing a co-hosted type never changes another host's delivery.
 */
@Entity
@Table(name = "notification_channel_meeting_type")
public class NotificationChannelMeetingType extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "channel_id", nullable = false)
    public Long channelId;

    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    public static List<NotificationChannelMeetingType> forType(Long meetingTypeId) {
        return list("meetingTypeId", meetingTypeId);
    }

    public static Set<Long> linkedChannelIds(Long meetingTypeId) {
        return forType(meetingTypeId).stream().map(l -> l.channelId).collect(Collectors.toSet());
    }

    /**
     * Replace THIS host's links for one meeting type: drops every link naming a channel in
     * {@code ownChannelIds} and re-creates one per id in {@code keepChannelIds}. Another host's
     * links on the same type are never touched, because they name channels this host does not own.
     */
    public static void replaceLinks(
            Long meetingTypeId, Collection<Long> ownChannelIds, Collection<Long> keepChannelIds) {
        if (!ownChannelIds.isEmpty()) {
            delete("meetingTypeId = ?1 and channelId in ?2", meetingTypeId, ownChannelIds);
        }
        for (Long channelId : keepChannelIds) {
            if (!ownChannelIds.contains(channelId)) continue; // never link a channel this host does not own
            var link = new NotificationChannelMeetingType();
            link.meetingTypeId = meetingTypeId;
            link.channelId = channelId;
            link.persist();
        }
    }
}
