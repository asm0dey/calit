package com.calit.user;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Verifies form-login credentials against the DB argon2id hash. Replaces the Elytron
 * credential-comparison path that cannot consume argon2. Loads the AppUser by username,
 * verifies the password with PasswordHasher, and builds a SecurityIdentity carrying the
 * user's roles. Disabled users are rejected here (and again by EnabledUserAugmentor).
 */
@ApplicationScoped
public class AppUserIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Inject
    PasswordHasher passwordHasher;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        return context.runBlocking(() -> authenticateBlocking(request));
    }

    @ActivateRequestContext
    SecurityIdentity authenticateBlocking(UsernamePasswordAuthenticationRequest request) {
        String username = request.getUsername();
        String password = new String(request.getPassword().getPassword());
        AppUser user = AppUser.findByUsername(username);
        if (user == null || !user.enabled || !passwordHasher.verify(password, user.passwordHash)) {
            throw new AuthenticationFailedException();
        }
        return AppUserSecurityIdentities.of(user);
    }
}
