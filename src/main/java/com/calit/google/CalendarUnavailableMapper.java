package com.calit.google;

import com.calit.web.PublicResource;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Booking POST hit a disconnected calendar (book() -> assertSlotAvailable -> availableSlots ->
 * freeBusy threw). Render the same "temporarily unavailable" page with 503 so no booking is created
 * over an event calit cannot see. (GET handlers catch the exception inline; this covers direct POSTs.)
 */
@Provider
public class CalendarUnavailableMapper implements ExceptionMapper<CalendarUnavailableException> {
    @Override
    public Response toResponse(CalendarUnavailableException ex) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.TEXT_HTML)
                .entity(PublicResource.Templates.unavailable().render())
                .build();
    }
}
