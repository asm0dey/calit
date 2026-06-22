package site.asm0dey.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Maps invalid booking-form input (e.g. a missing required custom field) to 422 Unprocessable Entity. */
@Provider
public class BookingValidationMapper implements ExceptionMapper<BookingValidationException> {
    @Override
    public Response toResponse(BookingValidationException ex) {
        return Response.status(422).entity(ex.getMessage()).build();
    }
}
