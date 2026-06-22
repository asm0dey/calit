package site.asm0dey.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Feature 16: maps an exceeded per-email/day booking cap to 429 Too Many Requests. */
@Provider
public class RateLimitMapper implements ExceptionMapper<RateLimitException> {
    @Override
    public Response toResponse(RateLimitException ex) {
        return Response.status(429).entity(ex.getMessage()).build();
    }
}
