package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "meeting_type")
public class MeetingType extends PanacheEntityBase {

    public enum LocationType {
        GOOGLE_MEET,
        PHONE,
        IN_PERSON,
        CUSTOM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String slug;

    @Column(name = "duration_minutes", nullable = false)
    public int durationMinutes;

    @Column(name = "buffer_before_minutes", nullable = false)
    public int bufferBeforeMinutes = 0;

    @Column(name = "buffer_after_minutes", nullable = false)
    public int bufferAfterMinutes = 0;

    @Column(columnDefinition = "text")
    public String description;

    @Column(nullable = false)
    public boolean active = true;

    /** Secret types are hidden from the public list but remain bookable via their direct slug/link. */
    @Column(nullable = false)
    public boolean secret = false;

    /** Minimum scheduling notice (minutes from "now"). Stored here; enforced as a slot filter in Plan 3. */
    @Column(name = "min_notice_minutes", nullable = false)
    public int minNoticeMinutes = 0;

    /** Booking horizon: how many days into the future are bookable. Stored here; filtered in Plan 3. */
    @Column(name = "horizon_days", nullable = false)
    public int horizonDays = 60;

    /** Where the meeting happens. Only GOOGLE_MEET triggers a Meet conference link (Plan 2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 16)
    public LocationType locationType = LocationType.GOOGLE_MEET;

    /** Free-text detail for non-Meet locations (phone number, address, custom instructions). */
    @Column(name = "location_detail", columnDefinition = "text")
    public String locationDetail;

    /** When true, bookings start PENDING and need owner approval (Plan 3 workflow). */
    @Column(name = "requires_approval", nullable = false)
    public boolean requiresApproval = false;

    @Column(name = "slot_interval_minutes")
    public Integer slotIntervalMinutes;

    /** Cadence (minutes) between consecutive slot starts; falls back to the duration when unset/non-positive. */
    public int effectiveSlotIntervalMinutes() {
        return (slotIntervalMinutes != null && slotIntervalMinutes > 0) ? slotIntervalMinutes : durationMinutes;
    }

    public static MeetingType findBySlug(Long ownerId, String slug) {
        return find("ownerId = ?1 and slug = ?2", ownerId, slug).firstResult();
    }

    /** Active, non-secret types for this owner — what their public landing page lists. */
    public static List<MeetingType> listPublic(Long ownerId) {
        return list("ownerId = ?1 and active = true and secret = false", ownerId);
    }

    /** Every type for this owner, including secret/inactive — the management listing. */
    public static List<MeetingType> listForOwner(Long ownerId) {
        return list("ownerId", ownerId);
    }

    /** True when this owner already has a *different* type using this slug. */
    public static boolean slugUsedByOwner(Long ownerId, String slug, Long excludeTypeId) {
        if (excludeTypeId == null) {
            return count("ownerId = ?1 and slug = ?2", ownerId, slug) > 0;
        }
        return count("ownerId = ?1 and slug = ?2 and id <> ?3", ownerId, slug, excludeTypeId) > 0;
    }

    /**
     * Resolves `/{urlOwnerId}/{slug}` for booking purposes: the owner's own type wins; otherwise a
     * multi-host type with that slug where {@code urlOwnerId} is an ACCEPTED co-host (a username
     * alias for the shared type). The slug-collision guard (co-hosting requires a free slug in the
     * candidate's own namespace) means at most one of the two can match. Returns null if neither.
     */
    public static MeetingType resolveForAlias(Long urlOwnerId, String slug) {
        var own = findBySlug(urlOwnerId, slug);
        if (own != null) {
            return own;
        }
        List<MeetingTypeHost> cohostRows = MeetingTypeHost.list(
                "ownerId = ?1 and role = ?2 and status = ?3",
                urlOwnerId,
                MeetingTypeHost.COHOST,
                MeetingTypeHost.ACCEPTED);
        if (cohostRows.isEmpty()) {
            return null;
        }
        // One query filtered by slug instead of findById-per-cohost-row (each candidate slug is
        // free in its own namespace, so at most one matches).
        List<Long> typeIds = cohostRows.stream().map(h -> h.meetingTypeId).toList();
        return MeetingType.<MeetingType>find("id in ?1 and slug = ?2", typeIds, slug)
                .firstResult();
    }

    /**
     * {@link #listPublic(Long)} plus multi-host types where {@code ownerId} is an ACCEPTED co-host
     * (and the type is active & not secret) — the union shown on that owner's public landing page,
     * co-hosted types included.
     */
    public static List<MeetingType> listPublicIncludingCohosted(Long ownerId) {
        List<MeetingType> result = new ArrayList<>(listPublic(ownerId));
        List<MeetingTypeHost> cohostRows = MeetingTypeHost.list(
                "ownerId = ?1 and role = ?2 and status = ?3",
                ownerId,
                MeetingTypeHost.COHOST,
                MeetingTypeHost.ACCEPTED);
        if (!cohostRows.isEmpty()) {
            // One query for all co-hosted types instead of findById per co-host row.
            List<Long> typeIds = cohostRows.stream().map(h -> h.meetingTypeId).toList();
            for (MeetingType t : MeetingType.<MeetingType>list("id in ?1", typeIds)) {
                if (t.active && !t.secret) {
                    result.add(t);
                }
            }
        }
        return result;
    }
}
