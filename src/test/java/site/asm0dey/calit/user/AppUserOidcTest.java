package site.asm0dey.calit.user;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AppUserOidcTest {
    @Test
    @Transactional
    void createOidcUser_setsPasswordlessNonLocalAdmin_rolesTrackOidcAdmin() {
        AppUser u = AppUser.createOidcUser("alice", "sub-123", true);
        assertThat(u.passwordHash).isNull();
        assertThat(u.oidcSub).isEqualTo("sub-123");
        assertThat(u.isAdmin).as("OIDC never sets the local admin bit").isFalse();
        assertThat(u.oidcAdmin).isTrue();
        assertThat(u.roles).as("effective roles include admin when oidcAdmin").isEqualTo("user,admin");
    }

    @Test
    @Transactional
    void applyOidcAdmin_revokesOidcAdmin_butKeepsLocalAdmin() {
        // local site admin
        AppUser local = AppUser.create("boss", "hash", true);
        // OIDC groups say "not admin"
        local.applyOidcAdmin(false);
        assertThat(local.isAdmin).as("local admin is sticky").isTrue();
        assertThat(local.oidcAdmin).isFalse();
        assertThat(local.roles).as("local admin keeps admin role").isEqualTo("user,admin");

        AppUser granted = AppUser.createOidcUser("temp", "sub-9", true);
        // removed from Authelia admin group
        granted.applyOidcAdmin(false);
        assertThat(granted.isAdmin).isFalse();
        assertThat(granted.oidcAdmin).isFalse();
        assertThat(granted.roles).as("OIDC-granted admin is revoked").isEqualTo("user");
    }

    @Test
    @Transactional
    void findByOidcSub_roundTrips_andNullSafe() {
        AppUser u = AppUser.createOidcUser("carol", "sub-round", false);
        u.persist();
        assertThat(AppUser.findByOidcSub("sub-round").id).isEqualTo(u.id);
        assertThat(AppUser.findByOidcSub(null)).isNull();
        assertThat(AppUser.findByOidcSub("no-such-sub")).isNull();
    }
}
