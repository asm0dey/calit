package site.asm0dey.calit.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;

@Path("/logout")
public class LogoutResource {

    // Form auth keeps no server session — logging out means expiring the encrypted credential cookie.
    @GET
    public Response logout() {
        NewCookie cleared = new NewCookie.Builder("quarkus-credential")
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        return Response.seeOther(URI.create("/login")).cookie(cleared).build();
    }
}
