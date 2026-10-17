package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.SelectedCalendar;

@QuarkusTest
class GoogleCalendarTest {
    @Test
    @TestTransaction
    void listsOnlyReadForBusyCalendars() {
        cal("work@example.com", "Work", true, false);
        cal("busy@example.com", "Side", true, false);
        cal("ignored@example.com", "Ignored", false, false);

        List<SelectedCalendar> readers = SelectedCalendar.busyCalendars(1L);

        assertThat(readers)
            .hasSize(2)
            .allMatch(c -> c.readForBusy);
    }

    @Test
    @TestTransaction
    void returnsTheSingleWriteTarget() {
        cal("read@example.com", "Read", true, false);
        cal("write@example.com", "Write", false, true);

        SelectedCalendar target = SelectedCalendar.writeTargetOf(1L);

        assertThat(target).isNotNull();
        assertThat(target.remoteId).isEqualTo("write@example.com");
    }

    @Test
    @TestTransaction
    void writeTargetIsNullWhenNoneSelected() {
        cal("read@example.com", "Read", true, false);
        assertThat(SelectedCalendar.writeTargetOf(1L)).isNull();
    }

    private SelectedCalendar cal(String id, String summary, boolean read, boolean write) {
        // Ensure a credential exists for owner 1 (reuse within the same transaction).
        GoogleCredential cred = GoogleCredential.forOwner(1L);
        if (cred == null) {
            cred = new GoogleCredential();
            cred.ownerId = 1L;
            cred.refreshToken = "rt-cal-test";
            cred.googleSub = "sub-cal-test";
            cred.persist();
        }
        SelectedCalendar c = new SelectedCalendar();
        c.ownerId = 1L;
        c.remoteId = id;
        c.summary = summary;
        c.readForBusy = read;
        c.writeTarget = write;
        c.accountId = cred.id;
        c.persist();
        return c;
    }
}
