package site.asm0dey.calit.oidc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.Usernames;

/**
 * Maps a verified OIDC (e.g. Authelia) identity to the {@link AppUser} that should be logged in:
 *   1. known oidc_sub          -> that user;
 *   2. unknown sub but the id_token's VERIFIED email matches exactly one existing account
 *      -> link the sub to that account;
 *   3. otherwise provision a new passwordless, not-yet-onboarded account, but only when
 *      SIGNUP_ENABLED=true; else reject with SIGNUP_DISABLED.
 * On every path, {@link AppUser#applyOidcAdmin(boolean)} recomputes admin from the IdP groups
 * (grant AND revoke), while the local {@code isAdmin} bit is left untouched (never demoted).
 * Ambiguous email (more than one same-email account) is rejected rather than guessed.
 *
 * @implNote Same caveat as the Google flow: auto-link trusts the IdP's verified email against the
 *           account's OwnerSettings email, which the app itself does not verify (free-text from the
 *           settings wizard). Acceptable pre-public.
 */
@ApplicationScoped
public class OidcSignInService {

    @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false")
    boolean signupEnabled;

    /** OIDC group whose members get admin, or absent/blank to disable OIDC-driven admin entirely.
     * Optional<String>, not a required String: an unset OIDC_ADMIN_GROUP resolves to an empty
     * property value, and SmallRye Config treats an empty value as "undefined" for non-Optional
     * injection points, which crashes the app at boot (SRCFG00040). */
    @ConfigProperty(name = "calit.oidc.admin-group")
    Optional<String> adminGroup;

    @Transactional
    public AppUser resolveOrProvision(OidcIdentity identity) {
        boolean grantsAdmin = grantsAdmin(identity.groups());

        AppUser bySub = AppUser.findByOidcSub(identity.sub());
        if (bySub != null) {
            bySub.applyOidcAdmin(grantsAdmin); // managed entity -> dirty-checked in this tx
            return bySub;
        }

        if (identity.emailVerified() && identity.email() != null) {
            List<Long> owners = OwnerSettings.findOwnerIdsByEmail(identity.email());
            if (owners.size() == 1) {
                AppUser linked = AppUser.findById(owners.getFirst());
                linked.oidcSub = identity.sub();
                linked.applyOidcAdmin(grantsAdmin);
                return linked;
            }
            if (owners.size() > 1) {
                throw new OidcSignInException(OidcSignInException.Reason.AMBIGUOUS_EMAIL);
            }
        }

        if (!signupEnabled) {
            throw new OidcSignInException(OidcSignInException.Reason.SIGNUP_DISABLED);
        }
        return provision(identity, grantsAdmin);
    }

    private boolean grantsAdmin(Set<String> groups) {
        return groups != null
                && adminGroup.filter(g -> !g.isBlank()).map(groups::contains).orElse(false);
    }

    private AppUser provision(OidcIdentity identity, boolean grantsAdmin) {
        String username = Usernames.uniquify(Usernames.fromEmail(identity.email()), AppUser::usernameTaken);
        AppUser u = AppUser.createOidcUser(username, identity.sub(), grantsAdmin);
        u.persist();

        // Pre-create the settings row so the first-login wizard (/me/setup) can pre-fill the email.
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = "";
        s.ownerEmail = identity.email() == null ? "" : identity.email();
        s.timezone = "UTC";
        s.persist();
        return u;
    }
}
