package site.asm0dey.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.format.DateTimeParseException;

/**
 * Maps a malformed ISO-8601 date/time in a public request (e.g. a bad {@code startUtc} body field
 * or {@code from}/{@code to} query param) to 400 Bad Request instead of a leaked 500. The booking
 * POST is public, so client-side format errors must surface as a clean client error.
 */
@Provider
public class MalformedDateTimeMapper implements ExceptionMapper<DateTimeParseException> {
    @Override
    public Response toResponse(DateTimeParseException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("Malformed date/time value: " + ex.getParsedString())
                .build();
    }
}
