package site.asm0dey.calit.user;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The trusted (cookie re-establish) provider must NOT throw an authentication failure when the
 * cookie's user no longer exists — that error triggers a form-auth challenge to /login, and since
 * /login re-runs the same proactive cookie check it challenges again: an infinite redirect loop
 * (hit when an already-logged-in browser holds a stale cookie and the DB has no matching user,
 * e.g. before first-run /setup). A vanished user's session must re-establish as anonymous instead.
 */
@QuarkusTest
class AppUserTrustedIdentityProviderTest {

    @Inject
    AppUserTrustedIdentityProvider provider;

    @Test
    void unknownUserReestablishesAsAnonymousNotAuthFailure() {
        SecurityIdentity id = provider.build(new TrustedAuthenticationRequest("ghost-user-does-not-exist"));
        assertTrue(id.isAnonymous(), "a cookie for a vanished user must yield anonymous, never throw");
    }

    @Test
    void existingUserReestablishesWithRoles() {
        // baseline admin is reseeded before each test
        SecurityIdentity id = provider.build(new TrustedAuthenticationRequest("admin"));
        assertFalse(id.isAnonymous());
        assertEquals("admin", id.getPrincipal().getName());
        assertTrue(id.hasRole("admin"));
    }
}
