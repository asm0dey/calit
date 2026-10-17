package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * The meeting type write-calendar override columns round-trip, and default to "unset".
 */
@QuarkusTest
class WriteTargetOverrideColumnsTest {
    @Test
    @TestTransaction
    void meetingTypeStoresTheOverride() {
        GoogleCredential cred = seedCredential("sub-override-type");
        MeetingType t = seedType();
        t.overrideAccountId = cred.id;
        t.overrideRemoteCalendarId = "work@example.com";
        t.persistAndFlush();

        MeetingType loaded = MeetingType.findById(t.id);
        assertThat(loaded.overrideAccountId).isEqualTo(cred.id);
        assertThat(loaded.overrideRemoteCalendarId).isEqualTo("work@example.com");
    }

    @Test
    @TestTransaction
    void aFreshTypeHasNoOverride() {
        MeetingType t = seedType();
        t.persistAndFlush();

        MeetingType loaded = MeetingType.findById(t.id);
        assertThat(loaded.overrideAccountId).isNull();
        assertThat(loaded.overrideRemoteCalendarId).isNull();
    }

    @Test
    @TestTransaction
    void findOwnedMatchesOnlyThisOwnersSelectedCalendar() {
        GoogleCredential cred = seedCredential("sub-find-owned");
        SelectedCalendar c = new SelectedCalendar();
        c.ownerId = 1L;
        c.accountId = cred.id;
        c.remoteId = "work@example.com";
        c.summary = "Work";
        c.persistAndFlush();

        assertThat(SelectedCalendar.findOwned(1L, cred.id, "work@example.com").id).isEqualTo(c.id);
        assertThat(SelectedCalendar.findOwned(1L, cred.id, "other@example.com")).isNull();
        assertThat(SelectedCalendar.findOwned(2L, cred.id, "work@example.com")).isNull();
        assertThat(SelectedCalendar.findOwned(1L, 999_999L, "work@example.com")).isNull();
    }

    private static GoogleCredential seedCredential(String sub) {
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt";
        cred.googleSub = sub;
        cred.persist();
        return cred;
    }

    private static MeetingType seedType() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "override-seed";
        t.slug = "override-seed-" + UUID.randomUUID();
        t.durationMinutes = 30;
        return t;
    }
}
