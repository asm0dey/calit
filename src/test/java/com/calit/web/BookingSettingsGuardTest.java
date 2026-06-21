package com.calit.web;

import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class BookingSettingsGuardTest {

    @Transactional
    void removeSettingsAndSeedType() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) { owner = AppUser.create("bob", "x", false); owner.persistAndFlush(); } // create() builds but does not persist; flush to assign id
        Long ownerId = owner.id;
        // Drop bob's settings so the booking page hits the notReady() guard — but bob the
        // AppUser must still exist so resolveOwner({user}) binds instead of 404ing first.
        OwnerSettings.delete("ownerId = ?1", ownerId);
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "guard-type");
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Guard Type"; t.slug = "guard-type"; t.durationMinutes = 30;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
    }

    /** Leave a valid OwnerSettings behind so test ordering can't strand other suites. */
    @AfterEach
    @Transactional
    void restoreSettings() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner != null && OwnerSettings.forOwner(owner.id) == null) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = owner.id;
            s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
            s.persist();
        }
    }

    @Test
    void bookPageShowsFriendlyMessageWhenSettingsMissing() {
        removeSettingsAndSeedType();
        // "isn't ready yet" renders as "isn&#39;t ready yet" in escaped HTML — match the stable part.
        given().when().get("/bob/guard-type")
            .then().statusCode(200)
                .body(containsString("booking page"))
                .body(containsString("ready yet"));
    }
}
