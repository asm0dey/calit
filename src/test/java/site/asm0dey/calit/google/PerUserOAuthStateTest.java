package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PerUserOAuthStateTest {
    @Inject
    GoogleTokenService tokenService;
    @Inject
    GoogleOAuthConfig oauthConfig;

    @Test
    void stateRoundTripsTheOwnerId() {
        var now = Instant.parse("2026-06-08T12:00:00Z");
        String state = tokenService.issueState(42L, now);
        assertThat(tokenService.validateState(state, now))
            .as("callback must recover the owner id that initiated /connect")
            .isEqualTo(42L);
    }

    @Test
    void forgedOrTamperedStateRejected() {
        var now = Instant.parse("2026-06-08T12:00:00Z");
        String state = tokenService.issueState(42L, now);
        // flips owner id -> signature mismatch
        var tampered = state.replace(":42:", ":99:");
        assertThat(tokenService.validateState(tampered, now)).as("tampered state must be rejected").isNull();
        assertThat(tokenService.validateState("garbage.value", now)).as("malformed state must be rejected").isNull();
        assertThat(tokenService.validateState(null, now)).as("null state must be rejected").isNull();
    }

    @Test
    void expiredStateRejected() {
        var issued = Instant.parse("2026-06-08T12:00:00Z");
        String state = tokenService.issueState(7L, issued);
        Instant tooLate = issued.plus(GoogleTokenService.STATE_TTL).plusSeconds(60);
        assertThat(tokenService.validateState(state, tooLate)).as("expired state must be rejected").isNull();
    }

    @Test
    void consentUrlCarriesSignedStateForOwner() {
        String url = tokenService.buildConsentUrl(7L, Instant.parse("2026-06-08T12:00:00Z"));
        assertThat(url)
            .as("consent URL must include a state param")
            .contains("state=")
            .as("consent URL points at Google")
            .startsWith("https://accounts.google.com/");
    }

    /**
     * Stubs the network seam so exchangeCode does no real Google call.
     */
    static final class StubTokenService extends GoogleTokenService {
        StubTokenService(GoogleOAuthConfig config) {
            super(config);
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return new TokenResponse("access-tok", "refresh-tok", now.plusSeconds(3600), "sub-peruser", "u@example.com");
        }
    }

    @Test
    @io.quarkus.test.TestTransaction
    void exchangeCodeWritesCredentialForTheGivenOwner() {
        site.asm0dey.calit.user.AppUser a = site.asm0dey.calit.user.AppUser.create("oauth-a", "x", false);
        a.persistAndFlush();
        site.asm0dey.calit.user.AppUser b = site.asm0dey.calit.user.AppUser.create("oauth-b", "x", false);
        b.persistAndFlush();

        var stub = new StubTokenService(oauthConfig);
        stub.exchangeCode(b.id, "any-code", Instant.parse("2026-06-08T12:00:00Z"));

        GoogleCredential credB = GoogleCredential.forOwner(b.id);
        assertThat(credB).as("credential must be written for owner B").isNotNull();
        assertThat(credB.ownerId).isEqualTo(b.id);
        assertThat(credB.accessToken).isEqualTo("access-tok");
        assertThat(GoogleCredential.forOwner(a.id)).as("owner A must have no credential").isNull();
    }
}
