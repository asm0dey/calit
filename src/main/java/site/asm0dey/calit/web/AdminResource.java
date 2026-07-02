package site.asm0dey.calit.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.domain.BookingField.FieldType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.i18n.AdminMessages;

@Path("/me")
@RolesAllowed("user")
public class AdminResource {

    @CheckedTemplate
    // S107: Qute @CheckedTemplate signatures pass one arg per template variable; param count is inherent.
    @SuppressWarnings("java:S107")
    public static class Templates {
        public static native TemplateInstance dashboard(
                List<Booking> upcoming, long pendingCount, String tzScript, boolean isAdmin, String title);

        public static native TemplateInstance meetingTypes(
                List<MeetingType> types,
                LocationType[] locationTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String username,
                String baseUrl,
                String title);

        public static native TemplateInstance meetingTypeDetail(
                MeetingType type,
                List<BookingField> fields,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                LocationType[] locationTypes,
                BookingField.FieldType[] fieldTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String title);

        public static native TemplateInstance availability(
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<MeetingType> types,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String title);

        public static native TemplateInstance settings(
                OwnerSettings settings,
                int reminderLeadMinutes,
                Long pendingCount,
                List<String> zones,
                boolean isAdmin,
                String title);

        public static native TemplateInstance bookingFields(
                List<BookingField> fields,
                BookingField.FieldType[] fieldTypes,
                Long pendingCount,
                boolean isAdmin,
                String title);

        public static native TemplateInstance dateOverrides(
                List<DateOverride> overrides,
                List<MeetingType> types,
                Long pendingCount,
                boolean isAdmin,
                String title);

        public static native TemplateInstance pending(
                List<Booking> pending, String tzScript, boolean isAdmin, String title);

        public static native TemplateInstance manageBooking(
                Booking booking,
                String currentLabel,
                String currentUtcIso,
                List<PublicResource.DaySlots> days,
                String guestsCsv,
                Long pendingCount,
                boolean isAdmin,
                String tzBar,
                String tzScript,
                String calScript,
                String title,
                String titleValue,
                String descriptionValue,
                String titlePlaceholder,
                String descPlaceholder);

        public static native TemplateInstance approvalResult(
                Long pendingCount, boolean isAdmin, String title, String h1, String desc);
    }

    @Inject
    BookingService bookingService;

    @Inject
    site.asm0dey.calit.user.CurrentOwner currentOwner;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @Inject
    SecurityIdentity identity;

    @Inject
    AdminMessageResolver adminMsgs;

    @Inject
    ActiveLocale activeLocale;

    /** True when the logged-in user holds the site-admin role (drives the Users nav link). */
    private boolean isAdmin() {
        return identity.hasRole("admin");
    }

    /** Returns the localized admin message bundle for the current request's locale. */
    private AdminMessages m() {
        return adminMsgs.forLocale(activeLocale.current());
    }

    @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "1440")
    int reminderLeadMinutes;

    // Mirrors PublicResource.daySlots formatting; the client TZ script relabels to the viewer's zone,
    // so this server label is only a fallback. ponytail: extract a shared helper if a 3rd consumer appears.
    private static final DateTimeFormatter MANAGE_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");
    private static final DateTimeFormatter MANAGE_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Available slots for a meeting type as an ordered per-day list (reuses the public view records). */
    private List<PublicResource.DaySlots> daySlots(MeetingType type) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        var from = LocalDate.now(zone);
        LocalDate to = from.plusDays(type.horizonDays);
        Map<String, PublicResource.DaySlots> byIso = new LinkedHashMap<>();
        for (TimeSlot slot : bookingService.availableSlots(type, from, to)) {
            String isoDate = slot.start().toLocalDate().toString();
            var day = byIso.computeIfAbsent(
                    isoDate,
                    k -> new PublicResource.DaySlots(
                            k, slot.start().format(MANAGE_DATE_FMT), new java.util.ArrayList<>()));
            day.slots()
                    .add(new PublicResource.SlotView(
                            slot.start().format(MANAGE_TIME_FMT),
                            slot.start().toInstant().toString()));
        }
        return new java.util.ArrayList<>(byIso.values());
    }

    /** Pending-approval count for the shared admin nav badge. */
    private long pendingCount() {
        return Booking.count(
                "ownerId = ?1 and status = ?2", currentOwner.id(), site.asm0dey.calit.booking.BookingStatus.PENDING);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        // Upcoming confirmed bookings, soonest first. PENDING ones live in the approval queue
        // (GET /me/pending), not here.
        List<Booking> upcoming = Booking.list(
                "ownerId = ?1 and status = ?2 and startUtc >= ?3 order by startUtc",
                currentOwner.id(),
                site.asm0dey.calit.booking.BookingStatus.CONFIRMED,
                java.time.Instant.now());
        var pendingCount = pendingCount();
        return Templates.dashboard(upcoming, pendingCount, Layout.TZ_SCRIPT, isAdmin(), m().adm_dashboard_title());
    }

    @GET
    @Path("/meeting-types")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance meetingTypes() {
        // Pass LocationType.values() so the form can render the location dropdown options.
        return Templates.meetingTypes(
                MeetingType.listForOwner(currentOwner.id()),
                allowedLocationTypes(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                currentOwner.require().username,
                baseUrl,
                m().adm_meetingTypes_title()); // includes secret
    }

    @POST
    @Path("/meeting-types")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createMeetingType(
            @RestForm String name,
            @RestForm String slug,
            @RestForm int durationMinutes,
            @RestForm @DefaultValue("0") int bufferBeforeMinutes,
            @RestForm @DefaultValue("0") int bufferAfterMinutes,
            @RestForm String secret,
            @RestForm int minNoticeMinutes,
            @RestForm int horizonDays,
            @RestForm String locationType,
            @RestForm String locationDetail,
            @RestForm String slotIntervalMinutes,
            @RestForm String requiresApproval,
            MultivaluedMap<String, String> form) {
        MeetingType t = new MeetingType();
        t.ownerId = currentOwner.id();
        t.name = name;
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(currentOwner.id(), slugBase, null);
        t.durationMinutes = durationMinutes;
        t.bufferBeforeMinutes = bufferBeforeMinutes;
        t.bufferAfterMinutes = bufferAfterMinutes;
        t.secret = "on".equals(secret); // unchecked checkbox sends no value
        t.minNoticeMinutes = minNoticeMinutes;
        t.horizonDays = horizonDays;
        t.locationType = parseLocationType(locationType);
        t.locationDetail = (locationDetail == null || locationDetail.isBlank()) ? null : locationDetail;
        // Slot cadence: blank = back-to-back (null → falls back to durationMinutes).
        t.slotIntervalMinutes = (slotIntervalMinutes == null || slotIntervalMinutes.isBlank())
                ? null
                : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        t.persist(); // need the generated id before scoping child rules/overrides to it
        createInitialWorkingHours(t.id, t.ownerId, form);
        createInitialDateOverride(t.id, t.ownerId, form);
        return Templates.meetingTypes(
                MeetingType.listForOwner(currentOwner.id()),
                allowedLocationTypes(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                currentOwner.require().username,
                baseUrl,
                m().adm_meetingTypes_title());
    }

    /**
     * Per-type weekly working hours captured on the create form. The form posts parallel
     * arrays ruleDay[]/ruleStart[]/ruleEnd[] (one row per weekday); a row with a blank
     * start or end is skipped.
     */
    private void createInitialWorkingHours(Long typeId, Long ownerId, MultivaluedMap<String, String> form) {
        List<String> days = form.getOrDefault("ruleDay", List.of());
        List<String> starts = form.getOrDefault("ruleStart", List.of());
        List<String> ends = form.getOrDefault("ruleEnd", List.of());
        for (var i = 0; i < days.size() && i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) {
                continue;
            }
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.meetingTypeId = typeId;
            r.dayOfWeek = DayOfWeek.valueOf(days.get(i));
            r.startTime = LocalTime.parse(starts.get(i));
            r.endTime = LocalTime.parse(ends.get(i));
            r.persist();
        }
    }

    /**
     * Optional per-type date override captured on the create form: a single overrideDate
     * plus parallel windowStart[]/windowEnd[] arrays. Blank date → no override; a date with
     * no (non-blank) windows → day off.
     */
    private void createInitialDateOverride(Long typeId, Long ownerId, MultivaluedMap<String, String> form) {
        String date = form.getFirst("overrideDate");
        if (date == null || date.isBlank()) {
            return;
        }
        DateOverride o = new DateOverride();
        o.ownerId = ownerId;
        o.meetingTypeId = typeId;
        o.overrideDate = LocalDate.parse(date);
        o.persist(); // need the generated id before persisting child windows
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (var i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) {
                continue;
            }
            DateOverrideWindow w = new DateOverrideWindow();
            w.dateOverrideId = o.id;
            w.startTime = LocalTime.parse(starts.get(i));
            w.endTime = LocalTime.parse(ends.get(i));
            w.persist();
        }
    }

    @POST
    @Path("/meeting-types/{id}/toggle")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance toggleActive(@PathParam("id") Long id) {
        MeetingType t = requireType(id);
        t.active = !t.active;
        return Templates.meetingTypes(
                MeetingType.listForOwner(currentOwner.id()),
                allowedLocationTypes(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                currentOwner.require().username,
                baseUrl,
                m().adm_meetingTypes_title());
    }

    @POST
    @Path("/meeting-types/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteMeetingType(@PathParam("id") Long id) {
        requireType(id);
        MeetingType.deleteById(id);
        return Templates.meetingTypes(
                MeetingType.listForOwner(currentOwner.id()),
                allowedLocationTypes(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                currentOwner.require().username,
                baseUrl,
                m().adm_meetingTypes_title());
    }

    /** Date overrides scoped to one meeting type, each with its (transient) windows loaded. */
    private List<DateOverride> overridesForType(Long typeId) {
        return withWindows(DateOverride.list("meetingTypeId = ?1 order by overrideDate", typeId));
    }

    /**
     * Loads each override's (transient) {@code windows} in ONE query for the whole list instead of
     * one-per-override (N+1). Preserves the given override ordering and per-override start-time
     * window ordering. Overrides with no windows get an empty list (day off).
     */
    private static List<DateOverride> withWindows(List<DateOverride> overrides) {
        if (overrides.isEmpty()) {
            return overrides;
        }
        List<Long> ids = overrides.stream().map(o -> o.id).toList();
        Map<Long, List<DateOverrideWindow>> byOverride =
                DateOverrideWindow.<DateOverrideWindow>list("dateOverrideId in ?1 order by startTime asc", ids).stream()
                        .collect(java.util.stream.Collectors.groupingBy(w -> w.dateOverrideId));
        for (DateOverride o : overrides) {
            o.windows = byOverride.getOrDefault(o.id, List.of());
        }
        return overrides;
    }

    /**
     * Location types offered on the create form. Drops GOOGLE_MEET when this owner's write-target
     * calendar can't mint Meet links, so the option is never even shown (it would 400 at booking).
     */
    private LocationType[] allowedLocationTypes() {
        if (GoogleCalendar.writeTargetBlocksMeet(currentOwner.id())) {
            return java.util.Arrays.stream(LocationType.values())
                    .filter(lt -> lt != LocationType.GOOGLE_MEET)
                    .toArray(LocationType[]::new);
        }
        return LocationType.values();
    }

    /**
     * Enforces the gate behind {@link #allowedLocationTypes()} for the actual write (the edit form
     * still shows every type so a stale value renders, and crafted POSTs must not slip through):
     * GOOGLE_MEET is rejected when the write target can't create Meet links.
     */
    private LocationType parseLocationType(String locationType) {
        LocationType lt = LocationType.valueOf(locationType);
        if (lt == LocationType.GOOGLE_MEET && GoogleCalendar.writeTargetBlocksMeet(currentOwner.id())) {
            throw new BadRequestException(
                    "The selected write-target calendar can't create Google Meet links; pick another location.");
        }
        return lt;
    }

    /** Load a meeting type or 404 — shared guard for detail-scoped GET/POST handlers. */
    private MeetingType requireType(Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t == null || !t.ownerId.equals(currentOwner.id())) {
            throw new NotFoundException("No meeting type " + id);
        }
        return t;
    }

    /** Re-render the detail page for one meeting type (shared by every detail-scoped handler). */
    private TemplateInstance detailInstance(Long id) {
        MeetingType t = requireType(id);
        List<BookingField> fields = BookingField.list("meetingTypeId = ?1 order by position", id);
        List<AvailabilityRule> rules = AvailabilityRule.list("meetingTypeId = ?1 order by dayOfWeek", id);
        List<DateOverride> overrides = overridesForType(id);
        String title = m().adm_meetingTypeDetail_title_prefix().stripTrailing() + " " + t.name;
        return Templates.meetingTypeDetail(
                t,
                fields,
                rules,
                weekRows(rules),
                overrides,
                LocationType.values(),
                BookingField.FieldType.values(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                title);
    }

    @GET
    @Path("/meeting-types/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance meetingTypeDetail(@PathParam("id") Long id) {
        requireType(id);
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance editMeetingType(
            @PathParam("id") Long id,
            @RestForm String name,
            @RestForm String slug,
            @RestForm int durationMinutes,
            @RestForm @DefaultValue("0") int bufferBeforeMinutes,
            @RestForm @DefaultValue("0") int bufferAfterMinutes,
            @RestForm String secret,
            @RestForm int minNoticeMinutes,
            @RestForm int horizonDays,
            @RestForm String locationType,
            @RestForm String locationDetail,
            @RestForm String slotIntervalMinutes,
            @RestForm String requiresApproval) {
        MeetingType t = requireType(id);
        t.name = name;
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(currentOwner.id(), slugBase, id);
        t.durationMinutes = durationMinutes;
        t.bufferBeforeMinutes = bufferBeforeMinutes;
        t.bufferAfterMinutes = bufferAfterMinutes;
        t.secret = "on".equals(secret);
        t.minNoticeMinutes = minNoticeMinutes;
        t.horizonDays = horizonDays;
        t.locationType = parseLocationType(locationType);
        t.locationDetail = (locationDetail == null || locationDetail.isBlank()) ? null : locationDetail;
        t.slotIntervalMinutes = (slotIntervalMinutes == null || slotIntervalMinutes.isBlank())
                ? null
                : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        return detailInstance(id); // managed entity flushes on commit
    }

    @POST
    @Path("/meeting-types/{id}/booking-fields")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeField(
            @PathParam("id") Long id,
            @RestForm String label,
            @RestForm String fieldKey,
            @RestForm String type,
            @RestForm String required,
            @RestForm @DefaultValue("0") int position) {
        requireType(id);
        BookingField f = new BookingField();
        f.ownerId = currentOwner.id();
        f.meetingTypeId = id;
        f.label = label;
        f.fieldKey = fieldKey;
        f.type = FieldType.valueOf(type);
        f.required = "on".equals(required);
        f.position = position;
        f.persist();
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/booking-fields/{fid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteTypeField(@PathParam("id") Long id, @PathParam("fid") Long fid) {
        requireType(id);
        BookingField f = BookingField.findById(fid);
        if (f != null && id.equals(f.meetingTypeId)) {
            BookingField.deleteById(fid);
        }
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/availability")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeRule(
            @PathParam("id") Long id,
            @RestForm String dayOfWeek,
            @RestForm String startTime,
            @RestForm String endTime) {
        requireType(id);
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = currentOwner.id();
        r.meetingTypeId = id;
        r.dayOfWeek = DayOfWeek.valueOf(dayOfWeek);
        r.startTime = LocalTime.parse(startTime);
        r.endTime = LocalTime.parse(endTime);
        r.persist();
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/availability/bulk")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance saveTypeWeeklyRules(@PathParam("id") Long id, MultivaluedMap<String, String> form) {
        requireType(id); // 404 a cross-owner type
        // Replace-all for this type's schedule only; global rules (meetingTypeId null) are untouched.
        AvailabilityRule.delete("ownerId = ?1 and meetingTypeId = ?2", currentOwner.id(), id);
        persistFrames(currentOwner.id(), id, form);
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/availability/{rid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteTypeRule(@PathParam("id") Long id, @PathParam("rid") Long rid) {
        requireType(id);
        AvailabilityRule r = AvailabilityRule.findById(rid);
        if (r != null && id.equals(r.meetingTypeId)) {
            AvailabilityRule.deleteById(rid);
        }
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeOverride(
            @PathParam("id") Long id, @RestForm String date, MultivaluedMap<String, String> form) {
        requireType(id);
        DateOverride o = new DateOverride();
        o.ownerId = currentOwner.id();
        o.meetingTypeId = id;
        o.overrideDate = LocalDate.parse(date);
        o.persist(); // need the generated id before persisting child windows
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (var i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) {
                continue;
            }
            DateOverrideWindow w = new DateOverrideWindow();
            w.dateOverrideId = o.id;
            w.startTime = LocalTime.parse(starts.get(i));
            w.endTime = LocalTime.parse(ends.get(i));
            w.persist();
        }
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/date-overrides/{oid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteTypeOverride(@PathParam("id") Long id, @PathParam("oid") Long oid) {
        requireType(id);
        DateOverride o = DateOverride.findById(oid);
        if (o != null && id.equals(o.meetingTypeId)) {
            DateOverrideWindow.delete("dateOverrideId = ?1", oid);
            DateOverride.deleteById(oid);
        }
        return detailInstance(id);
    }

    /** This owner's availability rules — global defaults + per-type — ordered for display. */
    private List<AvailabilityRule> ownerRules() {
        return AvailabilityRule.list("ownerId = ?1 order by meetingTypeId nulls first, dayOfWeek", currentOwner.id());
    }

    /** This owner's GLOBAL default rules only (meetingTypeId IS NULL), for the weekly grid. */
    private List<AvailabilityRule> globalRules() {
        return AvailabilityRule.list("ownerId = ?1 and meetingTypeId is null order by dayOfWeek", currentOwner.id());
    }

    /** Group rules into the fixed seven-row weekly grid. */
    private static List<WeekRow> weekRows(List<AvailabilityRule> rules) {
        return WeekRow.fromRules(rules);
    }

    @GET
    @Path("/availability")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance availability() {
        return Templates.availability(
                ownerRules(),
                weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                m().adm_availability_title());
    }

    @POST
    @Path("/availability")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createRule(
            @RestForm String dayOfWeek,
            @RestForm String startTime,
            @RestForm String endTime,
            @RestForm String meetingTypeId) {
        // Blank meetingTypeId = this owner's GLOBAL default rule. A non-blank id must be owned.
        var typeId = (meetingTypeId == null || meetingTypeId.isBlank()) ? null : Long.valueOf(meetingTypeId);
        if (typeId != null) {
            requireType(typeId); // 404 a cross-owner type
        }
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = currentOwner.id();
        r.meetingTypeId = typeId; // null = global default
        r.dayOfWeek = DayOfWeek.valueOf(dayOfWeek);
        r.startTime = LocalTime.parse(startTime);
        r.endTime = LocalTime.parse(endTime);
        r.persist();
        return Templates.availability(
                ownerRules(),
                weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                m().adm_availability_title());
    }

    @POST
    @Path("/availability/bulk")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance saveWeeklyRules(MultivaluedMap<String, String> form) {
        // Replace-all for this owner's GLOBAL schedule: wipe the scope, re-insert posted frames.
        AvailabilityRule.delete("ownerId = ?1 and meetingTypeId is null", currentOwner.id());
        persistFrames(currentOwner.id(), null, form);
        return Templates.availability(
                ownerRules(),
                weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                m().adm_availability_title());
    }

    /**
     * Zip parallel frameDay[]/frameStart[]/frameEnd[] arrays into AvailabilityRule rows for one
     * scope (meetingTypeId null = global, non-null = per-type). Skips a frame whose start or end is
     * blank, or whose end is not strictly after its start.
     */
    private void persistFrames(Long ownerId, Long meetingTypeId, MultivaluedMap<String, String> form) {
        List<String> days = form.getOrDefault("frameDay", List.of());
        List<String> starts = form.getOrDefault("frameStart", List.of());
        List<String> ends = form.getOrDefault("frameEnd", List.of());
        for (var i = 0; i < days.size() && i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) {
                continue;
            }
            var start = LocalTime.parse(starts.get(i));
            var end = LocalTime.parse(ends.get(i));
            if (!end.isAfter(start)) {
                continue;
            } // drop zero-length / inverted frames
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.meetingTypeId = meetingTypeId;
            r.dayOfWeek = DayOfWeek.valueOf(days.get(i));
            r.startTime = start;
            r.endTime = end;
            r.persist();
        }
    }

    @POST
    @Path("/availability/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteRule(@PathParam("id") Long id) {
        AvailabilityRule r = AvailabilityRule.findById(id);
        if (r != null && currentOwner.id().equals(r.ownerId)) {
            AvailabilityRule.deleteById(id);
        }
        return Templates.availability(
                ownerRules(),
                weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                m().adm_availability_title());
    }

    /** All IANA zone ids, sorted — for the Settings timezone combobox. */
    private static List<String> zoneIds() {
        return java.time.ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    @GET
    @Path("/settings")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        return Templates.settings(
                OwnerSettings.forOwner(currentOwner.id()),
                reminderLeadMinutes,
                pendingCount(),
                zoneIds(),
                isAdmin(),
                m().adm_settings_title());
    }

    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance updateSettings(
            @RestForm String ownerName,
            @RestForm String ownerEmail,
            @RestForm String timezone,
            @RestForm String locale,
            @RestForm String ownerNotificationsEnabled) {
        OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = currentOwner.id();
        }
        s.ownerName = ownerName;
        s.ownerEmail = ownerEmail;
        s.timezone = timezone;
        s.locale = site.asm0dey.calit.i18n.AppLocales.isSupported(locale) ? locale : "en";
        // Unchecked checkbox sends no value → notifications OFF (owner opt-out).
        s.ownerNotificationsEnabled = "on".equals(ownerNotificationsEnabled);
        s.persist();
        // The locale filter already ran (before this handler) with the OLD value; refresh the
        // request-scoped locale so THIS response (title, {adm:} keys, language dropdown) is in the new language.
        activeLocale.set(site.asm0dey.calit.i18n.AppLocales.pick(s.locale));
        return Templates.settings(
                s, reminderLeadMinutes, pendingCount(), zoneIds(), isAdmin(), m().adm_settings_title());
    }

    @GET
    @Path("/booking-fields")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance bookingFields() {
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()),
                FieldType.values(),
                pendingCount(),
                isAdmin(),
                m().adm_bookingFields_title());
    }

    @POST
    @Path("/booking-fields")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createBookingField(
            @RestForm String label,
            @RestForm String fieldKey,
            @RestForm String type,
            @RestForm String required,
            @RestForm int position) {
        BookingField f = new BookingField();
        f.ownerId = currentOwner.id();
        f.label = label;
        f.fieldKey = fieldKey;
        f.type = FieldType.valueOf(type);
        f.required = "on".equals(required); // unchecked checkbox sends no value
        f.position = position;
        f.meetingTypeId = null; // standalone page manages this owner's global defaults
        f.persist();
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()),
                FieldType.values(),
                pendingCount(),
                isAdmin(),
                m().adm_bookingFields_title());
    }

    @POST
    @Path("/booking-fields/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteBookingField(@PathParam("id") Long id) {
        BookingField f = BookingField.findById(id);
        if (f != null && currentOwner.id().equals(f.ownerId)) {
            BookingField.deleteById(id);
        }
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()),
                FieldType.values(),
                pendingCount(),
                isAdmin(),
                m().adm_bookingFields_title());
    }

    /**
     * All overrides with their (transient) {@code windows} loaded for display.
     * {@link DateOverride#windows} is @Transient (not cascade-mapped), so listAll()
     * leaves it empty; we populate each from {@link DateOverrideWindow} by id.
     */
    private List<DateOverride> overridesWithWindows() {
        return withWindows(
                DateOverride.list("ownerId = ?1 order by meetingTypeId nulls first, overrideDate", currentOwner.id()));
    }

    @GET
    @Path("/date-overrides")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dateOverrides() {
        return Templates.dateOverrides(
                overridesWithWindows(),
                MeetingType.listForOwner(currentOwner.id()),
                pendingCount(),
                isAdmin(),
                m().adm_dateOverrides_title());
    }

    @POST
    @Path("/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createOverride(
            @RestForm String date, @RestForm String meetingTypeId, MultivaluedMap<String, String> form) {
        // Blank meetingTypeId = this owner's GLOBAL override. A non-blank id must be owned.
        var typeId = (meetingTypeId == null || meetingTypeId.isBlank()) ? null : Long.valueOf(meetingTypeId);
        if (typeId != null) {
            requireType(typeId); // 404 a cross-owner type
        }
        DateOverride o = new DateOverride();
        o.ownerId = currentOwner.id();
        o.overrideDate = LocalDate.parse(date);
        o.meetingTypeId = typeId; // null = global override
        o.persist(); // need the generated id before persisting child windows
        // Zip parallel windowStart[]/windowEnd[] into windows; none → zero windows = day off.
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (var i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) {
                continue;
            }
            DateOverrideWindow w = new DateOverrideWindow();
            w.dateOverrideId = o.id;
            w.startTime = LocalTime.parse(starts.get(i));
            w.endTime = LocalTime.parse(ends.get(i));
            w.persist();
        }
        return Templates.dateOverrides(
                overridesWithWindows(),
                MeetingType.listForOwner(currentOwner.id()),
                pendingCount(),
                isAdmin(),
                m().adm_dateOverrides_title());
    }

    @POST
    @Path("/date-overrides/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteOverride(@PathParam("id") Long id) {
        DateOverride o = DateOverride.findById(id);
        if (o != null && currentOwner.id().equals(o.ownerId)) {
            DateOverrideWindow.delete("dateOverrideId = ?1", id);
            DateOverride.deleteById(id);
        }
        return Templates.dateOverrides(
                overridesWithWindows(),
                MeetingType.listForOwner(currentOwner.id()),
                pendingCount(),
                isAdmin(),
                m().adm_dateOverrides_title());
    }

    @GET
    @Path("/pending")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pending() {
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(),
                site.asm0dey.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT, isAdmin(), m().adm_pending_title());
    }

    /** Load a booking owned by the current owner, or 404. */
    private Booking requireOwnedBooking(Long id) {
        Booking b = Booking.findById(id);
        if (b == null || !currentOwner.id().equals(b.ownerId)) {
            throw new NotFoundException("No booking " + id);
        }
        return b;
    }

    @GET
    @Path("/bookings/{id}/manage")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance manageBooking(@PathParam("id") Long id) {
        return renderManage(requireOwnedBooking(id));
    }

    /** Render the owner's Manage hub for a booking (shared by GET manage and POST edit-details). */
    private TemplateInstance renderManage(Booking b) {
        MeetingType type = MeetingType.findById(b.meetingTypeId);
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        String current =
                b.startUtc.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
        String guestsCsv = BookingGuest.activeForBooking(b.id).stream()
                .map(g -> g.email)
                .collect(java.util.stream.Collectors.joining(","));
        return Templates.manageBooking(
                b,
                current,
                b.startUtc.toString(),
                daySlots(type),
                guestsCsv,
                pendingCount(),
                isAdmin(),
                Layout.TZ_BAR,
                Layout.TZ_SCRIPT,
                Layout.CALENDAR_SCRIPT,
                m().adm_dashboard_h2(),
                b.title == null ? "" : b.title, // raw override (empty when none) — never the effective value
                b.description == null ? "" : b.description,
                type.name, // placeholder = default name
                type.description == null ? "" : type.description);
    }

    @POST
    @Path("/bookings/{id}/reschedule")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance ownerReschedule(@PathParam("id") Long id, @RestForm String startUtc) {
        Booking b = requireOwnedBooking(id);
        // Time only -- guests untouched (null). Host edits guests via /me/bookings/{id}/edit-details.
        bookingService.reschedule(b.manageToken, Instant.parse(startUtc), null, true); // host-initiated
        return dashboard(); // re-render /me; rescheduled booking reflects its new time (or moves to pending queue)
    }

    @POST
    @Path("/bookings/{id}/cancel")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance ownerCancel(@PathParam("id") Long id) {
        Booking b = requireOwnedBooking(id);
        bookingService.cancel(b.manageToken, true); // host-initiated; keyed by the booking's own token
        return dashboard(); // re-render /me; the cancelled booking drops off the upcoming list
    }

    @POST
    @Path("/bookings/{id}/edit-details")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    // Transactional so the reload below shares updateDetails' persistence context instead of
    // hitting this request's long-lived non-transactional EntityManager, which would still hold
    // the pre-update entity in its L1 cache and serve stale data back to renderManage.
    @Transactional
    public TemplateInstance ownerEditDetails(
            @PathParam("id") Long id,
            @RestForm String title,
            @RestForm String description,
            MultivaluedMap<String, String> form) {
        Booking b = requireOwnedBooking(id); // owner-scoped
        bookingService.updateDetails(b.manageToken, title, description, parseGuests(form), true); // host-initiated
        return renderManage(requireOwnedBooking(id)); // reload committed state → back to the hub
    }

    // ponytail: an 8-line CSV splitter duplicated from PublicResource; not worth a shared util.
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
    @Path("/bookings/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance approveBooking(@PathParam("id") Long id) {
        requireOwnedBooking(id);
        bookingService.approve(id); // PENDING→CONFIRMED (+ Google event if connected)
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(),
                site.asm0dey.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT, isAdmin(), m().adm_pending_title());
    }

    @POST
    @Path("/bookings/{id}/decline")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance declineBooking(@PathParam("id") Long id) {
        requireOwnedBooking(id);
        bookingService.decline(id); // PENDING→DECLINED
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(),
                site.asm0dey.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT, isAdmin(), m().adm_pending_title());
    }

    @GET
    @Path("/bookings/{id}/approve")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance approveFromEmail(@PathParam("id") Long id, @QueryParam("t") String token) {
        return actFromEmail(id, token, true);
    }

    @GET
    @Path("/bookings/{id}/decline")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance declineFromEmail(@PathParam("id") Long id, @QueryParam("t") String token) {
        return actFromEmail(id, token, false);
    }

    /**
     * Authenticated one-click approve/decline from the owner's email. Owner-scoped via
     * {@link #requireOwnedBooking} (404 if not theirs); the {@code token} query param is the CSRF
     * nonce (GET is not guarded by quarkus-rest-csrf) and must equal {@code approvalToken} (404 on
     * mismatch, no info leak). Acts only while PENDING, else renders the "already handled" result.
     */
    private TemplateInstance actFromEmail(Long id, String token, boolean approve) {
        Booking b = requireOwnedBooking(id);
        if (token == null || !token.equals(b.approvalToken)) {
            throw new NotFoundException("No booking " + id);
        }
        String h1;
        String desc;
        if (b.status != site.asm0dey.calit.booking.BookingStatus.PENDING) {
            h1 = m().adm_approve_gone_h1();
            desc = m().adm_approve_gone_desc();
        } else if (approve) {
            bookingService.approve(id); // PENDING -> CONFIRMED (+ Google event if connected)
            h1 = m().adm_approve_approved_h1();
            desc = m().adm_approve_approved_desc();
        } else {
            bookingService.decline(id); // PENDING -> DECLINED (frees the slot)
            h1 = m().adm_approve_declined_h1();
            desc = m().adm_approve_declined_desc();
        }
        return Templates.approvalResult(pendingCount(), isAdmin(), m().adm_approve_result_title(), h1, desc);
    }
}
