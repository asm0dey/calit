package site.asm0dey.calit.google;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

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
        a.ownerId = 3001L;
        a.refreshToken = "ra";
        a.googleSub = "sub-scope-3001";
        a.persist();
        GoogleCredential b = new GoogleCredential();
        b.ownerId = 3002L;
        b.refreshToken = "rb";
        b.googleSub = "sub-scope-3002";
        b.persist();

        assertThat(GoogleCredential.forOwner(3001L).refreshToken).isEqualTo("ra");
        assertThat(GoogleCredential.forOwner(3002L).refreshToken).isEqualTo("rb");
        assertThat(GoogleCredential.forOwner(9999L)).isNull();
    }

    @Test
    @TestTransaction
    void calendarReadAndWriteTargetsAreScoped() {
        TestOwners.ensure(em, 3001L);
        TestOwners.ensure(em, 3002L);
        GoogleCredential credA = new GoogleCredential();
        credA.ownerId = 3001L;
        credA.refreshToken = "ra-cal";
        credA.googleSub = "sub-cal-3001";
        credA.persist();
        GoogleCredential credB = new GoogleCredential();
        credB.ownerId = 3002L;
        credB.refreshToken = "rb-cal";
        credB.googleSub = "sub-cal-3002";
        credB.persist();
        GoogleCalendar a = new GoogleCalendar();
        a.ownerId = 3001L;
        a.googleCalendarId = "cal-a";
        a.summary = "A";
        a.readForBusy = true;
        a.writeTarget = true;
        a.googleCredentialId = credA.id;
        a.persist();
        GoogleCalendar b = new GoogleCalendar();
        b.ownerId = 3002L;
        b.googleCalendarId = "cal-b";
        b.summary = "B";
        b.readForBusy = true;
        b.writeTarget = true;
        b.googleCredentialId = credB.id;
        b.persist();

        assertThat(GoogleCalendar.readForBusy(3001L)).hasSize(1);
        assertThat(GoogleCalendar.writeTarget(3001L).googleCalendarId).isEqualTo("cal-a");
        assertThat(GoogleCalendar.writeTarget(3002L).googleCalendarId).isEqualTo("cal-b");
        assertThat(GoogleCalendar.findByGoogleId(3001L, "cal-a").googleCalendarId).isEqualTo("cal-a");
        assertThat(GoogleCalendar.findByGoogleId(3001L, "cal-b")).as("other owner's calendar id -> null").isNull();
    }
}
