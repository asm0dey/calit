package site.asm0dey.calit.google;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import site.asm0dey.calit.user.CurrentOwner;

@Path("/api/google/calendars")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GoogleCalendarResource {

    private final CalendarListPort calendarListPort;
    private final CurrentOwner currentOwner;
    private final CalendarSelectionService selectionService;

    @Inject
    public GoogleCalendarResource(
            CalendarListPort calendarListPort, CurrentOwner currentOwner, CalendarSelectionService selectionService) {
        this.calendarListPort = calendarListPort;
        this.currentOwner = currentOwner;
        this.selectionService = selectionService;
    }

    public record CalendarSelection(
            String googleCalendarId, String summary, boolean readForBusy, boolean writeTarget) {}

    public record SaveSelectionRequest(List<CalendarSelection> calendars) {}

    /** List the owner's Google calendars so they can pick read/write ones. */
    @GET
    public List<CalendarListPort.RemoteCalendar> list() {
        return calendarListPort.listCalendars();
    }

    /**
     * Persist the read/write selection. Replaces any prior selection; enforces one write target.
     * NOT @Transactional: it re-fetches the live calendar list (network) to learn each calendar's
     * Meet capability and must not hold a pooled DB connection across that I/O; the actual write is
     * one atomic transaction inside {@code selectionService.save} (which also rolls back on reject).
     */
    @POST
    public Response save(SaveSelectionRequest req) {
        // Legacy single-account JSON endpoint: all rows go to the owner's (single) credential.
        // The multi-account page (/me/google) passes explicit per-account credential ids instead.
        GoogleCredential cred = GoogleCredential.forOwner(currentOwner.id());
        if (cred == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Connect a Google account first")
                    .build();
        }
        java.util.Map<String, Boolean> meetByCalendar = calendarListPort.listCalendars(cred).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CalendarListPort.RemoteCalendar::googleCalendarId,
                        CalendarListPort.RemoteCalendar::meetSupported,
                        (a, _) -> a));
        try {
            selectionService.save(
                    currentOwner.id(),
                    req.calendars().stream()
                            .map(s -> new CalendarSelectionService.Selection(
                                    cred.id,
                                    s.googleCalendarId(),
                                    s.summary(),
                                    s.readForBusy(),
                                    s.writeTarget(),
                                    meetByCalendar.getOrDefault(s.googleCalendarId(), false)))
                            .toList());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(e.getMessage())
                    .build();
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
