package com.calit.api;

import com.calit.availability.SlotService;
import com.calit.availability.TimeSlot;
import com.calit.booking.BookingService;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

@Path("/api/meeting-types")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MeetingTypeResource {

    @Inject
    SlotService slotService;

    @Inject
    BookingService bookingService;

    public record CreateMeetingTypeRequest(
            String name, String slug, int durationMinutes,
            Integer bufferBeforeMinutes, Integer bufferAfterMinutes, String description,
            Boolean secret) {}

    /** Public listing — active, non-secret types only (the invitee landing page). */
    @GET
    public List<MeetingType> list() {
        return MeetingType.listPublic();
    }

    /** Admin listing — every type, including secret/inactive. (Auth-gated in Plan 5.) */
    @GET
    @Path("/all")
    public List<MeetingType> listAllAdmin() {
        return MeetingType.listAll();
    }

    @POST
    @Transactional
    public Response create(CreateMeetingTypeRequest req) {
        MeetingType t = new MeetingType();
        t.name = req.name();
        t.slug = req.slug();
        t.durationMinutes = req.durationMinutes();
        t.bufferBeforeMinutes = req.bufferBeforeMinutes() == null ? 0 : req.bufferBeforeMinutes();
        t.bufferAfterMinutes = req.bufferAfterMinutes() == null ? 0 : req.bufferAfterMinutes();
        t.description = req.description();
        t.secret = req.secret() != null && req.secret();
        t.persist();
        return Response.status(Response.Status.CREATED).entity(t).build();
    }

    @GET
    @Path("/{slug}/slots")
    @Transactional
    public List<TimeSlot> slots(@PathParam("slug") String slug,
                                @QueryParam("from") String from,
                                @QueryParam("to") String to) {
        MeetingType t = MeetingType.findBySlug(slug);
        if (t == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        return slotService.generateRawSlots(t, LocalDate.parse(from), LocalDate.parse(to));
    }

    /** Available bookable slots for a meeting type in [from, to] (feature 11 + busy-interval checks). */
    @GET
    @Path("/{slug}/available")
    public List<TimeSlot> available(@PathParam("slug") String slug,
                                    @QueryParam("from") String from,
                                    @QueryParam("to") String to) {
        MeetingType t = MeetingType.findBySlug(slug);
        if (t == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        return bookingService.availableSlots(t, LocalDate.parse(from), LocalDate.parse(to));
    }

    /** Resolved invitee form (excludes the always-present full name + email built-ins). */
    @GET
    @Path("/{slug}/form")
    public List<BookingField> form(@PathParam("slug") String slug) {
        MeetingType t = MeetingType.findBySlug(slug);
        if (t == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        return BookingField.formFor(t.id);
    }
}
