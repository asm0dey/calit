package com.calit.api;

import com.calit.domain.BookingField;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BookingFieldResource {

    public record FieldRequest(Long meetingTypeId, String fieldKey, String label,
                               BookingField.FieldType type, Boolean required, Integer position) {}

    @POST
    @Path("/booking-fields")
    @Transactional
    public Response create(FieldRequest req) {
        BookingField f = new BookingField();
        f.meetingTypeId = req.meetingTypeId();
        f.fieldKey = req.fieldKey();
        f.label = req.label();
        f.type = req.type();
        f.required = req.required() != null && req.required();
        f.position = req.position() == null ? 0 : req.position();
        f.persist();
        return Response.status(Response.Status.CREATED).entity(f).build();
    }

    @DELETE
    @Path("/booking-fields/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = BookingField.deleteById(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }
}
