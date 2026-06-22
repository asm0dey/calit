package site.asm0dey.calit.user;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AppUserPersistenceTest {

    @Test
    @TestTransaction
    void createAdminSyncsRolesAndPersists() {
        AppUser u = AppUser.create("Root-User", "hash-placeholder", true);
        u.persist();
        assertNotNull(u.id);
        assertEquals("root-user", u.username);   // normalized
        assertEquals("user,admin", u.roles);
        assertTrue(u.isAdmin);
        assertTrue(u.enabled);
        assertFalse(u.mustChangePassword);
        assertFalse(u.settingsComplete);
        assertNotNull(u.createdAt);
    }

    @Test
    @TestTransaction
    void createNonAdminGetsUserRoleOnly() {
        AppUser u = AppUser.create("plainuser", "h", false);
        u.persist();
        assertEquals("user", u.roles);
        assertFalse(u.isAdmin);
    }

    @Test
    @TestTransaction
    void findByUsernameAndUsernameTaken() {
        AppUser.create("findme", "h", false).persist();
        assertNotNull(AppUser.findByUsername("findme"));
        assertNull(AppUser.findByUsername("nobody"));
        assertTrue(AppUser.usernameTaken("findme"));
        assertFalse(AppUser.usernameTaken("nobody"));
    }
}
