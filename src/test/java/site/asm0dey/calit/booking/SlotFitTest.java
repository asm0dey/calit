package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class SlotFitTest {
    private static final Instant T10 = Instant.parse("2026-01-05T10:00:00Z");
    private static final Instant T11 = Instant.parse("2026-01-05T11:00:00Z");
    private static final Instant T1130 = Instant.parse("2026-01-05T11:30:00Z");
    private static final Instant EARLY = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-12-31T00:00:00Z");

    @Test
    void slotBeforeMinNoticeDoesNotFit() {
        assertThat(SlotFit.fits(T11, T1130, T11.plusSeconds(1), LATE, 0, 0, List.of())).isFalse();
    }

    @Test
    void slotAfterHorizonDoesNotFit() {
        assertThat(SlotFit.fits(T11, T1130, EARLY, T11.minusSeconds(1), 0, 0, List.of())).isFalse();
    }

    @Test
    void slotAtExactlyEarliestFits() {
        assertThat(SlotFit.fits(T11, T1130, T11, LATE, 0, 0, List.of())).isTrue();
    }

    @Test
    void slotAtExactlyLatestFits() {
        assertThat(SlotFit.fits(T11, T1130, EARLY, T11, 0, 0, List.of())).isTrue();
    }

    @Test
    void backToBackFitsButBufferOverlapDoesNot() {
        var busy = List.of(new Interval(T10, T11));
        assertThat(SlotFit.fits(T11, T1130, EARLY, LATE, 0, 0, busy)).isTrue();
        assertThat(SlotFit.fits(T11, T1130, EARLY, LATE, 1, 0, busy)).isFalse();
    }

    @Test
    void bufferAfterOverlapDoesNotFit() {
        var busy = List.of(new Interval(T1130, T1130.plusSeconds(3600)));
        assertThat(SlotFit.fits(T11, T1130, EARLY, LATE, 0, 0, busy)).isTrue();
        assertThat(SlotFit.fits(T11, T1130, EARLY, LATE, 0, 1, busy)).isFalse();
    }

    @Test
    void slotInsideBusyDoesNotFit() {
        var busy = List.of(new Interval(T10, T1130));
        assertThat(SlotFit.fits(T11, T1130, EARLY, LATE, 0, 0, busy)).isFalse();
    }

    @Test
    void needsReApprovalTruthTable() {
        // single row: re-approve only when the Invitee moves an approval type
        assertThat(SlotFit.needsReApproval(true, false, false)).isTrue();
        assertThat(SlotFit.needsReApproval(true, true, false)).isFalse();
        assertThat(SlotFit.needsReApproval(false, false, false)).isFalse();
        assertThat(SlotFit.needsReApproval(false, true, false)).isFalse();
        // group: byOwner is ignored
        assertThat(SlotFit.needsReApproval(true, false, true)).isTrue();
        assertThat(SlotFit.needsReApproval(true, true, true)).isTrue();
        assertThat(SlotFit.needsReApproval(false, false, true)).isFalse();
        assertThat(SlotFit.needsReApproval(false, true, true)).isFalse();
    }

    @Test
    void groupMoveKeepsInitiatorConfirmedOthersPending() {
        assertThat(SlotFit.groupRowStatusAfterMove(true, 1L, 1L, BookingStatus.CONFIRMED)).isEqualTo(
                BookingStatus.CONFIRMED
        );
        assertThat(SlotFit.groupRowStatusAfterMove(true, 2L, 1L, BookingStatus.CONFIRMED)).isEqualTo(
                BookingStatus.PENDING
        );
    }

    @Test
    void groupMoveByInviteeSendsEveryRowPending() {
        assertThat(SlotFit.groupRowStatusAfterMove(true, 1L, null, BookingStatus.CONFIRMED)).isEqualTo(
                BookingStatus.PENDING
        );
    }

    @Test
    void groupMoveWithoutReApprovalKeepsStatus() {
        assertThat(SlotFit.groupRowStatusAfterMove(false, 2L, 1L, BookingStatus.CONFIRMED)).isEqualTo(
                BookingStatus.CONFIRMED
        );
        assertThat(SlotFit.groupRowStatusAfterMove(false, 2L, null, BookingStatus.PENDING)).isEqualTo(
                BookingStatus.PENDING
        );
    }
}
