package com.calit.user;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
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
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        return context.runBlocking(() -> build(request));
    }

    @ActivateRequestContext
    SecurityIdentity build(TrustedAuthenticationRequest request) {
        AppUser user = AppUser.findByUsername(request.getPrincipal());
        if (user == null) {
            throw new AuthenticationFailedException();
        }
        return AppUserSecurityIdentities.of(user);
    }
}
