package site.asm0dey.calit.user;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EnabledUserAugmentorTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Inject
    EnabledUserAugmentor augmentor;

    private void upsert(String username, boolean enabled) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.findByUsername(username);
            if (u == null) {
                u = AppUser.create(username, HASHER.hash("pw12345"), true);
            }
            u.enabled = enabled;
            u.mustChangePassword = false;
            u.settingsComplete = true; // onboarded — reaches /me without the wizard redirect
            u.persist();
        });
    }

    @Test
    void disabledUserCookieIsRejected() {
        upsert("lockme", true);
        String cookie = given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "lockme")
                .formParam("j_password", "pw12345")
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(302)
                .extract()
                .cookie("quarkus-credential");

        // Sanity: cookie works while enabled.
        given().cookie("quarkus-credential", cookie)
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("Dashboard"));

        // Disable the user; the still-valid cookie must now be rejected.
        upsert("lockme", false);
        given().redirects()
                .follow(false)
                .cookie("quarkus-credential", cookie)
                .when()
                .get("/me")
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }

    // Trivial AuthenticationRequestContext: run the supplier synchronously, no request-context
    // activation needed since these tests don't touch Hibernate (no matching AppUser row).
    private static final AuthenticationRequestContext SYNC_CONTEXT =
            supplier -> Uni.createFrom().item(supplier.get());

    @Test
    void oidcIdentityWithUnknownUsernamePassesThroughUnaugmented() {
        // "sub-oidc-xyz" has no matching AppUser row. Without the fix, augment() would anonymize
        // this identity (findByUsername -> null) and the OIDC bridge would loop forever.
        SecurityIdentity oidcIdentity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("sub-oidc-xyz"))
                .addCredential(new IdTokenCredential("dummy-id-token"))
                .addRole("user")
                .build();

        SecurityIdentity result =
                augmentor.augment(oidcIdentity, SYNC_CONTEXT).await().indefinitely();

        assertFalse(result.isAnonymous());
        assertTrue(result.getRoles().contains("user"));
    }

    @Test
    void nonOidcIdentityWithUnknownUsernameIsStillAnonymized() {
        // Regression guard: an identity with the SAME unknown username but no IdTokenCredential
        // (i.e. not an OIDC code-flow identity) must still be downgraded to anonymous.
        SecurityIdentity plainIdentity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("sub-oidc-xyz"))
                .addRole("user")
                .build();

        SecurityIdentity result =
                augmentor.augment(plainIdentity, SYNC_CONTEXT).await().indefinitely();

        assertTrue(result.isAnonymous());
    }
}
