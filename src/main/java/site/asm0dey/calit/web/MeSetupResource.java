package site.asm0dey.calit.web;

import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.CurrentOwner;
import site.asm0dey.calit.user.PasswordHasher;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;

/** First-login wizard, distinct from the first-run /setup bootstrap. */
@Path("/me/setup")
@RolesAllowed("user")
public class MeSetupResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance meSetup(
                boolean mustChangePassword, OwnerSettings settings, List<String> zones, String error, String title);
    }

    @Inject
    CurrentOwner currentOwner;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    AdminMessageResolver adminMsgs;

    @Inject
    ActiveLocale activeLocale;

    /** All IANA zone ids, sorted — for the timezone combobox. */
    private static List<String> zoneIds() {
        return java.time.ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance wizard() {
        AppUser me = currentOwner.require(); // 401 if no owner resolved (never NPE on a null id)
        OwnerSettings existing = OwnerSettings.forOwner(me.id); // may be null on first visit
        String title = adminMsgs.forLocale(activeLocale.current()).mesetup_title();
        return Templates.meSetup(me.mustChangePassword, existing, zoneIds(), null, title);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response submit(@RestForm String newPassword,
                           @RestForm String ownerName,
                           @RestForm String ownerEmail,
                           @RestForm String timezone) {
        Long ownerId = currentOwner.require().id; // 401 if no owner resolved
        AppUser me = AppUser.findById(ownerId);   // managed entity for dirty-checking in this tx

        // Step 1: only when a forced reset is pending.
        if (me.mustChangePassword) {
            if (newPassword == null || newPassword.isBlank()) {
                return Response.ok(Templates.meSetup(true,
                        OwnerSettings.forOwner(ownerId), zoneIds(),
                        adminMsgs.forLocale(activeLocale.current()).mesetup_choose_new_password(), adminMsgs.forLocale(activeLocale.current()).mesetup_title())).build();
            }
            me.passwordHash = passwordHasher.hash(newPassword);
            me.mustChangePassword = false;
        }

        // Step 2: create/update this owner's settings row.
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = ownerName;
        s.ownerEmail = ownerEmail;
        s.timezone = timezone;
        s.persist();

        me.settingsComplete = true;
        return Response.seeOther(UriBuilder.fromUri("/me").build()).build();
    }
}
