package site.asm0dey.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.util.List;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.availability.DefaultAvailabilitySeeder;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.CurrentOwner;
import site.asm0dey.calit.user.PasswordHasher;

/** First-login wizard, distinct from the first-run /setup bootstrap. */
@Path("/me/setup")
@RolesAllowed("user")
public class MeSetupResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance meSetup(
                boolean mustChangePassword, OwnerSettings settings, List<String> zones, String error, String title);
    }

    final CurrentOwner currentOwner;

    final PasswordHasher passwordHasher;

    final AdminMessageResolver adminMsgs;

    final ActiveLocale activeLocale;

    @Inject
    public MeSetupResource(
            CurrentOwner currentOwner,
            PasswordHasher passwordHasher,
            AdminMessageResolver adminMsgs,
            ActiveLocale activeLocale) {
        this.currentOwner = currentOwner;
        this.passwordHasher = passwordHasher;
        this.adminMsgs = adminMsgs;
        this.activeLocale = activeLocale;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance wizard() {
        AppUser me = currentOwner.require(); // 401 if no owner resolved (never NPE on a null id)
        OwnerSettings existing = OwnerSettings.forOwner(me.id); // may be null on first visit
        String title = adminMsgs.forLocale(activeLocale.current()).mesetup_title();
        return Templates.meSetup(me.mustChangePassword, existing, OwnerSettings.zoneIds(), null, title);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response submit(
            @RestForm String newPassword,
            @RestForm String ownerName,
            @RestForm String ownerEmail,
            @RestForm String timezone) {
        Long ownerId = currentOwner.require().id; // 401 if no owner resolved
        AppUser me = AppUser.findById(ownerId); // managed entity for dirty-checking in this tx

        // Step 1: only when a forced reset is pending.
        if (me.mustChangePassword) {
            if (newPassword == null || newPassword.isBlank()) {
                return Response.ok(Templates.meSetup(
                                true,
                                OwnerSettings.forOwner(ownerId),
                                OwnerSettings.zoneIds(),
                                adminMsgs.forLocale(activeLocale.current()).mesetup_choose_new_password(),
                                adminMsgs.forLocale(activeLocale.current()).mesetup_title()))
                        .build();
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
        // Same guard as AdminResource.updateSettings: the rendered <select> can only submit a real
        // zone id, but a crafted POST is not bound by the form, and an unparseable value 500s the
        // owner's PUBLIC booking page and the booking transaction (calit-4whp).
        s.timezone = OwnerSettings.coerceZone(timezone);
        s.persist();

        // Step 3: a brand-new owner has no availability at all, so their meeting types would offer no
        // slots and the working-hours grid would render empty. Seed Mon–Fri 09:00–18:00 globals here —
        // MeOwnerFilter forces every user through this wizard before they can use /me, whichever path
        // created their row. Gated on settingsComplete (read before it's set below) rather than on the
        // row count: an owner can legitimately hold zero global rules after clearing their weekly grid
        // via the bulk-save endpoint, and MeOwnerFilter still lets an already-onboarded user re-POST
        // here — a count-based guard would re-seed hours they deliberately cleared. This is "first
        // completion only"; the row-count guard stays inside seedGlobalDefaults as belt-and-braces.
        if (!me.settingsComplete) {
            DefaultAvailabilitySeeder.seedGlobalDefaults(ownerId);
        }

        me.settingsComplete = true;
        return Response.seeOther(UriBuilder.fromUri("/me").build()).build();
    }
}
