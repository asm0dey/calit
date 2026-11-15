package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static site.asm0dey.calit.booking.BookingStatus.CANCELLED;
import static site.asm0dey.calit.booking.BookingStatus.CONFIRMED;
import static site.asm0dey.calit.booking.BookingStatus.DECLINED;
import static site.asm0dey.calit.booking.BookingStatus.PENDING;
import module java.base;
import org.junit.jupiter.api.Test;

class SetDecisionsTest {
    private static final UUID SET = UUID.randomUUID();
    private static final UUID G = UUID.randomUUID();

    private static Booking row(long id, long owner, UUID group, BookingStatus s) {
        var b = new Booking();
        b.id = id;
        b.ownerId = owner;
        b.groupId = group;
        b.status = s;
        b.setId = SET;
        return b;
    }

    @Test
    void undecidedOfSetReturnsInviteeFacingRowsWithAPendingRow() {
        var rows =
                List.of(
                        row(1, 1, null, PENDING),
                        row(2, 1, null, CONFIRMED),
                        row(3, 1, G, PENDING),
                        row(4, 2, G, PENDING)
        );

        assertThat(SetDecisions.undecidedOfSet(rows, 1L))
            .extracting(b -> b.id)
            .containsExactly(1L, 3L);
    }

    @Test
    void undecidedOfSetCountsAGroupWhoseOnlyPendingRowIsACoHosts() {
        var rows = List.of(row(3, 1, G, CONFIRMED), row(4, 2, G, PENDING));

        assertThat(SetDecisions.undecidedOfSet(rows, 1L))
            .extracting(b -> b.id)
            .containsExactly(3L);
    }

    @Test
    void undecidedOfSetSkipsAFullyConfirmedGroup() {
        var rows = List.of(row(3, 1, G, CONFIRMED), row(4, 2, G, CONFIRMED));

        assertThat(SetDecisions.undecidedOfSet(rows, 1L)).isEmpty();
    }

    @Test
    void approveHostRowsConfirmsOnlyThatHostsPendingRows() {
        var rows =
                List.of(
                        row(1, 1, null, PENDING),
                        row(5, 2, null, PENDING),
                        row(3, 1, G, PENDING),
                        row(4, 2, G, PENDING)
        );
        var undecided = SetDecisions.undecidedOfSet(rows, 1L);

        SetDecisions.approveHostRows(rows, 1L, undecided);

        assertThat(rows)
            .extracting(b -> b.id, b -> b.status)
            .containsExactly(tuple(1L, CONFIRMED), tuple(5L, PENDING), tuple(3L, CONFIRMED), tuple(4L, PENDING));
    }

    @Test
    void approveHostRowsLeavesTheHostsNonPendingRowsAlone() {
        var rows = List.of(row(1, 1, null, PENDING), row(2, 1, null, CANCELLED), row(3, 1, null, CONFIRMED));
        var undecided = SetDecisions.undecidedOfSet(rows, 1L);

        SetDecisions.approveHostRows(rows, 1L, undecided);

        assertThat(rows)
            .extracting(b -> b.id, b -> b.status)
            .containsExactly(tuple(1L, CONFIRMED), tuple(2L, CANCELLED), tuple(3L, CONFIRMED));
    }

    @Test
    void coHostedBookingStaysUndecidedUntilEveryHostApproves() {
        var rows = List.of(row(1, 1, null, PENDING), row(3, 1, G, PENDING), row(4, 2, G, PENDING));
        var undecided = SetDecisions.undecidedOfSet(rows, 1L);

        assertThat(SetDecisions.approveHostRows(rows, 1L, undecided))
            .extracting(b -> b.id)
            .containsExactly(1L);
        assertThat(rows.get(2).status).isEqualTo(PENDING);
    }

    @Test
    void lastHostsApprovalConfirmsTheCoHostedBooking() {
        var rows = List.of(row(3, 1, G, CONFIRMED), row(4, 2, G, PENDING));
        var undecided = SetDecisions.undecidedOfSet(rows, 1L);

        assertThat(SetDecisions.approveHostRows(rows, 2L, undecided))
            .extracting(b -> b.id)
            .containsExactly(3L);
    }

    @Test
    void approveHostRowsReturnsNothingWhenEveryBookingStillWaitsOnAnotherHost() {
        var rows = List.of(row(3, 1, G, PENDING), row(4, 2, G, PENDING));
        var undecided = SetDecisions.undecidedOfSet(rows, 1L);

        assertThat(SetDecisions.approveHostRows(rows, 1L, undecided)).isEmpty();
    }

    @Test
    void declineBookingsDeclinesPendingAndConfirmedRowsOfUndecidedOnly() {
        var rows = List.of(
                row(1, 1, null, PENDING),
                row(2, 1, null, CONFIRMED),
                row(3, 1, G, CONFIRMED),
                row(4, 2, G, PENDING),
                row(6, 3, G, CANCELLED)
        );
        var undecided = SetDecisions.undecidedOfSet(rows, 1L);

        SetDecisions.declineBookings(rows, undecided);

        assertThat(rows)
            .extracting(b -> b.id, b -> b.status)
            .containsExactly(
                    tuple(1L, DECLINED),
                    tuple(2L, CONFIRMED),
                    tuple(3L, DECLINED),
                    tuple(4L, DECLINED),
                    tuple(6L, CANCELLED)
            );
    }

    @Test
    void pendingRowOfFindsTheHostsPendingRowOnly() {
        var rows = List.of(row(1, 1, null, CONFIRMED), row(2, 1, null, PENDING), row(3, 2, null, PENDING));

        assertThat(SetDecisions.pendingRowOf(rows, 1L))
            .get()
            .extracting(b -> b.id)
            .isEqualTo(2L);
        assertThat(SetDecisions.pendingRowOf(rows, 3L)).isEmpty();
    }

    @Test
    void groupFullyApprovedOnlyWhenNoRowPending() {
        assertThat(SetDecisions.groupFullyApproved(List.of(row(3, 1, G, CONFIRMED), row(4, 2, G, PENDING)))).isFalse();
        assertThat(SetDecisions.groupFullyApproved(List.of(row(3, 1, G, CONFIRMED), row(4, 2, G, CONFIRMED)))).isTrue();
    }

    @Test
    void announceIsApprovedForLoneAndConfirmedForGroup() {
        assertThat(SetDecisions.announce(List.of(row(1, 1, null, CONFIRMED), row(3, 1, G, CONFIRMED))))
            .isEqualTo(SetDecisions.Announce.APPROVED);
        assertThat(SetDecisions.announce(List.of(row(3, 1, G, CONFIRMED), row(1, 1, null, CONFIRMED))))
            .isEqualTo(SetDecisions.Announce.CONFIRMED);
    }
}
