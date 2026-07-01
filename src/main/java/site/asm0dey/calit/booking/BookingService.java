package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.BusyInterval;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.i18n.AppLocales;

@ApplicationScoped
public class BookingService {

    // Collaborators + config are constructor-injected (immutable, fail-fast, plain-unit testable,
    // consistent with the Plan 2 google package). The six CDI Event<> emitters below stay as @Inject
    // fields: they are framework plumbing and would otherwise bloat the constructor to ten args.
    private final SlotService slotService;
    private final CalendarPort calendarPort;
    private final TurnstileVerifier turnstileVerifier;
    private final long perEmailDailyCap;

    /** Max guests an invitee may attach to one booking. ponytail: a constant, not a config knob. */
    public static final int MAX_GUESTS_PER_BOOKING = 10;

    @Inject
    public BookingService(
            SlotService slotService,
            CalendarPort calendarPort,
            TurnstileVerifier turnstileVerifier,
            @ConfigProperty(name = "calit.abuse.per-email-daily-cap", defaultValue = "10") long perEmailDailyCap) {
        this.slotService = slotService;
        this.calendarPort = calendarPort;
        this.turnstileVerifier = turnstileVerifier;
        this.perEmailDailyCap = perEmailDailyCap;
    }

    @Inject
    Event<BookingRequested> bookingRequestedEvent;

    @Inject
    Event<BookingConfirmed> bookingConfirmedEvent;

    @Inject
    Event<BookingApproved> bookingApprovedEvent;

    @Inject
    Event<BookingDeclined> bookingDeclinedEvent;

    @Inject
    Event<BookingRescheduled> bookingRescheduledEvent;

    @Inject
    Event<BookingCancelled> bookingCancelledEvent;

    @Inject
    Event<GuestDeclined> guestDeclinedEvent;

    @Inject
    Event<GuestRemoved> guestRemovedEvent;

    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to) {
        return availableSlots(type, from, to, null);
    }

    /**
     * Bookable slots = raw work-hour slots (Plan 1b override semantics already applied) whose
     * buffered interval does not overlap any busy interval — busy = Google free/busy
     * (only when {@code isConnected()}) + all PENDING/CONFIRMED bookings — and which also survive
     * the min-notice and horizon filters relative to {@code now} (feature 11). {@code excludeBookingId}
     * omits one booking from the busy set (used by reschedule so a booking can move within its own window).
     */
    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to, Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        var fromInstant = from.atStartOfDay(zone).toInstant();
        var toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Interval> busy = busyIntervals(type.ownerId, fromInstant, toInstant, excludeBookingId);

        // Feature 11 bounds, captured once relative to request time.
        var now = Instant.now();
        Instant earliest = now.plusSeconds(60L * type.minNoticeMinutes);
        Instant latest = now.plus(type.horizonDays, ChronoUnit.DAYS);

        List<TimeSlot> raw = slotService.generateRawSlots(type, from, to);
        List<TimeSlot> available = new ArrayList<>();
        for (TimeSlot slot : raw) {
            Instant slotStart = slot.start().toInstant();
            // Feature 11: drop too-soon (before now+minNotice) and too-far (after now+horizon) slots.
            if (slotStart.isBefore(earliest) || slotStart.isAfter(latest)) {
                continue;
            }
            Interval buffered = new Interval(
                    slotStart.minusSeconds(60L * type.bufferBeforeMinutes),
                    slot.end().toInstant().plusSeconds(60L * type.bufferAfterMinutes));
            if (!buffered.overlapsAny(busy)) {
                available.add(slot);
            }
        }
        return available;
    }

    /**
     * Google busy intervals (only when connected — degraded mode skips freeBusy) plus all
     * PENDING+CONFIRMED bookings in the window (minus an excluded one). PENDING is included so a
     * pending approval request holds its slot (feature 14).
     */
    List<Interval> busyIntervals(Long ownerId, Instant from, Instant to, Long excludeBookingId) {
        List<Interval> busy = new ArrayList<>();
        if (calendarPort.isConnected(ownerId)) {
            for (BusyInterval bi : calendarPort.freeBusy(ownerId, from, to)) {
                busy.add(new Interval(bi.start(), bi.end()));
            }
        }
        for (Booking b : Booking.<Booking>heldOverlapping(ownerId, from, to)) {
            if (excludeBookingId != null && excludeBookingId.equals(b.id)) {
                continue;
            }
            busy.add(new Interval(b.startUtc, b.endUtc));
        }
        return busy;
    }

    @Transactional
    public Booking book(
            Long ownerId,
            String meetingTypeSlug,
            Instant startUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String turnstileToken,
            String honeypot,
            String locale,
            List<String> guestEmails) {
        validateInviteeEmail(inviteeEmail);
        validateInputBounds(inviteeName, answers);
        MeetingType type = MeetingType.findBySlug(ownerId, meetingTypeSlug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug + " for owner " + ownerId);
        }

        // Feature 16: all three abuse guards run first, inside book(). The Plan 5 web layer
        // just forwards the cf-turnstile-response (turnstileToken) and website (honeypot) form values.
        turnstileVerifier.verify(turnstileToken); // -> AbuseException (400) when enabled & invalid
        if (honeypot != null && !honeypot.isBlank()) { // a bot filled the hidden field
            throw new AbuseException("Honeypot field was filled."); // -> AbuseException (400)
        }
        enforcePerEmailDailyCap(type, inviteeEmail); // -> RateLimitException (429) over cap

        Map<String, String> submitted = answers == null ? Map.of() : answers;

        // Feature 10: every required custom field must have a non-blank value. Built-in
        // name/email are method params, not BookingField rows, so they are not in this loop.
        validateRequiredFields(type, submitted);

        Instant endUtc = startUtc.plusSeconds(60L * type.durationMinutes);

        // App-level availability re-check: nice errors + buffer/min-notice/horizon enforcement
        // (the DB constraint only guards raw-time overlap, not buffers).
        assertSlotAvailable(type, startUtc, null);

        Booking booking = new Booking();
        booking.ownerId = type.ownerId;
        booking.meetingTypeId = type.id;
        booking.inviteeName = inviteeName;
        booking.inviteeEmail = inviteeEmail;
        booking.startUtc = startUtc;
        booking.endUtc = endUtc;
        booking.createdAt = Instant.now();
        booking.manageToken = UUID.randomUUID().toString();
        booking.answers = submitted;
        booking.locale = AppLocales.isSupported(locale) ? locale : "en";
        // Feature 14: approval types hold the slot as PENDING; auto types are CONFIRMED immediately.
        booking.status = type.requiresApproval ? BookingStatus.PENDING : BookingStatus.CONFIRMED;
        // Approval-required bookings carry an unguessable token used as a CSRF nonce on the owner's
        // email approve/decline links.
        if (type.requiresApproval) {
            booking.approvalToken = UUID.randomUUID().toString();
        }

        // NFR cross-node guard: persist + flush now so a concurrent replica's overlapping
        // held (PENDING|CONFIRMED) row trips the `booking_no_overlap_held` exclusion constraint
        // here, surfaced as the same 409 the app-level check uses (instead of a 500).
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException("Slot " + startUtc + " is not available for " + type.slug);
            }
            throw ex;
        }

        persistGuests(booking, guestEmails);

        if (type.requiresApproval) {
            // Feature 14: PENDING request — NO Google event yet; the owner approves/declines later.
            bookingRequestedEvent.fire(new BookingRequested(booking.id));
            return booking;
        }

        // Auto type: create the Google event when connected (degraded mode skips it entirely).
        // If createEvent throws, the @Transactional boundary rolls back this booking (no orphan row).
        // `createGoogleEvent` (shared with `approve`) applies the feature-13
        // location logic: createMeetLink=(locationType==GOOGLE_MEET), locationText=locationDetail.
        if (calendarPort.isConnected(type.ownerId)) {
            createGoogleEvent(type, booking);
        }

        bookingConfirmedEvent.fire(new BookingConfirmed(booking.id));
        return booking;
    }

    /**
     * Normalizes + persists the invitee-supplied guest emails as INVITED rows. Trims, lower-cases for
     * de-dup, drops blanks / the invitee's own address / anything that fails {@link #isPlausibleEmail},
     * and caps at {@link #MAX_GUESTS_PER_BOOKING}. Bad entries are dropped silently (one typo must not
     * fail the whole booking). owner_id is copied from the booking for the multi-tenancy invariant.
     */
    private void persistGuests(Booking booking, List<String> guestEmails) {
        for (String email : normalizeGuestEmails(guestEmails, booking.inviteeEmail)) {
            BookingGuest g = new BookingGuest();
            g.ownerId = booking.ownerId;
            g.bookingId = booking.id;
            g.email = email;
            g.status = GuestStatus.INVITED;
            g.declineToken = UUID.randomUUID().toString();
            g.createdAt = Instant.now();
            g.persist();
        }
    }

    /** Cleaned, de-duped (case-insensitive), capped, invitee-excluded guest list. Preserves order. */
    private static List<String> normalizeGuestEmails(List<String> guestEmails, String inviteeEmail) {
        if (guestEmails == null || guestEmails.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashMap<String, String> byLower = new java.util.LinkedHashMap<>();
        for (String raw : guestEmails) {
            var email = raw == null ? "" : raw.trim();
            // Drop blanks/over-length, the invitee's own address (they already get every mail), and
            // malformed addresses — silently, so one bad entry never fails the booking. Cap at the max.
            var acceptable = !email.isEmpty()
                    && email.length() <= 254
                    && !email.equalsIgnoreCase(inviteeEmail)
                    && isPlausibleEmail(email);
            if (acceptable && byLower.size() < MAX_GUESTS_PER_BOOKING) {
                byLower.putIfAbsent(email.toLowerCase(), email);
            }
        }
        return List.copyOf(byLower.values());
    }

    /**
     * Creates the Google event for a CONFIRMED booking and stores its ids. Applies the feature-13
     * location logic: {@code createMeetLink = (locationType == GOOGLE_MEET)},
     * {@code locationText = locationDetail}. Shared by {@code book} (auto branch) and {@code approve}.
     * Caller must guard with {@code calendarPort.isConnected()} (degraded mode skips this entirely).
     */
    private void createGoogleEvent(MeetingType type, Booking booking) {
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
        CreatedEvent created = calendarPort.createEvent(
                type.ownerId,
                type.name + " with " + booking.inviteeName,
                "Booked via calit.",
                booking.startUtc,
                booking.endUtc,
                attendeeEmails(booking, owner),
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
        booking.googleEventId = created.googleEventId();
        booking.meetLink = created.meetLink();
    }

    /**
     * The full Google attendee set for this booking: invitee + owner + currently-active (INVITED) guests.
     * ponytail: guests here also gain Google's native Accept/Decline RSVP, which calit can't observe — the
     * calit decline link stays authoritative; a Google-side RSVP won't update BookingGuest.status. No Google
     * API suppresses native RSVP, so this is accepted.
     */
    private static List<String> attendeeEmails(Booking booking, OwnerSettings owner) {
        List<String> emails = new ArrayList<>();
        emails.add(booking.inviteeEmail);
        emails.add(owner.ownerEmail);
        for (BookingGuest g : BookingGuest.<BookingGuest>activeForBooking(booking.id)) {
            emails.add(g.email);
        }
        return emails;
    }

    /**
     * Feature 16: rejects the booking (HTTP 429) if this invitee email already created at least
     * {@code perEmailDailyCap} bookings during today's owner-tz day window.
     */
    private static void validateInputBounds(String inviteeName, Map<String, String> answers) {
        if (inviteeName == null || inviteeName.isBlank()) {
            throw new BookingValidationException("Name is required.");
        }
        if (inviteeName.length() > 200) {
            throw new BookingValidationException("Name is too long.");
        }
        if (answers != null) {
            for (String v : answers.values()) {
                if (v != null && v.length() > 2000) {
                    throw new BookingValidationException("An answer is too long.");
                }
            }
        }
    }

    private static void validateInviteeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BookingValidationException("Email is required.");
        }
        if (email.length() > 254) {
            throw new BookingValidationException("Email is too long.");
        }
        if (!isPlausibleEmail(email)) {
            throw new BookingValidationException("Enter a valid email address.");
        }
    }

    // RFC-pragmatic single-address check done WITHOUT a regex (so there is no ReDoS or regex-engine
    // stack-overflow risk on hostile input): exactly one non-leading '@', a domain with at least one
    // dot and no empty labels, and no whitespace or comma anywhere — the latter blocks header/ICS
    // injection and CRLF smuggling. Not a full RFC 5322 parser (SEC-INPUT-01).
    private static boolean isPlausibleEmail(String email) {
        for (var i = 0; i < email.length(); i++) {
            var ch = email.charAt(i);
            if (ch == ',' || Character.isWhitespace(ch)) {
                return false; // commas and CR/LF/space/tab are injection vectors — reject
            }
        }
        var at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false; // need exactly one '@', neither first nor last char
        }
        var domain = email.substring(at + 1);
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
            return false; // no empty domain labels
        }
        return domain.indexOf('.') >= 0; // domain must have at least one dot
    }

    private void enforcePerEmailDailyCap(MeetingType type, String inviteeEmail) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        var today = Instant.now().atZone(zone).toLocalDate();
        var dayStart = today.atStartOfDay(zone).toInstant();
        var dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        if (Booking.countByEmailCreatedBetween(inviteeEmail, dayStart, dayEnd) >= perEmailDailyCap) {
            throw new RateLimitException("Daily booking cap reached for " + inviteeEmail);
        }
    }

    /**
     * Feature 10: rejects the booking (HTTP 422) if any required field in
     * {@code BookingField.formFor(type.ownerId, type.id)} is missing or blank in the submitted answers.
     */
    private void validateRequiredFields(MeetingType type, Map<String, String> answers) {
        for (BookingField field : BookingField.formFor(type.ownerId, type.id)) {
            if (field.required) {
                String value = answers.get(field.fieldKey);
                if (value == null || value.isBlank()) {
                    throw new BookingValidationException("Required field '" + field.fieldKey + "' is missing or blank");
                }
            }
        }
    }

    /** True if {@code ex} (or a cause) is the no-overlap exclusion-constraint violation. */
    private boolean isNoOverlapViolation(Throwable ex) {
        for (var t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && "booking_no_overlap_held".equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** Throws BookingConflictException unless an available slot starts exactly at {@code startUtc}. */
    private void assertSlotAvailable(MeetingType type, Instant startUtc, Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        var day = startUtc.atZone(zone).toLocalDate();
        boolean ok = availableSlots(type, day, day, excludeBookingId).stream()
                .anyMatch(s -> s.start().toInstant().equals(startUtc));
        if (!ok) {
            throw new BookingConflictException("Slot " + startUtc + " is not available for " + type.slug);
        }
    }

    @Transactional
    public void approve(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            throw new NotFoundException("No booking " + bookingId);
        }
        booking.status = BookingStatus.CONFIRMED;
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        // Feature 14 + degraded mode: create the Google event now, only when connected.
        if (calendarPort.isConnected(type.ownerId)) {
            createGoogleEvent(type, booking);
        }
        bookingApprovedEvent.fire(new BookingApproved(bookingId));
    }

    @Transactional
    public void decline(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            throw new NotFoundException("No booking " + bookingId);
        }
        // DECLINED leaves the PENDING|CONFIRMED partial constraint -> frees the slot.
        // A PENDING request has no Google event, so nothing to delete.
        booking.status = BookingStatus.DECLINED;
        bookingDeclinedEvent.fire(new BookingDeclined(bookingId));
    }

    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc) {
        return reschedule(manageToken, newStartUtc, null, false);
    }

    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails) {
        return reschedule(manageToken, newStartUtc, guestEmails, false);
    }

    /**
     * Reschedules a booking by its manage token. When {@code guestEmails} is non-null the active guest
     * set is reconciled to it (added guests -> INVITED + GuestRemoved/invite emails downstream; dropped
     * guests -> REMOVED + cancel email; kept guests get the reschedule .ics via BookingRescheduled).
     * A null {@code guestEmails} leaves guests untouched (the JSON API + the 2-arg overload).
     * {@code byOwner} true when the host drove the reschedule (from /me or an owner email link),
     * which flips the notification wording so nobody is told the guest moved it.
     */
    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails, boolean byOwner) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null || booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.DECLINED) {
            throw new NotFoundException("No active booking for token " + manageToken);
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        Instant newEnd = newStartUtc.plusSeconds(60L * type.durationMinutes);

        // Exclude this booking so it may move freely within its own window.
        assertSlotAvailable(type, newStartUtc, booking.id);

        Instant oldStart = booking.startUtc;
        booking.startUtc = newStartUtc;
        booking.endUtc = newEnd;
        // Bump the iTIP SEQUENCE so guest .ics updates/cancels supersede the prior event.
        booking.icsSequence = booking.icsSequence + 1;

        boolean reApproval = type.requiresApproval;
        String priorEventId = booking.googleEventId;
        if (reApproval) {
            // Feature 14: return to the approval queue; drop any existing event.
            booking.status = BookingStatus.PENDING;
            booking.googleEventId = null;
            booking.meetLink = null;
        }

        // Reconcile guests (if the caller supplied a list) inside the same transaction. Collect the
        // ids of guests removed so we can fire cancel emails after commit.
        List<Long> removedGuestIds = reconcileGuests(booking, guestEmails);

        // NFR cross-node guard: flush so the no-overlap exclusion constraint is checked against
        // the new range; a concurrent overlap is surfaced as the same 409 as a double-book.
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + newStartUtc + " is not available for token " + manageToken);
            }
            throw ex;
        }

        // Cancel emails for guests the invitee removed (fired regardless of approval/auto).
        for (Long guestId : removedGuestIds) {
            guestRemovedEvent.fire(new GuestRemoved(booking.id, guestId));
        }

        if (reApproval) {
            if (calendarPort.isConnected(type.ownerId) && priorEventId != null) {
                calendarPort.deleteEvent(type.ownerId, priorEventId);
            }
            bookingRequestedEvent.fire(new BookingRequested(booking.id)); // re-approval request
        } else {
            if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
                OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
                calendarPort.updateEvent(
                        type.ownerId, booking.googleEventId, newStartUtc, newEnd, attendeeEmails(booking, owner));
            }
            bookingRescheduledEvent.fire(new BookingRescheduled(booking.id, oldStart, byOwner));
        }
        return booking;
    }

    /**
     * Reconciles the booking's guests to {@code guestEmails}. Returns the ids of guests transitioned to
     * REMOVED (so the caller can fire cancel emails after commit). A null list is a no-op (returns empty).
     */
    private List<Long> reconcileGuests(Booking booking, List<String> guestEmails) {
        if (guestEmails == null) {
            return List.of();
        }
        List<String> wanted = normalizeGuestEmails(guestEmails, booking.inviteeEmail);
        java.util.Set<String> wantedLower = new java.util.HashSet<>();
        for (String e : wanted) wantedLower.add(e.toLowerCase());

        // Existing rows for this booking, keyed by lowercase email.
        Map<String, BookingGuest> existing = new java.util.HashMap<>();
        for (BookingGuest g : BookingGuest.<BookingGuest>allForBooking(booking.id)) {
            existing.put(g.email.toLowerCase(), g);
        }

        // Add or re-activate wanted guests.
        for (String email : wanted) {
            BookingGuest g = existing.get(email.toLowerCase());
            if (g == null) {
                g = new BookingGuest();
                g.ownerId = booking.ownerId;
                g.bookingId = booking.id;
                g.email = email;
                g.declineToken = UUID.randomUUID().toString();
                g.createdAt = Instant.now();
                g.status = GuestStatus.INVITED;
                g.persist();
            } else if (g.status != GuestStatus.INVITED) {
                g.status = GuestStatus.INVITED; // re-invited a previously removed/declined guest
            }
        }

        // Remove active guests no longer wanted.
        List<Long> removed = new ArrayList<>();
        for (BookingGuest g : existing.values()) {
            if (g.status == GuestStatus.INVITED && !wantedLower.contains(g.email.toLowerCase())) {
                g.status = GuestStatus.REMOVED;
                removed.add(g.id);
            }
        }
        return removed;
    }

    @Transactional
    public void cancel(String manageToken) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        booking.status = BookingStatus.CANCELLED;
        if (calendarPort.isConnected(booking.ownerId) && booking.googleEventId != null) {
            calendarPort.deleteEvent(booking.ownerId, booking.googleEventId);
        }
        bookingCancelledEvent.fire(new BookingCancelled(booking.id));
    }

    @Transactional
    public void declineGuest(String declineToken) {
        BookingGuest guest = BookingGuest.findByDeclineToken(declineToken);
        if (guest == null) {
            throw new NotFoundException("No guest for token " + declineToken);
        }
        if (guest.status == GuestStatus.DECLINED) {
            return; // idempotent: a second decline click is a no-op
        }
        guest.status = GuestStatus.DECLINED;
        // Re-sync the Google attendee list (now excludes this guest, since activeForBooking returns only INVITED).
        // Google emails the removed guest a cancellation via sendUpdates=all.
        Booking booking = Booking.findById(guest.bookingId);
        if (booking != null && calendarPort.isConnected(guest.ownerId) && booking.googleEventId != null) {
            OwnerSettings owner = OwnerSettings.forOwner(guest.ownerId);
            calendarPort.updateEvent(
                    guest.ownerId,
                    booking.googleEventId,
                    booking.startUtc,
                    booking.endUtc,
                    attendeeEmails(booking, owner));
        }
        guestDeclinedEvent.fire(new GuestDeclined(guest.bookingId, guest.id));
    }
}
