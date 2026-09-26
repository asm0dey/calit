package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An {@code app.terms-path} value that isn't a valid path string at all (here, one carrying an
 * embedded NUL byte) makes {@code java.nio.file.Path.of(...)} itself throw {@link
 * java.nio.file.InvalidPathException} — a different failure than "file not found" ({@link
 * PrivacyPolicyUnreadableFragmentTest}), thrown before any I/O is attempted. {@link
 * site.asm0dey.calit.web.LegalResource#fragment} must catch this too and fall back to the shipped
 * copy, never surface a 500.
 */
@QuarkusTest
@TestProfile(PrivacyPolicyMalformedFragmentPathTest.MalformedPath.class)
class PrivacyPolicyMalformedFragmentPathTest {
    @Test
    void aMalformedOverridePathFallsBackToTheShippedCopy() {
        given().when().get("/terms").then().statusCode(200).body(containsString("CALIT_LEGAL_TERMS"));
    }

    public static class MalformedPath implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.terms-path", "bad\0path.html");
        }
    }
}
