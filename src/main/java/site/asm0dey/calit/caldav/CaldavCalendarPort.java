package site.asm0dey.calit.caldav;

import module java.base;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.transaction.Transactional;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Summary;
import org.jboss.logging.Logger;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.calendar.*;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.IcsBuilder;
import site.asm0dey.calit.email.IcsEvent;

/**
 * {@link CalendarPort} over the owner's CalDAV accounts. Mirror events carry no ATTENDEE (calit sends the
 * invitations itself), so the attendee list lives in the description. {@code @Typed} keeps it out of plain
 * {@code CalendarPort} injection until a router picks a provider.
 */
@ApplicationScoped
@Typed({CaldavCalendarPort.class, ProviderCalendarPort.class})
public class CaldavCalendarPort implements ProviderCalendarPort {
    private static final Logger LOG = Logger.getLogger(CaldavCalendarPort.class);
    // booking.event_id is mirrored into the pre-V37 google_event_id VARCHAR(255)
    private static final int MAX_EVENT_ID = 255;
    private static final String ATTENDEES = "Attendees:\n";
    private final CaldavClient client;

    public CaldavCalendarPort(CaldavClient client) {
        this.client = client;
    }

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.CALDAV;
    }

    @Override
    @Transactional
    public boolean isConnected(Long ownerId) {
        return CaldavAccount.count("ownerId", ownerId) > 0;
    }

    @Override
    public List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to) {
        var byAccount = SelectedCalendar.readForBusyByAccount(ownerId, CalendarProvider.CALDAV);
        if (byAccount.isEmpty()) {
            return List.of();
        }
        var zone = ZoneId.of(OwnerSettings.forOwner(ownerId).timezone);
        var raw = new ArrayList<BusyInterval>();
        for (var e : byAccount.entrySet()) {
            if (!(CalendarAccount.findOwned(ownerId, e.getKey()) instanceof CaldavAccount a) || a.needsReconnect) {
                throw new CalendarUnavailableException(
                        "CalDAV account " + e.getKey() + " is disconnected; availability unavailable"
                );
            }
            try {
                var data = new ArrayList<String>();
                for (var c : e.getValue()) {
                    data.addAll(client.queryEvents(a, c.remoteId, from, to));
                }
                raw.addAll(BusyExtractor.busy(data, from, to, zone));
            } catch (CaldavException ex) {
                flagIfUnauthorized(a, ex);
                LOG.warnf("freeBusy failed for CalDAV account %d (%s); failing closed", a.id, ex.reason());
                throw new CalendarUnavailableException("Could not read CalDAV busy time for account " + a.id, ex);
            }
        }
        return BusyIntervals.merge(raw);
    }

    @Override
    @Transactional
    public CreatedEvent createEvent(
            Long ownerId,
            CalendarRef target,
            String uid,
            String summary,
            String description,
            Instant start,
            Instant end,
            List<String> attendeeEmails,
            boolean createMeetLink,
            String locationText
    ) {
        var addr = writeContext(ownerId, target);
        var url = eventUrl(addr.calendarHref(), uid);
        var ics = IcsBuilder.buildResource(IcsEvent
            .builder()
            .uid(uid)
            .summary(summary)
            .description(withAttendees(description, attendeeEmails))
            .location(locationText)
            .start(start)
            .end(end)
            .sequence(inviteSequence("manageToken = ?1 and ownerId = ?2", uid, ownerId).orElse(0))
            .build()
        );
        call(addr.account(), () -> client.create(addr.account(), url, ics));
        // a Mirror event has no video link: createMeetLink is ignored (design call 11)
        return new CreatedEvent(url.getRawPath(), null, null, new CalendarRef(addr.account().id, addr.calendarHref()));
    }

    @Override
    @Transactional
    public void updateEvent(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            Instant start,
            Instant end,
            List<String> attendeeEmails
    ) {
        modify(ownerId, ref, eventId, event -> {
            event.replace(new DtStart<>(start));
            event.replace(new DtEnd<>(end));
            if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
                event.replace(new Description(withAttendees(baseOf(description(event)), attendeeEmails)));
            }
        });
    }

    @Override
    @Transactional
    public void updateEventDetails(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            String summary,
            String description,
            List<String> attendeeEmails
    ) {
        modify(ownerId, ref, eventId, event -> {
            var attendees =
                    attendeeEmails != null && !attendeeEmails.isEmpty()
                    ? attendeeEmails
                    : attendeesOf(description(event));
            event.replace(new Summary(summary));
            event.replace(new Description(withAttendees(description, attendees)));
        });
    }

    @Override
    @Transactional
    public void deleteEvent(Long ownerId, CalendarRef ref, String eventId) {
        var addr = writeAddress(ownerId, ref);
        call(addr.account(), () -> {
            client.delete(addr.account(), resolve(addr.account(), eventId));
            return null;
        });
    }

    /**
     * GET, edit the first VEVENT, bump SEQUENCE, PUT back under the fetched ETag (unconditionally when the
     * server gave none).
     */
    private void modify(Long ownerId, CalendarRef ref, String eventId, Consumer<VEvent> edit) {
        var addr = writeAddress(ownerId, ref);
        var account = addr.account();
        var url = resolve(account, eventId);
        call(account, () -> {
            var fetched = client.get(account, url);
            if (fetched == null) {
                // recreating a vanished Mirror event is calit-o69e's job; Google behaves the same today
                LOG.warnf("CalDAV event %s on account %d is gone; nothing to update", eventId, account.id);
                return null;
            }
            net.fortuna.ical4j.model.Calendar cal;
            try {
                cal = new CalendarBuilder().build(new StringReader(fetched.ics()));
            } catch (IOException | ParserException ex) {
                throw new CaldavException(CaldavException.Reason.HTTP_ERROR, 0, "Unreadable CalDAV event data", ex);
            }
            VEvent event = cal
                .<VEvent>getComponent(Component.VEVENT)
                .orElseThrow(() -> new CaldavException(CaldavException.Reason.HTTP_ERROR, "CalDAV event has no VEVENT"));
            edit.accept(event);
            // a client may have overwritten the resource with calit's invite: parties would re-enable implicit
            // scheduling (design call 6)
            cal.removeAll(Property.METHOD);
            event.removeAll(Property.ORGANIZER, Property.ATTENDEE);
            int seq = event.<Sequence>getProperty(Property.SEQUENCE).map(Sequence::getSequenceNo).orElse(0) + 1;
            // same UID as the invitee's .ics (design call 12): never fall behind its SEQUENCE
            var invite = inviteSequence("eventId = ?1 and ownerId = ?2", eventId, ownerId);
            event.replace(new Sequence(Math.max(seq, invite.orElse(0))));
            client.update(account, url, cal.toString(), fetched.etag());
            return null;
        });
    }

    /**
     * The highest invite SEQUENCE among this owner's bookings matching {@code where}; empty when none does.
     */
    private static OptionalInt inviteSequence(String where, Object... params) {
        return Booking
            .<Booking>list(where, params)
            .stream()
            .mapToInt(b -> b.icsSequence)
            .max();
    }

    /**
     * Runs a CalDAV call; a rejected password also flags the account for reconnect, committed on its own
     * because the caller's transaction is about to roll back.
     */
    private <T> T call(CaldavAccount account, Supplier<T> op) {
        try {
            return op.get();
        } catch (CaldavException ex) {
            flagIfUnauthorized(account, ex);
            throw ex;
        }
    }

    private static void flagIfUnauthorized(CaldavAccount account, CaldavException ex) {
        if (ex.reason() != CaldavException.Reason.UNAUTHORIZED) {
            return;
        }
        Long id = account.id;
        QuarkusTransaction.requiringNew().run(() -> {
            CaldavAccount a = CaldavAccount.findById(id);
            if (a != null) {
                a.needsReconnect = true;
            }
        });
    }

    /**
     * {@code calendarHref + uid + ".ics"}. Its path is what gets stored, so it must fit the 255-char column.
     */
    static URI eventUrl(String calendarHref, String uid) {
        var base = calendarHref.endsWith("/") ? calendarHref : calendarHref + "/";
        var url = URI.create(base + URLEncoder.encode(uid, StandardCharsets.UTF_8) + ".ics");
        if (url.getRawPath().length() > MAX_EVENT_ID) {
            throw new CaldavException(
                    CaldavException.Reason.HTTP_ERROR,
                    "CalDAV calendar URL too long to store its events (path over " + MAX_EVENT_ID + " chars)"
            );
        }
        return url;
    }

    /**
     * Whether events on this calendar get storable ids: {@link #eventUrl} with a booking's uid (a UUID) fits.
     */
    public static boolean fitsEventIds(String calendarHref) {
        try {
            eventUrl(calendarHref, UUID.randomUUID().toString());
            return true;
        } catch (CaldavException | IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * The stored event id is a server-relative path; the account's home supplies scheme and host.
     */
    private static URI resolve(CaldavAccount account, String eventId) {
        return URI.create(account.homeUrl).resolve(eventId);
    }

    private record WriteAddress(String calendarHref, CaldavAccount account) {}

    /**
     * The resolved target when it still names one of this owner's CalDAV calendars, else the write target.
     */
    private static WriteAddress writeContext(Long ownerId, CalendarRef ref) {
        if (ref != null) {
            var target = SelectedCalendar.findOwned(ownerId, ref.accountId(), ref.remoteCalendarId());
            if (target != null && CalendarAccount.findOwned(ownerId, target.accountId) instanceof CaldavAccount a) {
                return new WriteAddress(target.remoteId, a);
            }
        }
        return writeTarget(ownerId);
    }

    /**
     * The stored address when its account is still this owner's, else the write target (ADR 0001).
     */
    private static WriteAddress writeAddress(Long ownerId, CalendarRef ref) {
        if (ref != null
                && ref.remoteCalendarId() != null
                && CalendarAccount.findOwned(ownerId, ref.accountId()) instanceof CaldavAccount a) {
            return new WriteAddress(ref.remoteCalendarId(), a);
        }
        return writeTarget(ownerId);
    }

    private static WriteAddress writeTarget(Long ownerId) {
        var target = SelectedCalendar.writeTarget(ownerId);
        if (target == null || !(CalendarAccount.findOwned(ownerId, target.accountId) instanceof CaldavAccount a)) {
            throw new IllegalStateException("No CalDAV write-target calendar selected.");
        }
        return new WriteAddress(target.remoteId, a);
    }

    private static String description(VEvent event) {
        return event.<Description>getProperty(Property.DESCRIPTION).map(Description::getValue).orElse("");
    }

    private static String withAttendees(String base, List<String> emails) {
        var text = base == null ? "" : base.strip();
        if (emails == null || emails.isEmpty()) {
            return text;
        }
        var block = ATTENDEES + String.join("\n", emails);
        return text.isEmpty() ? block : text + "\n\n" + block;
    }

    private static String baseOf(String description) {
        var i = description.lastIndexOf(ATTENDEES);
        return i < 0 ? description : description.substring(0, i);
    }

    private static List<String> attendeesOf(String description) {
        var i = description.lastIndexOf(ATTENDEES);
        return i < 0 ? List.of() : List.of(description.substring(i + ATTENDEES.length()).split("\n"));
    }
}
