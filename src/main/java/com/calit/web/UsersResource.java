package com.calit.web;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.user.AppUser;
import com.calit.user.CurrentOwner;
import com.calit.user.PasswordHasher;
import com.calit.user.Usernames;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;

@Path("/me/users")
@RolesAllowed("admin")
public class UsersResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance users(List<AppUser> users, String error, boolean isAdmin,
                                                    Long pendingCount);
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
    com.calit.audit.AuditLog audit;

    /** This admin's own pending-approval count — drives the shared nav badge (consistent with other /me pages). */
    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2", currentOwner.id(), BookingStatus.PENDING);
    }

    /** All users, oldest first. Page is admin-only, so isAdmin is always true here. */
    private TemplateInstance render(String error) {
        return Templates.users(AppUser.list("order by createdAt asc"), error, true, pendingCount());
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        return render(null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance create(@RestForm String username, @RestForm String tempPassword) {
        String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken); // throws on invalid/reserved/taken
        } catch (IllegalArgumentException e) {
            return render(e.getMessage());
        }
        AppUser u = AppUser.create(normalized, passwordHasher.hash(tempPassword), false);
        u.mustChangePassword = true;   // must reset the temp password on first login
        u.settingsComplete = false;    // and complete the settings wizard
        u.persist();
        audit.event(identity.getPrincipal().getName(), "create-user", USER_TARGET +normalized, null);
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
    @Transactional
    public TemplateInstance grantAdmin(@PathParam("id") Long id) {
        requireUser(id).setAdmin(true);
        audit.event(identity.getPrincipal().getName(), "grant-admin", USER_TARGET +id, null);
        return render(null);
    }

    @POST
    @Path("/{id}/revoke-admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance revokeAdmin(@PathParam("id") Long id) {
        AppUser target = requireUser(id);
        // Block removing the last enabled admin — there is no in-app recovery path (SEC-AUTHZ-01).
        if (target.isAdmin && enabledAdminCount() <= 1) {
            return render("Cannot revoke admin from the last enabled admin.");
        }
        target.setAdmin(false);
        audit.event(identity.getPrincipal().getName(), "revoke-admin", USER_TARGET +id, null);
        return render(null);
    }

    @POST
    @Path("/{id}/lock")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance lock(@PathParam("id") Long id) {
        if (isSelf(id)) {
            return render("You cannot lock your own account.");
        }
        AppUser target = requireUser(id);
        // Locking the last enabled admin also destroys admin capability (SEC-AUTHZ-01).
        if (target.isAdmin && target.enabled && enabledAdminCount() <= 1) {
            return render("Cannot lock the last enabled admin.");
        }
        target.enabled = false;
        audit.event(identity.getPrincipal().getName(), "lock", USER_TARGET +id, null);
        return render(null);
    }

    @POST
    @Path("/{id}/unlock")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance unlock(@PathParam("id") Long id) {
        requireUser(id).enabled = true;
        audit.event(identity.getPrincipal().getName(), "unlock", USER_TARGET +id, null);
        return render(null);
    }
}
