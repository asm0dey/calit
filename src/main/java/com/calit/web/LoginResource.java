package com.calit.web;

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
        public static native TemplateInstance login(boolean error, boolean googleEnabled, String notice);
    }

    @Inject
    SecurityIdentity identity;

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
        return Response.ok(Templates.login(error, googleEnabled, noticeMessage(notice))).build();
    }

    /** Map a notice code from the Google sign-in flow to a human message, or null for none. */
    private static String noticeMessage(String notice) {
        if (notice == null) {
            return null;
        }
        return switch (notice) {
            case "google_signup_disabled" -> "No account is linked to that Google account, and sign-ups are disabled.";
            case "google_ambiguous" -> "That Google email matches more than one account; sign in with your password instead.";
            case "google" -> "Google sign-in could not be completed. Please try again.";
            default -> null;
        };
    }
}
