package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

@Entity
@Table(name = "availability_rule")
public class AvailabilityRule extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    public DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    public LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    public LocalTime endTime;

    /**
     * Null = this owner's global default rule (applies to all of their meeting types).
     * Otherwise this rule overrides for that meeting type. Either way it carries {@link #ownerId}.
     */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    /** This owner's per-type rules for a weekday. (meetingTypeId is already the owner's; ownerId is
     *  defence-in-depth and keeps every query uniformly owner-filtered.) */
    public static List<AvailabilityRule> forMeetingType(Long ownerId, Long meetingTypeId, DayOfWeek dow) {
        return list("ownerId = ?1 and meetingTypeId = ?2 and dayOfWeek = ?3", ownerId, meetingTypeId, dow);
    }

    /** This owner's GLOBAL default rules (meetingTypeId IS NULL) for a weekday. */
    public static List<AvailabilityRule> globalForOwner(Long ownerId, DayOfWeek dow) {
        return list("ownerId = ?1 and meetingTypeId is null and dayOfWeek = ?2", ownerId, dow);
    }
}
