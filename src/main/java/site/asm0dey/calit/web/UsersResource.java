package site.asm0dey.calit.web;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.CurrentOwner;
import site.asm0dey.calit.user.PasswordHasher;
import site.asm0dey.calit.user.Usernames;

@Path("/me/users")
@RolesAllowed("admin")
public class UsersResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance users(
                List<AppUser> users, String error, boolean isAdmin, Long pendingCount, String title);
    }

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    CurrentOwner currentOwner;

    /** Audit-event target prefix for a user-directed admin action. */
    private static final String USER_TARGET = "user:";

    @Inject
    SecurityIdentity identity;

    @Inject
    site.asm0dey.calit.audit.AuditLog audit;

    @Inject
    AdminMessageResolver adminMsgs;

    @Inject
    ActiveLocale activeLocale;

    /** This admin's own pending-approval count — drives the shared nav badge (consistent with other /me pages). */
    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2", currentOwner.id(), BookingStatus.PENDING);
    }

    /** All users, oldest first. Page is admin-only, so isAdmin is always true here. */
    private TemplateInstance render(String error) {
        return Templates.users(
                AppUser.list("order by createdAt asc"),
                error,
                true,
                pendingCount(),
                adminMsgs.forLocale(activeLocale.current()).adm_users_title());
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        return render(null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance create(@RestForm String username, @RestForm String tempPassword) {
        String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken); // throws on invalid/reserved/taken
        } catch (IllegalArgumentException e) {
            return render(e.getMessage());
        }
        // Create in its own tx that commits before the user-list render (issue #75): no DB
        // connection is held across the Qute render below.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.create(normalized, passwordHasher.hash(tempPassword), false);
            u.mustChangePassword = true; // must reset the temp password on first login
            u.settingsComplete = false; // and complete the settings wizard
            u.persist();
            audit.event(identity.getPrincipal().getName(), "create-user", USER_TARGET + normalized, null);
        });
        return render(null);
    }

    private AppUser requireUser(Long id) {
        AppUser u = AppUser.findById(id);
        if (u == null) {
            throw new NotFoundException("No user " + id);
        }
        return u;
    }

    /** The currently-authenticated admin's own AppUser row (principal name == username). */
    private AppUser currentUser() {
        return AppUser.find("username", identity.getPrincipal().getName()).firstResult();
    }

    /** Count of admins that can still log in — the invariant we must never drive to zero. */
    private static long enabledAdminCount() {
        return AppUser.count("isAdmin = true and enabled = true");
    }

    private boolean isSelf(Long targetId) {
        AppUser me = currentUser();
        return me != null && me.id.equals(targetId);
    }

    @POST
    @Path("/{id}/grant-admin")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance grantAdmin(@PathParam("id") Long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            requireUser(id).setAdmin(true); // managed entity loaded inside the tx → flushes on commit
            audit.event(identity.getPrincipal().getName(), "grant-admin", USER_TARGET + id, null);
        });
        return render(null);
    }

    @POST
    @Path("/{id}/revoke-admin")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance revokeAdmin(@PathParam("id") Long id) {
        AppUser target = requireUser(id);
        // Block removing the last enabled admin — there is no in-app recovery path (SEC-AUTHZ-01).
        if (target.isAdmin && enabledAdminCount() <= 1) {
            return render("Cannot revoke admin from the last enabled admin.");
        }
        QuarkusTransaction.requiringNew().run(() -> {
            requireUser(id).setAdmin(false); // re-load inside the tx so the change flushes on commit
            audit.event(identity.getPrincipal().getName(), "revoke-admin", USER_TARGET + id, null);
        });
        return render(null);
    }

    @POST
    @Path("/{id}/lock")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance lock(@PathParam("id") Long id) {
        if (isSelf(id)) {
            return render("You cannot lock your own account.");
        }
        AppUser target = requireUser(id);
        // Locking the last enabled admin also destroys admin capability (SEC-AUTHZ-01).
        if (target.isAdmin && target.enabled && enabledAdminCount() <= 1) {
            return render("Cannot lock the last enabled admin.");
        }
        QuarkusTransaction.requiringNew().run(() -> {
            requireUser(id).enabled = false; // re-load inside the tx so the change flushes on commit
            audit.event(identity.getPrincipal().getName(), "lock", USER_TARGET + id, null);
        });
        return render(null);
    }

    @POST
    @Path("/{id}/unlock")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance unlock(@PathParam("id") Long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            requireUser(id).enabled = true; // managed entity loaded inside the tx → flushes on commit
            audit.event(identity.getPrincipal().getName(), "unlock", USER_TARGET + id, null);
        });
        return render(null);
    }
}
