package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.SelectedCalendar;
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
                Set.of(cred.id),
                List.of(new CalendarSelectionService.Selection(cred.id, "write@example.com", "Write", false, true))
        );
        SelectedCalendar saved = SelectedCalendar.writeTargetOf(1L);
        assertThat(saved.remoteId).isEqualTo("write@example.com");
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
        var covered = Set.of(cred.id);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(1L, covered, selections));
    }

    @Test
    @Transactional
    void rejectsForeignCredential() {
        // Seed a real user for owner 2 so the FK on google_credential.owner_id is satisfied.
        TestOwners.ensure(em, 2L);
        GoogleCredential other = cred(2L, "sub-X");
        other.persist();
        var selections = List.of(new CalendarSelectionService.Selection(other.id, "a", "A", true, false));
        var covered = Set.of(other.id);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(1L, covered, selections));
    }

    @Test
    @Transactional
    void persistsMeetCapabilityWhenUnsupported() {
        GoogleCredential cred = cred(1L, "sub-meet");
        cred.persist();
        service.save(
                1L,
                Set.of(cred.id),
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
        SelectedCalendar wt = SelectedCalendar.writeTargetOf(1L);
        assertThat(wt.supportsMeet).as("capability must persist from the selection").isFalse();
    }

    @Test
    @Transactional
    void leavesCalendarsOfAccountsNotCoveredAlone() {
        GoogleCredential g = cred(1L, "sub-G");
        g.persist();
        CaldavAccount dav = new CaldavAccount();
        dav.ownerId = 1L;
        dav.serverUrl = "https://dav.example.com/";
        dav.username = "alice";
        dav.password = "secret";
        dav.principalUrl = "https://dav.example.com/p/";
        dav.homeUrl = "https://dav.example.com/cal/";
        dav.persist();
        SelectedCalendar old = new SelectedCalendar();
        old.ownerId = 1L;
        old.accountId = dav.id;
        old.remoteId = "https://dav.example.com/cal/work/";
        old.summary = "Work";
        old.readForBusy = true;
        old.writeTarget = true;
        old.persist();

        service.save(1L, Set.of(g.id), List.of(new CalendarSelectionService.Selection(g.id, "g", "G", true, true)));
        // bulk statements bypass the persistence context; read what the database holds
        em.clear();
        // the CalDAV calendar survives, demoted so the owner still has exactly one write target
        assertThat(SelectedCalendar.<SelectedCalendar>list("ownerId", 1L))
            .extracting(c -> c.remoteId + ":" + c.readForBusy + ":" + c.writeTarget)
            .containsExactlyInAnyOrder("https://dav.example.com/cal/work/:true:false", "g:true:true");
    }

    @Test
    @Transactional
    void rejectsSelectionOutsideTheCoveredAccounts() {
        GoogleCredential a = cred(1L, "sub-A");
        a.persist();
        GoogleCredential b = cred(1L, "sub-B");
        b.persist();
        var selections = List.of(new CalendarSelectionService.Selection(b.id, "b", "B", true, false));
        var covered = Set.of(a.id);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.save(1L, covered, selections));
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner;
        c.refreshToken = "rt";
        c.googleSub = sub;
        return c;
    }
}
