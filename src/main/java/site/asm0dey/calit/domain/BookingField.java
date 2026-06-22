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
@Table(name = "booking_field")
public class BookingField extends PanacheEntityBase {

    public enum FieldType { SHORT_TEXT, LONG_TEXT, EMAIL, PHONE, NUMBER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** Null = part of the global default form. Otherwise overrides the form for that meeting type. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    @Column(name = "field_key", nullable = false, length = 64)
    public String fieldKey;

    @Column(nullable = false)
    public String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public FieldType type;

    @Column(nullable = false)
    public boolean required = false;

    @Column(nullable = false)
    public int position = 0;

    /** This owner's global default form fields (meeting_type_id IS NULL), ordered by position. */
    public static List<BookingField> globalForOwner(Long ownerId) {
        return list("ownerId = ?1 and meetingTypeId is null order by position", ownerId);
    }

    /**
     * Per-type fields if the meeting type defines any (still scoped to this owner); otherwise the
     * owner's global default form. The owner scope is defence-in-depth: meeting-type ids are already
     * the owner's, but the global fallback MUST be the owner's globals, never another owner's.
     */
    public static List<BookingField> formFor(Long ownerId, Long meetingTypeId) {
        List<BookingField> typed = list(
                "ownerId = ?1 and meetingTypeId = ?2 order by position", ownerId, meetingTypeId);
        return typed.isEmpty() ? globalForOwner(ownerId) : typed;
    }
}
