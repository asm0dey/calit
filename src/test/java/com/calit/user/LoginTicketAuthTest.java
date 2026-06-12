package com.calit.user;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LoginTicketAuthTest {

    @Inject
    AppUserIdentityProvider provider;

    @Inject
    LoginTicketService tickets;

    private static UsernamePasswordAuthenticationRequest req(String user, String pass) {
        return new UsernamePasswordAuthenticationRequest(user, new PasswordCredential(pass.toCharArray()));
    }

    @Test
    @TestTransaction
    void validTicketAuthenticatesAsItsUser() {
        AppUser u = AppUser.createGoogleUser("ticket-login", "sub-tl");
        u.persistAndFlush();
        String token = tickets.issue(u.id, Instant.now());

        SecurityIdentity id = provider.authenticateBlocking(req("ticket-login", token));
        assertEquals("ticket-login", id.getPrincipal().getName());
        assertTrue(id.getRoles().contains("user"));
    }

    @Test
    @TestTransaction
    void ticketForOtherUsernameIsRejected() {
        AppUser u = AppUser.createGoogleUser("ticket-owner", "sub-to");
        u.persistAndFlush();
        AppUser other = AppUser.createGoogleUser("someone-else", "sub-se");
        other.persistAndFlush();
        String token = tickets.issue(u.id, Instant.now());

        // Token is valid but submitted under the wrong username -> reject (defence in depth).
        assertThrows(AuthenticationFailedException.class,
                () -> provider.authenticateBlocking(req("someone-else", token)));
    }

    @Test
    @TestTransaction
    void passwordUsersStillAuthenticateNormally() {
        // Sanity: the existing argon2id path is unaffected.
        AppUser u = AppUser.create("pw-user", new com.calit.user.PasswordHasher().hash("s3cret"), false);
        u.persistAndFlush();
        SecurityIdentity id = provider.authenticateBlocking(req("pw-user", "s3cret"));
        assertEquals("pw-user", id.getPrincipal().getName());
    }

    @Test
    @TestTransaction
    void disabledUserWithValidTicketIsRejected() {
        AppUser u = AppUser.createGoogleUser("disabled-tkt", "sub-dis");
        u.enabled = false;
        u.persistAndFlush();
        String token = tickets.issue(u.id, Instant.now());

        // A valid ticket must NOT log in a disabled account (the enabled gate).
        assertThrows(AuthenticationFailedException.class,
                () -> provider.authenticateBlocking(req("disabled-tkt", token)));
    }
}
