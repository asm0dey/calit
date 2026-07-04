package site.asm0dey.calit.web;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.CurrentOwner;
import site.asm0dey.calit.user.PasswordResetService;
import site.asm0dey.calit.user.Usernames;

@Path("/me/users")
@RolesAllowed("admin")
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class UsersResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance users(
                List<AppUser> users, String error, boolean isAdmin, Long pendingCount, String title);
    }

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

    @Inject
    PasswordResetService resetService;

    @Inject
    EmailService emailService;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

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
    public TemplateInstance create(@RestForm String username, @RestForm String email) {
        var m = adminMsgs.forLocale(activeLocale.current());
        String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken); // throws on invalid/reserved/taken
        } catch (IllegalArgumentException e) {
            return render(e.getMessage());
        }
        var candidate = email == null ? "" : email.trim();
        if (!looksLikeEmail(candidate)) {
            return render(m.users_error_email_invalid());
        }
        var inviteEmail = candidate;
        var now = Instant.now();
        // One tx: create the dormant user + its settings row + mint the activation token together.
        String token = QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create(normalized, null, false); // null hash => cannot log in until activated
            u.settingsComplete = false;
            u.persist();
            // Pre-create the settings row (mirrors GoogleSignInService.provision): ownerName/timezone
            // are NOT NULL, so seed placeholders the first-login wizard overwrites; ownerEmail holds
            // the invite address so resend + the wizard's pre-fill both find it.
            OwnerSettings s = new OwnerSettings();
            s.ownerId = u.id;
            s.ownerName = "";
            s.ownerEmail = inviteEmail;
            s.timezone = "UTC";
            s.persist();
            audit.event(identity.getPrincipal().getName(), "invite-user", USER_TARGET + normalized, null);
            return resetService.issue(u.id, now, Duration.ofHours(48));
        });
        emailService.sendInvite(
                inviteEmail,
                baseUrl + "/reset-password?token=" + token,
                inviterEmail(),
                baseUrl,
                now.plus(Duration.ofHours(48)),
                activeLocale.current());
        return render(null);
    }

    /** The inviting admin's display email (their settings address), falling back to their username. */
    private String inviterEmail() {
        String adminName = identity.getPrincipal().getName();
        AppUser me = AppUser.findByUsername(adminName);
        if (me != null) {
            OwnerSettings s = OwnerSettings.forOwner(me.id);
            if (s != null && s.ownerEmail != null && !s.ownerEmail.isBlank()) {
                return s.ownerEmail;
            }
        }
        return adminName;
    }

    /**
     * Cheap, ReDoS-free structural email check (not full RFC 5322): exactly one '@' with a non-empty
     * local part, a dot in the domain with characters on both sides, and no whitespace. Linear scan —
     * no backtracking regex. A malformed address that slips through simply bounces when the invite is
     * sent.
     */
    private static boolean looksLikeEmail(String s) {
        var at = s.indexOf('@');
        if (at <= 0 || at != s.lastIndexOf('@') || at == s.length() - 1) {
            return false;
        }
        var dot = s.indexOf('.', at + 1);
        if (dot < 0 || dot == at + 1 || dot == s.length() - 1) {
            return false;
        }
        for (var i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
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

    @POST
    @Path("/{id}/resend-invite")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resendInvite(@PathParam("id") Long id) {
        var m = adminMsgs.forLocale(activeLocale.current());
        AppUser u = requireUser(id);
        OwnerSettings s = OwnerSettings.forOwner(u.id);
        var pending = u.passwordHash == null && u.googleSub == null;
        if (!pending || s == null || s.ownerEmail == null || s.ownerEmail.isBlank()) {
            return render(m.users_error_not_pending());
        }
        var now = Instant.now();
        String token = resetService.issue(u.id, now, Duration.ofHours(48));
        audit.event(identity.getPrincipal().getName(), "resend-invite", USER_TARGET + u.username, null);
        emailService.sendInvite(
                s.ownerEmail,
                baseUrl + "/reset-password?token=" + token,
                inviterEmail(),
                baseUrl,
                now.plus(Duration.ofHours(48)),
                activeLocale.current());
        return render(null);
    }
}
