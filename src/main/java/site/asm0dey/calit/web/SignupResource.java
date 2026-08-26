package site.asm0dey.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessageResolver;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;
import site.asm0dey.calit.user.Usernames;

@Path("/signup")
public class SignupResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance signup(String title, String error, OgCard og);
    }

    final boolean signupEnabled;

    final PasswordHasher passwordHasher;

    final AppMessageResolver messages;

    final ActiveLocale activeLocale;

    final OgCards ogCards;

    @Inject
    public SignupResource(
            PasswordHasher passwordHasher,
            AppMessageResolver messages,
            ActiveLocale activeLocale,
            @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false") boolean signupEnabled,
            OgCards ogCards) {
        this.passwordHasher = passwordHasher;
        this.messages = messages;
        this.activeLocale = activeLocale;
        this.signupEnabled = signupEnabled;
        this.ogCards = ogCards;
    }

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
        String title = messages.forLocale(activeLocale.current()).auth_signup_title();
        return Templates.signup(title, null, ogCards.product("/signup"));
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response register(@RestForm String username, @RestForm String password) {
        requireEnabled();
        String title = messages.forLocale(activeLocale.current()).auth_signup_title();
        String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken); // throws on invalid/reserved/taken
        } catch (IllegalArgumentException _) {
            String error = messages.forLocale(activeLocale.current()).auth_signup_error();
            return Response.ok(Templates.signup(title, error, ogCards.product("/signup")))
                    .build();
        }
        AppUser u = AppUser.create(normalized, passwordHasher.hash(password), false);
        u.mustChangePassword = false; // self-chosen password → no forced reset
        u.settingsComplete = false; // still needs the first-login settings wizard
        u.persist();
        OwnerSettings.seed(u.id, null); // NOT NULL placeholders; the wizard overwrites them
        // Registered — send them to log in; the wizard kicks in at /me after login.
        return Response.seeOther(UriBuilder.fromUri("/login").build()).build();
    }
}
