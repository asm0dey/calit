package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleCalendarTest {
    @Test
    @TestTransaction
    void listsOnlyReadForBusyCalendars() {
        cal("work@example.com", "Work", true, false);
        cal("busy@example.com", "Side", true, false);
        cal("ignored@example.com", "Ignored", false, false);

        List<GoogleCalendar> readers = GoogleCalendar.readForBusy(1L);

        assertThat(readers).hasSize(2);
        assertThat(readers.stream().allMatch(c -> c.readForBusy)).isTrue();
    }

    @Test
    @TestTransaction
    void returnsTheSingleWriteTarget() {
        cal("read@example.com", "Read", true, false);
        cal("write@example.com", "Write", false, true);

        GoogleCalendar target = GoogleCalendar.writeTarget(1L);

        assertThat(target).isNotNull();
        assertThat(target.googleCalendarId).isEqualTo("write@example.com");
    }

    @Test
    @TestTransaction
    void writeTargetIsNullWhenNoneSelected() {
        cal("read@example.com", "Read", true, false);
        assertThat(GoogleCalendar.writeTarget(1L)).isNull();
    }

    private GoogleCalendar cal(String id, String summary, boolean read, boolean write) {
        // Ensure a credential exists for owner 1 (reuse within the same transaction).
        GoogleCredential cred = GoogleCredential.forOwner(1L);
        if (cred == null) {
            cred = new GoogleCredential();
            cred.ownerId = 1L;
            cred.refreshToken = "rt-cal-test";
            cred.googleSub = "sub-cal-test";
            cred.persist();
        }
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCalendarId = id;
        c.summary = summary;
        c.readForBusy = read;
        c.writeTarget = write;
        c.googleCredentialId = cred.id;
        c.persist();
        return c;
    }
}
