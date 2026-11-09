package site.asm0dey.calit.privacy;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.web.CommonFeaturesProfile;

/**
 * The operator fragment-override path ({@code app.privacy-policy-path}). A top-level class, not a
 * nested one: {@code @TestProfile} requires an in-JVM Quarkus restart that JUnit/surefire only
 * wires up for a class it discovers directly — a {@code static class} nested inside another
 * top-level {@code @QuarkusTest} is skipped by surefire's default include pattern and never runs.
 */
@QuarkusTest
@TestProfile(CommonFeaturesProfile.class)
class PrivacyPolicyOverrideTest {
    @Test
    void theOperatorFragmentReplacesTheShippedBody() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("OPERATOR SUPPLIED POLICY"))
            .body(not(containsString("CALIT_LEGAL_PRIVACY")));
    }
}
