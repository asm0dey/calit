package site.asm0dey.calit.oidc;

import io.quarkus.oidc.IdToken;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import site.asm0dey.calit.google.GoogleLoginResource;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.LoginTicketService;

/**
 * The "Sign in with SSO" (OIDC / Authelia) landing. quarkus-oidc protects this path with the
 * authorization-code flow: an unauthenticated hit is redirected to the provider, which redirects
 * back here with a session. This resource then reads the verified id_token, resolves/provisions the
 * {@link AppUser}, mints a single-use login ticket, and returns an auto-submitting form that POSTs
 * the ticket to /j_security_check (which mints the form-auth session cookie) — the same bridge the
 * Google flow uses. The OIDC session cookie is expired so every SSO click re-checks the provider.
 */
@Path("/api/oidc/login")
public class OidcLoginResource {

    // Default quarkus-oidc session cookie. ponytail: single cookie assumed; add q_session_1.. handling
    // only if OIDC token chunking is ever enabled (Authelia id_tokens are small).
    private static final String OIDC_SESSION_COOKIE = "q_session";

    // Field injection is required here: the @IdToken qualifier is not @Target-applicable to
    // constructor parameters, so quarkus-oidc's verified id_token can only be injected as a field.
    @SuppressWarnings("java:S6813")
    @Inject
    @IdToken
    JsonWebToken idToken;

    private final OidcSignInService signInService;
    private final LoginTicketService loginTickets;
    private final Clock clock;

    @Inject
    public OidcLoginResource(OidcSignInService signInService, LoginTicketService loginTickets, Clock clock) {
        this.signInService = signInService;
        this.loginTickets = loginTickets;
        this.clock = clock;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response login() {
        var now = clock.instant();
        var identity = new OidcIdentity(
                idToken.getSubject(),
                idToken.getClaim("email"),
                Boolean.TRUE.equals(idToken.getClaim("email_verified")),
                groups());

        AppUser user;
        try {
            user = signInService.resolveOrProvision(identity);
        } catch (OidcSignInException e) {
            return dropOidcSession(redirectToLogin(
                            switch (e.reason) {
                                case SIGNUP_DISABLED -> "sso_signup_disabled";
                                case AMBIGUOUS_EMAIL -> "sso_ambiguous";
                            }))
                    .build();
        }

        String token = loginTickets.issue(user.id, now);
        // ponytail: reuse the Google bridge template — it is IdP-agnostic (username + one-time ticket only).
        // The page carries a single-use login token in its body — never cache it.
        return dropOidcSession(Response.ok(GoogleLoginResource.Templates.bridge(user.username, token))
                        .header("Cache-Control", "no-store"))
                .build();
    }

    /** The id_token "groups" claim as a Set, or empty when the provider sends none. */
    private Set<String> groups() {
        Set<String> g = idToken.getGroups();
        return g == null ? Set.of() : g;
    }

    private static Response.ResponseBuilder dropOidcSession(Response.ResponseBuilder b) {
        // Expire the OIDC session so the next SSO click re-authenticates at the provider —
        // required for group/admin changes to take effect on next login.
        return b.cookie(
                new NewCookie.Builder(OIDC_SESSION_COOKIE).path("/").maxAge(0).build());
    }

    private static Response.ResponseBuilder redirectToLogin(String notice) {
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/login?notice=" + java.net.URLEncoder.encode(notice, StandardCharsets.UTF_8)));
    }
}
