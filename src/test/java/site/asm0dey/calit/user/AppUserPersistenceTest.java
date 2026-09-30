package site.asm0dey.calit.user;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AppUserPersistenceTest {
    @Test
    @TestTransaction
    void createAdminSyncsRolesAndPersists() {
        AppUser u = AppUser.create("Root-User", "hash-placeholder", true);
        u.persist();
        assertThat(u.id).isNotNull();
        // normalized
        assertThat(u.username).isEqualTo("root-user");
        assertThat(u.roles).isEqualTo("user,admin");
        assertThat(u.isAdmin).isTrue();
        assertThat(u.enabled).isTrue();
        assertThat(u.mustChangePassword).isFalse();
        assertThat(u.settingsComplete).isFalse();
        assertThat(u.createdAt).isNotNull();
    }

    @Test
    @TestTransaction
    void createNonAdminGetsUserRoleOnly() {
        AppUser u = AppUser.create("plainuser", "h", false);
        u.persist();
        assertThat(u.roles).isEqualTo("user");
        assertThat(u.isAdmin).isFalse();
    }

    @Test
    @TestTransaction
    void findByUsernameAndUsernameTaken() {
        AppUser.create("findme", "h", false).persist();
        assertThat(AppUser.findByUsername("findme")).isNotNull();
        assertThat(AppUser.findByUsername("nobody")).isNull();
        assertThat(AppUser.usernameTaken("findme")).isTrue();
        assertThat(AppUser.usernameTaken("nobody")).isFalse();
    }
}
