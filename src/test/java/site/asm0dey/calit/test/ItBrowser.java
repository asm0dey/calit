package site.asm0dey.calit.test;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import io.restassured.response.Response;

/**
 * A cookie-jar HTTP client for {@code @QuarkusIntegrationTest} classes. The app under test is a separate
 * %prod process, so CSRF is on and every cookie is {@code Secure}: RestAssured would not send them back
 * over plain http, so the jar keeps them and sends them by hand, and every POST carries the csrf-token.
 */
public final class ItBrowser {
    public record Owner(String username, String password) {}

    /**
     * Every cookie the app has set so far, csrf-token and quarkus-credential included.
     */
    private final Map<String, String> jar = new HashMap<>();

    public Map<String, String> cookies() {
        return jar;
    }

    public Response get(String path) {
        Response r = given().cookies(jar).redirects().follow(false).get(path);
        jar.putAll(r.getCookies());
        return r;
    }

    public Response post(String path, Map<String, ?> form) {
        Response r = given()
            .cookies(jar)
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParams(form)
            .formParam("csrf-token", jar.get("csrf-token"))
            .post(path);
        jar.putAll(r.getCookies());
        return r;
    }

    /**
     * Creates a signed-in owner who finished the first-login wizard. The first user on an empty DB goes
     * through {@code /setup}; otherwise (a DB left over from surefire, or another IT ran first) it signs
     * up, which needs SIGNUP_ENABLED=true in failsafe's environment.
     */
    public Owner signUpOwner(String prefix, ZoneId zone) {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var owner = new Owner(prefix + suffix, "it-pass-" + suffix);
        var credentials = Map.of("username", owner.username(), "password", owner.password());
        if (get("/setup").statusCode() == 200) {
            assertThat(post("/setup", credentials).statusCode()).isEqualTo(302);
        } else {
            assertThat(get("/signup").statusCode()).isEqualTo(200);
            assertThat(post("/signup", credentials).statusCode()).isEqualTo(303);
        }
        assertThat(post("/j_security_check", Map.of("j_username", owner.username(), "j_password", owner.password()))
            .statusCode()
        )
            .isEqualTo(302);
        assertThat(jar).containsKey("quarkus-credential");
        assertThat(get("/me/setup").statusCode()).isEqualTo(200);
        assertThat(post(
                "/me/setup",
                Map.of(
                        "ownerName",
                        "IT Owner",
                        "ownerEmail",
                        owner.username() + "@example.com",
                        "timezone",
                        zone.getId()
                )
        )
            .statusCode()
        )
            .isEqualTo(303);
        return owner;
    }

    /**
     * A 30-minute phone meeting type bookable from now on, for the signed-in owner.
     */
    public void createMeetingType(String slug) {
        assertThat(post(
                "/me/meeting-types",
                Map.of(
                        "name",
                        "IT " + slug,
                        "slug",
                        slug,
                        "durationMinutes",
                        "30",
                        "minNoticeMinutes",
                        "0",
                        "horizonDays",
                        "30",
                        "locationType",
                        "PHONE"
                )
        )
            .statusCode()
        )
            .isEqualTo(200);
    }
}
