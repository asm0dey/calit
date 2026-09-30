package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * The per-(type, host) write-calendar override columns round-trip, and default to "unset".
 */
@QuarkusTest
class WriteTargetOverrideColumnsTest {
    @Test
    @TestTransaction
    void meetingTypeStoresTheOverride() {
        GoogleCredential cred = seedCredential("sub-override-type");
        MeetingType t = seedType();
        t.googleCredentialId = cred.id;
        t.googleCalendarId = "work@example.com";
        t.persistAndFlush();

        MeetingType loaded = MeetingType.findById(t.id);
        assertThat(loaded.googleCredentialId).isEqualTo(cred.id);
        assertThat(loaded.googleCalendarId).isEqualTo("work@example.com");
    }

    @Test
    @TestTransaction
    void aFreshTypeHasNoOverride() {
        MeetingType t = seedType();
        t.persistAndFlush();

        MeetingType loaded = MeetingType.findById(t.id);
        assertThat(loaded.googleCredentialId).isNull();
        assertThat(loaded.googleCalendarId).isNull();
    }

    @Test
    @TestTransaction
    void hostRowStoresItsOwnOverride() {
        GoogleCredential cred = seedCredential("sub-override-host");
        MeetingType t = seedType();
        t.persistAndFlush();
        MeetingTypeHost h = MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED);
        h.googleCredentialId = cred.id;
        h.googleCalendarId = "cohost@example.com";
        h.persistAndFlush();

        MeetingTypeHost loaded = MeetingTypeHost.findById(h.id);
        assertThat(loaded.googleCredentialId).isEqualTo(cred.id);
        assertThat(loaded.googleCalendarId).isEqualTo("cohost@example.com");
    }

    @Test
    @TestTransaction
    void findOwnedMatchesOnlyThisOwnersSelectedCalendar() {
        GoogleCredential cred = seedCredential("sub-find-owned");
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCredentialId = cred.id;
        c.googleCalendarId = "work@example.com";
        c.summary = "Work";
        c.persistAndFlush();

        assertThat(GoogleCalendar.findOwned(1L, cred.id, "work@example.com").id).isEqualTo(c.id);
        assertThat(GoogleCalendar.findOwned(1L, cred.id, "other@example.com")).isNull();
        assertThat(GoogleCalendar.findOwned(2L, cred.id, "work@example.com")).isNull();
        assertThat(GoogleCalendar.findOwned(1L, 999_999L, "work@example.com")).isNull();
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
