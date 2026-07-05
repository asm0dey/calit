package site.asm0dey.calit.oidc;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
@TestProfile(OidcSignInServiceTest.SignupOnAdminGroup.class)
class OidcSignInServiceTest {

    /** Enable signup + define the admin group, so provisioning and admin mapping are exercised. */
    public static class SignupOnAdminGroup implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.signup.enabled", "true", "calit.oidc.admin-group", "calit-admins");
        }
    }

    @Inject
    OidcSignInService service;

    private static OidcIdentity id(String sub, String email, boolean verified, String... groups) {
        return new OidcIdentity(sub, email, verified, Set.of(groups));
    }

    @Test
    @Transactional
    void provisionsNewUser_andGrantsAdminFromGroup() {
        AppUser u = service.resolveOrProvision(id("sub-new", "new@example.com", true, "calit-admins"));
        assertNotNull(u.id);
        assertEquals("sub-new", u.oidcSub);
        assertNull(u.passwordHash);
        assertFalse(u.isAdmin);
        assertTrue(u.oidcAdmin);
        assertEquals("user,admin", u.roles);
        assertEquals("new@example.com", OwnerSettings.forOwner(u.id).ownerEmail);
    }

    @Test
    @Transactional
    void provisionsNewUser_withoutAdminGroup_isPlainUser() {
        AppUser u = service.resolveOrProvision(id("sub-plain", "plain@example.com", true, "some-other-group"));
        assertFalse(u.oidcAdmin);
        assertEquals("user", u.roles);
    }

    @Test
    @Transactional
    void secondLoginRevokesOidcAdmin_whenGroupRemoved() {
        service.resolveOrProvision(id("sub-rev", "rev@example.com", true, "calit-admins"));
        AppUser after = service.resolveOrProvision(id("sub-rev", "rev@example.com", true)); // no groups now
        assertFalse(after.oidcAdmin);
        assertEquals("user", after.roles);
    }

    @Test
    @Transactional
    void linksByVerifiedEmail_toExistingLocalAdmin_withoutDemotingIt() {
        // Admin user id 1 always exists (DatabaseResetCallback). Give it a settings email to match on.
        AppUser admin = AppUser.findById(1L);
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "";
            s.timezone = "UTC";
        }
        s.ownerEmail = "root@example.com";
        s.persist();
        // OIDC login with matching verified email, but NOT in the admin group:
        AppUser linked = service.resolveOrProvision(id("sub-root", "root@example.com", true));
        assertEquals(admin.id, linked.id, "linked to the existing account by email");
        assertEquals("sub-root", linked.oidcSub);
        assertTrue(linked.isAdmin, "local admin is not demoted by OIDC");
        assertEquals("user,admin", linked.roles);
    }

    @Test
    @Transactional
    void unverifiedEmail_doesNotLink_provisionsFresh() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "";
            s.timezone = "UTC";
        }
        s.ownerEmail = "same@example.com";
        s.persist();
        AppUser u = service.resolveOrProvision(id("sub-unv", "same@example.com", false));
        assertNotEquals(1L, u.id, "unverified email must not auto-link");
    }

    @Test
    @Transactional
    void ambiguousEmail_isRejected() {
        AppUser a = AppUser.create("dup-a", "h", false);
        a.persist();
        AppUser b = AppUser.create("dup-b", "h", false);
        b.persist();
        for (AppUser x : new AppUser[] {a, b}) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = x.id;
            s.ownerName = "";
            s.ownerEmail = "dup@example.com";
            s.timezone = "UTC";
            s.persist();
        }
        var dup = id("sub-dup", "dup@example.com", true);
        var ex = assertThrows(OidcSignInException.class, () -> service.resolveOrProvision(dup));
        assertEquals(OidcSignInException.Reason.AMBIGUOUS_EMAIL, ex.reason);
    }
}
