package site.asm0dey.calit.google;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;

/**
 * Which Google calendar a given host writes a given meeting type's events on:
 *
 * <ol>
 *   <li>the (type, host) override — {@code meeting_type} columns for the creator, that host's
 *       {@code meeting_type_host} row for a co-host — when it still names one of that owner's
 *       selected calendars;
 *   <li>otherwise the owner's write target ({@code google_calendar.write_target});
 *   <li>otherwise nothing, and {@link CalendarPort} raises its existing "no write target"
 *       error.
 * </ol>
 *
 * A dangling override (calendar unticked, or the account disconnected so the FK nulled the
 * credential) never fails a booking: it degrades to the write target, loudly in the log and
 * visibly in the edit form.
 */
@ApplicationScoped
public class WriteTargetResolver {

    private static final Logger LOG = Logger.getLogger(WriteTargetResolver.class);

    /**
     * The write-calendar picker's sentinel form value meaning "leave the stored override exactly as
     * it is" (including a dangling one). Both {@code AdminResource} (creator) and
     * {@code SharedMeetingsResource} (co-host) render it as the dangling option's value and compare
     * their submitted {@code writeCalendar} field against it -- one definition so the two save paths
     * can never silently diverge on the string that stops an override from being erased.
     */
    public static final String KEEP = "keep";

    /**
     * The stored write override for this (type, host), or null when unset. A row whose credential
     * was nulled by disconnecting the account keeps its calendar id, and that half-row IS an
     * override — a dangling one. Reading it as "unset" would hide the very case the Host needs to
     * be told about.
     */
    public CalendarRef writeOverride(Long ownerId, MeetingType type) {
        if (type == null) {
            return null;
        }
        if (ownerId.equals(type.ownerId)) {
            return ref(type.googleCredentialId, type.googleCalendarId);
        }
        MeetingTypeHost host = type.id == null ? null : MeetingTypeHost.find(type.id, ownerId);
        return host == null ? null : ref(host.googleCredentialId, host.googleCalendarId);
    }

    /** True when {@code ref} names one of this owner's currently selected calendars. */
    public boolean owns(Long ownerId, CalendarRef ref) {
        return ref != null && GoogleCalendar.findOwned(ownerId, ref.credentialId(), ref.googleCalendarId()) != null;
    }

    /** The calendar this host writes {@code type} on, or null when they have no write calendar at all. */
    public GoogleCalendar resolveCalendar(Long ownerId, MeetingType type) {
        CalendarRef override = writeOverride(ownerId, type);
        if (override != null) {
            GoogleCalendar live =
                    GoogleCalendar.findOwned(ownerId, override.credentialId(), override.googleCalendarId());
            if (live != null) {
                return live;
            }
            LOG.warnf(
                    "Meeting type %s: write-calendar override %s (credential %s, owner %d) is no longer selected; falling back to the write target",
                    type.id, override.googleCalendarId(), override.credentialId(), ownerId);
        }
        return GoogleCalendar.writeTarget(ownerId);
    }

    /** {@link #resolveCalendar} as an address for {@link CalendarPort}, or null when there is none. */
    public CalendarRef resolve(Long ownerId, MeetingType type) {
        return address(resolveCalendar(ownerId, type));
    }

    /**
     * This Host's write target as an address, ignoring any override. Callers that are about to
     * CLEAR an override use it to know where the type will write next, which is not the same as
     * "nowhere".
     */
    public CalendarRef writeTargetRef(Long ownerId) {
        return address(GoogleCalendar.writeTarget(ownerId));
    }

    private static CalendarRef address(GoogleCalendar calendar) {
        return calendar == null ? null : new CalendarRef(calendar.googleCredentialId, calendar.googleCalendarId);
    }

    /**
     * True when the calendar this host would write {@code type} on cannot mint Google Meet links, so
     * GOOGLE_MEET must be refused. False when there is no calendar yet — don't over-block, the owner
     * may pick a Meet-capable one later.
     */
    public boolean blocksMeet(Long ownerId, MeetingType type) {
        GoogleCalendar target = resolveCalendar(ownerId, type);
        return target != null && !target.supportsMeet;
    }

    /**
     * Parse the {@code "credentialId:googleCalendarId"} value the pickers submit (same encoding the
     * Google settings page already uses). Blank or malformed → null, meaning "no override".
     */
    public static CalendarRef parseRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var sep = raw.indexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            return null;
        }
        try {
            return new CalendarRef(Long.valueOf(raw.substring(0, sep)), raw.substring(sep + 1));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * The calendar id alone decides whether an override exists: a null credential means the account
     * was disconnected (the FK nulled it), which is a dangling override, not the absence of one.
     */
    private static CalendarRef ref(Long credentialId, String googleCalendarId) {
        return googleCalendarId == null ? null : new CalendarRef(credentialId, googleCalendarId);
    }
}
