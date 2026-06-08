package com.calit.web;

import com.calit.domain.MeetingType;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/")
public class PublicResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance landing(List<MeetingType> types, String css);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance landing() {
        // listPublic() = active && !secret — secret types never reach this page.
        return Templates.landing(MeetingType.listPublic(), Layout.CSS);
    }
}
