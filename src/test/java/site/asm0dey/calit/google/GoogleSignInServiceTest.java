package site.asm0dey.calit.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class GoogleSignInServiceTest {
    @Inject
    GoogleSignInService signIn;

    @Test
    @TestTransaction
    void knownSubLogsInExistingUser() {
        AppUser u = AppUser.createGoogleUser("known", "sub-known");
        u.persistAndFlush();

        AppUser got = signIn.resolveOrProvision(new GoogleIdentity("sub-known", "known@x.com", true));
        assertThat(got.id).as("existing sub returns its user, no new account").isEqualTo(u.id);
    }

    @Test
    @TestTransaction
    void verifiedEmailMatchingExactlyOneAccountAutoLinks() {
        AppUser u = AppUser.create("pw-acct", "hash", false);
        u.persistAndFlush();
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = "n";
        s.ownerEmail = "link@x.com";
        s.timezone = "UTC";
        s.persistAndFlush();

        AppUser got = signIn.resolveOrProvision(new GoogleIdentity("sub-new", "link@x.com", true));
        assertThat(got.id).as("links to the existing account by verified email").isEqualTo(u.id);
        AppUser bySubLookup = AppUser.findByGoogleSub("sub-new");
        assertThat(bySubLookup.id).as("the sub is now linked to that same account").isEqualTo(u.id);
    }

    @Test
    @TestTransaction
    void unverifiedEmailDoesNotAutoLink() {
        AppUser u = AppUser.create("pw-acct2", "hash", false);
        u.persistAndFlush();
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = "n";
        s.ownerEmail = "unv@x.com";
        s.timezone = "UTC";
        s.persistAndFlush();

        var identity = new GoogleIdentity("sub-x", "unv@x.com", false);
        GoogleSignInException ex = assertThatExceptionOfType(GoogleSignInException.class)
            .isThrownBy(() -> signIn.resolveOrProvision(identity))
            .actual();
        assertThat(ex.reason).isEqualTo(GoogleSignInException.Reason.SIGNUP_DISABLED);
    }

    @Test
    @TestTransaction
    void unknownIdentityRejectedWhenSignupDisabled() {
        var identity = new GoogleIdentity("sub-none", "new@x.com", true);
        GoogleSignInException ex = assertThatExceptionOfType(GoogleSignInException.class)
            .isThrownBy(() -> signIn.resolveOrProvision(identity))
            .actual();
        assertThat(ex.reason).isEqualTo(GoogleSignInException.Reason.SIGNUP_DISABLED);
    }
}
