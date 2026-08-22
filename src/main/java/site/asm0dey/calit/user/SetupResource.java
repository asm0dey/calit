package site.asm0dey.calit.user;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessageResolver;
import site.asm0dey.calit.i18n.AppMessages;

/**
 * First-run bootstrap. While no user exists, renders/creates the first (admin) user. Once any
 * user exists, every endpoint here returns 404 (the instance is bootstrapped).
 */
@Path("/setup")
public class SetupResource {

    final PasswordHasher passwordHasher;

    final AppMessageResolver messages;

    final ActiveLocale activeLocale;

    @Inject
    public SetupResource(PasswordHasher passwordHasher, AppMessageResolver messages, ActiveLocale activeLocale) {
        this.passwordHasher = passwordHasher;
        this.messages = messages;
        this.activeLocale = activeLocale;
    }

    @CheckedTemplate
    public static class Templates {
        private Templates() {}

        public static native TemplateInstance setup(String title, boolean error);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance setupForm() {
        requireUnbootstrapped();
        AppMessages m = messages.forLocale(activeLocale.current());
        return Templates.setup(m.auth_setup_title(), false);
    }

    @POST
    @Transactional
    @Produces(MediaType.TEXT_HTML)
    public Response createFirstUser(@FormParam("username") String username, @FormParam("password") String password) {
        requireUnbootstrapped();
        AppMessages m = messages.forLocale(activeLocale.current());
        final String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Templates.setup(m.auth_setup_title(), true))
                    .type(MediaType.TEXT_HTML)
                    .build();
        }
        if (password == null || password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Templates.setup(m.auth_setup_title(), true))
                    .type(MediaType.TEXT_HTML)
                    .build();
        }
        AppUser u = AppUser.create(normalized, passwordHasher.hash(password), true);
        u.mustChangePassword = false;
        u.settingsComplete = false;
        u.persist();
        OwnerSettings.seed(u.id, null);
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/login"))
                .build();
    }

    /** 404 once the instance has any user. */
    private void requireUnbootstrapped() {
        if (AppUser.count() > 0) {
            throw new NotFoundException();
        }
    }
}
