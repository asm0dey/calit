package site.asm0dey.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BookingConflictMapper implements ExceptionMapper<BookingConflictException> {
    @Override
    public Response toResponse(BookingConflictException ex) {
        return Response.status(Response.Status.CONFLICT).entity(ex.getMessage()).build();
    }
}
