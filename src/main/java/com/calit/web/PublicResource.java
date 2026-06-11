package com.calit.web;

import com.calit.availability.TimeSlot;
import com.calit.booking.AbuseException;
import com.calit.booking.Booking;
import com.calit.booking.BookingConflictException;
import com.calit.booking.BookingService;
import com.calit.booking.BookingStatus;
import com.calit.booking.BookingValidationException;
import com.calit.booking.RateLimitException;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
public class PublicResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance index();

        public static native TemplateInstance landing(List<MeetingType> types, String user, String ownerName);

        public static native TemplateInstance book(
                MeetingType type,
                java.util.List<PublicResource.DaySlots> days,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String tzBar,
                String tzScript,
                String calScript,
                boolean turnstileEnabled,
                String turnstileSiteKey,
                boolean googleConnected,
                String ownerName);

        public static native TemplateInstance confirmation(
                com.calit.booking.Booking booking, com.calit.domain.MeetingType type,
                boolean pending, String location, String whenLabel, String startUtcIso,
                String tzBar, String tzScript);

        public static native TemplateInstance manage(
                com.calit.booking.Booking booking, String currentLabel, String currentUtcIso,
                java.util.List<PublicResource.DaySlots> days,
                String tzBar, String tzScript, String calScript);

        public static native TemplateInstance cancelled();

        public static native TemplateInstance notReady();
    }

    @Inject
    BookingService bookingService;

    @jakarta.inject.Inject
    com.calit.google.CalendarPort calendarPort;

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
    public record DaySlots(String isoDate, String label, java.util.List<SlotView> slots) {}

    /**
     * Resolve a meeting type by slug, globally, regardless of owner.
     * Phase 3 replaces this global lookup with /{user}/{slug} owner resolution.
     */
    private static MeetingType findBySlugGlobal(String slug) {
        return MeetingType.find("slug", slug).firstResult();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        // Root is a generic product page — NOT any owner's landing. Per-owner landings live at /{user}.
        return Templates.index();
    }

    @GET
    @Path("/book/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance book(@PathParam("slug") String slug) {
        // Phase 3 replaces this global lookup with /{user}/{slug} owner resolution.
        MeetingType type = findBySlugGlobal(slug); // secret types reachable by direct link
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            return Templates.notReady();
        }
        List<DaySlots> byDate = daySlots(type);
        // Resolved EXTRA fields (per-type-else-global), already ordered by position.
        List<BookingField> fields = BookingField.formFor(type.ownerId, type.id);
        // turnstileEnabled drives the widget; site key is public (rendered). The approval
        // flag (type.requiresApproval) + locationType/locationDetail are read off `type`
        // directly in the template for the button wording + location line.
        return Templates.book(type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(type.ownerId),
                              settings.ownerName);
    }

    private String turnstileSiteKey() {
        return turnstileSiteKeyConfig.orElse("");
    }

    @POST
    @Path("/book/{slug}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance submitBooking(@PathParam("slug") String slug,
                                          @RestForm String startUtc,
                                          @RestForm String inviteeName,
                                          @RestForm String inviteeEmail,
                                          @RestForm String website,                 // honeypot
                                          @RestForm("cf-turnstile-response") String turnstileToken,
                                          MultivaluedMap<String, String> form) {
        // Phase 3 replaces this global lookup with /{user}/{slug} owner resolution.
        MeetingType type = findBySlugGlobal(slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            return Templates.notReady();
        }

        // Collect every "answers.<fieldKey>" form param into the answers map (strip the prefix).
        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : form.entrySet()) {
            if (e.getKey().startsWith("answers.")) {
                answers.put(e.getKey().substring("answers.".length()),
                            e.getValue().isEmpty() ? "" : e.getValue().get(0));
            }
        }

        Booking booking;
        try {
            // book(...) enforces required fields AND the abuse guards (Turnstile + honeypot +
            // per-email/day cap) server-side; the handler just forwards the two raw inputs.
            booking = bookingService.book(
                    type.ownerId, slug, Instant.parse(startUtc), inviteeName, inviteeEmail, answers,
                    turnstileToken, website);
        } catch (BookingValidationException | AbuseException | RateLimitException
                 | BookingConflictException be) {
            // Required-field 422 OR an abuse-guard rejection (filled honeypot / failed Turnstile /
            // per-email cap) / slot conflict. Re-render the form inline with the message; do NOT
            // 500, NOT confirm. (Plan 3 has no common BookingException superclass, so catch each.)
            return Templates.book(type, daySlots(type), BookingField.formFor(type.ownerId, type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(type.ownerId),
                                  settings.ownerName);
        }
        return confirmationPage(booking, type);
    }

    /** Renders the confirmation/request-sent page for a freshly created/rescheduled booking. */
    private TemplateInstance confirmationPage(Booking booking, MeetingType type) {
        // Server fallback label is owner-tz; the page also carries the booked instant as a
        // data-utc attribute so the shared script can relabel it to the viewer's zone.
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        String when = booking.startUtc.atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
        String startUtcIso = booking.startUtc.toString(); // absolute instant for data-utc
        // Approval types come back PENDING → "request sent" page (no Meet link yet); auto types
        // come back CONFIRMED → location/Meet confirmation.
        boolean pending = booking.status == BookingStatus.PENDING;
        String location = (type.locationType == MeetingType.LocationType.GOOGLE_MEET)
                ? booking.meetLink : type.locationDetail;
        return Templates.confirmation(booking, type, pending, location, when, startUtcIso,
                                      Layout.TZ_BAR, Layout.TZ_SCRIPT);
    }

    @GET
    @Path("/booking/{manageToken}/manage")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance manage(@PathParam("manageToken") String manageToken) {
        Booking booking = Booking.findByManageToken(manageToken); // unguessable key, not id
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken); // unknown token → 404
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            return Templates.notReady();
        }
        ZoneId zone = ZoneId.of(settings.timezone);
        List<DaySlots> byDate = daySlots(type);
        String current = booking.startUtc.atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
        String currentUtcIso = booking.startUtc.toString(); // absolute instant for data-utc
        return Templates.manage(booking, current, currentUtcIso, byDate,
                                Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT);
    }

    @POST
    @Path("/booking/{manageToken}/reschedule")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance rescheduleBooking(@PathParam("manageToken") String manageToken,
                                              @RestForm String startUtc) {
        // startUtc is the absolute UTC instant the invitee chose; the viewer's display zone
        // never altered it (the picker only relabels). reschedule(...) is keyed by the token.
        // For approval types Plan 3 returns the booking to PENDING; auto types stay CONFIRMED.
        Booking booking = bookingService.reschedule(manageToken, Instant.parse(startUtc));
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return confirmationPage(booking, type);
    }

    @POST
    @Path("/booking/{manageToken}/cancel")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelBooking(@PathParam("manageToken") String manageToken) {
        bookingService.cancel(manageToken); // keyed by the token
        return Templates.cancelled();
    }

    /** Available slots as an ordered per-day list (ISO date + label), chronological. */
    private List<DaySlots> daySlots(MeetingType type) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        LocalDate from = LocalDate.now(zone);
        // Show the full configured booking horizon; availableSlots(...) also clamps to the same
        // horizon (now + horizonDays) and to min-notice, so this only sets the candidate range.
        LocalDate to = from.plusDays(type.horizonDays);
        Map<String, DaySlots> byIso = new LinkedHashMap<>();
        for (TimeSlot slot : bookingService.availableSlots(type, from, to)) {
            String isoDate = slot.start().toLocalDate().toString();
            DaySlots day = byIso.computeIfAbsent(isoDate,
                    k -> new DaySlots(k, slot.start().format(DATE_FMT), new java.util.ArrayList<>()));
            day.slots().add(new SlotView(slot.start().format(TIME_FMT),
                                         slot.start().toInstant().toString()));
        }
        return new java.util.ArrayList<>(byIso.values());
    }
}
