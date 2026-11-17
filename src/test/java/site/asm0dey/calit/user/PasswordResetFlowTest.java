package site.asm0dey.calit.user;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Exercises the forgot-password email end to end via the built-in MockMailbox (no GreenMail
 * needed — Quarkus mocks the mailer in %test). Proves the reset mail is sent to the account's
 * stored address, its link works, and the request side never discloses account existence.
 */
@QuarkusTest
class PasswordResetFlowTest {
    private static final String ADMIN_EMAIL = "admin@example.com";
    @Inject
    MockMailbox mailbox;
    @Inject
    PasswordResetService resetService;

    @BeforeEach
    void init() {
        mailbox.clear();
        // Baseline admin is always id 1, but has no OwnerSettings — seed an address to mail.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Admin";
            s.ownerEmail = ADMIN_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.persist();
        });
    }

    @Test
    void forgotPasswordEmailsAWorkingResetLink() {
        given()
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "admin")
            .when()
            .post("/forgot-password")
            .then()
            .statusCode(200)
            .body(containsString("If that account exists"));

        List<Mail> sent = mailbox.getMailsSentTo(ADMIN_EMAIL);
        assertThat(sent).as("exactly one reset mail to the account address").hasSize(1);
        Mail m = sent.getFirst();
        assertThat(m.getSubject().toLowerCase()).as("subject mentions reset").contains("reset");

        Matcher tok = Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)").matcher(m.getHtml());
        assertThat(tok.find()).as("body carries a reset-password link with a token").isTrue();
        var token = tok.group(1);
        // The link renders the set-password form...
        given()
            .when()
            .get("/reset-password?token=" + token)
            .then()
            .statusCode(200)
            .body(containsString("name=\"password\""));
        // ...and submitting it sets a new password (302 -> /login).
        given()
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("token", token)
            .formParam("password", "brand-new-pw-99")
            .when()
            .post("/reset-password")
            .then()
            .statusCode(302)
            .header("Location", containsString("/login"));
        // Proof the password actually changed: the new credentials authenticate.
        given()
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "admin")
            .formParam("j_password", "brand-new-pw-99")
            .when()
            .post("/j_security_check")
            .then()
            .statusCode(302);
    }

    @Test
    void usernameForNamesOnlyAStillValidTokenWithoutConsumingIt() {
        var now = Instant.now();
        var valid = resetService.issue(1L, now);
        var expired = resetService.issue(1L, now.minus(Duration.ofHours(1)), Duration.ofMinutes(1));
        assertThat(resetService.usernameFor(valid, now)).isEqualTo("admin");
        assertThat(resetService.usernameFor(valid, now)).as("lookup does not consume").isEqualTo("admin");
        assertThat(resetService.usernameFor(expired, now)).isNull();
        assertThat(resetService.usernameFor("no-such-token", now)).isNull();
        assertThat(resetService.usernameFor(" ", now)).isNull();
        assertThat(resetService.usernameFor(null, now)).isNull();
    }

    @Test
    void blankPasswordRetryKeepsTheUsernameAndAnUnknownTokenShowsNone() {
        var token = resetService.issue(1L, Instant.now());
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("token", token)
            .formParam("password", "")
            .when()
            .post("/reset-password")
            .then()
            .statusCode(400)
            .body(containsString("value=\"admin\" readonly autocomplete=\"username\""));
        given()
            .when()
            .get("/reset-password?token=no-such-token")
            .then()
            .statusCode(200)
            .body(containsString("name=\"password\""))
            .body(not(containsString("autocomplete=\"username\"")));
    }

    @Test
    void unknownUsernameSendsNoMailButLooksIdentical() {
        given()
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "no-such-user")
            .when()
            .post("/forgot-password")
            .then()
            .statusCode(200)
            // same response as a hit
            .body(containsString("If that account exists"));

        assertThat(mailbox.getTotalMessagesSent()).as("no account -> no mail (anti-enumeration)").isZero();
    }
}
