package com.calit.web;

import com.calit.i18n.AppLocales;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/** Invitee language switch. GET (no state mutation beyond a preference cookie) -> no CSRF token. */
@Path("/lang")
public class LangResource {

    @GET
    @Path("/{code}")
    public Response set(@PathParam("code") String code, @QueryParam("return") String ret) {
        String target = safeLocal(ret);
        Response.ResponseBuilder rb = Response.seeOther(URI.create(target)); // 303
        if (AppLocales.isSupported(code)) {
            rb.cookie(new NewCookie.Builder("calit_lang")
                    .value(code).path("/").maxAge(60 * 60 * 24 * 365)
                    .sameSite(NewCookie.SameSite.LAX).build());
        }
        return rb.build();
    }

    /** Only same-site absolute paths; anything else -> "/". Blocks open redirects. */
    private static String safeLocal(String ret) {
        if (ret == null || !ret.startsWith("/") || ret.startsWith("//")) return "/";
        return ret;
    }
}
