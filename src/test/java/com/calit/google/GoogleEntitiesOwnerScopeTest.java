package com.calit.google;

import com.calit.user.TestOwners;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class GoogleEntitiesOwnerScopeTest {

    @Inject
    EntityManager em;

    @Test
    @TestTransaction
    void credentialForOwnerIsScoped() {
        TestOwners.ensure(em, 3001L);
        TestOwners.ensure(em, 3002L);
        GoogleCredential a = new GoogleCredential();
        a.ownerId = 3001L; a.refreshToken = "ra"; a.googleSub = "sub-scope-3001"; a.persist();
        GoogleCredential b = new GoogleCredential();
        b.ownerId = 3002L; b.refreshToken = "rb"; b.googleSub = "sub-scope-3002"; b.persist();

        assertEquals("ra", GoogleCredential.forOwner(3001L).refreshToken);
        assertEquals("rb", GoogleCredential.forOwner(3002L).refreshToken);
        assertNull(GoogleCredential.forOwner(9999L));
    }

    @Test
    @TestTransaction
    void calendarReadAndWriteTargetsAreScoped() {
        TestOwners.ensure(em, 3001L);
        TestOwners.ensure(em, 3002L);
        GoogleCredential credA = new GoogleCredential();
        credA.ownerId = 3001L; credA.refreshToken = "ra-cal"; credA.googleSub = "sub-cal-3001"; credA.persist();
        GoogleCredential credB = new GoogleCredential();
        credB.ownerId = 3002L; credB.refreshToken = "rb-cal"; credB.googleSub = "sub-cal-3002"; credB.persist();
        GoogleCalendar a = new GoogleCalendar();
        a.ownerId = 3001L; a.googleCalendarId = "cal-a"; a.summary = "A";
        a.readForBusy = true; a.writeTarget = true; a.googleCredentialId = credA.id; a.persist();
        GoogleCalendar b = new GoogleCalendar();
        b.ownerId = 3002L; b.googleCalendarId = "cal-b"; b.summary = "B";
        b.readForBusy = true; b.writeTarget = true; b.googleCredentialId = credB.id; b.persist();

        assertEquals(1, GoogleCalendar.readForBusy(3001L).size());
        assertEquals("cal-a", GoogleCalendar.writeTarget(3001L).googleCalendarId);
        assertEquals("cal-b", GoogleCalendar.writeTarget(3002L).googleCalendarId);
        assertEquals("cal-a", GoogleCalendar.findByGoogleId(3001L, "cal-a").googleCalendarId);
        assertNull(GoogleCalendar.findByGoogleId(3001L, "cal-b"), "other owner's calendar id -> null");
    }
}
