package site.asm0dey.calit.privacy;

import module java.base;
import io.quarkus.narayana.jta.QuarkusTransaction;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailTag;
import site.asm0dey.calit.scheduler.Reminder;

/**
 * Shared erasure-test fixtures: a CONFIRMED past booking with a guest, an unsent reminder and a
 * parked mail, tagged to invitee "Dana Vogel" / dana@example.com. Public so tests outside this
 * package (route tests) can seed the same booking shape without duplicating it.
 */
public final class ErasureFixtures {
    /**
     * The seeded admin owner — DatabaseResetCallback guarantees id 1.
     */
    public static final Long OWNER = 1L;
    /**
     * Spaces out repeated {@link #seedPastBookingId()} calls within one test so their held
     * ([start, end)) windows never overlap for the same owner — {@code booking_no_overlap_held}
     * would otherwise reject a second CONFIRMED row landing in the same ~30-day-ago slot.
     */
    private static final AtomicLong SEED_OFFSET = new AtomicLong();

    private ErasureFixtures() {
    }

    /**
     * A CONFIRMED booking in the past, with a guest, a parked mail and an unsent reminder. Returns
     * its id.
     */
    public static Long seedPastBookingId() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var b = new Booking();
            b.ownerId = OWNER;
            b.meetingTypeId = firstMeetingTypeId();
            b.inviteeName = "Dana Vogel";
            b.inviteeEmail = "dana@example.com";
            b.answers = new HashMap<>(Map.of("why", "annual review"));
            b.title = "Dana's slot";
            b.description = "notes from Dana";
            b.meetLink = "https://meet.google.com/abc-defg-hij";
            b.startUtc = Instant
                .now()
                .minus(30, ChronoUnit.DAYS)
                .minus(SEED_OFFSET.getAndIncrement(), ChronoUnit.HOURS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now().minus(31, ChronoUnit.DAYS);
            b.manageToken = UUID.randomUUID().toString();
            b.persist();

            var g = new BookingGuest();
            g.ownerId = OWNER;
            g.bookingId = b.id;
            g.email = "guest@example.com";
            g.status = GuestStatus.INVITED;
            g.declineToken = UUID.randomUUID().toString();
            g.createdAt = Instant.now();
            g.persist();

            var r = new Reminder();
            r.bookingId = b.id;
            r.sendAt = Instant.now().plus(1, ChronoUnit.DAYS);
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();

            EmailOutbox.enqueue(
                    "dana@example.com",
                    "Your booking",
                    "<p>Hi Dana Vogel</p>",
                    null,
                    null,
                    "seed",
                    MailTag.forBooking(b.id, OWNER)
            );
            return b.id;
        });
    }

    /**
     * A CONFIRMED booking in the FUTURE — same shape as {@link #seedPastBookingId()} minus the
     * guest/reminder/mail dependents, which retention tests don't need. Proves retention measures
     * from {@code end_utc}: a booking that has not ended must never be swept, however short the
     * window. Shares {@link #SEED_OFFSET} with {@link #seedPastBookingId()} so repeated calls never
     * collide with each other under {@code booking_no_overlap_held} — future and past windows are
     * ~30 days apart, so cross-type collision is not a concern either.
     */
    public static Long seedUpcomingBookingId() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var b = new Booking();
            b.ownerId = OWNER;
            b.meetingTypeId = firstMeetingTypeId();
            b.inviteeName = "Dana Vogel";
            b.inviteeEmail = "dana@example.com";
            b.answers = new HashMap<>(Map.of("why", "annual review"));
            b.title = "Dana's upcoming slot";
            b.description = "notes from Dana";
            b.meetLink = "https://meet.google.com/abc-defg-hij";
            b.startUtc = Instant
                .now()
                .plus(30, ChronoUnit.DAYS)
                .plus(SEED_OFFSET.getAndIncrement(), ChronoUnit.HOURS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    /**
     * Same seed as {@link #seedPastBookingId()}, returning the manage token instead of the id.
     */
    public static String seedPastBooking() {
        var id = seedPastBookingId();
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id).manageToken);
    }

    /**
     * DatabaseResetCallback seeds only the admin {@code app_user} row, not a MeetingType, so this
     * seeds one on demand (adapted from the brief, which assumed a pre-seeded type for owner 1).
     * Also ensures {@link OwnerSettings} exists for the owner: the route tests render the real
     * manage/erase pages, and {@code renderManage} 404s to the "not ready yet" page without one.
     */
    public static Long firstMeetingTypeId() {
        ensureOwnerSettings();
        var existing = MeetingType.<MeetingType>find("ownerId", OWNER).firstResult();
        if (existing != null) {
            return existing.id;
        }
        var t = new MeetingType();
        t.ownerId = OWNER;
        t.name = "Erasure test type";
        t.slug = "erasure-test-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.persist();
        return t.id;
    }

    private static void ensureOwnerSettings() {
        if (OwnerSettings.forOwner(OWNER) != null) {
            return;
        }
        var s = new OwnerSettings();
        s.ownerId = OWNER;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "UTC";
        s.persist();
    }
}
