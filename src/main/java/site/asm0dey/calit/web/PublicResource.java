package site.asm0dey.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarUnavailableException;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessageResolver;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.CurrentOwner;
import site.asm0dey.calit.user.Usernames;

@Path("/")
public class PublicResource {

    @CheckedTemplate
    // S107: Qute @CheckedTemplate signatures pass one arg per template variable; param count is inherent.
    @SuppressWarnings("java:S107")
    public static class Templates {
        public static native TemplateInstance index(String title, boolean authenticated, String username);

        public static native TemplateInstance landing(
                String title, List<MeetingType> types, String user, String ownerName);

        public static native TemplateInstance book(
                String title,
                String user,
                MeetingType type,
                List<PublicResource.DaySlots> days,
                List<site.asm0dey.calit.domain.BookingField> fields,
                String error,
                String tzBar,
                String tzScript,
                String calScript,
                boolean turnstileEnabled,
                String turnstileSiteKey,
                boolean googleConnected,
                String ownerName,
                String initialGuests);

        public static native TemplateInstance confirmation(
                String title,
                Booking booking,
                MeetingType type,
                String meetingName,
                boolean pending,
                String location,
                String whenLabel,
                String startUtcIso,
                String tzBar,
                String tzScript);

        public static native TemplateInstance manage(
                String title,
                Booking booking,
                String currentLabel,
                String currentUtcIso,
                List<PublicResource.DaySlots> days,
                String tzBar,
                String tzScript,
                String calScript,
                String initialGuests,
                String titleValue,
                String descriptionValue,
                String titlePlaceholder,
                String descPlaceholder);

        public static native TemplateInstance guestDeclineConfirm(
                String title,
                Booking booking,
                MeetingType type,
                String guestEmail,
                String guestDeclineToken,
                String tzScript);

        public static native TemplateInstance guestDeclined(String title);

        public static native TemplateInstance cancelConfirm(
                String title, Booking booking, MeetingType type, String meetingName, String tzScript);

        public static native TemplateInstance cancelled(String title);

        public static native TemplateInstance notReady(String title);

        public static native TemplateInstance unavailable(String title);
    }

    @Inject
    BookingService bookingService;

    @Inject
    CurrentOwner currentOwner;

    @Inject
    ActiveLocale activeLocale;

    @Inject
    AppMessageResolver messages;

    @jakarta.inject.Inject
    site.asm0dey.calit.google.CalendarPort calendarPort;

    // Root landing is public; with proactive auth this is the anonymous identity when logged out,
    // or the logged-in user's identity (so the landing can show Logout/Settings instead of Sign in).
    @jakarta.inject.Inject
    io.quarkus.security.identity.SecurityIdentity identity;

    // Owner-configurable Turnstile (feature 16). When disabled, the template skips the widget.
    @ConfigProperty(name = "calit.turnstile.enabled", defaultValue = "false")
    boolean turnstileEnabled;
    // SmallRye converts the empty-string property value to null, so bind it as Optional
    // and unwrap to "" — a non-Optional String injection would fail config validation.
    @ConfigProperty(name = "calit.turnstile.site-key")
    java.util.Optional<String> turnstileSiteKeyConfig;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** One selectable slot: human label + the UTC instant string used as the form value. */
    public record SlotView(String label, String startUtc) {}

    /** One day's worth of selectable slots: ISO date (for the JS calendar), a human label, and the slots. */
    public record DaySlots(String isoDate, String label, List<SlotView> slots) {}

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        // Root is a generic product page — NOT any owner's landing. Per-owner landings live at /{user}.
        // Auth-aware: a logged-in visitor sees Settings/Log out + their dashboard, not "Sign in".
        var m = messages.forLocale(activeLocale.current());
        var authenticated = !identity.isAnonymous();
        String username = authenticated ? identity.getPrincipal().getName() : null;
        return Templates.index(m.pub_index_title(), authenticated, username);
    }

    @GET
    @Path("/{user}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance userLanding(@PathParam("user") String user) {
        var m = messages.forLocale(activeLocale.current());
        AppUser owner = resolveOwner(user);
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        if (settings == null) {
            return Templates.notReady(m.pub_not_ready_title());
        }
        // listPublic(ownerId) = that owner's active && !secret types.
        return Templates.landing(
                m.pub_user_title(), MeetingType.listPublic(owner.id), owner.username, settings.ownerName);
    }

    @GET
    @Path("/{user}/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance book(@PathParam("user") String user, @PathParam("slug") String slug) {
        var m = messages.forLocale(activeLocale.current());
        AppUser owner = resolveOwner(user); // 404 if unknown; binds CurrentOwner
        MeetingType type = MeetingType.findBySlug(owner.id, slug); // secret types reachable by direct link
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        if (settings == null) {
            return Templates.notReady(m.pub_not_ready_title());
        }
        List<DaySlots> byDate;
        try {
            byDate = daySlots(type);
        } catch (CalendarUnavailableException e) {
            // Fail-closed: the owner's Google calendar can't be read, so we cannot safely offer slots.
            return Templates.unavailable(m.pub_unavailable_title());
        }
        // Resolved EXTRA fields (per-type-else-global), already ordered by position.
        List<BookingField> fields = BookingField.formFor(owner.id, type.id);
        // turnstileEnabled drives the widget; site key is public (rendered). The approval
        // flag (type.requiresApproval) + locationType/locationDetail are read off `type`
        // directly in the template for the button wording + location line.
        String bookTitle = m.pub_book_title_prefix() + " " + type.name;
        return Templates.book(
                bookTitle,
                owner.username,
                type,
                byDate,
                fields,
                null,
                Layout.TZ_BAR,
                Layout.TZ_SCRIPT,
                Layout.CALENDAR_SCRIPT,
                turnstileEnabled,
                turnstileSiteKey(),
                calendarPort.isConnected(owner.id),
                settings.ownerName,
                "");
    }

    private String turnstileSiteKey() {
        return turnstileSiteKeyConfig.orElse("");
    }

    /** Splits the optional "guests" form field on commas/whitespace into a clean email list. */
    private static List<String> parseGuests(MultivaluedMap<String, String> form) {
        String raw = form.getFirst("guests");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    @POST
    @Path("/{user}/{slug}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance submitBooking(
            @PathParam("user") String user,
            @PathParam("slug") String slug,
            @RestForm String startUtc,
            @RestForm String inviteeName,
            @RestForm String inviteeEmail,
            @RestForm String website, // honeypot
            @RestForm("cf-turnstile-response") String turnstileToken,
            MultivaluedMap<String, String> form) {
        var m = messages.forLocale(activeLocale.current());
        AppUser owner = resolveOwner(user); // 404 if unknown; binds CurrentOwner
        MeetingType type = MeetingType.findBySlug(owner.id, slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        if (settings == null) {
            return Templates.notReady(m.pub_not_ready_title());
        }
        String bookTitle = m.pub_book_title_prefix() + " " + type.name;

        // Collect every "answers.<fieldKey>" form param into the answers map (strip the prefix).
        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : form.entrySet()) {
            if (e.getKey().startsWith("answers.")) {
                answers.put(
                        e.getKey().substring("answers.".length()),
                        e.getValue().isEmpty() ? "" : e.getValue().getFirst());
            }
        }

        Booking booking;
        try {
            // book(...) enforces required fields AND the abuse guards (Turnstile + honeypot +
            // per-email/day cap) server-side; the handler just forwards the two raw inputs.
            // Locale is resolved server-side from the request (set by LocaleResolutionFilter).
            String locale = activeLocale.current().getLanguage();
            booking = bookingService.book(
                    owner.id,
                    slug,
                    Instant.parse(startUtc),
                    inviteeName,
                    inviteeEmail,
                    answers,
                    turnstileToken,
                    website,
                    locale,
                    parseGuests(form));
        } catch (BookingValidationException | AbuseException | RateLimitException | BookingConflictException be) {
            // Required-field 422 OR an abuse-guard rejection (filled honeypot / failed Turnstile /
            // per-email cap) / slot conflict. Re-render the form inline with the message; do NOT
            // 500, NOT confirm. (Plan 3 has no common BookingException superclass, so catch each.)
            return Templates.book(
                    bookTitle,
                    owner.username,
                    type,
                    daySlots(type),
                    BookingField.formFor(owner.id, type.id),
                    be.getMessage(),
                    Layout.TZ_BAR,
                    Layout.TZ_SCRIPT,
                    Layout.CALENDAR_SCRIPT,
                    turnstileEnabled,
                    turnstileSiteKey(),
                    calendarPort.isConnected(owner.id),
                    settings.ownerName,
                    "");
        }
        return confirmationPage(booking, type);
    }

    /** Renders the confirmation/request-sent page for a freshly created/rescheduled booking. */
    private TemplateInstance confirmationPage(Booking booking, MeetingType type) {
        var m = messages.forLocale(activeLocale.current());
        // Server fallback label is owner-tz; the page also carries the booked instant as a
        // data-utc attribute so the shared script can relabel it to the viewer's zone.
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        String when =
                booking.startUtc.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
        String startUtcIso = booking.startUtc.toString(); // absolute instant for data-utc
        // Approval types come back PENDING → "request sent" page (no Meet link yet); auto types
        // come back CONFIRMED → location/Meet confirmation.
        var pending = booking.status == BookingStatus.PENDING;
        String title = pending ? m.pub_conf_title_pending() : m.pub_conf_title_confirmed();
        String location =
                (type.locationType == MeetingType.LocationType.GOOGLE_MEET) ? booking.meetLink : type.locationDetail;
        String meetingName = booking.effectiveTitle(type);
        return Templates.confirmation(
                title,
                booking,
                type,
                meetingName,
                pending,
                location,
                when,
                startUtcIso,
                Layout.TZ_BAR,
                Layout.TZ_SCRIPT);
    }

    @GET
    @Path("/booking/{manageToken}/manage")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance manage(@PathParam("manageToken") String manageToken) {
        Booking booking = Booking.findByManageToken(manageToken); // unguessable key, not id
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken); // unknown token → 404
        }
        return renderManage(booking);
    }

    /** Render the invitee's Manage hub (shared by GET manage and POST edit-details). */
    private TemplateInstance renderManage(Booking booking) {
        var m = messages.forLocale(activeLocale.current());
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            return Templates.notReady(m.pub_not_ready_title());
        }
        ZoneId zone = ZoneId.of(settings.timezone);
        List<DaySlots> byDate;
        try {
            byDate = daySlots(type);
        } catch (CalendarUnavailableException e) {
            return Templates.unavailable(m.pub_unavailable_title());
        }
        String current =
                booking.startUtc.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
        String currentUtcIso = booking.startUtc.toString(); // absolute instant for data-utc
        String guestsCsv = BookingGuest.activeForBooking(booking.id).stream()
                .map(g -> g.email)
                .collect(java.util.stream.Collectors.joining(","));
        return Templates.manage(
                m.pub_manage_title(),
                booking,
                current,
                currentUtcIso,
                byDate,
                Layout.TZ_BAR,
                Layout.TZ_SCRIPT,
                Layout.CALENDAR_SCRIPT,
                guestsCsv,
                booking.title == null ? "" : booking.title, // raw override
                booking.description == null ? "" : booking.description,
                type.name,
                type.description == null ? "" : type.description);
    }

    @POST
    @Path("/booking/{manageToken}/edit-details")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    // Transactional so the reload below shares updateDetails' persistence context instead of hitting this
    // request's long-lived non-transactional EntityManager, which would still hold the pre-update entity
    // in its L1 cache and serve stale data back to renderManage (same reasoning as AdminResource's
    // ownerEditDetails).
    @jakarta.transaction.Transactional
    public TemplateInstance editDetails(
            @PathParam("manageToken") String manageToken,
            @RestForm String title,
            @RestForm String description,
            MultivaluedMap<String, String> form) {
        // Authenticated solely by the unguessable manage token. Re-renders the Manage hub with fresh values.
        bookingService.updateDetails(manageToken, title, description, parseGuests(form), false);
        Booking booking = Booking.findByManageToken(manageToken);
        return renderManage(booking);
    }

    @POST
    @Path("/booking/{manageToken}/reschedule")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance rescheduleBooking(@PathParam("manageToken") String manageToken, @RestForm String startUtc) {
        // Time only -- guests are managed separately via /booking/{token}/edit-details. Passing the 2-arg
        // overload leaves the guest set untouched (guestEmails=null).
        Booking booking = bookingService.reschedule(manageToken, Instant.parse(startUtc));
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return confirmationPage(booking, type);
    }

    @GET
    @Path("/booking/{manageToken}/cancel")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelConfirmPage(@PathParam("manageToken") String manageToken) {
        var m = messages.forLocale(activeLocale.current());
        Booking booking = Booking.findByManageToken(manageToken); // unguessable key, not id
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        if (booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.DECLINED) {
            // Already gone -> the same terminal page the POST cancel renders.
            return Templates.cancelled(m.pub_cancelled_title());
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return Templates.cancelConfirm(
                m.pub_cancel_confirm_title(), booking, type, booking.effectiveTitle(type), Layout.TZ_SCRIPT);
    }

    @POST
    @Path("/booking/{manageToken}/cancel")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelBooking(@PathParam("manageToken") String manageToken) {
        var m = messages.forLocale(activeLocale.current());
        bookingService.cancel(manageToken); // keyed by the token
        return Templates.cancelled(m.pub_cancelled_title());
    }

    @GET
    @Path("/guest/{declineToken}/decline")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance guestDeclineConfirm(@PathParam("declineToken") String declineToken) {
        var m = messages.forLocale(activeLocale.current());
        BookingGuest guest = BookingGuest.findByDeclineToken(declineToken); // unguessable key
        if (guest == null) {
            throw new NotFoundException("No guest for token " + declineToken);
        }
        if (guest.status != GuestStatus.INVITED) {
            return Templates.guestDeclined(m.pub_guest_declined_title()); // already declined/removed
        }
        Booking booking = Booking.findById(guest.bookingId);
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return Templates.guestDeclineConfirm(
                m.pub_guest_decline_confirm_title(), booking, type, guest.email, declineToken, Layout.TZ_SCRIPT);
    }

    @POST
    @Path("/guest/{declineToken}/decline")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance guestDecline(@PathParam("declineToken") String declineToken) {
        var m = messages.forLocale(activeLocale.current());
        bookingService.declineGuest(declineToken); // keyed by the token; idempotent
        return Templates.guestDeclined(m.pub_guest_declined_title());
    }

    /** Resolve the {user} segment to an owner, 404 if unknown, and bind CurrentOwner for the request. */
    private AppUser resolveOwner(String user) {
        AppUser owner = AppUser.findByUsername(Usernames.normalize(user));
        if (owner == null) {
            throw new NotFoundException("No user " + user);
        }
        currentOwner.set(owner);
        return owner;
    }

    /** Available slots as an ordered per-day list (ISO date + label), chronological. */
    private List<DaySlots> daySlots(MeetingType type) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        var from = LocalDate.now(zone);
        // Show the full configured booking horizon; availableSlots(...) also clamps to the same
        // horizon (now + horizonDays) and to min-notice, so this only sets the candidate range.
        LocalDate to = from.plusDays(type.horizonDays);
        Map<String, DaySlots> byIso = new LinkedHashMap<>();
        for (TimeSlot slot : bookingService.availableSlots(type, from, to)) {
            String isoDate = slot.start().toLocalDate().toString();
            var day = byIso.computeIfAbsent(
                    isoDate, k -> new DaySlots(k, slot.start().format(DATE_FMT), new java.util.ArrayList<>()));
            day.slots()
                    .add(new SlotView(
                            slot.start().format(TIME_FMT),
                            slot.start().toInstant().toString()));
        }
        return new java.util.ArrayList<>(byIso.values());
    }
}
