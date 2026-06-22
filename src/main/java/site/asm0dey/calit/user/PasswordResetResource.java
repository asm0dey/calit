package site.asm0dey.calit.user;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppLocales;
import site.asm0dey.calit.i18n.AppMessageResolver;
import site.asm0dey.calit.i18n.AppMessages;

import java.net.URI;
import java.time.Instant;

/**
 * Forgot-password flow (unauthenticated). Request a reset by username → a single-use link is
 * emailed to the account's stored owner email; the link lets the user set a new password.
 * The request side never reveals whether an account exists (anti-enumeration).
 */
@Path("/")
public class PasswordResetResource {

    @Inject
    PasswordResetService resetService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    EmailService emailService;

    @Inject
    AppMessageResolver messages;

    @Inject
    ActiveLocale activeLocale;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance forgot(String title, boolean sent);
        public static native TemplateInstance reset(String title, String token, boolean error);
    }

    @GET
    @Path("forgot-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgotForm() {
        AppMessages m = messages.forLocale(activeLocale.current());
        return Templates.forgot(m.auth_forgot_title(), false);
    }

    @POST
    @Path("forgot-password")
    @Transactional
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance requestReset(@FormParam("username") String username) {
        AppUser user = username == null || username.isBlank() ? null : AppUser.findByUsername(username);
        if (user != null) {
            OwnerSettings os = OwnerSettings.forOwner(user.id);
            if (os != null && os.ownerEmail != null && !os.ownerEmail.isBlank()) {
                Instant now = Instant.now();
                String token = resetService.issue(user.id, now);
                emailService.sendPasswordReset(os.ownerEmail,
                        baseUrl + "/reset-password?token=" + token, now.plus(PasswordResetService.TTL),
                        AppLocales.pick(os.locale));
            }
        }
        // Always the same response — never disclose whether the account exists.
        AppMessages m = messages.forLocale(activeLocale.current());
        return Templates.forgot(m.auth_forgot_title(), true);
    }

    @GET
    @Path("reset-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resetForm(@QueryParam("token") String token) {
        AppMessages m = messages.forLocale(activeLocale.current());
        return Templates.reset(m.auth_reset_title(), token, false);
    }

    @POST
    @Path("reset-password")
    @Transactional
    @Produces(MediaType.TEXT_HTML)
    public Response doReset(@FormParam("token") String token, @FormParam("password") String password) {
        AppMessages m = messages.forLocale(activeLocale.current());
        if (password == null || password.isBlank()) {
            // Token not yet consumed — let them retry with the same link.
            return html(Response.Status.BAD_REQUEST, Templates.reset(m.auth_reset_title(), token, true));
        }
        AppUser user = resetService.consume(token, Instant.now());
        if (user == null) {
            // Unknown/expired/used: dead-end view (token=null) prompts a fresh request.
            return html(Response.Status.BAD_REQUEST, Templates.reset(m.auth_reset_title(), null, true));
        }
        user.passwordHash = passwordHasher.hash(password);
        user.mustChangePassword = false; // managed entity in this tx — flushed on commit
        return Response.status(Response.Status.FOUND).location(URI.create("/login")).build();
    }

    private static Response html(Response.Status status, TemplateInstance body) {
        return Response.status(status).entity(body).type(MediaType.TEXT_HTML).build();
    }
}
