package site.asm0dey.calit.user;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AppUserGoogleTest {

    @Test
    @TestTransaction
    void createGoogleUserPersistsWithNoPasswordAndIsFoundBySub() {
        AppUser u = AppUser.createGoogleUser("alice", "google-sub-123");
        u.persistAndFlush();

        assertNotNull(u.id, "id assigned");
        assertNull(u.passwordHash, "OAuth-only user has no password hash");
        assertEquals("user", u.roles, "non-admin role");
        assertFalse(u.mustChangePassword, "no forced password reset for OAuth users");
        assertFalse(u.settingsComplete, "still needs the first-login wizard");
        assertFalse(u.isAdmin, "Google users are non-admin");

        AppUser found = AppUser.findByGoogleSub("google-sub-123");
        assertNotNull(found, "lookup by sub returns the user");
        assertEquals(u.id, found.id);
        assertEquals("google-sub-123", found.googleSub, "sub round-trips");
    }

    @Test
    @TestTransaction
    void findByGoogleSubReturnsNullForUnknownAndNull() {
        assertNull(AppUser.findByGoogleSub("nope"));
        assertNull(AppUser.findByGoogleSub(null));
    }
}
