package site.asm0dey.calit.google;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessageResolver;
import site.asm0dey.calit.web.PublicResource;

/**
 * Booking POST hit a disconnected calendar (book() -> assertSlotAvailable -> availableSlots ->
 * freeBusy threw). Render the same "temporarily unavailable" page with 503 so no booking is created
 * over an event calit cannot see. (GET handlers catch the exception inline; this covers direct POSTs.)
 */
@Provider
public class CalendarUnavailableMapper implements ExceptionMapper<CalendarUnavailableException> {

    @Inject
    AppMessageResolver messages;

    @Inject
    ActiveLocale activeLocale;

    @Override
    public Response toResponse(CalendarUnavailableException ex) {
        var m = messages.forLocale(activeLocale.current());
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.TEXT_HTML)
                .entity(PublicResource.Templates.unavailable(m.pub_unavailable_title())
                        .render())
                .build();
    }
}
