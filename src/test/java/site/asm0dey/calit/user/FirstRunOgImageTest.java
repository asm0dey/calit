package site.asm0dey.calit.user;

import static io.restassured.RestAssured.given;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the bug OgImageResourceIT caught in CI (calit-o89d): a fresh,
 * unbootstrapped instance (zero {@link AppUser} rows) 302'd every /og*.png request to /setup
 * instead of serving the card PNG. Mirrors {@link FirstRunLegalPagesTest}'s pattern of actually
 * emptying the shared DB rather than unit-testing the filter's private allow-list in isolation --
 * this exercises the real {@link FirstRunRedirectFilter} + {@code OgImageResource} routing.
 */
@QuarkusTest
class FirstRunOgImageTest {

    private void deleteAllUsers() {
        QuarkusTransaction.requiringNew().run(() -> AppUser.deleteAll());
    }

    // Never leave the shared DB at zero users (mirrors SetupFlowTest / FirstRunLegalPagesTest).
    @AfterEach
    void restoreBaseline() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.count() == 0) {
                AppUser.create("admin", new PasswordHasher().hash("testpass"), true)
                        .persist();
            }
        });
    }

    @Test
    void productCardReachableWithNoUsers() {
        deleteAllUsers();
        given().redirects()
                .follow(false)
                .when()
                .get("/og.png")
                .then()
                .statusCode(200)
                .contentType("image/png");
    }

    @Test
    void ownerAndMeetingTypeCardsDegradeToProductCardWithNoUsers() {
        deleteAllUsers();
        // No AppUser rows at all, so these can only ever resolve to the unknown-owner fallback --
        // proving the request reaches OgImageResource instead of being redirected to /setup.
        given().redirects()
                .follow(false)
                .when()
                .get("/og/admin.png")
                .then()
                .statusCode(200)
                .contentType("image/png");
        given().redirects()
                .follow(false)
                .when()
                .get("/og/admin/coffee.png")
                .then()
                .statusCode(200)
                .contentType("image/png");
    }

    @Test
    void unrelatedPathsStillRedirectToSetupWithNoUsers() {
        deleteAllUsers();
        given().redirects()
                .follow(false)
                .when()
                .get("/me")
                .then()
                .statusCode(302)
                .header("Location", "/setup");
        given().redirects()
                .follow(false)
                .when()
                .get("/admin/coffee")
                .then()
                .statusCode(302)
                .header("Location", "/setup");
    }
}
