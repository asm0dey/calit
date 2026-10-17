package site.asm0dey.calit.web;

import module java.base;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The calendars page used to live at /me/google; keep bookmarks and old emails working.
 */
@Path("/me/google")
@RolesAllowed("user")
public class GooglePageRedirectResource {
    @GET
    public Response moved() {
        return Response.seeOther(URI.create("/me/calendars")).build();
    }
}
