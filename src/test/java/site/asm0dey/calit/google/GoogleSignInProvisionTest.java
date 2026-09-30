package site.asm0dey.calit.google;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.web.CommonFeaturesProfile;

@QuarkusTest
@TestProfile(CommonFeaturesProfile.class)
class GoogleSignInProvisionTest {
    @Inject
    GoogleSignInService signIn;

    @Test
    @TestTransaction
    void unknownIdentityProvisionsNewOnboardingUser() {
        AppUser got = signIn.resolveOrProvision(new GoogleIdentity("sub-prov", "jane.doe@x.com", true));
        assertThat(got.id).isNotNull();
        assertThat(got.passwordHash).as("provisioned Google user has no password").isNull();
        assertThat(got.settingsComplete).as("provisioned user must run the onboarding wizard").isFalse();
        assertThat(got.googleSub).isEqualTo("sub-prov");

        OwnerSettings s = OwnerSettings.forOwner(got.id);
        assertThat(s).as("settings row is pre-created so the wizard can pre-fill").isNotNull();
        assertThat(s.ownerEmail).as("email pre-filled from Google").isEqualTo("jane.doe@x.com");
    }
}
