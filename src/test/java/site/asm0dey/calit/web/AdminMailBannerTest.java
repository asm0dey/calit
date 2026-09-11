package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailHealth;

// #195: a deployment with broken or unconfigured SMTP must say so on the dashboard. %test runs
// quarkus.mailer.mock=true, so MailHealth reports UNCONFIGURED and the banner is the default here.
@QuarkusTest
class AdminMailBannerTest {

    // Spied, not mocked: every test but the last wants the real UNCONFIGURED status and the real
    // dead-letter query. Only the OK-with-dead-letters case stubs status(), because no %test
    // configuration can make the mocked mailer report OK.
    @InjectSpy
    MailHealth mailHealth;

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
        // startsWith, not contains: the count is the first token of the message, so a wrong count
        // that merely happens to contain a 1 (11, 21, "1 hour") cannot satisfy this.
        assertTrue(
                m.group(1).trim().startsWith("1 message(s)"),
                "dead-letter line leads with the count, got: " + m.group(1));
    }

    // The state the branch originally dropped: SMTP got fixed, the probe reports OK, but the rows
    // nobody ever received are still sitting in the outbox. That is the one thing an operator can
    // still act on (reach out by hand), and it used to vanish with the rest of the banner.
    // %test always reports UNCONFIGURED (mocked mailer), so OK has to be stubbed onto the seam.
    @Test
    void deadLettersKeepWarningAfterMailStartsWorkingAgain() {
        doReturn(new MailHealth.Status(MailHealth.State.OK, 3L))
                .when(mailHealth)
                .status();

        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-banner"))
                .body(containsString("Email is working now"))
                // Neither outage headline may appear: mail is up, this is history.
                .body(not(containsString("Email is not configured")))
                .body(not(containsString("Email is not being delivered")))
                // History, not an outage -- the banner must not shout in error red.
                .body(not(containsString("alert alert-error")))
                .body(containsString("alert alert-warning"))
                .extract()
                .body()
                .asString();

        var m = java.util.regex.Pattern.compile("data-mail-dead-letters[^>]*>([^<]*)</p>")
                .matcher(body);
        assertTrue(m.find(), "dead-letter line rendered even though mail is working");
        assertTrue(
                m.group(1).trim().startsWith("3 message(s)"),
                "dead-letter line leads with the count, got: " + m.group(1));
    }
}
