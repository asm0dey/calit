package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.calit.domain.MeetingTypeDuration.allowedDurations;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MeetingTypeDurationTest {
    /**
     * Admin is always owner id 1 (DatabaseResetCallback reseeds it per test).
     */
    private static final Long OWNER = 1L;

    @Transactional
    MeetingType seedType(String slug, int durationMinutes) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = durationMinutes;
        t.persist();
        return t;
    }

    @Transactional
    void seedDuration(Long typeId, int minutes, Integer before, Integer after) {
        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = typeId;
        d.durationMinutes = minutes;
        d.bufferBeforeMinutes = before;
        d.bufferAfterMinutes = after;
        d.persist();
    }

    @Test
    void emptyTableMeansTheSetIsExactlyTheDefault() {
        MeetingType t = seedType("empty-set", 30);
        assertThat(allowedDurations(t)).containsExactly(30);
        assertThat(MeetingTypeDuration.shortestAllowed(t)).isEqualTo(30);
    }

    @Test
    void theDefaultIsAnImplicitMemberEvenWhenTheTableOmitsIt() {
        MeetingType t = seedType("implicit-default", 60);
        seedDuration(t.id, 30, null, null);
        seedDuration(t.id, 120, 45, 45);
        assertThat(allowedDurations(t)).containsExactly(30, 60, 120);
        assertThat(MeetingTypeDuration.shortestAllowed(t)).isEqualTo(30);
    }

    @Test
    void aRowForTheDefaultDoesNotDuplicateIt() {
        MeetingType t = seedType("default-row", 60);
        seedDuration(t.id, 60, 15, 15);
        assertThat(allowedDurations(t)).containsExactly(60);
    }

    @Test
    void isAllowedAcceptsTheDefaultAndConfiguredLengthsOnly() {
        MeetingType t = seedType("allowed-check", 60);
        seedDuration(t.id, 120, null, null);
        assertThat(MeetingTypeDuration.isAllowed(t, 60)).isTrue();
        assertThat(MeetingTypeDuration.isAllowed(t, 120)).isTrue();
        assertThat(MeetingTypeDuration.isAllowed(t, 45)).isFalse();
    }

    @Test
    void findRowReturnsTheBufferOverridesOrNull() {
        MeetingType t = seedType("find-row", 30);
        seedDuration(t.id, 120, 45, 50);
        MeetingTypeDuration row = MeetingTypeDuration.findRow(t.id, 120);
        assertThat(row).isNotNull();
        assertThat(row.bufferBeforeMinutes).isEqualTo(45);
        assertThat(row.bufferAfterMinutes).isEqualTo(50);
        assertThat(MeetingTypeDuration.findRow(t.id, 30)).isNull();
    }
}
