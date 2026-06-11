package com.calit.web;

import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
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
        OwnerSettings.delete("ownerId = ?1", 1L);
        MeetingType.delete("slug", "guard-type");
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Guard Type"; t.slug = "guard-type"; t.durationMinutes = 30;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
    }

    /** Leave a valid OwnerSettings behind so test ordering can't strand other suites. */
    @AfterEach
    @Transactional
    void restoreSettings() {
        if (OwnerSettings.forOwner(1L) == null) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
            s.persist();
        }
    }

    @Test
    void bookPageShowsFriendlyMessageWhenSettingsMissing() {
        removeSettingsAndSeedType();
        given().when().get("/book/guard-type")
            .then().statusCode(200)
                .body(containsString("isn't ready yet"));
    }
}
