package site.asm0dey.calit.privacy;

import module java.base;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import site.asm0dey.calit.booking.CaptchaProviderConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The "Google is not configured" case: {@link PrivacyFacts} is swapped for one built with a blank
 * client id, the signal {@code LoginResource} also uses to decide Google is unconfigured.
 */
@QuarkusTest
class PrivacyPolicyGoogleUnconfiguredTest {
    @Inject
    CaptchaProviderConfig captcha;
    @Inject
    PrivacyConfig privacyConfig;

    @BeforeEach
    void googleUnconfigured() {
        QuarkusMock.installMockForType(
                new PrivacyFacts(Optional.of(" "), false, Optional.empty(), true, captcha, privacyConfig),
                PrivacyFacts.class
        );
    }

    @Test
    void googleSectionsAreAbsentWhenGoogleIsUnconfigured() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_LEGAL_PRIVACY"))
            .body(not(containsString("Limited Use disclosure")))
            .body(not(containsString("How Google user data is used")));
    }

    /**
     * CalDAV is always available, so the recipients list is never empty: the CalDAV server is listed even
     * with Google, OIDC, Turnstile and channels all off.
     */
    @Test
    void dataSharingAlwaysListsTheCaldavServer() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("This deployment sends data to:"))
            .body(containsString("The CalDAV server you connect, if you connect one"))
            .body(not(containsString("does not send your data to any third party")));
    }

    /**
     * The Mirror event disclosure is CalDAV-only here, never dropped: CalDAV write calendars exist without
     * Google.
     */
    @Test
    void mirrorEventDisclosureRendersForCaldavAlone() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_MIRROR_EVENT"))
            .body(containsString("host's calendar provider — the CalDAV server the host connected"))
            .body(containsString("On a CalDAV server they are written into the event description"))
            .body(containsString("the email addresses of the invitee, the host(s) and any active guests"))
            .body(not(containsString("On Google they are added as event attendees")))
            .body(not(containsString("Google Meet link")));
    }
}
