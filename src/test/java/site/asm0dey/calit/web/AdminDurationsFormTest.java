package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static site.asm0dey.calit.domain.MeetingTypeDuration.allowedDurations;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;

@QuarkusTest
class AdminDurationsFormTest {
    @Transactional
    Long seedType(String slug, int defaultMinutes) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Durations Seed";
        t.slug = slug;
        t.durationMinutes = defaultMinutes;
        t.persist();
        return t.id;
    }

    @Test
    void savingRowsAddsThemToTheAllowedSetAlongsideTheDefault() {
        var id = seedType("durations-add-" + System.nanoTime(), 60);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30", "120")
            .formParam("d.before", "10", "45")
            .formParam("d.after", "10", "45")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertThat(allowedDurations(t)).containsExactly(30, 60, 120);
    }

    @Test
    void reSubmittingWithABlankDurationRemovesThatLength() {
        var id = seedType("durations-remove-" + System.nanoTime(), 60);
        String cred = FormAuth.login();

        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30", "120")
            .formParam("d.before", "10", "45")
            .formParam("d.after", "10", "45")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);
        // Re-post the same rows, but the 120 row's duration is now blank -> that row is dropped.
        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30", "")
            .formParam("d.before", "10", "45")
            .formParam("d.after", "10", "45")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertThat(allowedDurations(t)).containsExactly(30, 60);
    }

    @Test
    void clearingTheDefaultsRowDropsItsBuffersButNeverTheDuration() {
        var id = seedType("durations-default-" + System.nanoTime(), 60);
        String cred = FormAuth.login();
        // Save a row for the default (60) carrying buffer overrides.
        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "60")
            .formParam("d.before", "5")
            .formParam("d.after", "5")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertThat(allowedDurations(t)).containsExactly(60);
        MeetingTypeDuration savedRow = MeetingTypeDuration.findRow(id, 60);
        assertThat(savedRow.bufferBeforeMinutes).isEqualTo(5);
        assertThat(savedRow.bufferAfterMinutes).isEqualTo(5);
        // Re-post the 60 row blank -> its buffer row is gone, but 60 stays in the allowed set
        // because the set membership comes from the type's own durationMinutes, not the row.
        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "")
            .formParam("d.before", "")
            .formParam("d.after", "")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        t = MeetingType.findById(id);
        assertThat(allowedDurations(t)).as("the default is never removable").isEqualTo(List.of(60));
        assertThat(MeetingTypeDuration.findRow(id, 60)).as("the buffer-override row is gone").isNull();
    }

    @Test
    void duplicateDurationRowsDoNotFailTheSave() {
        // Two rows sharing a duration -- the ordinary user mistake of typing an already-present
        // length into the trailing blank spare row (calit-mjof twin: without de-dup this used to
        // 500 on the Hibernate insert, since both rows share the same @IdClass key).
        var id = seedType("durations-dup-" + System.nanoTime(), 60);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30", "30")
            .formParam("d.before", "5", "45")
            .formParam("d.after", "5", "45")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertThat(allowedDurations(t)).containsExactly(30, 60);
        // First occurrence wins; the second (duplicate) row is dropped rather than persisted.
        MeetingTypeDuration saved = MeetingTypeDuration.findRow(id, 30);
        assertThat(saved.bufferBeforeMinutes).isEqualTo(5);
        assertThat(saved.bufferAfterMinutes).isEqualTo(5);
    }

    @Test
    void aZeroBufferSurvivesASaveRenderSaveRoundTrip() {
        // Qute's falsy rule treats a zero Integer as false, so `{#if row.before}` used to render a
        // stored 0 as an empty box; the next save would then read that blank back as null and
        // silently revert the deliberate "no buffer at all" setting (calit-mjof twin).
        var id = seedType("durations-zero-buffer-" + System.nanoTime(), 60);
        String cred = FormAuth.login();

        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30")
            .formParam("d.before", "0")
            .formParam("d.after", "0")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingTypeDuration savedRow = MeetingTypeDuration.findRow(id, 30);
        assertThat(savedRow.bufferBeforeMinutes).isZero();
        assertThat(savedRow.bufferAfterMinutes).isZero();
        // Render the detail page: the 0 must show up as an explicit "0" in the input's value
        // attribute, not an empty box.
        String body = given()
            .cookie("quarkus-credential", cred)
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();
        assertThat(body.split("name=\"d\\.before\" value=\"0\"", -1).length - 1)
            .as("the stored 0 buffer must render as an explicit 0, not a blank box")
            .isOne();
        assertThat(body.split("name=\"d\\.after\" value=\"0\"", -1).length - 1)
            .as("the stored 0 buffer must render as an explicit 0, not a blank box")
            .isOne();
        // Re-submitting the rendered (non-blank) value must persist 0 again, not revert to null.
        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30")
            .formParam("d.before", "0")
            .formParam("d.after", "0")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingTypeDuration resaved = MeetingTypeDuration.findRow(id, 30);
        assertThat(resaved.bufferBeforeMinutes).as("the 0 buffer must survive the round trip").isZero();
        assertThat(resaved.bufferAfterMinutes).as("the 0 buffer must survive the round trip").isZero();
    }

    @Test
    void detailPageRendersOneFilledRowPerMemberOfTheUnionPlusOneSpare() {
        var id = seedType("durations-render-" + System.nanoTime(), 60);
        String cred = FormAuth.login();

        given()
            .cookie("quarkus-credential", cred)
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30")
            .formParam("d.before", "10")
            .formParam("d.after", "10")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        String body = given()
            .cookie("quarkus-credential", cred)
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .body(containsString("Allowed durations"))
            .extract()
            .body()
            .asString();
        // One filled row per allowed length (30, 60) plus one blank spare row -> 3 duration inputs.
        // Counted over the rows container only: the <template> durations.js clones also holds a
        // d.duration input, but it is inert -- never rendered, never submitted -- so counting it
        // would assert about markup the owner can neither see nor post.
        var renderedRows = body.substring(body.indexOf("data-duration-list"), body.indexOf("data-duration-template"));
        var occurrences = renderedRows.split("name=\"d\\.duration\"", -1).length - 1;
        assertThat(occurrences).as("one row per union member (30, 60) plus one empty spare").isEqualTo(3);
        assertThat(body.split("name=\"d\\.duration\" value=\"30\"", -1).length - 1)
            .as("a filled row for the added length 30")
            .isOne();
        assertThat(body.split("name=\"d\\.duration\" value=\"60\"", -1).length - 1)
            .as("a filled row for the implicit default 60")
            .isOne();
    }

    /**
     * The add/remove buttons are a JavaScript enhancement, and RestAssured cannot run JavaScript —
     * so this pins the CONTRACT the script binds to. If a marker is renamed here without renaming it
     * in {@code durations.js}, the button silently stops working in the browser and every other test
     * still passes.
     */
    @Test
    void theEditorCarriesTheMarkersDurationsJsBindsTo() {
        var id = seedType("durations-markers-" + System.nanoTime(), 60);

        String html = given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThat(html)
            .as("form must be the script's scope root")
            .contains("data-durations")
            .as("rows container must be findable")
            .contains("data-duration-list")
            .as("template to clone must be present")
            .contains("data-duration-template")
            .as("add button must be present")
            .contains("data-add-duration")
            .as("the script must actually be loaded")
            .contains("/durations.js");
    }

    /**
     * Progressive enhancement: the trailing blank row is the no-JS path. Without it an owner with
     * JavaScript disabled could never add a second length, because the add button does nothing for
     * them. It is deliberately NOT redundant with the button.
     */
    @Test
    void aBlankRowIsRenderedSoTheNoJsPathCanStillAddALength() {
        var id = seedType("durations-nojs-" + System.nanoTime(), 60);

        String html = given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();
        // One row per allowed length (just the default here) plus the blank spare, and the
        // template's own row must not be counted as one of them.
        var rows = html.substring(html.indexOf("data-duration-list"), html.indexOf("data-duration-template"));
        assertThat(countOccurrences(rows, "data-duration-row")).as("default row + one blank spare").isEqualTo(2);
        assertThat(rows).as("the spare row's duration must be empty").contains("name=\"d.duration\" value=\"\"");
    }

    private static int countOccurrences(String haystack, String needle) {
        var n = 0;
        for (var i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * Moving the default is an edit to the meeting type, not to a row — the default length lives on
     * {@code meeting_type.duration_minutes} (ADR-0003) and is an implicit member of the set. The old
     * default keeps its row, so moving it never drops a length.
     */
    @Test
    void selectingARowAsDefaultMovesTheTypesOwnDuration() {
        var id = seedType("durations-default-" + System.nanoTime(), 60);
        // Rows submit as [30, 60, 120]; index 2 is the 120 row.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30", "60", "120")
            .formParam("d.before", "", "", "")
            .formParam("d.after", "", "", "")
            .formParam("defaultRow", "2")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertThat(t.durationMinutes).as("the chosen row becomes the type's default").isEqualTo(120);
        assertThat(allowedDurations(t)).as("the old default is still offered").isEqualTo(List.of(30, 60, 120));
    }

    /**
     * The radio carries a row INDEX rather than a duration precisely so a length typed into the
     * blank spare can be made default in the SAME save — at render time that row has no value for
     * the server to match on.
     */
    @Test
    void aLengthAddedInThisSaveCanBeMadeDefaultInTheSameSave() {
        var id = seedType("durations-newdefault-" + System.nanoTime(), 60);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "60", "90")
            .formParam("d.before", "", "")
            .formParam("d.after", "", "")
            .formParam("defaultRow", "1")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertThat(t.durationMinutes).as("a length added in this save can be the default").isEqualTo(90);
        assertThat(allowedDurations(t)).containsExactly(60, 90);
    }

    /**
     * A default pointing at a blank or out-of-range row leaves the default where it was.
     */
    @Test
    void anEmptyOrOutOfRangeDefaultRowLeavesTheDefaultAlone() {
        var id = seedType("durations-baddefault-" + System.nanoTime(), 60);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30", "")
            .formParam("d.before", "", "")
            .formParam("d.after", "", "")
            // the blank spare
            .formParam(
                    // the blank spare
            "defaultRow",
                    "1")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        assertThat(((MeetingType) MeetingType.findById(id)).durationMinutes).isEqualTo(60);

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("d.duration", "30")
            .formParam("d.before", "")
            .formParam("d.after", "")
            // past the end
            .formParam(
                    // past the end
            "defaultRow",
                    "99")
            .when()
            .post("/me/meeting-types/" + id + "/durations")
            .then()
            .statusCode(200);

        assertThat(((MeetingType) MeetingType.findById(id)).durationMinutes).isEqualTo(60);
    }
}
