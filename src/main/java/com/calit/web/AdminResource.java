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
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
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

@Path("/admin")
@RolesAllowed("admin")
public class AdminResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount);

        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes, Long pendingCount);

        public static native TemplateInstance meetingTypeDetail(
                MeetingType type,
                List<BookingField> fields,
                List<AvailabilityRule> rules,
                List<DateOverride> overrides,
                LocationType[] locationTypes,
                BookingField.FieldType[] fieldTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount);

        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<MeetingType> types,
                DayOfWeek[] daysOfWeek, Long pendingCount);

        public static native TemplateInstance settings(
                OwnerSettings settings, int reminderLeadMinutes, Long pendingCount, java.util.List<String> zones);

        public static native TemplateInstance google(Long pendingCount);

        public static native TemplateInstance bookingFields(
                List<BookingField> fields, BookingField.FieldType[] fieldTypes, Long pendingCount);

        public static native TemplateInstance dateOverrides(
                List<DateOverride> overrides, List<MeetingType> types, Long pendingCount);

        public static native TemplateInstance pending(List<Booking> pending);
    }

    @Inject
    BookingService bookingService;

    @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "1440")
    int reminderLeadMinutes;

    /** Pending-approval count for the shared admin nav badge. */
    private long pendingCount() {
        return Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        // Upcoming confirmed bookings, soonest first. PENDING ones live in the approval queue
        // (Task 12, GET /admin/pending), not here.
        List<Booking> upcoming = Booking.list(
                "status = ?1 and startUtc >= ?2 order by startUtc",
                com.calit.booking.BookingStatus.CONFIRMED, java.time.Instant.now());
        long pendingCount = Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
        return Templates.dashboard(upcoming, pendingCount);
    }

    @GET
    @Path("/meeting-types")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance meetingTypes() {
        // Pass LocationType.values() so the form can render the location dropdown options.
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), pendingCount()); // includes secret
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
                                              @RestForm String requiresApproval) {
        MeetingType t = new MeetingType();
        t.name = name;
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(slugBase, null);
        t.durationMinutes = durationMinutes;
        t.bufferBeforeMinutes = bufferBeforeMinutes;
        t.bufferAfterMinutes = bufferAfterMinutes;
        t.secret = "on".equals(secret); // unchecked checkbox sends no value
        t.minNoticeMinutes = minNoticeMinutes;
        t.horizonDays = horizonDays;
        t.locationType = LocationType.valueOf(locationType);
        t.locationDetail = (locationDetail == null || locationDetail.isBlank()) ? null : locationDetail;
        // Slot cadence: blank = back-to-back (null → falls back to durationMinutes).
        t.slotIntervalMinutes = (slotIntervalMinutes == null || slotIntervalMinutes.isBlank())
                ? null : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        t.persist();
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), pendingCount());
    }

    @POST
    @Path("/meeting-types/{id}/toggle")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance toggleActive(@PathParam("id") Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t != null) { t.active = !t.active; }
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), pendingCount());
    }

    @POST
    @Path("/meeting-types/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteMeetingType(@PathParam("id") Long id) {
        MeetingType.deleteById(id);
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), pendingCount());
    }

    /** Date overrides scoped to one meeting type, each with its (transient) windows loaded. */
    private List<DateOverride> overridesForType(Long typeId) {
        List<DateOverride> all = DateOverride.list("meetingTypeId = ?1 order by overrideDate", typeId);
        for (DateOverride o : all) {
            o.windows = DateOverrideWindow.list("dateOverrideId = ?1 order by startTime asc", o.id);
        }
        return all;
    }

    /** Re-render the detail page for one meeting type (shared by every detail-scoped handler). */
    private TemplateInstance detailInstance(Long id) {
        MeetingType t = MeetingType.findById(id);
        List<BookingField> fields = BookingField.list("meetingTypeId = ?1 order by position", id);
        List<AvailabilityRule> rules = AvailabilityRule.list("meetingTypeId = ?1 order by dayOfWeek", id);
        List<DateOverride> overrides = overridesForType(id);
        return Templates.meetingTypeDetail(t, fields, rules, overrides,
                LocationType.values(), BookingField.FieldType.values(),
                DayOfWeek.values(), pendingCount());
    }

    @GET
    @Path("/meeting-types/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance meetingTypeDetail(@PathParam("id") Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t == null) {
            throw new jakarta.ws.rs.NotFoundException("No meeting type " + id);
        }
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
        MeetingType t = MeetingType.findById(id);
        t.name = name;
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(slugBase, id);
        t.durationMinutes = durationMinutes;
        t.bufferBeforeMinutes = bufferBeforeMinutes;
        t.bufferAfterMinutes = bufferAfterMinutes;
        t.secret = "on".equals(secret);
        t.minNoticeMinutes = minNoticeMinutes;
        t.horizonDays = horizonDays;
        t.locationType = LocationType.valueOf(locationType);
        t.locationDetail = (locationDetail == null || locationDetail.isBlank()) ? null : locationDetail;
        t.slotIntervalMinutes = (slotIntervalMinutes == null || slotIntervalMinutes.isBlank())
                ? null : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        return detailInstance(id); // managed entity flushes on commit
    }

    @GET
    @Path("/availability")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance availability() {
        return Templates.availability(
                AvailabilityRule.listAll(),
                MeetingType.listAll(), DayOfWeek.values(), pendingCount());
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
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.valueOf(dayOfWeek);
        r.startTime = LocalTime.parse(startTime);
        r.endTime = LocalTime.parse(endTime);
        r.meetingTypeId = (meetingTypeId == null || meetingTypeId.isBlank())
                ? null : Long.valueOf(meetingTypeId); // empty = global
        r.persist();
        return Templates.availability(
                AvailabilityRule.<AvailabilityRule>listAll(),
                MeetingType.listAll(), DayOfWeek.values(), pendingCount());
    }

    @POST
    @Path("/availability/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteRule(@PathParam("id") Long id) {
        AvailabilityRule.deleteById(id);
        return Templates.availability(
                AvailabilityRule.<AvailabilityRule>listAll(),
                MeetingType.listAll(), DayOfWeek.values(), pendingCount());
    }

    /** All IANA zone ids, sorted — for the Settings timezone combobox. */
    private static java.util.List<String> zoneIds() {
        return java.time.ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    @GET
    @Path("/settings")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        return Templates.settings(OwnerSettings.get(), reminderLeadMinutes, pendingCount(), zoneIds());
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
        OwnerSettings s = OwnerSettings.get();
        if (s == null) { s = new OwnerSettings(); s.id = OwnerSettings.SINGLETON_ID; }
        s.ownerName = ownerName;
        s.ownerEmail = ownerEmail;
        s.timezone = timezone;
        // Unchecked checkbox sends no value → notifications OFF (owner opt-out).
        s.ownerNotificationsEnabled = "on".equals(ownerNotificationsEnabled);
        s.persist();
        return Templates.settings(s, reminderLeadMinutes, pendingCount(), zoneIds());
    }

    @GET
    @Path("/google")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance google() {
        long pendingCount = Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
        return Templates.google(pendingCount);
    }

    @GET
    @Path("/booking-fields")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance bookingFields() {
        return Templates.bookingFields(
                BookingField.<BookingField>listAll(),
                FieldType.values(), pendingCount());
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
                                               @RestForm int position,
                                               @RestForm String meetingTypeId) {
        BookingField f = new BookingField();
        f.label = label;
        f.fieldKey = fieldKey;
        f.type = FieldType.valueOf(type);
        f.required = "on".equals(required); // unchecked checkbox sends no value
        f.position = position;
        f.meetingTypeId = (meetingTypeId == null || meetingTypeId.isBlank())
                ? null : Long.valueOf(meetingTypeId); // empty = global
        f.persist();
        return Templates.bookingFields(
                BookingField.<BookingField>listAll(),
                FieldType.values(), pendingCount());
    }

    @POST
    @Path("/booking-fields/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteBookingField(@PathParam("id") Long id) {
        BookingField.deleteById(id);
        return Templates.bookingFields(
                BookingField.<BookingField>listAll(),
                FieldType.values(), pendingCount());
    }

    /**
     * All overrides with their (transient) {@code windows} loaded for display.
     * {@link DateOverride#windows} is @Transient (not cascade-mapped), so listAll()
     * leaves it empty; we populate each from {@link DateOverrideWindow} by id.
     */
    private List<DateOverride> overridesWithWindows() {
        List<DateOverride> all = DateOverride.listAll();
        for (DateOverride o : all) {
            o.windows = DateOverrideWindow.list("dateOverrideId = ?1 order by startTime asc", o.id);
        }
        return all;
    }

    @GET
    @Path("/date-overrides")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dateOverrides() {
        return Templates.dateOverrides(
                overridesWithWindows(),
                MeetingType.listAll(), pendingCount());
    }

    @POST
    @Path("/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createOverride(@RestForm String date,
                                           @RestForm String meetingTypeId,
                                           MultivaluedMap<String, String> form) {
        DateOverride o = new DateOverride();
        o.overrideDate = LocalDate.parse(date);
        o.meetingTypeId = (meetingTypeId == null || meetingTypeId.isBlank())
                ? null : Long.valueOf(meetingTypeId); // empty = global
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
                overridesWithWindows(), MeetingType.listAll(), pendingCount());
    }

    @POST
    @Path("/date-overrides/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteOverride(@PathParam("id") Long id) {
        DateOverrideWindow.delete("dateOverrideId = ?1", id);
        DateOverride.deleteById(id);
        return Templates.dateOverrides(
                overridesWithWindows(), MeetingType.listAll(), pendingCount());
    }

    @GET
    @Path("/pending")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pending() {
        List<Booking> pending = Booking.list(
                "status = ?1 order by startUtc", com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending);
    }

    @POST
    @Path("/bookings/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance approveBooking(@PathParam("id") Long id) {
        bookingService.approve(id); // PENDING→CONFIRMED (+ Google event if connected)
        List<Booking> pending = Booking.list(
                "status = ?1 order by startUtc", com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending);
    }

    @POST
    @Path("/bookings/{id}/decline")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance declineBooking(@PathParam("id") Long id) {
        bookingService.decline(id); // PENDING→DECLINED
        List<Booking> pending = Booking.list(
                "status = ?1 order by startUtc", com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending);
    }
}
