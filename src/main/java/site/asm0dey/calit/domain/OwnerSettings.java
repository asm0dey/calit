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

    /**
     * This owner's clock preference for their OWN surfaces: {@code auto} (the viewer's device on
     * /me, the translated pattern in email), {@code h12}, or {@code h23}. Never applied to
     * invitee-facing pages or invitee emails — a public booking page must not carry one person's
     * preference. Values match Intl's {@code hourCycle} vocabulary.
     */
    @Column(name = "time_format", nullable = false, length = 8)
    public String timeFormat = "auto";

    /** The legal {@link #timeFormat} values; anything else is coerced to {@code auto} on save. */
    public static final java.util.Set<String> HOUR_CYCLES = java.util.Set.of("auto", "h12", "h23");

    /** When false, the owner suppresses their own notification emails (Plan 4 gates on this). */
    @Column(name = "owner_notifications_enabled", nullable = false)
    public boolean ownerNotificationsEnabled = true;

    /** Returns this owner's settings row, or null if not yet configured. */
    public static OwnerSettings forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }

    /**
     * Persists the placeholder settings row that EVERY account-creation path must leave behind.
     *
     * <p>{@code ownerName}, {@code ownerEmail} and {@code timezone} are NOT NULL, and the public
     * booking path reads {@code forOwner(id).timezone} unguarded (issue #99) — so an account
     * without this row is one unguarded read away from an NPE. {@code V24__backfill_owner_settings}
     * fixed the rows that existed at that boot; it is a one-shot backfill, not a runtime guarantee.
     * The first-login wizard overwrites every placeholder here.
     *
     * @param email the address to seed when the creating path knows one (an invite, or a verified
     *     Google identity); {@code null} becomes {@code ""} to satisfy the NOT NULL constraint.
     */
    public static OwnerSettings seed(Long ownerId, String email) {
        var s = new OwnerSettings();
        s.ownerId = ownerId;
        s.ownerName = "";
        s.ownerEmail = email == null ? "" : email;
        s.timezone = "UTC";
        s.persist();
        return s;
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
        return OwnerSettings.find("lower(ownerEmail) = ?1", email.trim().toLowerCase()).<OwnerSettings>list().stream()
                .map(s -> s.ownerId)
                .toList();
    }
}
