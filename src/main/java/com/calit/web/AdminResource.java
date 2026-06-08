package com.calit.web;

import com.calit.booking.Booking;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

@Path("/admin")
@RolesAllowed("admin")
public class AdminResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount, String css);

        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes, String css);

        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<MeetingType> types, String css);

        public static native TemplateInstance settings(
                OwnerSettings settings, int reminderLeadMinutes, String css);
    }

    @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "120")
    int reminderLeadMinutes;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        // Upcoming confirmed bookings, soonest first. PENDING ones live in the approval queue
        // (Task 12, GET /admin/pending), not here.
        List<Booking> upcoming = Booking.list(
                "status = ?1 and startUtc >= ?2 order by startUtc",
                com.calit.booking.BookingStatus.CONFIRMED, java.time.Instant.now());
        long pendingCount = Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
        return Templates.dashboard(upcoming, pendingCount, Layout.CSS);
    }

    @GET
    @Path("/meeting-types")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance meetingTypes() {
        // Pass LocationType.values() so the form can render the location dropdown options.
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), Layout.CSS); // includes secret
    }

    @POST
    @Path("/meeting-types")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createMeetingType(@RestForm String name,
                                              @RestForm String slug,
                                              @RestForm int durationMinutes,
                                              @RestForm String secret,
                                              @RestForm int minNoticeMinutes,
                                              @RestForm int horizonDays,
                                              @RestForm String locationType,
                                              @RestForm String locationDetail,
                                              @RestForm String slotIntervalMinutes,
                                              @RestForm String requiresApproval) {
        MeetingType t = new MeetingType();
        t.name = name;
        t.slug = slug;
        t.durationMinutes = durationMinutes;
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
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), Layout.CSS);
    }

    @POST
    @Path("/meeting-types/{id}/toggle")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance toggleActive(@PathParam("id") Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t != null) { t.active = !t.active; }
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), Layout.CSS);
    }

    @POST
    @Path("/meeting-types/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteMeetingType(@PathParam("id") Long id) {
        MeetingType.deleteById(id);
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), Layout.CSS);
    }

    @GET
    @Path("/availability")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance availability() {
        return Templates.availability(
                AvailabilityRule.<AvailabilityRule>listAll(),
                MeetingType.listAll(), Layout.CSS);
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
                MeetingType.listAll(), Layout.CSS);
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
                MeetingType.listAll(), Layout.CSS);
    }

    @GET
    @Path("/settings")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        return Templates.settings(OwnerSettings.get(), reminderLeadMinutes, Layout.CSS);
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
        return Templates.settings(s, reminderLeadMinutes, Layout.CSS);
    }
}
