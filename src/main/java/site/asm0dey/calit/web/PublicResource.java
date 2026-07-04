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
import site.asm0dey.calit.i18n.AppMessages;
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
                String title, List<PublicResource.LandingType> types, String user, String ownerName);

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

        /** Alias resolves to a multi-host type that isn't fully bookable yet (a co-host hasn't accepted). */
        public static native TemplateInstance hostPending(String title);
    }

    @Inject
    BookingService bookingService;

    @Inject
    MeetingHosts meetingHosts;

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

    /**
     * One landing-page entry: the type plus the URL it should be booked at. Own types book at
     * `/{user}/{slug}`; co-hosted types book at the CANONICAL `/{creatorUsername}/{slug}` so every
     * host's landing links to the same, single booking URL.
     */
    public record LandingType(MeetingType type, String bookUrl) {}

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
        // listPublicIncludingCohosted(ownerId) = that owner's own active && !secret types PLUS
        // multi-host types where they are an ACCEPTED co-host. Co-hosted entries link to the
        // canonical /{creatorUsername}/{slug} — every host's landing points at the same URL.
        // A multi-host candidate is filtered out here (domain layer can't reach MeetingHosts,
        // a CDI bean, without bad layering) unless meetingHosts.bookable(t) — i.e. EVERY host row
        // is ACCEPTED and enabled, not just the viewer's own row. Applies to the owner's own
        // multi-host types too: a creator's not-yet-fully-accepted type is hidden from their own
        // landing as well.
        List<MeetingType> candidates = MeetingType.listPublicIncludingCohosted(owner.id);
        // Batch the multi-host bookability check: one host query + one user query for the whole
        // set, instead of isMultiHost() + bookable() (forType + findById per host) per type.
        java.util.Set<Long> bookableIds = meetingHosts.bookableTypeIds(candidates);
        List<LandingType> types = candidates.stream()
                .filter(t -> bookableIds.contains(t.id))
                .map(t -> new LandingType(t, "/" + bookUsernameFor(t, owner) + "/" + t.slug))
                .toList();
        return Templates.landing(m.pub_user_title(), types, owner.username, settings.ownerName);
    }

    @GET
    @Path("/{user}/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance book(@PathParam("user") String user, @PathParam("slug") String slug) {
        var m = messages.forLocale(activeLocale.current());
        BookingTarget target = resolveBookingTarget(user, slug, m);
        if (target.earlyExit() != null) {
            return target.earlyExit();
        }
        AppUser urlUser = target.urlUser();
        MeetingType type = target.type();
        OwnerSettings settings = target.settings();
        List<DaySlots> byDate;
        try {
            byDate = daySlots(type);
        } catch (CalendarUnavailableException e) {
            // Fail-closed: the owner's Google calendar can't be read, so we cannot safely offer slots.
            return Templates.unavailable(m.pub_unavailable_title());
        }
        // Resolved EXTRA fields (per-type-else-global), already ordered by position.
        List<BookingField> fields = BookingField.formFor(type.ownerId, type.id);
        // turnstileEnabled drives the widget; site key is public (rendered). The approval
        // flag (type.requiresApproval) + locationType/locationDetail are read off `type`
        // directly in the template for the button wording + location line.
        String bookTitle = m.pub_book_title_prefix() + " " + type.name;
        return Templates.book(
                bookTitle,
                urlUser.username, // keeps the form posting back to the alias URL it was reached at
                type,
                byDate,
                fields,
                null,
                Layout.TZ_BAR,
                Layout.TZ_SCRIPT,
                Layout.CALENDAR_SCRIPT,
                turnstileEnabled,
                turnstileSiteKey(),
                calendarPort.isConnected(type.ownerId),
                settings.ownerName,
                "");
    }

    private String turnstileSiteKey() {
        return turnstileSiteKeyConfig.orElse("");
    }

    /** Resolved booking target; a non-null {@code earlyExit} page means "render it and stop". */
    private record BookingTarget(
            MeetingType type, OwnerSettings settings, AppUser urlUser, TemplateInstance earlyExit) {}

    /**
     * Resolve {@code {user}/{slug}} to a bookable type: 404 on unknown, bind {@link CurrentOwner} to
     * the type's CREATOR (context is always the creator, whichever alias URL was used), then gate on
     * owner-setup + host readiness. Returns an {@code earlyExit} page (notReady / hostPending)
     * instead of a target when the type isn't bookable yet. Shared by the GET and POST handlers.
     */
    private BookingTarget resolveBookingTarget(String user, String slug, AppMessages m) {
        AppUser urlUser = resolveOwner(user); // 404 if unknown; binds CurrentOwner (may be a co-host alias)
        // resolveForAlias: urlUser's own type wins, else a multi-host type urlUser is an ACCEPTED
        // co-host of (secret types still reachable by direct link, as before).
        MeetingType type = MeetingType.resolveForAlias(urlUser.id, slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        currentOwner.set(AppUser.findById(type.ownerId));
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            return new BookingTarget(type, null, urlUser, Templates.notReady(m.pub_not_ready_title()));
        }
        if (!meetingHosts.bookable(type)) {
            // A co-host invite is still PENDING (or a host got disabled) — no aliases can book yet.
            return new BookingTarget(type, settings, urlUser, Templates.hostPending(m.pub_host_pending_title()));
        }
        return new BookingTarget(type, settings, urlUser, null);
    }

    /** The username a landing entry should book at: the owner's own for their types, else the creator's. */
    private static String bookUsernameFor(MeetingType t, AppUser landingOwner) {
        if (t.ownerId.equals(landingOwner.id)) {
            return landingOwner.username;
        }
        AppUser creator = AppUser.findById(t.ownerId);
        return creator.username;
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
            @RestForm("altcha") String altchaSolution,
            MultivaluedMap<String, String> form) {
        var m = messages.forLocale(activeLocale.current());
        BookingTarget target = resolveBookingTarget(user, slug, m);
        if (target.earlyExit() != null) {
            return target.earlyExit();
        }
        AppUser urlUser = target.urlUser();
        MeetingType type = target.type();
        OwnerSettings settings = target.settings();
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
                    type.ownerId,
                    type.slug,
                    Instant.parse(startUtc),
                    inviteeName,
                    inviteeEmail,
                    answers,
                    turnstileToken,
                    altchaSolution,
                    website,
                    locale,
                    parseGuests(form));
        } catch (BookingValidationException | AbuseException | RateLimitException | BookingConflictException be) {
            // Required-field 422 OR an abuse-guard rejection (filled honeypot / failed Turnstile /
            // per-email cap) / slot conflict. Re-render the form inline with the message; do NOT
            // 500, NOT confirm. (Plan 3 has no common BookingException superclass, so catch each.)
            return Templates.book(
                    bookTitle,
                    urlUser.username,
                    type,
                    daySlots(type),
                    BookingField.formFor(type.ownerId, type.id),
                    be.getMessage(),
                    Layout.TZ_BAR,
                    Layout.TZ_SCRIPT,
                    Layout.CALENDAR_SCRIPT,
                    turnstileEnabled,
                    turnstileSiteKey(),
                    calendarPort.isConnected(type.ownerId),
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
