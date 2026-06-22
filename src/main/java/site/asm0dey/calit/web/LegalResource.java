package site.asm0dey.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Public privacy policy and terms pages. Required for Google OAuth verification: the consent
 * screen must link a same-domain privacy policy that discloses how Google user data is used.
 * Content is operator-customizable via {@code {inject:site.*}} (see {@link SiteInfo}); GET-only,
 * no state mutation, so no CSRF token (mirrors {@code LangResource}).
 */
@Path("/")
public class LegalResource {

    @CheckedTemplate
    public static class Templates {
        private Templates() {}
        public static native TemplateInstance privacy(String title);
        public static native TemplateInstance terms(String title);
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy() {
        return Templates.privacy("Privacy Policy");
    }

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms() {
        return Templates.terms("Terms of Service");
    }
}
