package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
@TestProfile(GoogleSignInProvisionTest.SignupEnabledProfile.class)
class GoogleSignInProvisionTest {

    @Inject
    GoogleSignInService signIn;

    @Test
    @TestTransaction
    void unknownIdentityProvisionsNewOnboardingUser() {
        AppUser got = signIn.resolveOrProvision(new GoogleIdentity("sub-prov", "jane.doe@x.com", true));
        assertNotNull(got.id);
        assertNull(got.passwordHash, "provisioned Google user has no password");
        assertFalse(got.settingsComplete, "provisioned user must run the onboarding wizard");
        assertEquals("sub-prov", got.googleSub);

        OwnerSettings s = OwnerSettings.forOwner(got.id);
        assertNotNull(s, "settings row is pre-created so the wizard can pre-fill");
        assertEquals("jane.doe@x.com", s.ownerEmail, "email pre-filled from Google");
    }

    public static class SignupEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.signup.enabled", "true");
        }
    }
}
