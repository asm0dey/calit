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
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.BusyInterval;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarUnavailableException;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.i18n.AppLocales;

@ApplicationScoped
public class BookingService {

    // Collaborators + config are constructor-injected (immutable, fail-fast, plain-unit testable,
    // consistent with the Plan 2 google package). The six CDI Event<> emitters below stay as @Inject
    // fields: they are framework plumbing and would otherwise bloat the constructor to ten args.
    private final SlotService slotService;
    private final CalendarPort calendarPort;
    private final CaptchaVerifier captchaVerifier;
    private final MeetingHosts meetingHosts;
    private final long perEmailDailyCap;

    /**
     * Max guests an invitee may attach to one booking. ponytail: a constant, not a config knob.
     */
    public static final int MAX_GUESTS_PER_BOOKING = 10;

    @Inject
    public BookingService(
            SlotService slotService,
            CalendarPort calendarPort,
            CaptchaVerifier captchaVerifier,
            MeetingHosts meetingHosts,
            @ConfigProperty(name = "calit.abuse.per-email-daily-cap", defaultValue = "10") long perEmailDailyCap) {
        this.slotService = slotService;
        this.calendarPort = calendarPort;
        this.captchaVerifier = captchaVerifier;
        this.meetingHosts = meetingHosts;
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

    @Inject
    Event<BookingDetailsChanged> bookingDetailsChangedEvent;

    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to) {
        return availableSlots(type, from, to, (Long) null);
    }

    /**
     * Bookable slots = raw work-hour slots (Plan 1b override semantics already applied) whose
     * buffered interval does not overlap any busy interval — busy = Google free/busy
     * (only when {@code isConnected()}) + all PENDING/CONFIRMED bookings — and which also survive
     * the min-notice and horizon filters relative to {@code now} (feature 11). {@code excludeBookingId}
     * omits one booking from the busy set (used by reschedule so a booking can move within its own window).
     */
    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to, Long excludeBookingId) {
        return availableSlots(type, from, to, excludeBookingId == null ? Set.of() : Set.of(excludeBookingId));
    }

    /**
     * Same as {@link #availableSlots(MeetingType, LocalDate, LocalDate, Long)} but excludes every id in
     * {@code excludeBookingIds} from every host's busy set. Needed by a GROUP reschedule re-check
     * ({@link #rescheduleGroup}): a group has one row per host, and until the move loop runs every row
     * still sits at the OLD time, so excluding only the row whose manageToken resolved the reschedule
     * left each OTHER host's own sibling row counted as busy against itself (Task 11 review fix).
     */
    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to, Set<Long> excludeBookingIds) {
        if (!meetingHosts.bookable(type)) {
            return List.of();
        }
        List<Long> hostIds = meetingHosts.hostOwnerIds(type);
        var now = Instant.now();
        Instant earliest = now.plusSeconds(60L * type.minNoticeMinutes);
        Instant latest = now.plus(type.horizonDays, ChronoUnit.DAYS);

        // Per-host free set, keyed by slot-start instant; a slot survives only if free for ALL hosts.
        // Single-host keeps the pre-multi-host contract of letting CalendarUnavailableException
        // propagate (the web layer renders the "temporarily unavailable" page for it); only the
        // multi-host case fails closed to an empty list, since a caller can't sensibly render
        // "unavailable" for just one host out of several bookable ones.
        var singleHost = hostIds.size() == 1;
        Map<Instant, TimeSlot> candidate = null;
        for (Long hostId : hostIds) {
            Map<Instant, TimeSlot> hostFree;
            if (singleHost) {
                hostFree = hostFreeSlots(type, hostId, from, to, excludeBookingIds, earliest, latest, singleHost);
            } else {
                try {
                    hostFree = hostFreeSlots(type, hostId, from, to, excludeBookingIds, earliest, latest, singleHost);
                } catch (CalendarUnavailableException _) {
                    return List.of(); // any host's calendar unverifiable (multi-host) -> offer nothing
                }
            }
            if (candidate == null) {
                candidate = hostFree;
            } else {
                candidate.keySet().retainAll(hostFree.keySet()); // intersection by start instant
            }
            if (candidate.isEmpty()) {
                return List.of();
            }
        }
        return candidate == null ? List.of() : new ArrayList<>(candidate.values());
    }

    /**
     * One host's free slot-start set for {@code [from, to]}: raw slots (buffer/min-notice/horizon
     * filters applied) whose buffered interval does not overlap that host's busy set. Always
     * returns a real map -- never {@code null}. Lets {@link CalendarUnavailableException} from
     * {@link #busyIntervals} propagate to the caller; the single-host caller lets it bubble up
     * further (the web layer renders the "temporarily unavailable" page for it), while the
     * multi-host caller catches it and fails closed to an empty slot list for the whole request,
     * since it can't sensibly render "unavailable" for just one host out of several bookable ones.
     * Split out of {@link #availableSlots(MeetingType, LocalDate, LocalDate, Set)} to keep that
     * method's cognitive complexity in check.
     */
    private Map<Instant, TimeSlot> hostFreeSlots(
            MeetingType type,
            Long hostId,
            LocalDate from,
            LocalDate to,
            Set<Long> excludeBookingIds,
            Instant earliest,
            Instant latest,
            boolean singleHost) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(hostId).timezone);
        var fromInstant = from.atStartOfDay(zone).toInstant();
        var toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Interval> busy = busyIntervals(hostId, fromInstant, toInstant, excludeBookingIds);
        int bufBefore = meetingHosts.effectiveBufferBefore(type, hostId);
        int bufAfter = meetingHosts.effectiveBufferAfter(type, hostId);
        Map<Instant, TimeSlot> hostFree = new LinkedHashMap<>();
        for (TimeSlot slot : slotService.generateRawSlots(type, hostId, from, to, !singleHost)) {
            Instant slotStart = slot.start().toInstant();
            // Feature 11: drop too-soon (before now+minNotice) and too-far (after now+horizon) slots.
            if (slotStart.isBefore(earliest) || slotStart.isAfter(latest)) {
                continue;
            }
            Interval buffered = new Interval(
                    slotStart.minusSeconds(60L * bufBefore),
                    slot.end().toInstant().plusSeconds(60L * bufAfter));
            if (!buffered.overlapsAny(busy)) {
                hostFree.put(slotStart, slot);
            }
        }
        return hostFree;
    }

    /**
     * Google busy intervals (only when connected — degraded mode skips freeBusy) plus all
     * PENDING+CONFIRMED bookings in the window (minus an excluded one). PENDING is included so a
     * pending approval request holds its slot (feature 14).
     */
    List<Interval> busyIntervals(Long ownerId, Instant from, Instant to, Long excludeBookingId) {
        return busyIntervals(ownerId, from, to, excludeBookingId == null ? Set.of() : Set.of(excludeBookingId));
    }

    /**
     * Same as {@link #busyIntervals(Long, Instant, Instant, Long)} but excludes every id in
     * {@code excludeBookingIds} rather than just one — see {@link #availableSlots(MeetingType, LocalDate,
     * LocalDate, Set)}.
     */
    List<Interval> busyIntervals(Long ownerId, Instant from, Instant to, Set<Long> excludeBookingIds) {
        List<Interval> busy = new ArrayList<>();
        if (calendarPort.isConnected(ownerId)) {
            for (BusyInterval bi : calendarPort.freeBusy(ownerId, from, to)) {
                busy.add(new Interval(bi.start(), bi.end()));
            }
        }
        for (Booking b : Booking.<Booking>heldOverlapping(ownerId, from, to)) {
            if (excludeBookingIds.contains(b.id)) {
                continue;
            }
            busy.add(new Interval(b.startUtc, b.endUtc));
        }
        return busy;
    }

    /** Backward-compatible overload: no ALTCHA solution (turnstile/none paths, and all existing tests). */
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
        return book(
                ownerId,
                meetingTypeSlug,
                startUtc,
                inviteeName,
                inviteeEmail,
                answers,
                turnstileToken,
                null,
                honeypot,
                locale,
                guestEmails);
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
            String altchaSolution,
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
        captchaVerifier.verify(turnstileToken, altchaSolution); // -> AbuseException (400) on invalid CAPTCHA
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
        assertSlotAvailable(type, startUtc, (Long) null);

        if (MeetingTypeHost.isMultiHost(type.id)) {
            return bookGroup(type, startUtc, endUtc, inviteeName, inviteeEmail, submitted, locale, guestEmails);
        }

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
     * Multi-host booking write: one row per accepted host, sharing a new {@code group_id}, inside a
     * single flush so a cross-node overlap on ANY host's row rolls back the whole group (same 409 the
     * single-host path uses). Guests attach to the lead (creator's) row only. Approval types insert N
     * PENDING rows (each with its own approvalToken) and fire one {@code BookingRequested(leadId)}; auto
     * types insert N CONFIRMED rows and create exactly one Google event on the chosen organizer.
     */
    private Booking bookGroup(
            MeetingType type,
            Instant startUtc,
            Instant endUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String locale,
            List<String> guestEmails) {
        var groupId = UUID.randomUUID();
        List<Long> hostIds = meetingHosts.hostOwnerIds(type);
        boolean approval = type.requiresApproval;
        Booking lead = null;
        try {
            for (Long hostId : hostIds) {
                Booking b = new Booking();
                b.ownerId = hostId;
                b.meetingTypeId = type.id;
                b.inviteeName = inviteeName;
                b.inviteeEmail = inviteeEmail;
                b.startUtc = startUtc;
                b.endUtc = endUtc;
                b.createdAt = Instant.now();
                b.manageToken = UUID.randomUUID().toString();
                b.answers = answers;
                b.locale = AppLocales.isSupported(locale) ? locale : "en";
                b.status = approval ? BookingStatus.PENDING : BookingStatus.CONFIRMED;
                if (approval) {
                    b.approvalToken = UUID.randomUUID().toString();
                }
                b.groupId = groupId;
                b.persist();
                if (hostId.equals(type.ownerId)) {
                    lead = b;
                }
            }
            Booking.getEntityManager().flush(); // surface booking_no_overlap_held now
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException("Slot " + startUtc + " is not available for " + type.slug);
            }
            throw ex;
        }

        // guests attach to the lead row only
        persistGuests(lead, guestEmails);

        if (approval) {
            bookingRequestedEvent.fire(new BookingRequested(lead.id));
            return lead;
        }

        createGroupGoogleEvent(type, groupId);
        bookingConfirmedEvent.fire(new BookingConfirmed(lead.id));
        return lead;
    }

    /** One Google event on the chosen organizer, all other hosts + invitee + guests invited. */
    private void createGroupGoogleEvent(MeetingType type, UUID groupId) {
        List<Long> hostIds = meetingHosts.hostOwnerIds(type);
        Long organizer = meetingHosts.chooseOrganizer(type, hostIds);
        if (organizer == null) {
            return; // no host has Google -> calit-only booking
        }
        Booking lead = Booking.leadOfGroup(groupId, type.ownerId);
        Booking organizerRow = organizer.equals(type.ownerId)
                ? lead
                : Booking.<Booking>group(groupId).stream()
                        .filter(r -> r.ownerId.equals(organizer))
                        .findFirst()
                        .orElseThrow();
        List<String> attendees = groupAttendeeEmails(type, groupId, hostIds);
        CreatedEvent created = calendarPort.createEvent(
                organizer,
                googleSummary(type, lead),
                googleDescription(type, lead),
                lead.startUtc,
                lead.endUtc,
                attendees,
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
        organizerRow.googleEventId = created.googleEventId();
        organizerRow.meetLink = created.meetLink();
        // propagate the meet link to the lead row too so invitee-facing views show it
        if (lead.meetLink == null) {
            lead.meetLink = created.meetLink();
        }
    }

    /** Invitee + every host's OwnerSettings.ownerEmail + active guests (guests live on the lead row). */
    private List<String> groupAttendeeEmails(MeetingType type, UUID groupId, List<Long> hostIds) {
        List<String> emails = new ArrayList<>();
        Booking lead = Booking.leadOfGroup(groupId, type.ownerId);
        emails.add(lead.inviteeEmail);
        for (Long hostId : hostIds) {
            OwnerSettings os = OwnerSettings.forOwner(hostId);
            if (os != null && os.ownerEmail != null) {
                emails.add(os.ownerEmail);
            }
        }
        for (BookingGuest g : BookingGuest.<BookingGuest>activeForBooking(lead.id)) {
            emails.add(g.email);
        }
        return emails;
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

    /**
     * Cleaned, de-duped (case-insensitive), capped, invitee-excluded guest list. Preserves order.
     */
    private static List<String> normalizeGuestEmails(List<String> guestEmails, String inviteeEmail) {
        if (guestEmails == null || guestEmails.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, String> byLower = new LinkedHashMap<>();
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
                googleSummary(type, booking),
                googleDescription(type, booking),
                booking.startUtc,
                booking.endUtc,
                attendeeEmails(booking, owner),
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
        booking.googleEventId = created.googleEventId();
        booking.meetLink = created.meetLink();
    }

    /**
     * Google event SUMMARY for a booking: "{effective title} with {invitee}".
     */
    private static String googleSummary(MeetingType type, Booking booking) {
        return booking.effectiveTitle(type) + " with " + booking.inviteeName;
    }

    /**
     * Google event DESCRIPTION: the booking's effective description, or "" when none. Empty (not null) so a
     * PATCH actively clears a removed description (a null field is omitted from the patch and would linger).
     */
    private static String googleDescription(MeetingType type, Booking booking) {
        String d = booking.effectiveDescription(type);
        return d == null ? "" : d;
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
        if (Booking.countDistinctBookingsByEmailBetween(inviteeEmail, dayStart, dayEnd) >= perEmailDailyCap) {
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

    /**
     * True if {@code ex} (or a cause) is the no-overlap exclusion-constraint violation.
     */
    private boolean isNoOverlapViolation(Throwable ex) {
        for (var t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && "booking_no_overlap_held".equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Throws BookingConflictException unless an available slot starts exactly at {@code startUtc}.
     */
    private void assertSlotAvailable(MeetingType type, Instant startUtc, Long excludeBookingId) {
        assertSlotAvailable(type, startUtc, excludeBookingId == null ? Set.of() : Set.of(excludeBookingId));
    }

    /**
     * Same as {@link #assertSlotAvailable(MeetingType, Instant, Long)} but excludes every id in
     * {@code excludeBookingIds} — used by the group reschedule re-check.
     */
    private void assertSlotAvailable(MeetingType type, Instant startUtc, Set<Long> excludeBookingIds) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        var day = startUtc.atZone(zone).toLocalDate();
        boolean ok = availableSlots(type, day, day, excludeBookingIds).stream()
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
        // Group idempotency guard: a double-submit (double-click / back-button replay) of approve
        // on an already-processed group row must not re-run createGroupGoogleEvent / re-fire
        // BookingConfirmed. Single-host is unaffected (groupId == null -> guard is false).
        if (booking.groupId != null && booking.status != BookingStatus.PENDING) {
            return;
        }
        booking.status = BookingStatus.CONFIRMED;
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        if (booking.groupId == null) {
            // Single-host: unchanged from pre-multi-host behavior.
            // Feature 14 + degraded mode: create the Google event now, only when connected.
            if (calendarPort.isConnected(type.ownerId)) {
                createGoogleEvent(type, booking);
            }
            bookingApprovedEvent.fire(new BookingApproved(bookingId));
            return;
        }
        // Group: row-status is the source of truth for "all approved" -- no extra group-state
        // column. Only the LAST host's approval (no group row still PENDING) creates the ONE
        // shared Google event and fires the invitee-facing BookingConfirmed; earlier approvals
        // just flip that host's row and wait.
        boolean anyPending =
                Booking.<Booking>group(booking.groupId).stream().anyMatch(r -> r.status == BookingStatus.PENDING);
        if (!anyPending) {
            createGroupGoogleEvent(type, booking.groupId);
            Booking lead = Booking.leadOfGroup(booking.groupId, type.ownerId);
            bookingConfirmedEvent.fire(new BookingConfirmed(lead.id));
        }
    }

    @Transactional
    public void decline(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            throw new NotFoundException("No booking " + bookingId);
        }
        // Group idempotency guard: a double-submit of decline on an already-DECLINED group row is
        // a no-op (the group was already killed by the first decline). Single-host is unaffected.
        if (booking.groupId != null && booking.status == BookingStatus.DECLINED) {
            return;
        }
        if (booking.groupId == null) {
            // Single-host: unchanged. DECLINED leaves the PENDING|CONFIRMED partial constraint ->
            // frees the slot. A PENDING request has no Google event, so nothing to delete.
            booking.status = BookingStatus.DECLINED;
            bookingDeclinedEvent.fire(new BookingDeclined(bookingId));
            return;
        }
        // Group: any single host declining kills the whole group -- no event was created
        // pre-confirmation, so there's nothing to delete on Google's side.
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        for (Booking row : Booking.<Booking>group(booking.groupId)) {
            row.status = BookingStatus.DECLINED;
        }
        Booking lead = Booking.leadOfGroup(booking.groupId, type.ownerId);
        bookingDeclinedEvent.fire(new BookingDeclined(lead.id));
    }

    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc) {
        return reschedule(manageToken, newStartUtc, null, false, null);
    }

    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails) {
        return reschedule(manageToken, newStartUtc, guestEmails, false, null);
    }

    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails, boolean byOwner) {
        return reschedule(manageToken, newStartUtc, guestEmails, byOwner, null);
    }

    /**
     * Reschedules a booking by its manage token. When {@code guestEmails} is non-null the active guest
     * set is reconciled to it (added guests -> INVITED + GuestRemoved/invite emails downstream; dropped
     * guests -> REMOVED + cancel email; kept guests get the reschedule .ics via BookingRescheduled).
     * A null {@code guestEmails} leaves guests untouched (the JSON API + the 2/3-arg overloads).
     * {@code byOwner} true when the host drove the reschedule (from /me or an owner email link), which
     * flips the notification wording so nobody is told the guest moved it, and — for an approval type —
     * spares the initiating host's own approval (see below). {@code initiatorOwnerId} is the acting
     * host's owner id, used only for a multi-host group reschedule; null for the invitee path and for
     * every single-host overload (single host has only one possible initiating host, so there is nothing
     * to disambiguate).
     */
    @Transactional
    public Booking reschedule(
            String manageToken, Instant newStartUtc, List<String> guestEmails, boolean byOwner, Long initiatorOwnerId) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null || booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.DECLINED) {
            throw new NotFoundException("No active booking for token " + manageToken);
        }

        // No-op: same time and guests untouched (web callers pass null) -> nothing to do. Avoids a spurious
        // SEQUENCE bump + reschedule email when the invitee re-picks the current slot.
        if (newStartUtc.equals(booking.startUtc) && guestEmails == null) {
            return booking;
        }

        if (booking.groupId != null) {
            return rescheduleGroup(booking, newStartUtc, guestEmails, byOwner, initiatorOwnerId);
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

        // Deliberate behavior change (Task 11): an owner-initiated reschedule of an approval type no
        // longer reverts to PENDING -- only an invitee-initiated one does. A host already knows their
        // own slot is free (they just picked it), so re-approving themselves would be theater.
        var reApproval = type.requiresApproval && !byOwner;
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

        applyRescheduleOutcome(booking, type, oldStart, priorEventId, reApproval, byOwner);
        return booking;
    }

    /**
     * Post-commit calendar sync + domain event for a reschedule. Re-approval types drop any prior Google
     * event and re-enter the approval queue (BookingRequested); auto types patch the existing event's time
     * (when connected) and fire BookingRescheduled. Split out of {@link #reschedule} to keep that method's
     * cognitive complexity in check.
     */
    private void applyRescheduleOutcome(
            Booking booking,
            MeetingType type,
            Instant oldStart,
            String priorEventId,
            boolean reApproval,
            boolean byOwner) {
        if (reApproval) {
            if (calendarPort.isConnected(type.ownerId) && priorEventId != null) {
                calendarPort.deleteEvent(type.ownerId, priorEventId);
            }
            bookingRequestedEvent.fire(new BookingRequested(booking.id)); // re-approval request
        } else {
            if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
                OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
                calendarPort.updateEvent(
                        type.ownerId,
                        booking.googleEventId,
                        booking.startUtc,
                        booking.endUtc,
                        attendeeEmails(booking, owner));
            }
            bookingRescheduledEvent.fire(new BookingRescheduled(booking.id, oldStart, byOwner));
        }
    }

    /**
     * Group reschedule: re-checks the multi-host intersection at the new time, moves every row in the
     * group (start/end/SEQUENCE), and drops the one shared Google event (recreated on full re-confirm).
     * For an approval type this re-enters the approval queue: an invitee-initiated reschedule
     * ({@code initiatorOwnerId == null}) resets every host row to PENDING; a host-initiated one spares
     * only the initiating host's row (that host just picked the slot, so re-approving themselves would
     * be theater) and resets every other host. A non-approval type just moves the rows and recreates the
     * event. {@code row} may be any row of the group (its manageToken resolved the group); only its
     * {@code groupId}/{@code meetingTypeId}/{@code startUtc} are read.
     */
    private Booking rescheduleGroup(
            Booking row, Instant newStartUtc, List<String> guestEmails, boolean byOwner, Long initiatorOwnerId) {
        MeetingType type = MeetingType.findById(row.meetingTypeId);
        Instant newEnd = newStartUtc.plusSeconds(60L * type.durationMinutes);

        // Re-check the intersection across all hosts at the new time (fail-closed inside availableSlots).
        // Exclude EVERY row of the group, not just `row` -- a group has one row per host, all still at
        // the OLD time until the move loop below runs, so excluding a single row left each OTHER host's
        // own sibling row counted as busy against itself (Task 11 review fix: falsely rejected a small
        // shift / adjacent-slot reschedule whenever a buffer made the new slot's buffered interval
        // overlap the group's own old occupied interval).
        Set<Long> groupRowIds = new HashSet<>();
        for (Booking r : Booking.<Booking>group(row.groupId)) {
            groupRowIds.add(r.id);
        }
        assertSlotAvailable(type, newStartUtc, groupRowIds);

        boolean reApproval = type.requiresApproval;
        var initiator = byOwner ? initiatorOwnerId : null;

        deleteGroupGoogleEvent(row.groupId); // drop the one event; recreated below only on full re-confirm
        Instant oldStart = row.startUtc;
        moveGroupRows(row, newStartUtc, newEnd, reApproval, initiator);

        Booking freshLead = Booking.leadOfGroup(row.groupId, type.ownerId);
        // Guests live on the lead row only.
        List<Long> removedGuestIds = guestEmails != null ? reconcileGuests(freshLead, guestEmails) : List.of();
        for (Long guestId : removedGuestIds) {
            guestRemovedEvent.fire(new GuestRemoved(freshLead.id, guestId));
        }

        if (reApproval) {
            bookingRequestedEvent.fire(new BookingRequested(freshLead.id)); // hosts re-approve
        } else {
            createGroupGoogleEvent(type, row.groupId);
            bookingRescheduledEvent.fire(new BookingRescheduled(freshLead.id, oldStart, byOwner));
        }
        return freshLead;
    }

    private void moveGroupRows(Booking row, Instant newStartUtc, Instant newEnd, boolean reApproval, Long initiator) {
        for (Booking r : Booking.<Booking>group(row.groupId)) {
            r.startUtc = newStartUtc;
            r.endUtc = newEnd;
            r.icsSequence = r.icsSequence + 1;
            if (reApproval) {
                var keepsApproval = initiator != null && initiator.equals(r.ownerId);
                r.status = keepsApproval ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
                if (!keepsApproval && r.approvalToken == null) {
                    r.approvalToken = UUID.randomUUID().toString();
                }
            }
        }
    }

    /**
     * Edits a booking's meeting name, description, and guest list by its manage token — usable by both the
     * host (byOwner=true) and the invitee (byOwner=false). {@code title}/{@code description} are trimmed;
     * blank stores null so the meeting type's value shows through (bounded: title ≤ 200, description ≤ 2000).
     * {@code guestEmails} reconciles the active guest set exactly (empty list removes all). If nothing
     * actually changed (normalized title/description/guest-set all equal current) this is a NO-OP: it
     * returns without bumping the SEQUENCE, patching Google, or emailing anyone. On a real change it bumps
     * the iTIP SEQUENCE, patches the Google event (name/description + attendees), fires GuestRemoved per
     * dropped guest and BookingDetailsChanged so EmailService re-notifies invitee + owner + guests. Never
     * changes status or time.
     */
    @Transactional
    public Booking updateDetails(
            String manageToken, String title, String description, List<String> guestEmails, boolean byOwner) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null || booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.DECLINED) {
            throw new NotFoundException("No active booking for token " + manageToken);
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);

        if (booking.groupId != null) {
            return updateGroupDetails(booking, type, title, description, guestEmails, byOwner);
        }

        var newTitle = blankToNull(title);
        var newDescription = blankToNull(description);
        validateDetailBounds(newTitle, newDescription);
        List<String> wanted = normalizeGuestEmails(guestEmails, booking.inviteeEmail);

        // No-op guard: nothing changed → no notification storm, no SEQUENCE churn.
        if (java.util.Objects.equals(newTitle, booking.title)
                && java.util.Objects.equals(newDescription, booking.description)
                && sameGuestSet(booking, wanted)) {
            return booking;
        }

        booking.title = newTitle;
        booking.description = newDescription;
        // Bump SEQUENCE so an updated .ics supersedes the prior one in the recipient's calendar.
        booking.icsSequence = booking.icsSequence + 1;

        List<Long> removedGuestIds = reconcileGuests(booking, guestEmails);
        booking.persistAndFlush();

        for (Long guestId : removedGuestIds) {
            guestRemovedEvent.fire(new GuestRemoved(booking.id, guestId));
        }

        if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
            OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
            calendarPort.updateEventDetails(
                    type.ownerId,
                    booking.googleEventId,
                    googleSummary(type, booking),
                    googleDescription(type, booking),
                    attendeeEmails(booking, owner));
        }

        bookingDetailsChangedEvent.fire(new BookingDetailsChanged(booking.id, byOwner));
        return booking;
    }

    /**
     * Group counterpart of {@link #updateDetails}: title/description are written to every row in the
     * group (kept identical across hosts), each row's iTIP SEQUENCE bumps (same as {@link
     * #rescheduleGroup}) so a resent guest .ics supersedes the prior one, guests reconcile on the lead
     * row only with {@link GuestRemoved} fired per dropped guest, and — if the group has a shared
     * Google event and the organizer is still connected — it is patched exactly once via the
     * organizer's row (a disconnected organizer skips the remote patch, mirroring {@link
     * #updateDetails}'s {@code isConnected} guard, but local rows still update). Fires a single
     * {@link BookingDetailsChanged} keyed by the lead. No no-op short-circuit here; unlike the
     * single-host path, group edits always propagate to keep every row's title/description in
     * lockstep.
     */
    private Booking updateGroupDetails(
            Booking booking,
            MeetingType type,
            String title,
            String description,
            List<String> guestEmails,
            boolean byOwner) {
        var newTitle = blankToNull(title);
        var newDescription = blankToNull(description);
        validateDetailBounds(newTitle, newDescription);

        for (Booking r : Booking.<Booking>group(booking.groupId)) {
            r.title = newTitle;
            r.description = newDescription;
            // Bump the iTIP SEQUENCE so guest .ics updates supersede the prior one (same as rescheduleGroup).
            r.icsSequence = r.icsSequence + 1;
        }
        Booking lead = Booking.leadOfGroup(booking.groupId, type.ownerId);
        if (guestEmails != null) {
            List<Long> removedGuestIds = reconcileGuests(lead, guestEmails);
            for (Long guestId : removedGuestIds) {
                guestRemovedEvent.fire(new GuestRemoved(lead.id, guestId));
            }
        }

        String eventId = groupEventId(booking.groupId);
        Long organizer = organizerOwnerOf(booking.groupId);
        if (eventId != null && organizer != null && calendarPort.isConnected(organizer)) {
            calendarPort.updateEventDetails(
                    organizer,
                    eventId,
                    googleSummary(type, lead),
                    googleDescription(type, lead),
                    groupAttendeeEmails(type, booking.groupId, meetingHosts.hostOwnerIds(type)));
        }

        bookingDetailsChangedEvent.fire(new BookingDetailsChanged(lead.id, byOwner));
        return lead;
    }

    /**
     * The Google event id shared by a group booking (only the organizer's row carries it — see
     * {@link #createGroupGoogleEvent}), or null when the group never had a Google event.
     */
    private String groupEventId(UUID groupId) {
        for (Booking r : Booking.<Booking>group(groupId)) {
            if (r.googleEventId != null) {
                return r.googleEventId;
            }
        }
        return null;
    }

    /**
     * The owner id of the group row that carries the shared Google event (the organizer), or null
     * when the group never had a Google event.
     */
    private Long organizerOwnerOf(UUID groupId) {
        for (Booking r : Booking.<Booking>group(groupId)) {
            if (r.googleEventId != null) {
                return r.ownerId;
            }
        }
        return null;
    }

    /**
     * Trim; null for null/blank so a cleared override falls back to the meeting type's value.
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Enforce the same input-bounds discipline as validateInputBounds (SEC-INPUT): title ≤ 200, desc ≤ 2000.
     */
    private static void validateDetailBounds(String title, String description) {
        if (title != null && title.length() > 200) {
            throw new BookingValidationException("Meeting name is too long.");
        }
        if (description != null && description.length() > 2000) {
            throw new BookingValidationException("Description is too long.");
        }
    }

    /**
     * True iff the booking's current active guest set equals {@code wanted} (case-insensitive).
     */
    private static boolean sameGuestSet(Booking booking, List<String> wanted) {
        Set<String> current = new HashSet<>();
        for (BookingGuest g : BookingGuest.<BookingGuest>activeForBooking(booking.id)) {
            current.add(g.email.toLowerCase());
        }
        Set<String> want = new HashSet<>();
        for (String e : wanted) {
            want.add(e.toLowerCase());
        }
        return current.equals(want);
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
        Set<String> wantedLower = new HashSet<>();
        for (String e : wanted) wantedLower.add(e.toLowerCase());

        // Existing rows for this booking, keyed by lowercase email.
        Map<String, BookingGuest> existing = new HashMap<>();
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
        cancel(manageToken, false);
    }

    /**
     * {@code byOwner} true when the host cancelled (from /me or an owner email link) -> initiator-aware wording.
     * A group booking (any host may cancel on behalf of the whole meeting) deletes the one shared Google
     * event, flips every host's row to CANCELLED, and fires a single {@code BookingCancelled} keyed by the
     * lead row; single-host behavior is unchanged.
     */
    @Transactional
    public void cancel(String manageToken, boolean byOwner) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        if (booking.groupId == null) {
            cancelSingle(booking, byOwner);
            return;
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        deleteGroupGoogleEvent(booking.groupId); // one shared event
        for (Booking r : Booking.<Booking>group(booking.groupId)) {
            r.status = BookingStatus.CANCELLED;
        }
        Booking lead = Booking.leadOfGroup(booking.groupId, type.ownerId);
        bookingCancelledEvent.fire(new BookingCancelled(lead.id, byOwner));
    }

    private void cancelSingle(Booking booking, boolean byOwner) {
        booking.status = BookingStatus.CANCELLED;
        if (calendarPort.isConnected(booking.ownerId) && booking.googleEventId != null) {
            calendarPort.deleteEvent(booking.ownerId, booking.googleEventId);
        }
        bookingCancelledEvent.fire(new BookingCancelled(booking.id, byOwner));
    }

    /**
     * Deletes the one Google event shared by a group (only the organizer's row carries it — see
     * {@link #createGroupGoogleEvent}) and clears it from that row. A no-op when the group never had a
     * Google event (degraded mode, or no host was connected at confirm time). If the organizer has
     * since disconnected Google, the remote delete is skipped (mirrors {@link #cancelSingle}'s
     * {@code isConnected} guard) but the local event refs are still cleared — the calit-side state
     * must not be left dangling just because the remote call can't be made.
     */
    private void deleteGroupGoogleEvent(UUID groupId) {
        for (Booking r : Booking.<Booking>group(groupId)) {
            if (r.googleEventId != null) {
                if (calendarPort.isConnected(r.ownerId)) {
                    calendarPort.deleteEvent(r.ownerId, r.googleEventId);
                }
                r.googleEventId = null;
                r.meetLink = null;
            }
        }
    }

    /**
     * Task 18: cascade for the co-host-removal keep-vs-cancel interstitial. Cancels every future
     * (PENDING|CONFIRMED) group booking that {@code hostOwnerId} is a party to on {@code type} —
     * each whole group (all hosts' rows -> CANCELLED, the one shared Google event deleted, one
     * {@code BookingCancelled} fired), by delegating to the same {@link #cancel(String, boolean)}
     * used by the manual group-cancel path. Lives here (not on {@link MeetingHosts}) so it can
     * reuse {@code cancel} without a constructor cycle — {@code BookingService} already depends on
     * {@code MeetingHosts}, not the other way around. Deduplicates by group id so a host with only
     * one row per group (the normal case) is cancelled exactly once per group even if this method
     * is ever called from a batch context.
     */
    @Transactional
    public void cancelFutureGroupBookingsForHost(MeetingType type, Long hostOwnerId) {
        List<Booking> rows = Booking.list(
                "ownerId = ?1 and meetingTypeId = ?2 and startUtc > ?3 and status in ?4 and groupId is not null",
                hostOwnerId,
                type.id,
                Instant.now(),
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED));
        Set<UUID> cancelledGroups = new HashSet<>();
        for (Booking row : rows) {
            if (cancelledGroups.add(row.groupId)) {
                cancel(row.manageToken, true);
            }
        }
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
