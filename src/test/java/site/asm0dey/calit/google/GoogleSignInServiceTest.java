package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(u.id, got.id, "existing sub returns its user, no new account");
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
        assertEquals(u.id, got.id, "links to the existing account by verified email");
        AppUser bySubLookup = AppUser.findByGoogleSub("sub-new");
        assertEquals(u.id, bySubLookup.id, "the sub is now linked to that same account");
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

        GoogleSignInException ex = assertThrows(
                GoogleSignInException.class,
                () -> signIn.resolveOrProvision(new GoogleIdentity("sub-x", "unv@x.com", false)));
        assertEquals(GoogleSignInException.Reason.SIGNUP_DISABLED, ex.reason);
    }

    @Test
    @TestTransaction
    void unknownIdentityRejectedWhenSignupDisabled() {
        GoogleSignInException ex = assertThrows(
                GoogleSignInException.class,
                () -> signIn.resolveOrProvision(new GoogleIdentity("sub-none", "new@x.com", true)));
        assertEquals(GoogleSignInException.Reason.SIGNUP_DISABLED, ex.reason);
    }
}
