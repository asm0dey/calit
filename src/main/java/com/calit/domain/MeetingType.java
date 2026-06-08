package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "meeting_type")
public class MeetingType extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false, unique = true)
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

    public static MeetingType findBySlug(String slug) {
        return find("slug", slug).firstResult();
    }

    /** Active, non-secret types — what the public invitee landing page lists. */
    public static List<MeetingType> listPublic() {
        return list("active = true and secret = false");
    }
}
