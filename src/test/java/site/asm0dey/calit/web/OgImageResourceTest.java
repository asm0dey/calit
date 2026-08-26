package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
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

    /** A row with a blank name -- reachable because AdminResource never rejects one server-side. */
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

    /** An owner who HAD a real name and a public type -- then was switched off (calit-h8mb). */
    private static void seedDisabledOwner(String username, String slug) {
        QuarkusTransaction.requiringNew().run(() -> {
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
            u.enabled = false; // managed entity -> flushed on commit
        });
    }

    @Test
    void servesAPngForAMeetingType() {
        seed("card-public", false);
        byte[] body = given().when()
                .get("/og/admin/card-public.png")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("Cache-Control", "public, max-age=3600")
                .extract()
                .asByteArray();
        assertArrayEquals(PNG_MAGIC, Arrays.copyOf(body, 4));
    }

    @Test
    void productAndOwnerCardsRender() {
        seed("card-owner", false);
        assertArrayEquals(
                PNG_MAGIC,
                Arrays.copyOf(
                        given().when()
                                .get("/og.png")
                                .then()
                                .statusCode(200)
                                .extract()
                                .asByteArray(),
                        4));
        assertArrayEquals(
                PNG_MAGIC,
                Arrays.copyOf(
                        given().when()
                                .get("/og/admin.png")
                                .then()
                                .statusCode(200)
                                .extract()
                                .asByteArray(),
                        4));
    }

    @Test
    void etagIsStableAndHonoursIfNoneMatch() {
        seed("card-etag", false);
        String etag = given().when()
                .get("/og/admin/card-etag.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(etag);
        assertEquals(
                etag,
                given().when().get("/og/admin/card-etag.png").then().extract().header("ETag"),
                "same inputs must produce the same ETag");
        given().header("If-None-Match", etag)
                .when()
                .get("/og/admin/card-etag.png")
                .then()
                .statusCode(304);
    }

    @Test
    void renamingTheMeetingTypeChangesTheEtag() {
        seed("card-rename", false);
        String before = given().when()
                .get("/og/admin/card-rename.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(before);

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

        String after = given().when()
                .get("/og/admin/card-rename.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(after);
        assertNotEquals(before, after, "renaming the meeting type must change the ETag (no-invalidation design)");
    }

    @Test
    void disabledOwnerCardsFallBackToTheProductCard() {
        seedDisabledOwner("disabled-og-owner", "card-disabled");
        byte[] product =
                given().when().get("/og.png").then().statusCode(200).extract().asByteArray();

        // Same enumeration-oracle guard as PublicResource.resolveOwner (calit-h8mb): the real
        // booking page 404s a disabled account, so this endpoint must be just as blind to it --
        // not render the real owner name via the /{user}.png card.
        byte[] ownerCard = given().when()
                .get("/og/disabled-og-owner.png")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertArrayEquals(product, ownerCard, "a disabled owner's card must not reveal their name");

        byte[] typeCard = given().when()
                .get("/og/disabled-og-owner/card-disabled.png")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertArrayEquals(product, typeCard, "a disabled owner's meeting-type card must not reveal name/type/duration");
    }

    @Test
    void inactiveTypeServesTheProductCard() {
        seedInactive("card-inactive");
        byte[] inactive = given().when()
                .get("/og/admin/card-inactive.png")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertArrayEquals(product, inactive, "an inactive type must not be named in its card");
    }

    @Test
    void changingTheDurationOrLocationChangesTheEtag() {
        seed("card-etag-axes", false);
        String before = given().when()
                .get("/og/admin/card-etag-axes.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(before);

        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.findBySlug(1L, "card-etag-axes");
            t.durationMinutes = 45;
        });
        String afterDuration = given().when()
                .get("/og/admin/card-etag-axes.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(afterDuration);
        assertNotEquals(before, afterDuration, "changing the allowed duration must change the ETag");

        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.findBySlug(1L, "card-etag-axes");
            t.locationType = LocationType.PHONE;
        });
        String afterLocation = given().when()
                .get("/og/admin/card-etag-axes.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(afterLocation);
        assertNotEquals(afterDuration, afterLocation, "changing the location kind must change the ETag");
    }

    @Test
    void secretTypeServesTheProductCard() {
        seed("card-secret", true);
        byte[] secret = given().when()
                .get("/og/admin/card-secret.png")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertArrayEquals(product, secret, "a secret type must not be named in its card");
    }

    @Test
    void blankMeetingTypeNameServesTheProductCardNotA500() {
        // AdminResource.createMeetingType has no server-side blank check (the HTML "required"
        // attribute is client-side only), and Slugs.uniqueMeetingTypeSlug turns a blank base into
        // "meeting", so this row is addressable. Before the fix, fitHeadline's empty run list made
        // render() throw NoSuchElementException here -- a 500, not a graceful fallback.
        seedBlankName("card-blank-name");
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        byte[] blank = given().when()
                .get("/og/admin/card-blank-name.png")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertArrayEquals(product, blank, "a blank meeting-type name must degrade to the product card, not crash");
    }

    @Test
    void unknownTargetsFallBackToTheProductCardNotA404() {
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertArrayEquals(
                product,
                given().when()
                        .get("/og/nosuchuser.png")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asByteArray());
        assertArrayEquals(
                product,
                given().when()
                        .get("/og/admin/nosuchslug.png")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asByteArray());
    }
}
