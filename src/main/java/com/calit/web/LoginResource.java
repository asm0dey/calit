package com.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/login")
public class LoginResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance login(boolean error);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login(@QueryParam("error") boolean error) {
        return Templates.login(error);
    }
}
