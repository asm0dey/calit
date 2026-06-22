package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "owner_settings")
public class OwnerSettings extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(name = "owner_name", nullable = false)
    public String ownerName;

    @Column(name = "owner_email", nullable = false)
    public String ownerEmail;

    @Column(nullable = false, length = 64)
    public String timezone;

    /** BCP-47 language tag for this owner's admin UI + owner-copy emails. */
    @Column(nullable = false)
    public String locale = "en";

    /** When false, the owner suppresses their own notification emails (Plan 4 gates on this). */
    @Column(name = "owner_notifications_enabled", nullable = false)
    public boolean ownerNotificationsEnabled = true;

    /** Returns this owner's settings row, or null if not yet configured. */
    public static OwnerSettings forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }

    /**
     * Owner ids whose settings email equals {@code email} (case-insensitive). Empty for
     * null/blank input. Used to auto-link a verified Google identity to an existing account;
     * the caller links only when exactly one id is returned.
     */
    public static java.util.List<Long> findOwnerIdsByEmail(String email) {
        if (email == null || email.isBlank()) {
            return java.util.List.of();
        }
        return OwnerSettings.find("lower(ownerEmail) = ?1", email.trim().toLowerCase())
                .<OwnerSettings>list().stream().map(s -> s.ownerId).toList();
    }
}
