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
    private final CalendarSelectionService selectionService;

    @Inject
    public GoogleCalendarResource(CalendarListPort calendarListPort,
                                  com.calit.user.CurrentOwner currentOwner,
                                  CalendarSelectionService selectionService) {
        this.calendarListPort = calendarListPort;
        this.currentOwner = currentOwner;
        this.selectionService = selectionService;
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
        GoogleCredential cred = GoogleCredential.forOwner(currentOwner.id());
        if (cred == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Connect a Google account first").build();
        }
        try {
            selectionService.save(currentOwner.id(), req.calendars().stream()
                    .map(s -> new CalendarSelectionService.Selection(
                            cred.id, s.googleCalendarId(), s.summary(), s.readForBusy(), s.writeTarget()))
                    .toList());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
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
