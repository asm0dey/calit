package site.asm0dey.calit.user;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AppUserOidcTest {

    @Test
    @Transactional
    void createOidcUser_setsPasswordlessNonLocalAdmin_rolesTrackOidcAdmin() {
        AppUser u = AppUser.createOidcUser("alice", "sub-123", true);
        assertNull(u.passwordHash);
        assertEquals("sub-123", u.oidcSub);
        assertFalse(u.isAdmin, "OIDC never sets the local admin bit");
        assertTrue(u.oidcAdmin);
        assertEquals("user,admin", u.roles, "effective roles include admin when oidcAdmin");
    }

    @Test
    @Transactional
    void applyOidcAdmin_revokesOidcAdmin_butKeepsLocalAdmin() {
        AppUser local = AppUser.create("boss", "hash", true); // local site admin
        local.applyOidcAdmin(false); // OIDC groups say "not admin"
        assertTrue(local.isAdmin, "local admin is sticky");
        assertFalse(local.oidcAdmin);
        assertEquals("user,admin", local.roles, "local admin keeps admin role");

        AppUser granted = AppUser.createOidcUser("temp", "sub-9", true);
        granted.applyOidcAdmin(false); // removed from Authelia admin group
        assertFalse(granted.isAdmin);
        assertFalse(granted.oidcAdmin);
        assertEquals("user", granted.roles, "OIDC-granted admin is revoked");
    }

    @Test
    @Transactional
    void findByOidcSub_roundTrips_andNullSafe() {
        AppUser u = AppUser.createOidcUser("carol", "sub-round", false);
        u.persist();
        assertEquals(u.id, AppUser.findByOidcSub("sub-round").id);
        assertNull(AppUser.findByOidcSub(null));
        assertNull(AppUser.findByOidcSub("no-such-sub"));
    }
}
