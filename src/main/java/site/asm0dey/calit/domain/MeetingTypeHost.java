package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "meeting_type_host")
public class MeetingTypeHost extends PanacheEntityBase {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String CREATOR = "CREATOR";
    public static final String COHOST = "COHOST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(nullable = false, length = 16)
    public String role;

    @Column(name = "consent_token")
    public UUID consentToken;

    @Column(name = "buffer_before_minutes")
    public Integer bufferBeforeMinutes;

    @Column(name = "buffer_after_minutes")
    public Integer bufferAfterMinutes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "responded_at")
    public Instant respondedAt;

    public static MeetingTypeHost of(Long meetingTypeId, Long ownerId, String role, String status) {
        var h = new MeetingTypeHost();
        h.meetingTypeId = meetingTypeId;
        h.ownerId = ownerId;
        h.role = role;
        h.status = status;
        h.createdAt = Instant.now();
        return h;
    }

    public boolean accepted() {
        return ACCEPTED.equals(status);
    }

    public static List<MeetingTypeHost> forType(Long meetingTypeId) {
        return list("meetingTypeId", meetingTypeId);
    }

    public static List<MeetingTypeHost> acceptedForType(Long meetingTypeId) {
        return list("meetingTypeId = ?1 and status = ?2", meetingTypeId, ACCEPTED);
    }

    public static MeetingTypeHost find(Long meetingTypeId, Long ownerId) {
        return find("meetingTypeId = ?1 and ownerId = ?2", meetingTypeId, ownerId)
                .firstResult();
    }

    public static MeetingTypeHost findByConsentToken(String token) {
        return find("consentToken", UUID.fromString(token)).firstResult();
    }

    /** Rows where this owner is a co-host (any status), for their "Shared" list. */
    public static List<MeetingTypeHost> cohostedTypesFor(Long ownerId) {
        return list("ownerId = ?1 and role = ?2", ownerId, COHOST);
    }

    /** True once the type has any co-host row (i.e. it is multi-host). */
    public static boolean isMultiHost(Long meetingTypeId) {
        return count("meetingTypeId = ?1 and role = ?2", meetingTypeId, COHOST) > 0;
    }

    /**
     * The subset of {@code typeIds} that are multi-host (have at least one COHOST row), in a single
     * query. Batched replacement for calling {@link #isMultiHost(Long)} once per type in a loop.
     */
    public static Set<Long> multiHostTypeIdsIn(Collection<Long> typeIds) {
        if (typeIds.isEmpty()) {
            return Set.of();
        }
        return MeetingTypeHost.<MeetingTypeHost>list("role = ?1 and meetingTypeId in ?2", COHOST, typeIds).stream()
                .map(h -> h.meetingTypeId)
                .collect(Collectors.toSet());
    }
}
