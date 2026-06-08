package com.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Feature 16: maps a failed abuse guard (e.g. Turnstile) to 400 Bad Request. */
@Provider
public class AbuseMapper implements ExceptionMapper<AbuseException> {
    @Override
    public Response toResponse(AbuseException ex) {
        return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
    }
}
