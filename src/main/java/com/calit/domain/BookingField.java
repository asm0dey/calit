package com.calit.domain;

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

    /** Per-type fields if the meeting type defines any; otherwise the global default form. */
    public static List<BookingField> formFor(Long meetingTypeId) {
        List<BookingField> typed = list("meetingTypeId = ?1 order by position", meetingTypeId);
        return typed.isEmpty()
                ? list("meetingTypeId is null order by position")
                : typed;
    }
}
