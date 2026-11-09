package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.BookingInvite;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The Owner's "Generate single-use invite" action on the meeting-type detail page.
 */
@QuarkusTest
class AdminInviteTest {
    private static final Pattern URL = Pattern.compile("value=\"([^\"]*\\?invite=[^\"]+)\"");

    @Test
    void generateShowsWorkingUrl() {
        var id = seedType(1L);
        seedOwnerSettings();

        var html = given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .post("/me/meeting-types/" + id + "/invites")
            .then()
            .statusCode(200)
            .body(containsString("Your invite"))
            .body(containsString("/admin?invite="))
            .extract()
            .asString();

        var m = URL.matcher(html);
        assertThat(m.find()).isTrue();
        var url = m.group(1);
        assertThat(url).contains("/admin?invite=");
        var path = url.substring(url.indexOf("/admin?invite="));
        given()
            .when()
            .get(path)
            .then()
            .statusCode(200)
            .body(containsString("Detail Seed"))
            .body(not(containsString("no longer valid")));
    }

    @Test
    void generatedUrlIsNotOnDetailPageAfterwards() {
        var id = seedType(1L);
        var html = given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .post("/me/meeting-types/" + id + "/invites")
            .then()
            .statusCode(200)
            .extract()
            .asString();
        var m = URL.matcher(html);
        assertThat(m.find()).isTrue();
        var token = m.group(1).substring(m.group(1).indexOf("invite=") + "invite=".length());

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .body(not(containsString(token)));
    }

    @Test
    void cannotGenerateForAnotherOwnersType() {
        var otherId =
                QuarkusTransaction
            .requiringNew()
            .call(() -> MultiHostFixtures.enabledUser("invite-admin-other").id);
        var id = seedType(otherId);
        var before = inviteCount();

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .post("/me/meeting-types/" + id + "/invites")
            .then()
            .statusCode(404);
        assertThat(inviteCount()).isEqualTo(before);
    }

    @Test
    void requiresLogin() {
        var id = seedType(1L);
        var before = inviteCount();

        given()
            .redirects()
            .follow(false)
            .when()
            .post("/me/meeting-types/" + id + "/invites")
            .then()
            .statusCode(org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.is(302),
                    org.hamcrest.Matchers.is(401),
                    org.hamcrest.Matchers.is(403)
            ));
        assertThat(inviteCount()).isEqualTo(before);
    }

    @Test
    void detailPageHasGenerateFormWithCsrfField() {
        var id = seedType(1L);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .body(containsString("action=\"/me/meeting-types/" + id + "/invites\""))
            .body(containsString("Generate single-use invite"));
    }

    private static void seedOwnerSettings() {
        QuarkusTransaction.requiringNew().run(() -> {
            var s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();
        });
    }

    private static long inviteCount() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> BookingInvite.count());
    }

    private static Long seedType(Long ownerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var t = new MeetingType();
            t.ownerId = ownerId;
            t.name = "Detail Seed";
            t.slug = "invite-admin-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            return t.id;
        });
    }
}
