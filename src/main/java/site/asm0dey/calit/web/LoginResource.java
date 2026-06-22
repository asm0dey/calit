package site.asm0dey.calit.web;

import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessages;
import site.asm0dey.calit.i18n.AppMessageResolver;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/login")
public class LoginResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance login(String title, boolean error, boolean googleEnabled, String notice);
    }

    @Inject
    SecurityIdentity identity;

    @Inject
    AppMessageResolver messages;

    @Inject
    ActiveLocale activeLocale;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "google.oauth.client-id", defaultValue = "")
    String googleClientId;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response login(@QueryParam("error") boolean error, @QueryParam("notice") String notice) {
        // Already signed in -> skip the form and go to the dashboard. MeOwnerFilter routes a
        // not-yet-onboarded user on to /me/setup from there.
        if (!identity.isAnonymous()) {
            return Response.seeOther(URI.create("/me")).build();
        }
        boolean googleEnabled = googleClientId != null && !googleClientId.isBlank();
        AppMessages m = messages.forLocale(activeLocale.current());
        return Response.ok(Templates.login(m.auth_login_title(), error, googleEnabled, noticeMessage(m, notice))).build();
    }

    /** Map a notice code from the Google sign-in flow to a localized human message, or null for none. */
    private static String noticeMessage(AppMessages m, String notice) {
        if (notice == null) {
            return null;
        }
        return switch (notice) {
            case "google_signup_disabled" -> m.auth_login_notice_google_signup_disabled();
            case "google_ambiguous" -> m.auth_login_notice_google_ambiguous();
            case "google" -> m.auth_login_notice_google_generic();
            default -> null;
        };
    }
}
