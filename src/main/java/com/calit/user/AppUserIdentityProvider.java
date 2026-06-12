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
 * Authenticates form-login submissions. Two paths, in order:
 *   1. Normal login — verify the submitted password against the user's argon2id hash via
 *      PasswordHasher (skipped for passwordless Google-only users, whose hash is null).
 *   2. Google sign-in bridge — the submitted "password" may be a single-use login ticket;
 *      LoginTicketService.consume() validates and consumes it, and the ticket's user must match
 *      the submitted username.
 * Replaces the Elytron credential-comparison path that cannot consume argon2. Disabled users are
 * rejected on both paths here (and again by EnabledUserAugmentor on every request).
 */
@ApplicationScoped
public class AppUserIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    LoginTicketService loginTickets;

    @Inject
    java.time.Clock clock;

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
        String secret = new String(request.getPassword().getPassword());

        AppUser user = AppUser.findByUsername(username);
        // 1) Normal form login: verify the argon2id hash (skipped for passwordless Google users).
        if (user != null && user.enabled && user.passwordHash != null
                && passwordHasher.verify(secret, user.passwordHash)) {
            return AppUserSecurityIdentities.of(user);
        }
        // 2) Google sign-in bridge: the "password" may be a single-use login ticket. It is consumed
        //    here (single-use) and must belong to the username it was submitted under.
        AppUser ticketUser = loginTickets.consume(secret, clock.instant());
        if (ticketUser != null && ticketUser.enabled
                && ticketUser.username.equals(Usernames.normalize(username))) {
            return AppUserSecurityIdentities.of(ticketUser);
        }
        throw new AuthenticationFailedException();
    }
}
