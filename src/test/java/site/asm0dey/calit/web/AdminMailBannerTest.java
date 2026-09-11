package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;

// #195: a deployment with broken or unconfigured SMTP must say so on the dashboard. %test runs
// quarkus.mailer.mock=true, so MailHealth reports UNCONFIGURED and the banner is the default here.
@QuarkusTest
class AdminMailBannerTest {

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> EmailOutbox.deleteAll());
    }

    @Test
    void unconfiguredMailWarnsOnTheDashboard() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-banner"))
                .body(containsString("Email is not configured"));
    }

    @Test
    void deadLetterCountIsShownWhenThereAreDeadLetters() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(not(containsString("data-mail-dead-letters")));

        QuarkusTransaction.requiringNew().run(() -> {
            var r = new EmailOutbox();
            r.recipient = "dead@example.com";
            r.subject = "Subj";
            r.htmlBody = "<p>hi</p>";
            r.attempts = 10;
            r.nextAttemptAt = null; // given up on
            r.createdAt = Instant.now();
            r.persist();
        });

        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();
        var m = java.util.regex.Pattern.compile("data-mail-dead-letters[^>]*>([^<]*)</p>")
                .matcher(body);
        assertTrue(m.find(), "dead-letter line rendered");
        assertTrue(m.group(1).contains("1"), "dead-letter line carries the count, got: " + m.group(1));
    }
}
