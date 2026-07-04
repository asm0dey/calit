package site.asm0dey.calit.web;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessageResolver;

/**
 * Public one-click co-host consent flow, mirroring the {@code /booking/{manageToken}/...} trust
 * model: no login required — the unguessable {@code consentToken} on the {@link MeetingTypeHost}
 * row is the sole authorization. Unlike {@code AdminResource.approveFromEmail} (which is
 * authenticated and only uses the token as a CSRF nonce), this route requires no session at all,
 * since a co-host candidate may click the emailed link before ever logging in.
 */
@Path("/consent")
public class ConsentResource {

    @CheckedTemplate
    public static class Templates {
        private Templates() {}

        public static native TemplateInstance confirm(String title, MeetingType type, String creatorName, String token);

        public static native TemplateInstance done(String title, String h1, String desc);
    }

    @Inject
    MeetingHosts meetingHosts;

    @Inject
    AppMessageResolver messages;

    @Inject
    ActiveLocale activeLocale;

    /** Resolves the token to a still-pending host row, or 404 (unknown, already-used, or malformed). */
    private MeetingTypeHost requireHost(String token) {
        MeetingTypeHost host;
        try {
            host = MeetingTypeHost.findByConsentToken(token);
        } catch (IllegalArgumentException _) {
            throw new NotFoundException("No consent request for token " + token);
        }
        if (host == null) {
            throw new NotFoundException("No consent request for token " + token);
        }
        return host;
    }

    @GET
    @Path("/{token}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance confirmPage(@PathParam("token") String token) {
        var m = messages.forLocale(activeLocale.current());
        MeetingTypeHost host = requireHost(token);
        MeetingType type = MeetingType.findById(host.meetingTypeId);
        if (type == null) {
            throw new NotFoundException("No consent request for token " + token);
        }
        OwnerSettings creator = OwnerSettings.forOwner(type.ownerId);
        return Templates.confirm(m.pub_consent_confirm_title(), type, creator != null ? creator.ownerName : "?", token);
    }

    @POST
    @Path("/{token}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance respond(@PathParam("token") String token, @RestForm String action) {
        var m = messages.forLocale(activeLocale.current());
        requireHost(token); // 404 unknown/already-used/malformed before acting
        // Mutation commits in its own tx before the result render (issue #75). The host row is
        // (re)loaded INSIDE the tx: acceptConsent mutates the passed entity, so it must be managed
        // in the same persistence context to flush.
        if ("accept".equals(action)) {
            QuarkusTransaction.requiringNew().run(() -> meetingHosts.acceptConsent(requireHost(token)));
            return Templates.done(
                    m.pub_consent_result_title(), m.pub_consent_accepted_h1(), m.pub_consent_accepted_desc());
        }
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingTypeHost host = requireHost(token);
            MeetingTypeHost.delete("meetingTypeId = ?1 and ownerId = ?2", host.meetingTypeId, host.ownerId);
        });
        return Templates.done(m.pub_consent_result_title(), m.pub_consent_declined_h1(), m.pub_consent_declined_desc());
    }
}
