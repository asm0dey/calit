package site.asm0dey.calit.booking;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.inject.Inject;
import site.asm0dey.calit.domain.MeetingTypeRepository;

@ApplicationScoped
public class BookingRepository implements PanacheRepositoryBase<Booking, Long> {
    private static final String FUTURE_HELD_GROUP_ROWS =
            "ownerId = ?1 and meetingTypeId = ?2 and startUtc > ?3 and status in ?4 and groupId is not null";
    private static final List<BookingStatus> HELD = List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);
    private final MeetingTypeRepository meetingTypes;

    @Inject
    public BookingRepository(MeetingTypeRepository meetingTypes) {
        this.meetingTypes = meetingTypes;
    }

    public List<Booking> heldOverlapping(Long ownerId, Instant from, Instant to) {
        return Booking.heldOverlapping(ownerId, from, to);
    }

    public Booking findByManageToken(String manageToken) {
        return Booking.findByManageToken(manageToken);
    }

    public List<Booking> group(UUID groupId) {
        return Booking.group(groupId);
    }

    public Booking leadOfGroup(UUID groupId, Long creatorOwnerId) {
        return Booking.leadOfGroup(groupId, creatorOwnerId);
    }

    public List<Booking> set(UUID setId) {
        return Booking.set(setId);
    }

    public Booking leadOfSet(UUID setId, Long creatorOwnerId) {
        return Booking.leadOfSet(setId, creatorOwnerId);
    }

    public long countDistinctBookingsByEmailBetween(String email, Instant dayStart, Instant dayEnd) {
        return Booking.countDistinctBookingsByEmailBetween(email, dayStart, dayEnd);
    }

    /**
     * This host's held (PENDING/CONFIRMED) future group rows on the type.
     */
    public List<Booking> futureHeldGroupRows(Long hostOwnerId, Long meetingTypeId, Instant now) {
        return list(FUTURE_HELD_GROUP_ROWS, hostOwnerId, meetingTypeId, now, HELD);
    }

    public long countFutureHeldGroupRows(Long hostOwnerId, Long meetingTypeId, Instant now) {
        return count(FUTURE_HELD_GROUP_ROWS, hostOwnerId, meetingTypeId, now, HELD);
    }

    /**
     * Loads a booking for approve/decline under a row lock, so an overlapping second click waits for this
     * commit and then sees the settled status. A set row locks its set's lead instead (before any other
     * row), so clicks on different rows of one set serialise on one lock; every row is then re-read.
     * Null when no such booking.
     */
    public Booking lockForDecision(Long bookingId) {
        Booking booking = findById(bookingId);
        if (booking == null) {
            return null;
        }
        var em = getEntityManager();
        // refresh re-reads the DB and would discard this transaction's unflushed changes
        em.flush();
        if (booking.setId == null) {
            em.refresh(booking, LockModeType.PESSIMISTIC_WRITE);
            return booking;
        }
        Long creatorOwnerId = meetingTypes.findById(booking.meetingTypeId).ownerId;
        lockSetLead(booking.setId, creatorOwnerId);
        set(booking.setId).forEach(em::refresh);
        return booking;
    }

    /**
     * The set's lead, re-read under {@code PESSIMISTIC_WRITE}: the one lock every decision on the set takes.
     */
    public Booking lockSetLead(UUID setId, Long creatorOwnerId) {
        var em = getEntityManager();
        // refresh re-reads the DB and would discard this transaction's unflushed changes
        em.flush();
        Booking lead = leadOfSet(setId, creatorOwnerId);
        em.refresh(lead, LockModeType.PESSIMISTIC_WRITE);
        return lead;
    }
}
