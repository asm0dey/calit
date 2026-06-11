package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.PasswordHasher;
import com.calit.user.Usernames;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

@Path("/signup")
public class SignupResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance signup(String error);
    }

    @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false")
    boolean signupEnabled;

    @Inject
    PasswordHasher passwordHasher;

    /** When signup is disabled the whole resource is invisible: behave exactly like no route. */
    private void requireEnabled() {
        if (!signupEnabled) {
            throw new NotFoundException();
        }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance form() {
        requireEnabled();
        return Templates.signup(null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response register(@RestForm String username, @RestForm String password) {
        requireEnabled();
        String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken); // throws on invalid/reserved/taken
        } catch (IllegalArgumentException e) {
            return Response.ok(Templates.signup(e.getMessage())).build();
        }
        AppUser u = AppUser.create(normalized, passwordHasher.hash(password), false);
        u.mustChangePassword = false; // self-chosen password → no forced reset
        u.settingsComplete = false;   // still needs the first-login settings wizard
        u.persist();
        // Registered — send them to log in; the wizard kicks in at /me after login.
        return Response.seeOther(UriBuilder.fromUri("/login").build()).build();
    }
}
