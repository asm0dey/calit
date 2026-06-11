package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MeetingTypeExtensionsTest {

    @Test
    @TestTransaction
    void persistsWithPlan1bDefaults() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Defaults";
        t.slug = "mt-ext-defaults";
        t.durationMinutes = 30;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug(1L, "mt-ext-defaults");
        assertEquals(0, loaded.minNoticeMinutes);
        assertEquals(60, loaded.horizonDays);
        assertEquals(MeetingType.LocationType.GOOGLE_MEET, loaded.locationType);
        assertNull(loaded.locationDetail);
        assertFalse(loaded.requiresApproval);
    }

    @Test
    @TestTransaction
    void roundTripsNonDefaultLocationAndApproval() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Phone Approval";
        t.slug = "mt-ext-phone";
        t.durationMinutes = 30;
        t.minNoticeMinutes = 120;
        t.horizonDays = 14;
        t.locationType = MeetingType.LocationType.PHONE;
        t.locationDetail = "+31 6 1234 5678";
        t.requiresApproval = true;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug(1L, "mt-ext-phone");
        assertEquals(120, loaded.minNoticeMinutes);
        assertEquals(14, loaded.horizonDays);
        assertEquals(MeetingType.LocationType.PHONE, loaded.locationType);
        assertEquals("+31 6 1234 5678", loaded.locationDetail);
        assertTrue(loaded.requiresApproval);
    }
}
