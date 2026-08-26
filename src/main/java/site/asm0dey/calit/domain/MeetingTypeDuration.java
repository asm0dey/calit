package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One length a meeting type may be booked at, with optional buffer overrides for that length.
 *
 * <p>The meeting type's own {@code durationMinutes} is an IMPLICIT member of the set — see
 * {@link #allowedDurations(MeetingType)} and
 * {@code docs/adr/0003-a-meeting-types-duration-doubles-as-its-default.md}. A row whose
 * {@code durationMinutes} equals the default therefore carries only that length's buffers;
 * deleting it drops the buffers, never the duration.
 */
@Entity
@Table(name = "meeting_type_duration")
@IdClass(MeetingTypeDuration.Key.class)
public class MeetingTypeDuration extends PanacheEntityBase {

    /** Composite key mirroring the table's natural primary key. */
    public static class Key implements Serializable {
        public Long meetingTypeId;
        public int durationMinutes;

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(meetingTypeId, k.meetingTypeId)
                    && durationMinutes == k.durationMinutes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(meetingTypeId, durationMinutes);
        }
    }

    @Id
    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    @Id
    @Column(name = "duration_minutes", nullable = false)
    public int durationMinutes;

    /** Null = this length imposes no buffer of its own; see ADR-0002 for how it combines. */
    @Column(name = "buffer_before_minutes")
    public Integer bufferBeforeMinutes;

    /** Null = this length imposes no buffer of its own; see ADR-0002 for how it combines. */
    @Column(name = "buffer_after_minutes")
    public Integer bufferAfterMinutes;

    /** Configured rows for a type, shortest first. Does NOT include the implicit default. */
    public static List<MeetingTypeDuration> rowsFor(Long meetingTypeId) {
        return list("meetingTypeId = ?1 order by durationMinutes", meetingTypeId);
    }

    /**
     * Every length this type may be booked at, shortest first: the configured rows unioned with the
     * type's own {@code durationMinutes}, which is why the set can never omit its default.
     */
    public static List<Integer> allowedDurations(MeetingType type) {
        List<Integer> all = new ArrayList<>();
        all.add(type.durationMinutes);
        for (MeetingTypeDuration d : rowsFor(type.id)) {
            if (d.durationMinutes != type.durationMinutes) {
                all.add(d.durationMinutes);
            }
        }
        all.sort(Integer::compareTo);
        return all;
    }

    /** The cadence anchor: the shortest length on offer, which is NOT necessarily the default. */
    public static int shortestAllowed(MeetingType type) {
        return allowedDurations(type).getFirst();
    }

    public static boolean isAllowed(MeetingType type, int durationMinutes) {
        return allowedDurations(type).contains(durationMinutes);
    }

    /** The buffer-override row for one length, or null when that length has none. */
    public static MeetingTypeDuration findRow(Long meetingTypeId, int durationMinutes) {
        return find("meetingTypeId = ?1 and durationMinutes = ?2", meetingTypeId, durationMinutes)
                .firstResult();
    }
}
