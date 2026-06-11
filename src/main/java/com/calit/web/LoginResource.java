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
        public static native TemplateInstance login(boolean error);
    }

    @Inject
    SecurityIdentity identity;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response login(@QueryParam("error") boolean error) {
        // Already signed in -> skip the form and go to the dashboard. MeOwnerFilter routes a
        // not-yet-onboarded user on to /me/setup from there.
        if (!identity.isAnonymous()) {
            return Response.seeOther(URI.create("/me")).build();
        }
        return Response.ok(Templates.login(error)).build();
    }
}
