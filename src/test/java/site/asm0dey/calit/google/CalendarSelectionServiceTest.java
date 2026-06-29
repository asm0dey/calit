package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class CalendarSelectionServiceTest {

    @Inject
    CalendarSelectionService service;

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void writeTargetIsForcedReadForBusy() {
        GoogleCredential cred = cred(1L, "sub-A");
        cred.persist();
        service.save(
                1L,
                List.of(new CalendarSelectionService.Selection(cred.id, "write@example.com", "Write", false, true)));
        GoogleCalendar saved = GoogleCalendar.writeTarget(1L);
        assertEquals("write@example.com", saved.googleCalendarId);
        assertTrue(saved.readForBusy, "write target must be read for busy");
    }

    @Test
    @Transactional
    void rejectsTwoWriteTargets() {
        GoogleCredential cred = cred(1L, "sub-A");
        cred.persist();
        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(
                        1L,
                        List.of(
                                new CalendarSelectionService.Selection(cred.id, "a", "A", true, true),
                                new CalendarSelectionService.Selection(cred.id, "b", "B", true, true))));
    }

    @Test
    @Transactional
    void rejectsForeignCredential() {
        // Seed a real user for owner 2 so the FK on google_credential.owner_id is satisfied.
        TestOwners.ensure(em, 2L);
        GoogleCredential other = cred(2L, "sub-X");
        other.persist();
        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(
                        1L, List.of(new CalendarSelectionService.Selection(other.id, "a", "A", true, false))));
    }

    @Test
    @Transactional
    void persistsMeetCapabilityAndBlocksMeetWhenUnsupported() {
        GoogleCredential cred = cred(1L, "sub-meet");
        cred.persist();
        service.save(
                1L,
                List.of(new CalendarSelectionService.Selection(
                        cred.id, "nomeet@example.com", "No Meet", false, true, false)));
        GoogleCalendar wt = GoogleCalendar.writeTarget(1L);
        assertFalse(wt.supportsMeet, "capability must persist from the selection");
        assertTrue(GoogleCalendar.writeTargetBlocksMeet(1L), "a non-Meet write target blocks Meet");
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner;
        c.refreshToken = "rt";
        c.googleSub = sub;
        return c;
    }
}
