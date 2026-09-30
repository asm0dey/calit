package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
                List.of(new CalendarSelectionService.Selection(cred.id, "write@example.com", "Write", false, true))
        );
        GoogleCalendar saved = GoogleCalendar.writeTarget(1L);
        assertThat(saved.googleCalendarId).isEqualTo("write@example.com");
        assertThat(saved.readForBusy).as("write target must be read for busy").isTrue();
    }

    @Test
    @Transactional
    void rejectsTwoWriteTargets() {
        GoogleCredential cred = cred(1L, "sub-A");
        cred.persist();
        var selections = List.of(
                new CalendarSelectionService.Selection(cred.id, "a", "A", true, true),
                new CalendarSelectionService.Selection(cred.id, "b", "B", true, true)
        );
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(1L, selections));
    }

    @Test
    @Transactional
    void rejectsForeignCredential() {
        // Seed a real user for owner 2 so the FK on google_credential.owner_id is satisfied.
        TestOwners.ensure(em, 2L);
        GoogleCredential other = cred(2L, "sub-X");
        other.persist();
        var selections = List.of(new CalendarSelectionService.Selection(other.id, "a", "A", true, false));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(1L, selections));
    }

    @Test
    @Transactional
    void persistsMeetCapabilityWhenUnsupported() {
        GoogleCredential cred = cred(1L, "sub-meet");
        cred.persist();
        service.save(
                1L,
                List.of(
                        new CalendarSelectionService.Selection(
                                cred.id,
                                "nomeet@example.com",
                                "No Meet",
                                false,
                                true,
                                false
                        )
                )
        );
        GoogleCalendar wt = GoogleCalendar.writeTarget(1L);
        assertThat(wt.supportsMeet).as("capability must persist from the selection").isFalse();
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner;
        c.refreshToken = "rt";
        c.googleSub = sub;
        return c;
    }
}
