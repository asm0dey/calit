package site.asm0dey.calit.booking;

import module java.base;

/**
 * Pure approve/decline decisions over the rows of a multi-slot booking set.
 */
public final class SetDecisions {
    public enum Announce {
        APPROVED,
        CONFIRMED
    }

    private SetDecisions() {
    }

    /**
     * Flips the Host's PENDING rows of the set to CONFIRMED; returns the undecided Bookings that now have no PENDING
     * row.
     */
    static List<Booking> approveHostRows(List<Booking> setRows, Long hostOwnerId, List<Booking> undecided) {
        for (Booking row : setRows) {
            if (row.ownerId.equals(hostOwnerId) && row.status == BookingStatus.PENDING) {
                row.status = BookingStatus.CONFIRMED;
            }
        }
        // a co-hosted Booking stays undecided until every Host has approved it
        return undecided
            .stream()
            .filter(b -> rowsOfBooking(setRows, b).noneMatch(r -> r.status == BookingStatus.PENDING))
            .toList();
    }

    /**
     * Whether every row of a co-hosted group is approved: none is still PENDING.
     */
    static boolean groupFullyApproved(List<Booking> groupRows) {
        return groupRows
            .stream()
            .noneMatch(r -> r.status == BookingStatus.PENDING);
    }

    /**
     * Which event announces a set approval: a lone first Booking gets BookingApproved, a co-hosted one BookingConfirmed.
     */
    static Announce announce(List<Booking> confirmed) {
        return confirmed.getFirst().groupId == null ? Announce.APPROVED : Announce.CONFIRMED;
    }

    static Optional<Booking> pendingRowOf(List<Booking> setRows, Long hostOwnerId) {
        return setRows
            .stream()
            .filter(r -> r.ownerId.equals(hostOwnerId) && r.status == BookingStatus.PENDING)
            .findFirst();
    }

    /**
     * The Invitee-facing row (a lone row, or the Creator's row of a co-hosted group) of every Booking among
     * {@code setRows} still undecided, meaning it has a PENDING row; in time order.
     */
    public static List<Booking> undecidedOfSet(List<Booking> setRows, Long creatorOwnerId) {
        return setRows
            .stream()
            .filter(r -> r.groupId == null || r.ownerId.equals(creatorOwnerId))
            .filter(b -> rowsOfBooking(setRows, b).anyMatch(r -> r.status == BookingStatus.PENDING))
            .toList();
    }

    /**
     * Declines every held row of the given undecided Bookings (Invitee-facing rows, from {@link #undecidedOfSet}).
     */
    public static void declineBookings(List<Booking> setRows, List<Booking> undecided) {
        undecided
            .stream()
            .flatMap(b -> rowsOfBooking(setRows, b))
            .filter(r -> r.status == BookingStatus.PENDING || r.status == BookingStatus.CONFIRMED)
            .forEach(r -> r.status = BookingStatus.DECLINED);
    }

    private static Stream<Booking> rowsOfBooking(List<Booking> setRows, Booking b) {
        return b.groupId == null ? Stream.of(b) : setRows.stream().filter(r -> b.groupId.equals(r.groupId));
    }
}
