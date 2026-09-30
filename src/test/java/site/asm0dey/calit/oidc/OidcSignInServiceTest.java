package site.asm0dey.calit.oidc;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.web.CommonFeaturesProfile;

@QuarkusTest
@TestProfile(CommonFeaturesProfile.class)
class OidcSignInServiceTest {
    @Inject
    OidcSignInService service;

    private static OidcIdentity id(String sub, String email, boolean verified, String... groups) {
        return new OidcIdentity(sub, email, verified, Set.of(groups));
    }

    @Test
    @Transactional
    void provisionsNewUser_andGrantsAdminFromGroup() {
        AppUser u = service.resolveOrProvision(id("sub-new", "new@example.com", true, "calit-admins"));
        assertThat(u.id).isNotNull();
        assertThat(u.oidcSub).isEqualTo("sub-new");
        assertThat(u.passwordHash).isNull();
        assertThat(u.isAdmin).isFalse();
        assertThat(u.oidcAdmin).isTrue();
        assertThat(u.roles).isEqualTo("user,admin");
        assertThat(OwnerSettings.forOwner(u.id).ownerEmail).isEqualTo("new@example.com");
    }

    @Test
    @Transactional
    void provisionsNewUser_withoutAdminGroup_isPlainUser() {
        AppUser u = service.resolveOrProvision(id("sub-plain", "plain@example.com", true, "some-other-group"));
        assertThat(u.oidcAdmin).isFalse();
        assertThat(u.roles).isEqualTo("user");
    }

    @Test
    @Transactional
    void secondLoginRevokesOidcAdmin_whenGroupRemoved() {
        service.resolveOrProvision(id("sub-rev", "rev@example.com", true, "calit-admins"));
        // no groups now
        AppUser after = service.resolveOrProvision(id("sub-rev", "rev@example.com", true));
        assertThat(after.oidcAdmin).isFalse();
        assertThat(after.roles).isEqualTo("user");
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
        assertThat(linked.id).as("linked to the existing account by email").isEqualTo(admin.id);
        assertThat(linked.oidcSub).isEqualTo("sub-root");
        assertThat(linked.isAdmin).as("local admin is not demoted by OIDC").isTrue();
        assertThat(linked.roles).isEqualTo("user,admin");
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
        assertThat(u.id).as("unverified email must not auto-link").isNotEqualTo(1L);
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
        var ex = assertThatExceptionOfType(OidcSignInException.class)
            .isThrownBy(() -> service.resolveOrProvision(dup))
            .actual();
        assertThat(ex.reason).isEqualTo(OidcSignInException.Reason.AMBIGUOUS_EMAIL);
    }
}
