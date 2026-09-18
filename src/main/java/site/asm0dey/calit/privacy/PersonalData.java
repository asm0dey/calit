package site.asm0dey.calit.privacy;

import java.util.List;
import java.util.Set;

/**
 * What personal data this deployment holds, table by table, and what erasure does with each piece.
 *
 * <p>Hand-written on purpose. A registry interface would buy extensibility that has no second
 * consumer, and reflection over JPA metadata would hide exactly the judgement that matters: which
 * columns are personal, and what erasure does with them. The safety property is
 * {@code PersonalDataInventoryTest}, which reads the live {@code information_schema} and fails the
 * build when this file falls behind a migration.
 *
 * <p>ponytail: a flat list of records, no lookup indices. Twenty-odd entries walked a handful of
 * times per request at most.
 */
// Column-name literals are duplicated by design: this inventory mirrors information_schema
// verbatim, and PersonalDataInventoryTest checks every literal against the live schema.
@SuppressWarnings("java:S1192")
public final class PersonalData {

    private PersonalData() {}

    /** Whose data a table's personal columns are. */
    public enum Subject {
        INVITEE,
        GUEST,
        OWNER,
        NONE
    }

    /** What an erasure or account deletion does with a table. */
    public enum EraseRoute {
        /** Columns are blanked; the row survives so the owner keeps the slot record. */
        ANONYMISE_IN_PLACE,
        /** Rows are deleted outright. */
        DELETE_ROWS,
        /** Nothing explicit: an FK ON DELETE CASCADE removes these rows with their parent. */
        CASCADES,
        /** Removed on a fixed schedule regardless of any request. */
        AGE_PURGE,
        /**
         * Deliberately NEVER erased or purged — written AT deletion time specifically to outlive
         * the row it derives from, closing a security gap (deleted-username tombstones, R16:
         * blocks account-takeover via username reuse). Fix-round note: none of the four routes
         * above honestly describe this case (it isn't blanked, deleted, cascaded away, or aged
         * out — the whole point is that it is none of those), so this is a fifth, precisely-scoped
         * route rather than a contorted fit onto an existing one. Flagged for review in the fix
         * report rather than silently forced onto e.g. {@code DELETE_ROWS} or {@code AGE_PURGE}.
         */
        RETAINED_INDEFINITELY,
        /** No personal data. */
        NOT_PERSONAL
    }

    /**
     * One table's classification. {@code columns} is EVERY column the table has — that total is
     * what the guard test diffs against the live schema, so a new column cannot slip past
     * unclassified. {@code personalColumns} is the subset carrying personal data.
     */
    public record Classified(
            String table, Set<String> columns, Set<String> personalColumns, Subject subject, EraseRoute route) {}

    /** A place calit sends personal data that it may not be able to reach back into. */
    public record Destination(String name, String when, boolean reachableByErasure) {}

    public static final List<Classified> TABLES = List.of(
            // --- invitee / guest data -----------------------------------------------------------
            new Classified(
                    "booking",
                    Set.of(
                            "answers",
                            "approval_token",
                            "created_at",
                            "description",
                            "end_utc",
                            "erased_at",
                            "google_calendar_id",
                            "google_credential_id",
                            "google_event_id",
                            "group_id",
                            "ics_sequence",
                            "id",
                            "invitee_email",
                            "invitee_name",
                            "locale",
                            "manage_token",
                            "meet_link",
                            "meeting_type_id",
                            "owner_id",
                            "start_utc",
                            "status",
                            "title"),
                    // title/description are invitee-writable via POST /booking/{t}/edit-details, so
                    // they are free text about the invitee -- classified personal even though the
                    // design spec's table omitted them. Nulling them falls back to the meeting
                    // type's own name/description, so the owner's record stays readable.
                    Set.of("invitee_name", "invitee_email", "answers", "meet_link", "title", "description"),
                    Subject.INVITEE,
                    EraseRoute.ANONYMISE_IN_PLACE),
            new Classified(
                    "booking_guest",
                    Set.of("booking_id", "created_at", "decline_token", "email", "id", "owner_id", "status"),
                    Set.of("email"),
                    Subject.GUEST,
                    EraseRoute.DELETE_ROWS),
            new Classified(
                    "reminder",
                    Set.of("booking_id", "id", "kind", "send_at", "sent_at"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES),
            new Classified(
                    "email_outbox",
                    Set.of(
                            "attempts",
                            "booking_id",
                            "created_at",
                            "html_body",
                            "ics_bytes",
                            "id",
                            "last_error",
                            "next_attempt_at",
                            "not_after",
                            "owner_id",
                            "recipient",
                            "sent_at",
                            "subject"),
                    Set.of("recipient", "subject", "html_body", "ics_bytes"),
                    Subject.INVITEE,
                    EraseRoute.AGE_PURGE),
            // --- owner data ---------------------------------------------------------------------
            new Classified(
                    "deleted_username",
                    Set.of("deleted_at", "username_sha256"),
                    // A one-way hash of a deleted owner's username, kept forever on purpose — see
                    // EraseRoute.RETAINED_INDEFINITELY's javadoc.
                    Set.of("username_sha256"),
                    Subject.OWNER,
                    EraseRoute.RETAINED_INDEFINITELY),
            new Classified(
                    "app_user",
                    Set.of(
                            "created_at",
                            "enabled",
                            "google_sub",
                            "id",
                            "is_admin",
                            "must_change_password",
                            "oidc_admin",
                            "oidc_sub",
                            "password_hash",
                            "roles",
                            "settings_complete",
                            "username"),
                    Set.of("username", "password_hash", "google_sub", "oidc_sub"),
                    Subject.OWNER,
                    EraseRoute.DELETE_ROWS),
            new Classified(
                    "owner_settings",
                    Set.of(
                            "booking_retention_days",
                            "home_redirect_enabled",
                            "id",
                            "locale",
                            "owner_email",
                            "owner_id",
                            "owner_name",
                            "owner_notifications_enabled",
                            "time_format",
                            "timezone"),
                    Set.of("owner_name", "owner_email", "timezone"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "google_credential",
                    Set.of(
                            "access_token",
                            "access_token_expiry",
                            "account_email",
                            "google_sub",
                            "id",
                            "last_probed_at",
                            "needs_reconnect",
                            "owner_id",
                            "reconnect_notified_at",
                            "refresh_token"),
                    Set.of("account_email", "google_sub", "access_token", "refresh_token"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "google_calendar",
                    Set.of(
                            "google_calendar_id",
                            "google_credential_id",
                            "id",
                            "owner_id",
                            "read_for_busy",
                            "summary",
                            "supports_meet",
                            "write_target"),
                    Set.of("google_calendar_id", "summary"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "notification_channel",
                    Set.of(
                            "created_at",
                            "default_enabled",
                            "id",
                            "label",
                            "last_failure_at",
                            "last_success_at",
                            "owner_id",
                            "url"),
                    Set.of("url", "label"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "password_reset_token",
                    Set.of("expires_at", "id", "token_hash", "user_id"),
                    Set.of("user_id", "token_hash"),
                    Subject.OWNER,
                    EraseRoute.AGE_PURGE),
            new Classified(
                    "login_ticket",
                    Set.of("expires_at", "id", "token_hash", "user_id"),
                    Set.of("user_id", "token_hash"),
                    Subject.OWNER,
                    EraseRoute.AGE_PURGE),
            // --- remaining owner-configuration tables: no free text of their own besides what is
            //     called out below; every row is removed by the app_user cascade (directly via its
            //     own owner_id FK, or transitively through meeting_type / date_override /
            //     notification_channel, all of which themselves cascade from app_user).
            new Classified(
                    "meeting_type",
                    Set.of(
                            "active",
                            "buffer_after_minutes",
                            "buffer_before_minutes",
                            "description",
                            "duration_minutes",
                            "google_calendar_id",
                            "google_credential_id",
                            "guests_mode",
                            "horizon_days",
                            "id",
                            "location_detail",
                            "location_type",
                            "min_notice_minutes",
                            "name",
                            "name_mode",
                            "owner_id",
                            "requires_approval",
                            "secret",
                            "slot_interval_minutes",
                            "slug"),
                    // name/description/location_detail are free text the owner wrote; slug is a
                    // URL-routing identifier, not prose, so it stays out of personalColumns.
                    Set.of("name", "description", "location_detail"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "meeting_type_host",
                    Set.of(
                            "buffer_after_minutes",
                            "buffer_before_minutes",
                            "consent_token",
                            "created_at",
                            "google_calendar_id",
                            "google_credential_id",
                            "id",
                            "meeting_type_id",
                            "owner_id",
                            "responded_at",
                            "role",
                            "status"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES),
            new Classified(
                    "booking_field",
                    Set.of("field_key", "id", "label", "meeting_type_id", "owner_id", "position", "required", "type"),
                    // label is the owner's own text for the form field; field_key is a machine key.
                    Set.of("label"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "availability_rule",
                    Set.of("day_of_week", "end_time", "id", "meeting_type_id", "owner_id", "start_time"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES),
            new Classified(
                    "date_override",
                    Set.of("id", "meeting_type_id", "override_date", "owner_id"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES),
            new Classified(
                    "date_override_window",
                    Set.of("date_override_id", "end_time", "id", "start_time"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES),
            new Classified(
                    "notification_channel_meeting_type",
                    Set.of("channel_id", "id", "meeting_type_id"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES),
            // The set of alternate bookable lengths for a meeting type (ADR-0003) -- numeric
            // configuration only, no free text.
            new Classified(
                    "meeting_type_duration",
                    Set.of("buffer_after_minutes", "buffer_before_minutes", "duration_minutes", "meeting_type_id"),
                    Set.of(),
                    Subject.NONE,
                    EraseRoute.CASCADES));

    /**
     * Where calit sends invitee data that it cannot always reach back into. The guard test cannot
     * check this list against anything — it exists so that a NEW outbound integration lands next to
     * the line recording whether erasure reaches it, and so the erase confirm page can state the
     * limits before the invitee clicks.
     */
    public static final List<Destination> OUTBOUND = List.of(
            new Destination(
                    "Google Calendar event",
                    "the owner has Google connected",
                    // Reachable via the API -- but Google keeps the event in trash for roughly 30
                    // days, and the call cannot run at all if the grant was since disconnected.
                    true),
            new Destination("Notification channel message", "the owner configured a channel", false),
            new Destination("Email already delivered over SMTP", "every booking", false),
            new Destination("The .ics in the invitee's and guests' own calendars", "every booking", false));

    /** Personal columns of {@code table}, or an empty set when the table is unknown. */
    public static Set<String> personalColumnsOf(String table) {
        return TABLES.stream()
                .filter(c -> c.table().equals(table))
                .findFirst()
                .map(Classified::personalColumns)
                .orElse(Set.of());
    }
}
