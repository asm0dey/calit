package site.asm0dey.calit.privacy;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.asm0dey.calit.web.LegalFragments;

/**
 * The operator fragment-override path ({@code app.privacy-policy-path}), end to end through /privacy.
 */
@QuarkusTest
class PrivacyPolicyOverrideTest {
    @TempDir
    Path dir;

    @BeforeEach
    void operatorPolicy() throws IOException {
        var policy = Files.writeString(dir.resolve("policy.html"), "<h1>OPERATOR SUPPLIED POLICY</h1>");
        QuarkusMock.installMockForType(
                new LegalFragments(Optional.of(policy.toString()), Optional.empty()),
                LegalFragments.class
        );
    }

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
