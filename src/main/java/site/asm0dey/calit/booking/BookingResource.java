package site.asm0dey.calit.booking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.Usernames;

/**
 * Public JSON booking API. SECURITY (SEC-AUTHZ-02): every mutation MUST route through
 * {@link BookingService} so this API and the web form ({@code PublicResource}) share one set of
 * guards — invitee validation, abuse/rate limits, and conflict checks. Do not add booking logic
 * here; add it to BookingService or it will silently bypass this entry point.
 */
@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BookingResource {

    final BookingService bookingService;

    final ActiveLocale activeLocale;

    @Inject
    public BookingResource(BookingService bookingService, ActiveLocale activeLocale) {
        this.bookingService = bookingService;
        this.activeLocale = activeLocale;
    }

    public record BookRequest(
            String user,
            String slug,
            String startUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String turnstileToken,
            String altchaSolution,
            String honeypot) {}

    public record RescheduleRequest(String newStartUtc) {}

    @POST
    @Path("/bookings")
    public Response create(BookRequest req) {
        // Owner-scoped (carry-forward M1): the booking targets a specific owner identified by {user};
        // the meeting type is resolved within that owner so colliding slugs across owners never alias.
        if (req.user() == null || req.user().isBlank()) {
            throw new NotFoundException("Missing user");
        }
        AppUser owner = AppUser.findByUsername(Usernames.normalize(req.user()));
        if (owner == null || !owner.enabled) {
            // Same guard as PublicResource.resolveOwner: this endpoint resolves the username
            // itself, so a disabled owner must be refused here too (calit-h8mb). Single-host
            // types are not covered by MeetingHosts.bookable, which returns true for them.
            throw new NotFoundException("No user " + req.user());
        }
        MeetingType type = MeetingType.findBySlug(owner.id, req.slug());
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + req.slug());
        }
        // All abuse guards (Turnstile + honeypot + per-email/day cap) are enforced inside book().
        // Locale is resolved server-side from the request (ignore any client-supplied locale).
        String locale = activeLocale.current().getLanguage();
        Booking b = bookingService.book(
                owner.id,
                req.slug(),
                Instant.parse(req.startUtc()),
                req.inviteeName(),
                req.inviteeEmail(),
                req.answers(),
                req.turnstileToken(),
                req.altchaSolution(),
                req.honeypot(),
                locale,
                List.of());
        return Response.status(Response.Status.CREATED).entity(b).build();
    }

    // Invitee self-service (feature 5): reschedule/cancel are keyed by the manage-token.
    @POST
    @Path("/bookings/{manageToken}/reschedule")
    public Booking reschedule(@PathParam("manageToken") String manageToken, RescheduleRequest req) {
        return bookingService.reschedule(manageToken, Instant.parse(req.newStartUtc()));
    }

    @DELETE
    @Path("/bookings/{manageToken}")
    public Response cancel(@PathParam("manageToken") String manageToken) {
        bookingService.cancel(manageToken);
        return Response.noContent().build();
    }

    // NOTE: owner approve/decline are NOT exposed here. They are owner actions keyed by numeric id
    // and live only behind the authenticated, owner-scoped /me/bookings/{id}/approve|decline handlers
    // (AdminResource). A public /api/bookings/{id}/approve would be owner-blind (cross-owner) since
    // bookingService.approve/decline look up by id alone.
}
