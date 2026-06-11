package com.calit.google;

import com.calit.domain.OwnerSettings;
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
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleCalendarPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory,
                              com.calit.user.CurrentOwner currentOwner) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
        this.currentOwner = currentOwner;
    }

    private Calendar client() {
        return clientFactory.build(tokens.validAccessToken(currentOwner.id(), Instant.now()));
    }

    @Override
    @Transactional
    public boolean isConnected() {
        // Connected iff this owner's OAuth credential (with refresh token) exists. No Google call.
        return GoogleCredential.forOwner(currentOwner.id()) != null;
    }

    @Override
    public List<BusyInterval> freeBusy(Instant from, Instant to) {
        List<GoogleCalendar> readers = GoogleCalendar.readForBusy(currentOwner.id());
        if (readers.isEmpty()) {
            return List.of();
        }
        FreeBusyRequest request = new FreeBusyRequest()
                .setTimeMin(new DateTime(from.toEpochMilli()))
                .setTimeMax(new DateTime(to.toEpochMilli()))
                .setItems(readers.stream()
                        .map(c -> new FreeBusyRequestItem().setId(c.googleCalendarId))
                        .toList());
        try {
            FreeBusyResponse response = client().freebusy().query(request).execute();
            List<BusyInterval> raw = new ArrayList<>();
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
            return BusyIntervals.merge(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("freeBusy query failed", e);
        }
    }

    @Override
    public CreatedEvent createEvent(String summary, String description,
                                    Instant start, Instant end,
                                    List<String> attendeeEmails,
                                    boolean createMeetLink, String locationText) {
        GoogleCalendar target = requireWriteTarget();
        Event event = new Event()
                .setSummary(summary)
                .setDescription(description)
                .setStart(eventTime(start))
                .setEnd(eventTime(end));

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
            // setConferenceDataVersion(1) is required so Google honors the createRequest; harmless when absent.
            // setSendUpdates("all") makes Google email the attendees the invite (chosen invitee path).
            Event created = client().events()
                    .insert(target.googleCalendarId, event)
                    .setConferenceDataVersion(createMeetLink ? 1 : 0)
                    .setSendUpdates("all")
                    .execute();
            String meetLink = createMeetLink ? extractMeetLink(created) : null;
            return new CreatedEvent(created.getId(), meetLink, created.getHtmlLink());
        } catch (IOException e) {
            throw new UncheckedIOException("createEvent failed", e);
        }
    }

    @Override
    public void updateEvent(String eventId, Instant start, Instant end) {
        GoogleCalendar target = requireWriteTarget();
        Event patch = new Event()
                .setStart(eventTime(start))
                .setEnd(eventTime(end));
        try {
            // sendUpdates=all so Google emails the attendees the rescheduled time.
            client().events().patch(target.googleCalendarId, eventId, patch)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEvent failed", e);
        }
    }

    @Override
    public void deleteEvent(String eventId) {
        GoogleCalendar target = requireWriteTarget();
        try {
            // sendUpdates=all so Google emails the attendees the cancellation.
            client().events().delete(target.googleCalendarId, eventId)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("deleteEvent failed", e);
        }
    }

    private GoogleCalendar requireWriteTarget() {
        GoogleCalendar target = GoogleCalendar.writeTarget(currentOwner.id());
        if (target == null) {
            throw new IllegalStateException("No write-target Google calendar selected. POST /api/google/calendars.");
        }
        return target;
    }

    /**
     * Build the Google EventDateTime for a start/end instant. The absolute instant
     * (epoch-millis DateTime) is already unambiguous, but we additionally stamp the event's
     * start.timeZone / end.timeZone with the OWNER's IANA zone (read from the current owner's
     * OwnerSettings) so the Google event "owns" the owner's timezone — cleaner for attendees and for
     * DST handling on Google's side. Each attendee's Google client still displays the event in
     * their own local zone automatically; the invitee's timezone is never stored.
     */
    private EventDateTime eventTime(Instant instant) {
        String ownerZoneId = OwnerSettings.forOwner(currentOwner.id()).timezone;
        return new EventDateTime()
                .setDateTime(new DateTime(instant.toEpochMilli()))
                .setTimeZone(ownerZoneId);
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
