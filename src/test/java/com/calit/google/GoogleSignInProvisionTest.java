package com.calit.google;

import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
