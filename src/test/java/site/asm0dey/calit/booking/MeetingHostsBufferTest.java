package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /** ADR-0002: the max is over the overrides actually SET; an unset one is not a 10-minute floor. */
    @Test
    void neitherSetFallsBackToTheTypeBuffer() {
        MeetingType t = seed("buf-none", null, null);
        assertEquals(10, meetingHosts.effectiveBufferBefore(t, OWNER, 120));
        assertEquals(10, meetingHosts.effectiveBufferAfter(t, OWNER, 120));
    }

    @Test
    void aHostOverrideBelowTheTypeDefaultIsNotRaised() {
        MeetingType t = seed("buf-host-low", 5, null);
        assertEquals(5, meetingHosts.effectiveBufferBefore(t, OWNER, 120));
    }

    @Test
    void aDurationOverrideAppliesWhenTheHostHasNone() {
        MeetingType t = seed("buf-duration", null, 45);
        assertEquals(45, meetingHosts.effectiveBufferBefore(t, OWNER, 120));
    }

    @Test
    void theLargerOfTwoSetOverridesWins() {
        MeetingType t = seed("buf-both-duration-wins", 5, 45);
        assertEquals(45, meetingHosts.effectiveBufferBefore(t, OWNER, 120));

        MeetingType u = seed("buf-both-host-wins", 90, 45);
        assertEquals(90, meetingHosts.effectiveBufferBefore(u, OWNER, 120));
    }

    @Test
    void aLengthWithNoRowUsesOnlyTheHostOverride() {
        MeetingType t = seed("buf-other-length", 5, 45);
        // 30 has no meeting_type_duration row, so only the host's 5 is set.
        assertEquals(5, meetingHosts.effectiveBufferBefore(t, OWNER, 30));
    }
}
