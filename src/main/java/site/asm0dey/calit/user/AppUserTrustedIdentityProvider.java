package site.asm0dey.calit.user;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

/**
 * Re-establishes the logged-in identity on every request after login. Quarkus form auth stores
 * the principal in an encrypted cookie and issues a TrustedAuthenticationRequest (no password)
 * to rebuild the SecurityIdentity per request. We load the AppUser by principal name and rebuild
 * its roles. The enabled=false check is intentionally NOT done here — EnabledUserAugmentor enforces
 * it on every request so a mid-session disable is honoured in one place.
 */
@ApplicationScoped
public class AppUserTrustedIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        return context.runBlocking(() -> build(request));
    }

    @ActivateRequestContext
    SecurityIdentity build(TrustedAuthenticationRequest request) {
        AppUser user = AppUser.findByUsername(request.getPrincipal());
        if (user == null) {
            // The cookie is cryptographically valid but its user no longer exists (deleted, or the
            // DB was reset before first-run /setup). Re-establish as ANONYMOUS rather than throwing
            // AuthenticationFailedException: a throw makes proactive auth challenge → redirect to
            // /login, and /login re-runs this same check → an infinite redirect loop. Treating a
            // vanished user's session as "logged out" lets public pages (/, /setup, /login) render
            // and guarded pages fall through to a single normal login redirect. Mirrors
            // EnabledUserAugmentor, which already downgrades the user==null/disabled case to anonymous.
            // A principal is still set (anonymous + principal, like the augmentor) so downstream
            // code that reads identity.getPrincipal().getName() does not NPE.
            return QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
                    .setAnonymous(true)
                    .build();
        }
        return AppUserSecurityIdentities.of(user);
    }
}
