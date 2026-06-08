package com.calit.web;

import com.calit.booking.Booking;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
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
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;

@Path("/admin")
@RolesAllowed("admin")
public class AdminResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount, String css);

        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes, String css);
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
}
