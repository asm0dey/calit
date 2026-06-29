package site.asm0dey.calit.google;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.LoginTicketService;

/**
 * The "Sign in with Google" flow. /login (GET) bounces to Google; /login/callback (GET) verifies
 * the id_token, resolves/provisions the AppUser, mints a single-use login ticket, and returns an
 * auto-submitting form that POSTs the ticket to /j_security_check (which mints the session cookie).
 * Both paths are permitted (unauthenticated) by the google-login permission.
 */
@Path("/api/google/login")
public class GoogleLoginResource {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(GoogleLoginResource.class);

    @CheckedTemplate
    public static class Templates {
        private Templates() {}

        public static native TemplateInstance bridge(String username, String token);
    }

    private static final String NOTICE_GENERIC = "google";

    private final GoogleLoginService loginService;
    private final GoogleSignInService signInService;
    private final LoginTicketService loginTickets;
    private final java.time.Clock clock;

    @Inject
    public GoogleLoginResource(
            GoogleLoginService loginService,
            GoogleSignInService signInService,
            LoginTicketService loginTickets,
            java.time.Clock clock) {
        this.loginService = loginService;
        this.signInService = signInService;
        this.loginTickets = loginTickets;
        this.clock = clock;
    }

    @GET
    public Response start() {
        return Response.status(Response.Status.FOUND)
                .location(URI.create(loginService.buildConsentUrl(clock.instant())))
                .build();
    }

    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_HTML)
    public Response callback(
            @QueryParam("code") String code, @QueryParam("state") String state, @QueryParam("error") String error) {
        var now = clock.instant();
        if (error != null) {
            return redirectToLogin(NOTICE_GENERIC);
        }
        if (!loginService.validateLoginState(state, now)) {
            return redirectToLogin(NOTICE_GENERIC);
        }
        if (code == null || code.isBlank()) {
            return redirectToLogin(NOTICE_GENERIC);
        }

        GoogleIdentity identity;
        try {
            identity = loginService.exchangeForIdentity(code, now);
        } catch (RuntimeException e) {
            // Google/network error or malformed token response — recoverable; send the user back to login.
            LOG.warn("Google sign-in token exchange failed", e);
            return redirectToLogin(NOTICE_GENERIC);
        }
        AppUser user;
        try {
            user = signInService.resolveOrProvision(identity);
        } catch (GoogleSignInException e) {
            return redirectToLogin(
                    switch (e.reason) {
                        case SIGNUP_DISABLED -> "google_signup_disabled";
                        case AMBIGUOUS_EMAIL -> "google_ambiguous";
                    });
        }

        String token = loginTickets.issue(user.id, now);
        // The page carries a single-use login token in its body — never cache it.
        return Response.ok(Templates.bridge(user.username, token))
                .header("Cache-Control", "no-store")
                .build();
    }

    private static Response redirectToLogin(String notice) {
        return Response.status(Response.Status.FOUND)
                .location(URI.create(
                        "/login?notice=" + java.net.URLEncoder.encode(notice, java.nio.charset.StandardCharsets.UTF_8)))
                .build();
    }
}
