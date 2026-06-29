package site.asm0dey.calit.user;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "admin")
                .when()
                .post("/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("If that account exists"));

        List<Mail> sent = mailbox.getMailsSentTo(ADMIN_EMAIL);
        assertEquals(1, sent.size(), "exactly one reset mail to the account address");
        Mail m = sent.getFirst();
        assertTrue(m.getSubject().toLowerCase().contains("reset"), "subject mentions reset");

        Matcher tok =
                Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)").matcher(m.getHtml());
        assertTrue(tok.find(), "body carries a reset-password link with a token");
        var token = tok.group(1);

        // The link renders the set-password form...
        given().when()
                .get("/reset-password?token=" + token)
                .then()
                .statusCode(200)
                .body(containsString("name=\"password\""));

        // ...and submitting it sets a new password (302 -> /login).
        given().redirects()
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
        given().redirects()
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
    void unknownUsernameSendsNoMailButLooksIdentical() {
        given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "no-such-user")
                .when()
                .post("/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("If that account exists")); // same response as a hit

        assertEquals(0, mailbox.getTotalMessagesSent(), "no account -> no mail (anti-enumeration)");
    }
}
