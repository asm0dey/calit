package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "meeting_type")
public class MeetingType extends PanacheEntityBase {

    public enum LocationType { GOOGLE_MEET, PHONE, IN_PERSON, CUSTOM }

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
}
