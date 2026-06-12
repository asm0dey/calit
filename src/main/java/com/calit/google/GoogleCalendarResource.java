package com.calit.google;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/google/calendars")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GoogleCalendarResource {

    private final CalendarListPort calendarListPort;
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleCalendarResource(CalendarListPort calendarListPort,
                                  com.calit.user.CurrentOwner currentOwner) {
        this.calendarListPort = calendarListPort;
        this.currentOwner = currentOwner;
    }

    public record CalendarSelection(String googleCalendarId, String summary,
                                    boolean readForBusy, boolean writeTarget) {}

    public record SaveSelectionRequest(List<CalendarSelection> calendars) {}

    /** List the owner's Google calendars so they can pick read/write ones. */
    @GET
    public List<CalendarListPort.RemoteCalendar> list() {
        return calendarListPort.listCalendars();
    }

    /** Persist the read/write selection. Replaces any prior selection; enforces one write target. */
    @POST
    @Transactional
    public Response save(SaveSelectionRequest req) {
        long writeTargets = req.calendars().stream().filter(CalendarSelection::writeTarget).count();
        if (writeTargets > 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("At most one write-target calendar is allowed").build();
        }
        // Resolve the owner's primary credential — required now that calendars belong to an account.
        // TODO(Task 2+): accept a credentialId in the request body for multi-account selection.
        GoogleCredential cred = GoogleCredential.forOwner(currentOwner.id());
        if (cred == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("No Google account connected. Connect Google first.").build();
        }
        // Replace prior selection wholesale so removed calendars stop being read.
        GoogleCalendar.deleteForOwner(currentOwner.id());
        for (CalendarSelection sel : req.calendars()) {
            GoogleCalendar c = new GoogleCalendar();
            c.ownerId = currentOwner.id();
            c.googleCalendarId = sel.googleCalendarId();
            c.summary = sel.summary();
            c.readForBusy = sel.readForBusy();
            c.writeTarget = sel.writeTarget();
            c.googleCredentialId = cred.id;
            c.persist();
        }
        return Response.ok().build();
    }

    /** Convenience read used by the admin UI (Plan 5) and the test. */
    @GET
    @Path("/write-target")
    public GoogleCalendar writeTarget() {
        GoogleCalendar target = GoogleCalendar.writeTarget(currentOwner.id());
        if (target == null) {
            throw new NotFoundException("No write-target calendar selected");
        }
        return target;
    }
}
