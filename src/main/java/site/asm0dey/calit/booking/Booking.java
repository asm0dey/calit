package site.asm0dey.calit.booking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarRef;

@Entity
@Table(name = "booking")
public class Booking extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    @Column(name = "invitee_name", nullable = false)
    public String inviteeName;

    @Column(name = "invitee_email", nullable = false)
    public String inviteeEmail;

    @Column(name = "start_utc", nullable = false)
    public Instant startUtc;

    @Column(name = "end_utc", nullable = false)
    public Instant endUtc;

    @Column(name = "google_event_id")
    public String googleEventId;

    /** Google's calendar id for {@link #googleEventId}; null on rows created before V26. */
    @Column(name = "google_calendar_id", columnDefinition = "text")
    public String googleCalendarId;

    /** The connected account the event was created with; nulled when that account is disconnected. */
    @Column(name = "google_credential_id")
    public Long googleCredentialId;

    @Column(name = "meet_link", length = 512)
    public String meetLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public BookingStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * Invitee manage/reschedule/cancel key: a random UUID set at creation. Unique.
     * Plan 4 emails a tokenized link {app.base-url}/booking/{manageToken}/manage; Plan 5 must
     * expose that exact UI route. (The REST API uses /api/bookings/{manageToken}/... separately.)
     */
    @Column(name = "manage_token", nullable = false, length = 36, unique = true)
    public String manageToken;

    /** BCP-47 language tag captured from the invitee at booking time; drives invitee emails. */
    @Column(nullable = false)
    public String locale = "en";

    /**
     * Feature 14 owner-approval nonce: an unguessable random UUID, set only when the meeting type
     * requires approval. Emailed to the owner inside the authenticated approve/decline links
     * ({app.base-url}/me/bookings/{id}/approve?t={approvalToken}) as a CSRF nonce. Null otherwise.
     */
    @Column(name = "approval_token", length = 36, unique = true)
    public String approvalToken;

    /**
     * Feature 10: submitted values for the owner-defined custom BookingFields
     * (fieldKey -> value). Built-in full-name/email are NOT stored here — they
     * live in {@link #inviteeName}/{@link #inviteeEmail}. Stored as a JSONB
     * column; defaults to an empty map at the DB level.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", columnDefinition = "jsonb")
    public Map<String, String> answers = new java.util.HashMap<>();

    /**
     * Per-booking override of the meeting's displayed name. NULL (or blank) means "no override" —
     * {@link #effectiveTitle} falls back to the meeting type's name. Editable post-booking by both the
     * host and the invitee; changes propagate to email, the Google event summary, and the .ics SUMMARY.
     */
    @Column(columnDefinition = "text")
    public String title;

    /**
     * Per-booking override of the meeting description. NULL (or blank) falls back to the meeting type's
     * description (which may itself be null). Propagates to the .ics DESCRIPTION and Google event description.
     */
    @Column(columnDefinition = "text")
    public String description;

    /**
     * iTIP SEQUENCE for this booking's guest .ics invites. Starts at 0; reschedule() increments it so
     * a guest's calendar client supersedes the prior event (and so a CANCEL with an equal-or-higher
     * SEQUENCE removes it). Only guest .ics uses it today; the invitee/owner .ics still emits SEQUENCE:0.
     */
    @Column(name = "ics_sequence", nullable = false)
    public int icsSequence = 0;

    /**
     * Multi-host: links the N rows (one per host) of a single conceptual booking. NULL means a
     * single-host booking (the common case).
     */
    @Column(name = "group_id")
    public UUID groupId;

    /**
     * This owner's HELD (PENDING or CONFIRMED) bookings whose [startUtc, endUtc) overlaps the
     * window [from, to). These are the bookings that block THIS OWNER's calendar (a pending
     * approval request holds its slot too — feature 14). CANCELLED/DECLINED are excluded.
     * Owner-scoped: owners are isolated, so another owner's bookings are never in this set.
     * Overlap predicate: startUtc < to AND from < endUtc.
     */
    public static List<Booking> heldOverlapping(Long ownerId, Instant from, Instant to) {
        return list(
                "ownerId = ?1 and status in ?2 and startUtc < ?3 and ?4 < endUtc",
                ownerId,
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED),
                to,
                from);
    }

    /** Loads a booking by its invitee manage-token (reschedule/cancel key), or null. */
    public static Booking findByManageToken(String manageToken) {
        return find("manageToken", manageToken).firstResult();
    }

    /** Displayed meeting name: this booking's override when set + non-blank, else the type's name. */
    public String effectiveTitle(MeetingType type) {
        return (title != null && !title.isBlank()) ? title : type.name;
    }

    /** Meeting description: override when set + non-blank, else the type's description (may be null). */
    public String effectiveDescription(MeetingType type) {
        if (description != null && !description.isBlank()) {
            return description;
        }
        return (type.description != null && !type.description.isBlank()) ? type.description : null;
    }

    /** This row's Google event address, or null when unknown (pre-V26 rows) — see {@link CalendarRef}. */
    public CalendarRef calendarRef() {
        return googleCalendarId == null ? null : new CalendarRef(googleCredentialId, googleCalendarId);
    }

    /** Feature 16: how many bookings this invitee email created in [dayStart, dayEnd). */
    public static long countByEmailCreatedBetween(String email, Instant dayStart, Instant dayEnd) {
        return count("inviteeEmail = ?1 and createdAt >= ?2 and createdAt < ?3", email, dayStart, dayEnd);
    }

    /** Multi-host: all per-host rows sharing this group_id. */
    public static List<Booking> group(UUID groupId) {
        return list("groupId", groupId);
    }

    /** The creator's row in a group (the invitee-facing lead). */
    public static Booking leadOfGroup(UUID groupId, Long creatorOwnerId) {
        return find("groupId = ?1 and ownerId = ?2", groupId, creatorOwnerId).firstResult();
    }

    /** Counts one per conceptual booking: standalone rows + distinct group_id. */
    public static long countDistinctBookingsByEmailBetween(String email, Instant dayStart, Instant dayEnd) {
        Long singles = getEntityManager()
                .createQuery(
                        "select count(b) from Booking b where b.inviteeEmail = :e "
                                + "and b.createdAt >= :s and b.createdAt < :d and b.groupId is null",
                        Long.class)
                .setParameter("e", email)
                .setParameter("s", dayStart)
                .setParameter("d", dayEnd)
                .getSingleResult();
        Long groups = getEntityManager()
                .createQuery(
                        "select count(distinct b.groupId) from Booking b where b.inviteeEmail = :e "
                                + "and b.createdAt >= :s and b.createdAt < :d and b.groupId is not null",
                        Long.class)
                .setParameter("e", email)
                .setParameter("s", dayStart)
                .setParameter("d", dayEnd)
                .getSingleResult();
        return singles + groups;
    }
}
