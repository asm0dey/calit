package site.asm0dey.calit.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import site.asm0dey.calit.i18n.AppLocales;

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
        if (ret == null || ret.isBlank()) return "/";
        try {
            URI uri = URI.create(ret);
            String path = uri.getPath();
            if (uri.isAbsolute()
                    || uri.getScheme() != null
                    || uri.getAuthority() != null
                    || uri.getHost() != null
                    || uri.getUserInfo() != null
                    || path == null
                    || !path.startsWith("/")
                    || path.startsWith("//")) {
                return "/";
            }
            StringBuilder local = new StringBuilder(path);
            if (uri.getRawQuery() != null) local.append('?').append(uri.getRawQuery());
            if (uri.getRawFragment() != null) local.append('#').append(uri.getRawFragment());
            return local.toString();
        } catch (IllegalArgumentException _) {
            return "/";
        }
    }
}
