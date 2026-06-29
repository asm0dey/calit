package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MeOwnerFilterTest {

    @Test
    void meDashboardRequiresAuthAndResolvesOwner() {
        // Unauthenticated -> redirected to the form login page (302), never 200.
        given().redirects().follow(false).when().get("/me").then().statusCode(302);

        // Authenticated -> the filter resolves the owner and the dashboard renders.
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200);
    }

    @Test
    void oldAdminManagementPathIsGone() {
        // The /admin/* management surface moved to /me/* in Phase 2. Bare /admin now resolves to the
        // admin USER's public landing via /{user} (admin is a real account, id 1), but the old admin
        // management pages no longer exist — /admin/meeting-types falls through to /{user}/{slug} and
        // 404s (no meeting-type slug "meeting-types" for the admin owner).
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/admin/meeting-types")
                .then()
                .statusCode(404);
    }
}
