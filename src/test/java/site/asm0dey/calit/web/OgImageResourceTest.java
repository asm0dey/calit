package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class OgImageResourceTest {
    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private static void seed(String slug, boolean secret) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coffee chat";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.secret = secret;
            t.persist();
        });
    }

    /**
     * A row with a blank name -- reachable because AdminResource never rejects one server-side.
     */
    private static void seedBlankName(String slug) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.persist();
        });
    }

    private static void seedInactive(String slug) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coffee chat";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.active = false;
            t.persist();
        });
    }

    /**
     * An owner who HAD a real name and a public type -- then was switched off (calit-h8mb).
     */
    private static void seedDisabledOwner(String username, String slug) {
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                AppUser u = AppUser.create(username, "x", false);
                u.persist();
                OwnerSettings s = new OwnerSettings();
                s.ownerId = u.id;
                s.ownerName = "Gone Person";
                s.ownerEmail = "gone@example.com";
                s.timezone = "UTC";
                s.persist();
                MeetingType t = new MeetingType();
                t.ownerId = u.id;
                t.name = "Intro";
                t.slug = slug;
                t.durationMinutes = 30;
                t.locationType = LocationType.GOOGLE_MEET;
                t.persist();
                // managed entity -> flushed on commit
                u.enabled = false;
            });
    }

    @Test
    void servesAPngForAMeetingType() {
        seed("card-public", false);
        byte[] body = given()
            .when()
            .get("/og/admin/card-public.png")
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("Cache-Control", "public, max-age=3600")
            .extract()
            .asByteArray();
        assertThat(Arrays.copyOf(body, 4)).containsExactly(PNG_MAGIC);
    }

    @Test
    void productAndOwnerCardsRender() {
        seed("card-owner", false);
        assertThat(Arrays.copyOf(given().when().get("/og.png").then().statusCode(200).extract().asByteArray(), 4))
            .containsExactly(PNG_MAGIC);
        assertThat(Arrays.copyOf(given()
            .when()
            .get("/og/admin.png")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray(), 4))
            .containsExactly(PNG_MAGIC);
    }

    @Test
    void etagIsStableAndHonoursIfNoneMatch() {
        seed("card-etag", false);
        String etag = given()
            .when()
            .get("/og/admin/card-etag.png")
            .then()
            .statusCode(200)
            .extract()
            .header("ETag");
        assertThat(etag).isNotNull();
        assertThat(given().when().get("/og/admin/card-etag.png").then().extract().header("ETag"))
            .as("same inputs must produce the same ETag")
            .isEqualTo(etag);
        given().header("If-None-Match", etag).when().get("/og/admin/card-etag.png").then().statusCode(304);
    }

    @Test
    void renamingTheMeetingTypeChangesTheEtag() {
        seed("card-rename", false);
        String before = given()
            .when()
            .get("/og/admin/card-rename.png")
            .then()
            .statusCode(200)
            .extract()
            .header("ETag");
        assertThat(before).isNotNull();
        // The whole no-invalidation design rests on the ETag being derived from the render
        // inputs (owner name, type name, allowed durations, location kind) rather than from the
        // URL or a stored version counter. Renaming the type must change nothing else about the
        // request — same route, same owner, same slug — so a stable ETag here would mean the
        // hash is not actually input-sensitive, and a proxy/CDN would keep serving a stale card
        // under the old name forever.
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.findBySlug(1L, "card-rename");
            t.name = "Renamed chat";
        });

        String after = given()
            .when()
            .get("/og/admin/card-rename.png")
            .then()
            .statusCode(200)
            .extract()
            .header("ETag");
        assertThat(after).isNotNull();
        assertThat(after).as("renaming the meeting type must change the ETag (no-invalidation design)").isNotEqualTo(
                before
        );
    }

    @Test
    void disabledOwnerCardsFallBackToTheProductCard() {
        seedDisabledOwner("disabled-og-owner", "card-disabled");
        byte[] product = given().when().get("/og.png").then().statusCode(200).extract().asByteArray();
        // Same enumeration-oracle guard as PublicResource.resolveOwner (calit-h8mb): the real
        // booking page 404s a disabled account, so this endpoint must be just as blind to it --
        // not render the real owner name via the /{user}.png card.
        byte[] ownerCard =
                given().when().get("/og/disabled-og-owner.png").then().statusCode(200).extract().asByteArray();
        assertThat(ownerCard).as("a disabled owner's card must not reveal their name").containsExactly(product);

        byte[] typeCard =
                given()
            .when()
            .get("/og/disabled-og-owner/card-disabled.png")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
        assertThat(typeCard)
            .as("a disabled owner's meeting-type card must not reveal name/type/duration")
            .containsExactly(product);
    }

    @Test
    void inactiveTypeServesTheProductCard() {
        seedInactive("card-inactive");
        byte[] inactive =
                given().when().get("/og/admin/card-inactive.png").then().statusCode(200).extract().asByteArray();
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertThat(inactive).as("an inactive type must not be named in its card").containsExactly(product);
    }

    @Test
    void changingTheDurationOrLocationChangesTheEtag() {
        seed("card-etag-axes", false);
        String before =
                given().when().get("/og/admin/card-etag-axes.png").then().statusCode(200).extract().header("ETag");
        assertThat(before).isNotNull();

        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.findBySlug(1L, "card-etag-axes");
            t.durationMinutes = 45;
        });
        String afterDuration =
                given().when().get("/og/admin/card-etag-axes.png").then().statusCode(200).extract().header("ETag");
        assertThat(afterDuration).isNotNull();
        assertThat(afterDuration).as("changing the allowed duration must change the ETag").isNotEqualTo(before);

        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.findBySlug(1L, "card-etag-axes");
            t.locationType = LocationType.PHONE;
        });
        String afterLocation =
                given().when().get("/og/admin/card-etag-axes.png").then().statusCode(200).extract().header("ETag");
        assertThat(afterLocation).isNotNull();
        assertThat(afterLocation).as("changing the location kind must change the ETag").isNotEqualTo(afterDuration);
    }

    @Test
    void secretTypeServesTheProductCard() {
        seed("card-secret", true);
        byte[] secret = given()
            .when()
            .get("/og/admin/card-secret.png")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertThat(secret).as("a secret type must not be named in its card").containsExactly(product);
    }

    @Test
    void blankMeetingTypeNameServesTheProductCardNotA500() {
        // AdminResource.createMeetingType has no server-side blank check (the HTML "required"
        // attribute is client-side only), and Slugs.uniqueMeetingTypeSlug turns a blank base into
        // "meeting", so this row is addressable. Before the fix, fitHeadline's empty run list made
        // render() throw NoSuchElementException here -- a 500, not a graceful fallback.
        seedBlankName("card-blank-name");
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        byte[] blank =
                given().when().get("/og/admin/card-blank-name.png").then().statusCode(200).extract().asByteArray();
        assertThat(blank).as("a blank meeting-type name must degrade to the product card, not crash").containsExactly(
                product
        );
    }

    @Test
    void mixedScriptNameRendersInsteadOfFallingBackToTheProductCard() {
        // "Coffee" (Rubik) and "Σ" (falls back to Noto Sans, per TextRunsTest.greekFallsBackToNoto)
        // force TextRuns.split to flush more than one run for a single string -- the multi-run path
        // only a few unit tests exercise directly. Routing it through the real HTTP endpoint proves
        // the split survives end to end: a card is rendered, not a silent fallback to the product.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coffee Σ";
            t.slug = "card-mixed-script";
            t.durationMinutes = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.persist();
        });
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        byte[] mixed =
                given()
            .when()
            .get("/og/admin/card-mixed-script.png")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
        assertThat(Arrays.toString(mixed))
            .as("a mixed-script name must actually render, not silently fall back to the product card")
            .isNotEqualTo(Arrays.toString(product));
    }

    @Test
    void locationRendersAllFourKinds() {
        // Only GOOGLE_MEET (seed()) and PHONE (changingTheDurationOrLocationChangesTheEtag) were
        // ever exercised before -- IN_PERSON and CUSTOM are real, reachable switch arms.
        MeetingType t = new MeetingType();
        t.locationType = LocationType.GOOGLE_MEET;
        assertThat(OgImageResource.location(t)).isEqualTo("Google Meet");
        t.locationType = LocationType.PHONE;
        assertThat(OgImageResource.location(t)).isEqualTo("Phone");
        t.locationType = LocationType.IN_PERSON;
        assertThat(OgImageResource.location(t)).isEqualTo("In person");
        t.locationType = LocationType.CUSTOM;
        assertThat(OgImageResource.location(t)).isEqualTo("Online");
    }

    @Test
    void metaListsMultipleDurationsSortedWithASeparator() {
        MeetingType type = new MeetingType();
        QuarkusTransaction.requiringNew().run(() -> {
            type.ownerId = 1L;
            type.name = "Consult";
            type.slug = "card-meta-multi";
            type.durationMinutes = 30;
            type.locationType = LocationType.GOOGLE_MEET;
            type.persist();
            MeetingTypeDuration extra = new MeetingTypeDuration();
            extra.meetingTypeId = type.id;
            extra.durationMinutes = 60;
            extra.persist();
        });
        assertThat(OgImageResource.meta(type))
            .as("durations must be ascending with a separator")
            .isEqualTo("30 · 60 min · Google Meet");
    }

    @Test
    void metaWithASingleDurationHasNoSeparator() {
        MeetingType type = new MeetingType();
        QuarkusTransaction.requiringNew().run(() -> {
            type.ownerId = 1L;
            type.name = "Consult";
            type.slug = "card-meta-single";
            type.durationMinutes = 45;
            type.locationType = LocationType.PHONE;
            type.persist();
        });
        assertThat(OgImageResource.meta(type))
            .as("a single allowed duration must not carry a separator")
            .isEqualTo("45 min · Phone");
    }

    @Test
    void unknownTargetsFallBackToTheProductCardNotA404() {
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertThat(given().when().get("/og/nosuchuser.png").then().statusCode(200).extract().asByteArray())
            .containsExactly(product);
        assertThat(given().when().get("/og/admin/nosuchslug.png").then().statusCode(200).extract().asByteArray())
            .containsExactly(product);
    }
}
