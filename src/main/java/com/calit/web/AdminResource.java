package com.calit.web;

import com.calit.booking.Booking;
import com.calit.booking.BookingService;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
import com.calit.domain.BookingField.FieldType;
import com.calit.domain.DateOverride;
import com.calit.domain.DateOverrideWindow;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.Slugs;
import com.calit.domain.OwnerSettings;
import com.calit.google.GoogleCalendar;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Path("/me")
@RolesAllowed("user")
public class AdminResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount, String tzScript, boolean isAdmin);

        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes,
                DayOfWeek[] daysOfWeek, Long pendingCount, boolean isAdmin);

        public static native TemplateInstance meetingTypeDetail(
                MeetingType type,
                List<BookingField> fields,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                LocationType[] locationTypes,
                BookingField.FieldType[] fieldTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount, boolean isAdmin);

        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<WeekRow> week, List<MeetingType> types,
                DayOfWeek[] daysOfWeek, Long pendingCount, boolean isAdmin);

        public static native TemplateInstance settings(
                OwnerSettings settings, int reminderLeadMinutes, Long pendingCount, java.util.List<String> zones, boolean isAdmin);

        public static native TemplateInstance bookingFields(
                List<BookingField> fields, BookingField.FieldType[] fieldTypes, Long pendingCount, boolean isAdmin);

        public static native TemplateInstance dateOverrides(
                List<DateOverride> overrides, List<MeetingType> types, Long pendingCount, boolean isAdmin);

        public static native TemplateInstance pending(List<Booking> pending, String tzScript, boolean isAdmin);
    }

    @Inject
    BookingService bookingService;

    @Inject
    com.calit.user.CurrentOwner currentOwner;

    @Inject
    SecurityIdentity identity;

    /** True when the logged-in user holds the site-admin role (drives the Users nav link). */
    private boolean isAdmin() {
        return identity.hasRole("admin");
    }

    @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "1440")
    int reminderLeadMinutes;

    /** Pending-approval count for the shared admin nav badge. */
    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2",
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        // Upcoming confirmed bookings, soonest first. PENDING ones live in the approval queue
        // (GET /me/pending), not here.
        List<Booking> upcoming = Booking.list(
                "ownerId = ?1 and status = ?2 and startUtc >= ?3 order by startUtc",
                currentOwner.id(), com.calit.booking.BookingStatus.CONFIRMED, java.time.Instant.now());
        long pendingCount = pendingCount();
        return Templates.dashboard(upcoming, pendingCount, Layout.TZ_SCRIPT, isAdmin());
    }

    @GET
    @Path("/meeting-types")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance meetingTypes() {
        // Pass LocationType.values() so the form can render the location dropdown options.
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin()); // includes secret
    }

    @POST
    @Path("/meeting-types")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createMeetingType(@RestForm String name,
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
                ? null : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        t.persist(); // need the generated id before scoping child rules/overrides to it
        createInitialWorkingHours(t.id, t.ownerId, form);
        createInitialDateOverride(t.id, t.ownerId, form);
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin());
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
        for (int i = 0; i < days.size() && i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
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
        if (date == null || date.isBlank()) { return; }
        DateOverride o = new DateOverride();
        o.ownerId = ownerId;
        o.meetingTypeId = typeId;
        o.overrideDate = LocalDate.parse(date);
        o.persist(); // need the generated id before persisting child windows
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (int i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
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
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin());
    }

    @POST
    @Path("/meeting-types/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteMeetingType(@PathParam("id") Long id) {
        requireType(id);
        MeetingType.deleteById(id);
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin());
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
        java.util.Map<Long, List<DateOverrideWindow>> byOverride = DateOverrideWindow
                .<DateOverrideWindow>list("dateOverrideId in ?1 order by startTime asc", ids).stream()
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
            throw new jakarta.ws.rs.BadRequestException(
                    "The selected write-target calendar can't create Google Meet links; pick another location.");
        }
        return lt;
    }

    /** Load a meeting type or 404 — shared guard for detail-scoped GET/POST handlers. */
    private MeetingType requireType(Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t == null || !t.ownerId.equals(currentOwner.id())) {
            throw new jakarta.ws.rs.NotFoundException("No meeting type " + id);
        }
        return t;
    }

    /** Re-render the detail page for one meeting type (shared by every detail-scoped handler). */
    private TemplateInstance detailInstance(Long id) {
        MeetingType t = requireType(id);
        List<BookingField> fields = BookingField.list("meetingTypeId = ?1 order by position", id);
        List<AvailabilityRule> rules = AvailabilityRule.list("meetingTypeId = ?1 order by dayOfWeek", id);
        List<DateOverride> overrides = overridesForType(id);
        return Templates.meetingTypeDetail(t, fields, rules, weekRows(rules), overrides,
                LocationType.values(), BookingField.FieldType.values(),
                DayOfWeek.values(), pendingCount(), isAdmin());
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
    public TemplateInstance editMeetingType(@PathParam("id") Long id,
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
                ? null : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        return detailInstance(id); // managed entity flushes on commit
    }

    @POST
    @Path("/meeting-types/{id}/booking-fields")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeField(@PathParam("id") Long id,
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
    public TemplateInstance addTypeRule(@PathParam("id") Long id,
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
    public TemplateInstance saveTypeWeeklyRules(@PathParam("id") Long id,
                                                MultivaluedMap<String, String> form) {
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
    public TemplateInstance addTypeOverride(@PathParam("id") Long id,
                                            @RestForm String date,
                                            MultivaluedMap<String, String> form) {
        requireType(id);
        DateOverride o = new DateOverride();
        o.ownerId = currentOwner.id();
        o.meetingTypeId = id;
        o.overrideDate = LocalDate.parse(date);
        o.persist(); // need the generated id before persisting child windows
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (int i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
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
        return AvailabilityRule.list("ownerId = ?1 order by meetingTypeId nulls first, dayOfWeek",
                currentOwner.id());
    }

    /** This owner's GLOBAL default rules only (meetingTypeId IS NULL), for the weekly grid. */
    private List<AvailabilityRule> globalRules() {
        return AvailabilityRule.list(
                "ownerId = ?1 and meetingTypeId is null order by dayOfWeek", currentOwner.id());
    }

    /** Group rules into the fixed seven-row weekly grid. */
    private static List<WeekRow> weekRows(List<AvailabilityRule> rules) {
        return WeekRow.fromRules(rules);
    }

    @GET
    @Path("/availability")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance availability() {
        return Templates.availability(ownerRules(), weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount(), isAdmin());
    }

    @POST
    @Path("/availability")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createRule(@RestForm String dayOfWeek,
                                       @RestForm String startTime,
                                       @RestForm String endTime,
                                       @RestForm String meetingTypeId) {
        // Blank meetingTypeId = this owner's GLOBAL default rule. A non-blank id must be owned.
        Long typeId = (meetingTypeId == null || meetingTypeId.isBlank()) ? null : Long.valueOf(meetingTypeId);
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
        return Templates.availability(ownerRules(), weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount(), isAdmin());
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
        return Templates.availability(ownerRules(), weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount(), isAdmin());
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
        for (int i = 0; i < days.size() && i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
            LocalTime start = LocalTime.parse(starts.get(i));
            LocalTime end = LocalTime.parse(ends.get(i));
            if (!end.isAfter(start)) { continue; } // drop zero-length / inverted frames
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
        return Templates.availability(ownerRules(), weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount(), isAdmin());
    }

    /** All IANA zone ids, sorted — for the Settings timezone combobox. */
    private static java.util.List<String> zoneIds() {
        return java.time.ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    @GET
    @Path("/settings")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        return Templates.settings(OwnerSettings.forOwner(currentOwner.id()),
                reminderLeadMinutes, pendingCount(), zoneIds(), isAdmin());
    }

    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance updateSettings(@RestForm String ownerName,
                                           @RestForm String ownerEmail,
                                           @RestForm String timezone,
                                           @RestForm String ownerNotificationsEnabled) {
        OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
        if (s == null) { s = new OwnerSettings(); s.ownerId = currentOwner.id(); }
        s.ownerName = ownerName;
        s.ownerEmail = ownerEmail;
        s.timezone = timezone;
        // Unchecked checkbox sends no value → notifications OFF (owner opt-out).
        s.ownerNotificationsEnabled = "on".equals(ownerNotificationsEnabled);
        s.persist();
        return Templates.settings(s, reminderLeadMinutes, pendingCount(), zoneIds(), isAdmin());
    }

    @GET
    @Path("/booking-fields")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance bookingFields() {
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()), FieldType.values(), pendingCount(), isAdmin());
    }

    @POST
    @Path("/booking-fields")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createBookingField(@RestForm String label,
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
                BookingField.globalForOwner(currentOwner.id()), FieldType.values(), pendingCount(), isAdmin());
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
                BookingField.globalForOwner(currentOwner.id()), FieldType.values(), pendingCount(), isAdmin());
    }

    /**
     * All overrides with their (transient) {@code windows} loaded for display.
     * {@link DateOverride#windows} is @Transient (not cascade-mapped), so listAll()
     * leaves it empty; we populate each from {@link DateOverrideWindow} by id.
     */
    private List<DateOverride> overridesWithWindows() {
        return withWindows(DateOverride.list(
                "ownerId = ?1 order by meetingTypeId nulls first, overrideDate", currentOwner.id()));
    }

    @GET
    @Path("/date-overrides")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dateOverrides() {
        return Templates.dateOverrides(
                overridesWithWindows(),
                MeetingType.listForOwner(currentOwner.id()), pendingCount(), isAdmin());
    }

    @POST
    @Path("/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createOverride(@RestForm String date,
                                           @RestForm String meetingTypeId,
                                           MultivaluedMap<String, String> form) {
        // Blank meetingTypeId = this owner's GLOBAL override. A non-blank id must be owned.
        Long typeId = (meetingTypeId == null || meetingTypeId.isBlank()) ? null : Long.valueOf(meetingTypeId);
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
        for (int i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
            DateOverrideWindow w = new DateOverrideWindow();
            w.dateOverrideId = o.id;
            w.startTime = LocalTime.parse(starts.get(i));
            w.endTime = LocalTime.parse(ends.get(i));
            w.persist();
        }
        return Templates.dateOverrides(
                overridesWithWindows(), MeetingType.listForOwner(currentOwner.id()), pendingCount(), isAdmin());
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
                overridesWithWindows(), MeetingType.listForOwner(currentOwner.id()), pendingCount(), isAdmin());
    }

    @GET
    @Path("/pending")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pending() {
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT, isAdmin());
    }

    /** Load a booking owned by the current owner, or 404. */
    private Booking requireOwnedBooking(Long id) {
        Booking b = Booking.findById(id);
        if (b == null || !currentOwner.id().equals(b.ownerId)) {
            throw new jakarta.ws.rs.NotFoundException("No booking " + id);
        }
        return b;
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
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT, isAdmin());
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
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT, isAdmin());
    }
}
