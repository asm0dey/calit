package com.calit.user;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * First-run bootstrap. While no user exists, renders/creates the first (admin) user. Once any
 * user exists, every endpoint here returns 404 (the instance is bootstrapped).
 */
@Path("/setup")
public class SetupResource {

    @Inject
    PasswordHasher passwordHasher;

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance setup(boolean error);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance setupForm() {
        requireUnbootstrapped();
        return Templates.setup(false);
    }

    @POST
    @Transactional
    @Produces(MediaType.TEXT_HTML)
    public Response createFirstUser(@FormParam("username") String username,
                                    @FormParam("password") String password) {
        requireUnbootstrapped();
        final String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Templates.setup(true)).type(MediaType.TEXT_HTML).build();
        }
        if (password == null || password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Templates.setup(true)).type(MediaType.TEXT_HTML).build();
        }
        AppUser u = AppUser.create(normalized, passwordHasher.hash(password), true);
        u.mustChangePassword = false;
        u.settingsComplete = false;
        u.persist();
        return Response.status(Response.Status.FOUND).location(URI.create("/login")).build();
    }

    /** 404 once the instance has any user. */
    private void requireUnbootstrapped() {
        if (AppUser.count() > 0) {
            throw new NotFoundException();
        }
    }
}
