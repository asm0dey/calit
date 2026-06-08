package com.calit.booking;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;

@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BookingResource {

    @Inject
    BookingService bookingService;

    public record BookRequest(String slug, String startUtc, String inviteeName, String inviteeEmail,
                              Map<String, String> answers, String turnstileToken, String honeypot) {}

    public record RescheduleRequest(String newStartUtc) {}

    @POST
    @Path("/bookings")
    public Response create(BookRequest req) {
        // All abuse guards (Turnstile + honeypot + per-email/day cap) are enforced inside book().
        // The Plan 5 web layer forwards the cf-turnstile-response (turnstileToken) and website (honeypot) values.
        Booking b = bookingService.book(req.slug(), Instant.parse(req.startUtc()),
                req.inviteeName(), req.inviteeEmail(), req.answers(), req.turnstileToken(), req.honeypot());
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

    // Owner approval queue (feature 14): keyed by numeric id (owner action, not invitee self-service).
    @POST
    @Path("/bookings/{id}/approve")
    public Response approve(@PathParam("id") Long id) {
        bookingService.approve(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/bookings/{id}/decline")
    public Response decline(@PathParam("id") Long id) {
        bookingService.decline(id);
        return Response.noContent().build();
    }
}
