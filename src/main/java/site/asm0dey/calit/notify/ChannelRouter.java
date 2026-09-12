package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves which of a host's channels a meeting type's notifications go to.
 *
 * <pre>
 * link rows for this meetingType belonging to THIS host's channels
 *   non-empty -> exactly those channels                 (override)
 *   empty     -> this host's defaultEnabled channels     (inherit)
 * </pre>
 *
 * Resolved in Java rather than SQL: a host has a handful of channels, and the per-host filter is
 * the whole point — without it one host's override would silently change a co-host's delivery.
 */
@ApplicationScoped
public class ChannelRouter {

    public List<NotificationChannel> channelsFor(Long hostOwnerId, Long meetingTypeId) {
        List<NotificationChannel> own = NotificationChannel.forOwner(hostOwnerId);
        if (own.isEmpty()) {
            return own;
        }
        // No meeting type means no override is possible, so the inherit set is the whole answer.
        List<NotificationChannel> inherited =
                own.stream().filter(c -> c.defaultEnabled).toList();
        if (meetingTypeId == null) {
            return inherited;
        }
        Set<Long> ownIds = own.stream().map(c -> c.id).collect(Collectors.toSet());
        Set<Long> overridden = NotificationChannelMeetingType.linkedChannelIds(meetingTypeId).stream()
                .filter(ownIds::contains)
                .collect(Collectors.toSet());
        // An explicit pick wins over defaultEnabled: naming a channel here IS the opt-in.
        return overridden.isEmpty()
                ? inherited
                : own.stream().filter(c -> overridden.contains(c.id)).toList();
    }
}
