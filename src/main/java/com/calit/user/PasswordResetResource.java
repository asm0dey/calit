package com.calit.user;

import com.calit.domain.OwnerSettings;
import com.calit.email.EmailService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance forgot(boolean sent);
        public static native TemplateInstance reset(String token, boolean error);
    }

    @GET
    @Path("forgot-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgotForm() {
        return Templates.forgot(false);
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
                        baseUrl + "/reset-password?token=" + token, now.plus(PasswordResetService.TTL));
            }
        }
        // Always the same response — never disclose whether the account exists.
        return Templates.forgot(true);
    }

    @GET
    @Path("reset-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resetForm(@QueryParam("token") String token) {
        return Templates.reset(token, false);
    }

    @POST
    @Path("reset-password")
    @Transactional
    @Produces(MediaType.TEXT_HTML)
    public Response doReset(@FormParam("token") String token, @FormParam("password") String password) {
        if (password == null || password.isBlank()) {
            // Token not yet consumed — let them retry with the same link.
            return html(Response.Status.BAD_REQUEST, Templates.reset(token, true));
        }
        AppUser user = resetService.consume(token, Instant.now());
        if (user == null) {
            // Unknown/expired/used: dead-end view (token=null) prompts a fresh request.
            return html(Response.Status.BAD_REQUEST, Templates.reset(null, true));
        }
        user.passwordHash = passwordHasher.hash(password);
        user.mustChangePassword = false; // managed entity in this tx — flushed on commit
        return Response.status(Response.Status.FOUND).location(URI.create("/login")).build();
    }

    private static Response html(Response.Status status, TemplateInstance body) {
        return Response.status(status).entity(body).type(MediaType.TEXT_HTML).build();
    }
}
