package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.MeetingTypeHost;

@QuarkusTest
class MeetingHostsBufferTest {
    private static final Long OWNER = 1L;
    @Inject
    MeetingHosts meetingHosts;

    @Transactional
    MeetingType seed(String slug, Integer hostOverride, Integer durationOverride) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.bufferBeforeMinutes = 10;
        t.bufferAfterMinutes = 10;
        t.persist();

        MeetingTypeHost h = MeetingTypeHost.of(t.id, OWNER, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED);
        h.bufferBeforeMinutes = hostOverride;
        h.bufferAfterMinutes = hostOverride;
        h.persist();

        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = t.id;
        d.durationMinutes = 120;
        d.bufferBeforeMinutes = durationOverride;
        d.bufferAfterMinutes = durationOverride;
        d.persist();
        return t;
    }

    @Test
    void aLengthWithNoRowUsesOnlyTheHostOverride() {
        MeetingType t = seed("buf-other-length", 5, 45);
        // 30 has no meeting_type_duration row, so only the host's 5 is set.
        assertThat(meetingHosts.effectiveBufferBefore(t, OWNER, 30)).isEqualTo(5);
    }
}
