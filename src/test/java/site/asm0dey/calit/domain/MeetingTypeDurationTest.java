package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MeetingTypeDurationTest {

    /** Admin is always owner id 1 (DatabaseResetCallback reseeds it per test). */
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
        assertEquals(List.of(30), MeetingTypeDuration.allowedDurations(t));
        assertEquals(30, MeetingTypeDuration.shortestAllowed(t));
    }

    @Test
    void theDefaultIsAnImplicitMemberEvenWhenTheTableOmitsIt() {
        MeetingType t = seedType("implicit-default", 60);
        seedDuration(t.id, 30, null, null);
        seedDuration(t.id, 120, 45, 45);
        assertEquals(List.of(30, 60, 120), MeetingTypeDuration.allowedDurations(t));
        assertEquals(30, MeetingTypeDuration.shortestAllowed(t));
    }

    @Test
    void aRowForTheDefaultDoesNotDuplicateIt() {
        MeetingType t = seedType("default-row", 60);
        seedDuration(t.id, 60, 15, 15);
        assertEquals(List.of(60), MeetingTypeDuration.allowedDurations(t));
    }

    @Test
    void isAllowedAcceptsTheDefaultAndConfiguredLengthsOnly() {
        MeetingType t = seedType("allowed-check", 60);
        seedDuration(t.id, 120, null, null);
        assertTrue(MeetingTypeDuration.isAllowed(t, 60));
        assertTrue(MeetingTypeDuration.isAllowed(t, 120));
        assertFalse(MeetingTypeDuration.isAllowed(t, 45));
    }

    @Test
    void findRowReturnsTheBufferOverridesOrNull() {
        MeetingType t = seedType("find-row", 30);
        seedDuration(t.id, 120, 45, 50);
        MeetingTypeDuration row = MeetingTypeDuration.findRow(t.id, 120);
        assertNotNull(row);
        assertEquals(45, row.bufferBeforeMinutes);
        assertEquals(50, row.bufferAfterMinutes);
        assertNull(MeetingTypeDuration.findRow(t.id, 30));
    }
}
