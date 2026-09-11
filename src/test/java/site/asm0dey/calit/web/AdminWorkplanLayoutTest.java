package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// #197: the per-day action buttons must sit on the same axis as the time frames they act on,
// not on the day-name row. Asserted as document order inside the Monday card: the [data-frames]
// box opens before the first action button. RestAssured can't run JS and there's no HTML parser
// on the test classpath, so document order is checked by substring position -- deliberately not
// by Tailwind class names, so a restyle doesn't falsely fail this.
@QuarkusTest
class AdminWorkplanLayoutTest {

    @Test
    void dayActionsRenderAfterTheFramesBox() {
        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/availability")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        var cardStart = body.indexOf("data-day=\"MONDAY\"");
        assertTrue(cardStart > 0, "Monday day card is rendered");
        var frames = body.indexOf("data-frames", cardStart);
        var actions = body.indexOf("data-copy-all=\"MONDAY\"", cardStart);
        assertTrue(frames > 0 && actions > 0, "Monday card has both a frames box and its actions");
        assertTrue(
                actions > frames,
                "day actions come after the frames box, so they share the frames' axis (frames at " + frames
                        + ", actions at " + actions + ")");
    }

    @Test
    void dayLabelDoesNotShareAJustifyBetweenRowWithTheActions() {
        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/availability")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        var cardStart = body.indexOf("data-day=\"MONDAY\"");
        var cardEnd = body.indexOf("data-day=\"TUESDAY\"");
        assertTrue(cardStart > 0 && cardEnd > cardStart, "Monday and Tuesday cards both rendered");
        var mondayCard = body.substring(cardStart, cardEnd);
        assertTrue(
                !mondayCard.contains("justify-between"),
                "no justify-between inside a day card: all slack would land between the label and the "
                        + "buttons, making the gap a function of viewport width instead of a spacing step");
    }
}
