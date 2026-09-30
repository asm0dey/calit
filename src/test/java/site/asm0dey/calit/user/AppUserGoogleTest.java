package site.asm0dey.calit.user;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AppUserGoogleTest {
    @Test
    @TestTransaction
    void createGoogleUserPersistsWithNoPasswordAndIsFoundBySub() {
        AppUser u = AppUser.createGoogleUser("alice", "google-sub-123");
        u.persistAndFlush();

        assertThat(u.id).as("id assigned").isNotNull();
        assertThat(u.passwordHash).as("OAuth-only user has no password hash").isNull();
        assertThat(u.roles).as("non-admin role").isEqualTo("user");
        assertThat(u.mustChangePassword).as("no forced password reset for OAuth users").isFalse();
        assertThat(u.settingsComplete).as("still needs the first-login wizard").isFalse();
        assertThat(u.isAdmin).as("Google users are non-admin").isFalse();

        AppUser found = AppUser.findByGoogleSub("google-sub-123");
        assertThat(found).as("lookup by sub returns the user").isNotNull();
        assertThat(found.id).isEqualTo(u.id);
        assertThat(found.googleSub).as("sub round-trips").isEqualTo("google-sub-123");
    }

    @Test
    @TestTransaction
    void findByGoogleSubReturnsNullForUnknownAndNull() {
        assertThat(AppUser.findByGoogleSub("nope")).isNull();
        assertThat(AppUser.findByGoogleSub(null)).isNull();
    }
}
