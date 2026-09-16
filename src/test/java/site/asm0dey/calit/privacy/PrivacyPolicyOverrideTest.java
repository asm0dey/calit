package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The operator fragment-override path ({@code app.privacy-policy-path}). A top-level class, not a
 * nested one: {@code @TestProfile} requires an in-JVM Quarkus restart that JUnit/surefire only
 * wires up for a class it discovers directly — a {@code static class} nested inside another
 * top-level {@code @QuarkusTest} is skipped by surefire's default include pattern and never runs.
 */
@QuarkusTest
@TestProfile(PrivacyPolicyOverrideTest.WithOverride.class)
class PrivacyPolicyOverrideTest {

    @Test
    void theOperatorFragmentReplacesTheShippedBody() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("OPERATOR SUPPLIED POLICY"))
                .body(not(containsString("CALIT_LEGAL_PRIVACY")));
    }

    public static class WithOverride implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                var f = Files.createTempFile("policy", ".html");
                Files.writeString(f, "<h1>OPERATOR SUPPLIED POLICY</h1>");
                return Map.of("app.privacy-policy-path", f.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
