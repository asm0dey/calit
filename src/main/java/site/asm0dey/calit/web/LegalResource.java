package site.asm0dey.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.RawString;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import site.asm0dey.calit.notify.NotificationChannel;

/**
 * Public privacy policy and terms pages. Required for Google OAuth verification: the consent
 * screen must link a same-domain privacy policy that discloses how Google user data is used.
 * Content is operator-customizable via {@code {inject:site.*}} (see {@link SiteInfo}); GET-only,
 * no state mutation, so no CSRF token (mirrors {@code LangResource}).
 *
 * <p>An operator can also replace the entire shipped body with their own HTML fragment via {@code
 * app.privacy-policy-path} / {@code app.terms-path} — see {@link LegalFragments}.
 */
@Path("/")
public class LegalResource {
    @CheckedTemplate
    public static class Templates {
        private Templates() {
        }

        public static native TemplateInstance privacy(
                String title,
                OgCard og,
                RawString override,
                boolean anyChannelConfigured
        );

        public static native TemplateInstance terms(String title, OgCard og, RawString override);
    }

    final OgCards ogCards;
    final LegalFragments fragments;

    @Inject
    public LegalResource(OgCards ogCards, LegalFragments fragments) {
        this.ogCards = ogCards;
        this.fragments = fragments;
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy() {
        // Counted once per render and passed in: the template tests it in two places, and an
        // {inject:} getter would run the count query for each.
        return Templates.privacy(
                "Privacy Policy",
                ogCards.product("/privacy"),
                fragments.privacy(),
                NotificationChannel.count() > 0
        );
    }

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms() {
        return Templates.terms("Terms of Service", ogCards.product("/terms"), fragments.terms());
    }
}
