package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import site.asm0dey.calit.privacy.PrivacyConfig;

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
    /**
     * BCP-47 language tag for this owner's admin UI + owner-copy emails.
     */
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
    /**
     * The legal {@link #timeFormat} values; anything else is coerced to {@code auto} on save.
     */
    public static final java.util.Set<String> HOUR_CYCLES = java.util.Set.of("auto", "h12", "h23");
    /**
     * When false, the owner suppresses their own notification emails (Plan 4 gates on this).
     */
    @Column(name = "owner_notifications_enabled", nullable = false)
    public boolean ownerNotificationsEnabled = true;
    /**
     * Signed-in GET / 303s to /me. Opt-out, so it defaults on. The product page stays at /calit,
     * which never redirects. Named "home", not "landing": "landing" means /{username} here.
     */
    @Column(name = "home_redirect_enabled", nullable = false)
    public boolean homeRedirectEnabled = true;
    /**
     * This owner's booking-retention window in days. NULL = fall back to the instance default
     * ({@code calit.retention.booking-days}), which is itself unset by default = keep forever.
     */
    @Column(name = "booking_retention_days")
    public Integer bookingRetentionDays;

    /**
     * This owner's effective retention window in days, or null for "keep forever". The owner's own
     * value wins in BOTH directions — a longer window is as legitimate a choice as a shorter one,
     * and silently capping it at the instance default would be a deletion the operator did not ask
     * for. The result is always clamped to {@link PrivacyConfig#MAX_RETENTION_DAYS} via
     * {@link #clampDays(Integer)}: {@code bookingRetentionDays} is normally written through the
     * settings form's own clamp, but this is the one place every Java caller reads the value back
     * through, so a column value above the cap (however it got there) can never be reported as a
     * longer window than {@code RetentionScheduler}'s SQL — which applies the same cap — will
     * actually honour.
     */
    public Integer retentionDaysOrDefault(Integer instanceDefault) {
        return clampDays(bookingRetentionDays != null ? bookingRetentionDays : instanceDefault);
    }

    /**
     * Clamps a retention-day count to {@link PrivacyConfig#MAX_RETENTION_DAYS}; {@code null}
     * passes through unchanged ("keep forever"). The single clamp point for every retention-day
     * value any Java caller reads — {@link #retentionDaysOrDefault(Integer)} and the
     * no-settings-row fallback both go through it, so both branches stay consistent with the SQL
     * sweep's own {@code LEAST(...)} cap.
     */
    public static Integer clampDays(Integer days) {
        return days == null ? null : Math.min(days, PrivacyConfig.MAX_RETENTION_DAYS);
    }

    /**
     * Returns this owner's settings row, or null if not yet configured.
     */
    public static OwnerSettings forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }

    /**
     * Every zone id the JDK knows, sorted — the source for the settings and wizard pickers.
     */
    public static java.util.List<String> zoneIds() {
        return java.time.ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    /**
     * Coerces a submitted timezone to a storable value. {@code timezone} is NOT NULL and eleven
     * call sites do an unguarded {@code ZoneId.of(settings.timezone)} — including the owner's
     * PUBLIC booking page and the booking transaction — so a value the JDK cannot parse 500s
     * them all. Anything not a known zone id (including null and blank) becomes {@code "UTC"}.
     *
     * <p>Every path that writes {@link #timezone} must call this: the rendered {@code <select>}
     * can only submit a real zone id, but a crafted POST is not bound by the form (calit-4whp).
     */
    public static String coerceZone(String timezone) {
        return zoneIds().contains(timezone) ? timezone : "UTC";
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
        return OwnerSettings
            .find("lower(ownerEmail) = ?1", email.trim().toLowerCase())
            .<OwnerSettings>list()
            .stream()
            .map(s -> s.ownerId)
            .toList();
    }
}
