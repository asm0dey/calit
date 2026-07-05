package site.asm0dey.calit.user;

import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

/**
 * Rejects authenticated requests whose AppUser has been disabled (enabled=false), even when a
 * previously-issued credential cookie is still cryptographically valid. A disabled identity is
 * downgraded to anonymous so role-guarded paths (e.g. /admin) redirect to /login.
 */
@ApplicationScoped
public class EnabledUserAugmentor implements SecurityIdentityAugmentor {

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        // The transient OIDC authorization-code-flow identity (used only to reach /api/oidc/login,
        // which bridges to a form-auth session that IS enabled-checked at ticket consumption) has an
        // IdP-subject principal, not a calit username. Running the findByUsername enabled-check on it
        // would wrongly anonymize a valid — or not-yet-provisioned — SSO user and loop the login.
        // Leave it untouched.
        if (identity.getCredential(IdTokenCredential.class) != null) {
            return Uni.createFrom().item(identity);
        }
        // Hibernate ORM is blocking.
        return context.runBlocking(() -> check(identity));
    }

    @ActivateRequestContext
    SecurityIdentity check(SecurityIdentity identity) {
        String username = identity.getPrincipal().getName();
        AppUser user = AppUser.findByUsername(username);
        if (user == null || !user.enabled) {
            // Drop all roles -> anonymous-equivalent identity; guarded paths will 401/redirect.
            return QuarkusSecurityIdentity.builder()
                    .setPrincipal(identity.getPrincipal())
                    .setAnonymous(true)
                    .build();
        }
        return identity;
    }
}
