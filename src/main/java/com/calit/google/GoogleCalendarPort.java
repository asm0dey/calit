package com.calit.google;

import com.calit.domain.OwnerSettings;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.ConferenceSolutionKey;
import com.google.api.services.calendar.model.CreateConferenceRequest;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.FreeBusyCalendar;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import com.google.api.services.calendar.model.TimePeriod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real CalendarPort backed by the Google Calendar API. @ApplicationScoped so downstream
 * tests can replace it with a Mockito {@code @InjectMock CalendarPort}.
 */
@ApplicationScoped
public class GoogleCalendarPort implements CalendarPort {

    private final GoogleTokenService tokens;
    private final GoogleCalendarClientFactory clientFactory;

    @Inject
    public GoogleCalendarPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
    }

    private Calendar client(GoogleCredential cred) {
        return clientFactory.build(tokens.validAccessToken(cred, Instant.now()));
    }

    @Override
    @Transactional
    public boolean isConnected(Long ownerId) {
        // Connected iff this owner has at least one OAuth credential. No Google call.
        return GoogleCredential.countForOwner(ownerId) > 0;
    }

    @Override
    public List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to) {
        Map<Long, List<GoogleCalendar>> byCredential = GoogleCalendar.readForBusyByCredential(ownerId);
        if (byCredential.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> raw = new ArrayList<>();
        for (Map.Entry<Long, List<GoogleCalendar>> e : byCredential.entrySet()) {
            GoogleCredential cred = GoogleCredential.findById(e.getKey());
            if (cred == null || cred.needsReconnect) {
                continue; // fail-soft: skip an account that is gone or known-broken
            }
            FreeBusyRequest request = new FreeBusyRequest()
                    .setTimeMin(new DateTime(from.toEpochMilli()))
                    .setTimeMax(new DateTime(to.toEpochMilli()))
                    .setItems(e.getValue().stream()
                            .map(c -> new FreeBusyRequestItem().setId(c.googleCalendarId))
                            .toList());
            try {
                FreeBusyResponse response = client(cred).freebusy().query(request).execute();
                Map<String, FreeBusyCalendar> calendars = response.getCalendars();
                if (calendars != null) {
                    for (FreeBusyCalendar cal : calendars.values()) {
                        List<TimePeriod> busy = cal.getBusy();
                        if (busy != null) {
                            for (TimePeriod p : busy) {
                                raw.add(new BusyInterval(
                                        Instant.ofEpochMilli(p.getStart().getValue()),
                                        Instant.ofEpochMilli(p.getEnd().getValue())));
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Fail-soft: one broken account must not take down availability. KEEP this broad
                // catch (do NOT narrow to IOException): a revoked/expired refresh token surfaces as
                // an IllegalStateException (a RuntimeException) from validAccessToken, which is the
                // most common fail-soft case; narrowing would abort the whole freeBusy. Log so a
                // genuine defect is still discoverable rather than silently masquerading as "reconnect".
                org.jboss.logging.Logger.getLogger(GoogleCalendarPort.class)
                        .warnf(ex, "freeBusy failed for credential %d; flagging needsReconnect", cred.id);
                Long credId = cred.id;
                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                    GoogleCredential fresh = GoogleCredential.findById(credId);
                    if (fresh != null) { fresh.needsReconnect = true; fresh.persist(); }
                });
            }
        }
        return BusyIntervals.merge(raw);
    }

    @Override
    @Transactional
    public CreatedEvent createEvent(Long ownerId, String summary, String description,
                                    Instant start, Instant end,
                                    List<String> attendeeEmails,
                                    boolean createMeetLink, String locationText) {
        var ctx = writeContext(ownerId);
        GoogleCalendar target = ctx.target();
        GoogleCredential cred = ctx.cred();
        Event event = new Event()
                .setSummary(summary)
                .setDescription(description)
                .setStart(eventTime(ownerId, start))
                .setEnd(eventTime(ownerId, end));

        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            event.setAttendees(attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }

        if (createMeetLink) {
            // GOOGLE_MEET type: request a fresh Google Meet conference for this event only.
            event.setConferenceData(new ConferenceData().setCreateRequest(
                    new CreateConferenceRequest()
                            .setRequestId(UUID.randomUUID().toString())
                            .setConferenceSolutionKey(
                                    new ConferenceSolutionKey().setType("hangoutsMeet"))));
        } else if (locationText != null) {
            // PHONE/IN_PERSON/CUSTOM type: no conference; carry the per-type location text instead.
            event.setLocation(locationText);
        }

        try {
            Event created = insert(cred, target, event, createMeetLink);
            String meetLink = createMeetLink ? extractMeetLink(created) : null;
            return new CreatedEvent(created.getId(), meetLink, created.getHtmlLink());
        } catch (GoogleJsonResponseException e) {
            if (createMeetLink && isInvalidConferenceType(e)) {
                // This calendar can't mint Meet links (Workspace with Meet API disabled, or a
                // calendar this account doesn't own). Don't fail the booking: drop the conference,
                // retry the insert, and remember the capability so the UI stops offering GOOGLE_MEET
                // for this write target (config-time gate). The booking gets a plain event, no link.
                org.jboss.logging.Logger.getLogger(GoogleCalendarPort.class).warnf(
                        "Write-target calendar %s rejected a Meet conference; creating event without one",
                        target.googleCalendarId);
                target.supportsMeet = false; // managed entity; flushes with the booking transaction
                event.setConferenceData(null);
                try {
                    Event created = insert(cred, target, event, false);
                    return new CreatedEvent(created.getId(), null, created.getHtmlLink());
                } catch (IOException ex) {
                    throw new UncheckedIOException("createEvent failed", ex);
                }
            }
            if (e.getStatusCode() == 404) {
                // The write-target calendar was deleted on Google since the owner selected it.
                // Clear the selection + flag the account so the UI prompts a re-select/reconnect.
                // This @Transactional method rethrows below, which would roll back plain persists,
                // so commit the two flags in a separate transaction (same pattern as
                // GoogleTokenService.validAccessToken's fail-soft flag commit).
                Long targetId = target.id, credId = cred.id;
                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                    GoogleCalendar t = GoogleCalendar.findById(targetId);
                    if (t != null) { t.writeTarget = false; t.persist(); }
                    GoogleCredential c2 = GoogleCredential.findById(credId);
                    if (c2 != null) { c2.needsReconnect = true; c2.persist(); }
                });
                throw new IllegalStateException(
                        "Write-target calendar no longer exists on Google; re-select a write target.", e);
            }
            throw new UncheckedIOException("createEvent failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException("createEvent failed", e);
        }
    }

    @Override
    @Transactional
    public void updateEvent(Long ownerId, String eventId, Instant start, Instant end) {
        var ctx = writeContext(ownerId);
        GoogleCalendar target = ctx.target();
        GoogleCredential cred = ctx.cred();
        Event patch = new Event()
                .setStart(eventTime(ownerId, start))
                .setEnd(eventTime(ownerId, end));
        try {
            // sendUpdates=all so Google emails the attendees the rescheduled time.
            client(cred).events().patch(target.googleCalendarId, eventId, patch)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEvent failed", e);
        }
    }

    @Override
    @Transactional
    public void deleteEvent(Long ownerId, String eventId) {
        var ctx = writeContext(ownerId);
        GoogleCalendar target = ctx.target();
        GoogleCredential cred = ctx.cred();
        try {
            // sendUpdates=all so Google emails the attendees the cancellation.
            client(cred).events().delete(target.googleCalendarId, eventId)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("deleteEvent failed", e);
        }
    }

    private GoogleCalendar requireWriteTarget(Long ownerId) {
        GoogleCalendar target = GoogleCalendar.writeTarget(ownerId);
        if (target == null) {
            throw new IllegalStateException("No write-target Google calendar selected. POST /api/google/calendars.");
        }
        return target;
    }

    /** The write-target calendar plus its (non-null) owning credential, or fail clearly. */
    private record WriteContext(GoogleCalendar target, GoogleCredential cred) {}

    private WriteContext writeContext(Long ownerId) {
        GoogleCalendar target = requireWriteTarget(ownerId);
        GoogleCredential cred = GoogleCredential.findById(target.googleCredentialId);
        if (cred == null) {
            throw new IllegalStateException("Write-target calendar has no credential; reconnect Google.");
        }
        return new WriteContext(target, cred);
    }

    /**
     * Build the Google EventDateTime for a start/end instant. The absolute instant
     * (epoch-millis DateTime) is already unambiguous, but we additionally stamp the event's
     * start.timeZone / end.timeZone with the OWNER's IANA zone (read from the given owner's
     * OwnerSettings) so the Google event "owns" the owner's timezone — cleaner for attendees and for
     * DST handling on Google's side. Each attendee's Google client still displays the event in
     * their own local zone automatically; the invitee's timezone is never stored.
     */
    private EventDateTime eventTime(Long ownerId, Instant instant) {
        String ownerZoneId = OwnerSettings.forOwner(ownerId).timezone;
        return new EventDateTime()
                .setDateTime(new DateTime(instant.toEpochMilli()))
                .setTimeZone(ownerZoneId);
    }

    /**
     * Insert the event. setConferenceDataVersion(1) is required so Google honors a createRequest
     * (harmless at 0 when there's no conference); setSendUpdates("all") emails the attendees the invite.
     */
    private Event insert(GoogleCredential cred, GoogleCalendar target, Event event, boolean withMeet)
            throws IOException {
        return client(cred).events()
                .insert(target.googleCalendarId, event)
                .setConferenceDataVersion(withMeet ? 1 : 0)
                .setSendUpdates("all")
                .execute();
    }

    /** Google's 400 when the calendar doesn't allow the requested conference solution (e.g. Meet off). */
    private static boolean isInvalidConferenceType(GoogleJsonResponseException e) {
        if (e.getStatusCode() != 400) {
            return false;
        }
        String msg = e.getDetails() != null ? e.getDetails().getMessage() : e.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("conference type");
    }

    /** Prefer the top-level hangoutLink; fall back to the first video conference entry point. */
    private static String extractMeetLink(Event event) {
        if (event.getHangoutLink() != null) {
            return event.getHangoutLink();
        }
        ConferenceData cd = event.getConferenceData();
        if (cd != null && cd.getEntryPoints() != null) {
            return cd.getEntryPoints().stream()
                    .filter(ep -> "video".equals(ep.getEntryPointType()))
                    .map(ep -> ep.getUri())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
