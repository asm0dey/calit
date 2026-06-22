package site.asm0dey.calit.i18n;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/__i18n_probe")
public class I18nProbeResource {
    @CheckedTemplate(basePath = "", requireTypeSafeExpressions = false)
    static class Templates {
        static native TemplateInstance i18nProbe();
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public TemplateInstance probe() {
        return Templates.i18nProbe();
    }
}
