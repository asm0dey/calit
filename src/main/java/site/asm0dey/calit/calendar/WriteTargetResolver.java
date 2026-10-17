package site.asm0dey.calit.calendar;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import site.asm0dey.calit.domain.MeetingType;

/**
 * Which calendar (on any provider) a given host writes a given meeting type's Mirror events on:
 *
 * <ol>
 *   <li>the Creator's override on the {@code meeting_type} columns, when it still names one of
 *       their selected calendars (a Co-host has none; ADR 0007);
 *   <li>otherwise the owner's write target (the {@code calendar} table's {@code write_target} flag);
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
     * The Creator's stored write override for the type, or null when unset (a Co-host has none). A row whose credential
     * was nulled by disconnecting the account keeps its calendar id, and that half-row IS an
     * override — a dangling one. Reading it as "unset" would hide the very case the Host needs to
     * be told about.
     */
    public CalendarRef writeOverride(Long ownerId, MeetingType type) {
        if (ownerId == null || type == null) {
            return null;
        }
        return ownerId.equals(type.ownerId) ? ref(type.overrideAccountId, type.overrideRemoteCalendarId) : null;
    }

    /**
     * True when {@code ref} names one of this owner's currently selected calendars.
     */
    public boolean owns(Long ownerId, CalendarRef ref) {
        return ref != null && SelectedCalendar.findOwned(ownerId, ref.accountId(), ref.remoteCalendarId()) != null;
    }

    /**
     * The calendar this host writes {@code type} on, or null when they have no write calendar at all.
     */
    public SelectedCalendar resolveCalendar(Long ownerId, MeetingType type) {
        CalendarRef override = writeOverride(ownerId, type);
        if (override != null) {
            SelectedCalendar live =
                    SelectedCalendar.findOwned(ownerId, override.accountId(), override.remoteCalendarId());
            if (live != null) {
                return live;
            }
            LOG.warnf(
                    "Meeting type %s: write-calendar override %s (credential %s, owner %d) is no longer "
                    + "selected; falling back to the write target",
                    type.id,
                    override.remoteCalendarId(),
                    override.accountId(),
                    ownerId
            );
        }
        return SelectedCalendar.writeTargetOf(ownerId);
    }

    /**
     * {@link #resolveCalendar} as an address for {@link CalendarPort}, or null when there is none.
     */
    public CalendarRef resolve(Long ownerId, MeetingType type) {
        return address(resolveCalendar(ownerId, type));
    }

    /**
     * This Host's write target as an address, ignoring any override. Callers that are about to
     * CLEAR an override use it to know where the type will write next, which is not the same as
     * "nowhere".
     */
    public CalendarRef writeTargetRef(Long ownerId) {
        return address(SelectedCalendar.writeTargetOf(ownerId));
    }

    private static CalendarRef address(SelectedCalendar calendar) {
        return calendar == null ? null : new CalendarRef(calendar.accountId, calendar.remoteId);
    }

    /**
     * The write-calendar picker's three-valued form encoding for {@code override}: {@code ""} when
     * there is no override, {@link #KEEP} when {@code dangling} (round-trips a dangling override
     * through an unrelated save instead of erasing it), else the
     * {@code "accountId:remoteCalendarId"} pair -- byte-identical to {@link
     * SelectedCalendar#optionValue()}, which the template compares against to pre-select the matching
     * {@code <option>}. {@code AdminResource} (creator) and {@code SharedMeetingsResource} (co-host)
     * both call this one method so their two save paths can never silently diverge on the encoding.
     */
    public static String writeCalendarValue(CalendarRef override, boolean dangling) {
        if (override == null) {
            return "";
        }
        return dangling ? KEEP : override.accountId() + ":" + override.remoteCalendarId();
    }

    /**
     * True when the Google calendar this host would write {@code type} on cannot mint Google Meet links,
     * so GOOGLE_MEET must be refused (Meet links are Google-only). False when there is no calendar yet — don't over-block, the owner
     * may pick a Meet-capable one later — and false on a non-Google calendar, which is
     * {@link #meetUnavailable} (warn and allow) instead.
     */
    public boolean blocksMeet(Long ownerId, MeetingType type) {
        SelectedCalendar target = resolveCalendar(ownerId, type);
        return target != null && provider(ownerId, target) == CalendarProvider.GOOGLE && !target.supportsMeet;
    }

    /**
     * True when {@code type} asks for Google Meet but this host writes it on a non-Google calendar, so its
     * Mirror events get no link; the meeting type page warns, booking still works.
     */
    public boolean meetUnavailable(Long ownerId, MeetingType type) {
        if (type == null || type.locationType != MeetingType.LocationType.GOOGLE_MEET) {
            return false;
        }
        SelectedCalendar target = resolveCalendar(ownerId, type);
        return target != null && provider(ownerId, target) != CalendarProvider.GOOGLE;
    }

    /**
     * True when a booking of {@code type} really gets a Google Meet link: it asks for Meet and this host writes it on
     * a Meet-capable Google calendar. No write calendar means no Mirror event, so no link either.
     */
    public boolean mintsMeet(Long ownerId, MeetingType type) {
        if (type == null || type.locationType != MeetingType.LocationType.GOOGLE_MEET) {
            return false;
        }
        SelectedCalendar target = resolveCalendar(ownerId, type);
        return target != null && target.supportsMeet && provider(ownerId, target) == CalendarProvider.GOOGLE;
    }

    private static CalendarProvider provider(Long ownerId, SelectedCalendar calendar) {
        CalendarAccount account = CalendarAccount.findOwned(ownerId, calendar.accountId);
        return account == null ? CalendarProvider.GOOGLE : account.provider();
    }

    /**
     * Parse the {@code "accountId:remoteCalendarId"} value the pickers submit (same encoding the
     * calendar settings page uses). Blank or malformed → null, meaning "no override".
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
    private static CalendarRef ref(Long accountId, String remoteCalendarId) {
        return remoteCalendarId == null ? null : new CalendarRef(accountId, remoteCalendarId);
    }
}
