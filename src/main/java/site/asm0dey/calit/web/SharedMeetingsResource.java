package site.asm0dey.calit.web;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jboss.resteasy.reactive.RestForm;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.i18n.AdminMessages;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * Co-host's own view of shared meeting types: the pending consent-request dashboard, and the
 * per-type availability/buffers editor for a type this owner ACCEPTED co-hosting. Every read and
 * write here is scoped to {@code currentOwner.id()} — a co-host never sees or edits another
 * host's rows, and the creator does not manage co-hosts' hours from here (that stays on
 * {@code AdminResource}'s meeting-type detail page).
 */
// Deliberately @Path("/me"), NOT "/me/shared": AdminResource already owns the exact literal
// route GET /me/shared (its own "shared meeting types" list) via @Path("/me") + @Path("/shared").
// Giving this class its own @Path("/me/shared") collides with that existing resource at the
// Quarkus REST routing layer -- the literal "/me/shared" prefix gets claimed by whichever
// resource declares it, and AdminResource's bare GET /me/shared started 404ing once this class
// declared the identical class-level path (see SharedPageTest regression caught in Task 19).
// Every method below prefixes its own @Path with "/shared/..." instead, so the external URLs
// are unchanged (still /me/shared/requests, /me/shared/{typeId}/availability, ...).
@Path("/me")
@RolesAllowed("user")
public class SharedMeetingsResource {

    @CheckedTemplate
    // S107: Qute @CheckedTemplate signatures pass one arg per template variable; param count is inherent.
    @SuppressWarnings("java:S107")
    public static class Templates {
        private Templates() {}

        public static native TemplateInstance consentRequests(
                List<PendingRequestRow> requests, Long pendingCount, boolean isAdmin, String title);

        public static native TemplateInstance sharedAvailability(
                MeetingType type,
                MeetingTypeHost host,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String error,
                String title);

        public static native TemplateInstance revokeConfirm(
                MeetingType type, long futureBookingCount, Long pendingCount, boolean isAdmin, String title);
    }

    public record PendingRequestRow(MeetingType type, String creatorName) {}

    @Inject
    CurrentOwner currentOwner;

    @Inject
    MeetingHosts meetingHosts;

    @Inject
    BookingService bookingService;

    @Inject
    SecurityIdentity identity;

    @Inject
    AdminMessageResolver adminMsgs;

    @Inject
    ActiveLocale activeLocale;

    private boolean isAdmin() {
        return identity.hasRole("admin");
    }

    private AdminMessages m() {
        return adminMsgs.forLocale(activeLocale.current());
    }

    /** Pending-approval count for the shared admin nav badge (same query as AdminResource). */
    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2", currentOwner.id(), BookingStatus.PENDING);
    }

    /**
     * Authorization guard for every per-type read/write below: {@code typeId} must be a type this
     * owner is an ACCEPTED host of (creator or co-host). 404 otherwise — no distinction between
     * "not a host" and "unknown type" (no info leak).
     */
    private MeetingTypeHost requireAcceptedHost(Long typeId) {
        MeetingTypeHost h = MeetingTypeHost.find(typeId, currentOwner.id());
        if (h == null || !h.accepted()) {
            throw new NotFoundException("No accepted host row for type " + typeId);
        }
        return h;
    }

    // ---- Pending consent requests ----

    @GET
    @Path("/shared/requests")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance requests() {
        return requestsInstance();
    }

    private TemplateInstance requestsInstance() {
        List<PendingRequestRow> rows = new ArrayList<>();
        for (MeetingTypeHost h : MeetingTypeHost.cohostedTypesFor(currentOwner.id())) {
            if (!MeetingTypeHost.PENDING.equals(h.status)) {
                continue;
            }
            MeetingType t = MeetingType.findById(h.meetingTypeId);
            if (t == null) {
                continue;
            }
            OwnerSettings creator = OwnerSettings.forOwner(t.ownerId);
            rows.add(new PendingRequestRow(t, creator != null ? creator.ownerName : "?"));
        }
        return Templates.consentRequests(rows, pendingCount(), isAdmin(), m().adm_shared_requests_title());
    }

    /** Loads this owner's own PENDING co-host row for {@code typeId}, or 404. */
    private MeetingTypeHost requirePendingOwnRow(Long typeId) {
        MeetingTypeHost h = MeetingTypeHost.find(typeId, currentOwner.id());
        if (h == null || !MeetingTypeHost.PENDING.equals(h.status)) {
            throw new NotFoundException("No pending consent request for type " + typeId);
        }
        return h;
    }

    @POST
    @Path("/shared/requests/{typeId}/accept")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance acceptRequest(@PathParam("typeId") Long typeId) {
        // Commit before the render (#75). The row is loaded INSIDE the tx: acceptConsent mutates
        // the passed entity, so it must be managed in the same persistence context to flush.
        QuarkusTransaction.requiringNew().run(() -> meetingHosts.acceptConsent(requirePendingOwnRow(typeId)));
        return requestsInstance();
    }

    @POST
    @Path("/shared/requests/{typeId}/decline")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance declineRequest(@PathParam("typeId") Long typeId) {
        QuarkusTransaction.requiringNew().run(() -> {
            requirePendingOwnRow(typeId);
            MeetingTypeHost.delete("meetingTypeId = ?1 and ownerId = ?2", typeId, currentOwner.id());
        });
        return requestsInstance();
    }

    // ---- Shared-type availability editor ----

    @GET
    @Path("/shared/{typeId}/availability")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance sharedAvailability(@PathParam("typeId") Long typeId) {
        return availabilityInstance(typeId, null);
    }

    private TemplateInstance availabilityInstance(Long typeId, String error) {
        MeetingTypeHost h = requireAcceptedHost(typeId);
        MeetingType type = MeetingType.findById(typeId);
        if (type == null) {
            throw new NotFoundException("No meeting type " + typeId);
        }
        List<AvailabilityRule> rules = AvailabilityRule.list(
                "ownerId = ?1 and meetingTypeId = ?2 order by dayOfWeek", currentOwner.id(), typeId);
        List<DateOverride> overrides = ownTypeOverrides(typeId);
        return Templates.sharedAvailability(
                type,
                h,
                rules,
                WeekRow.fromRules(rules),
                overrides,
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                error,
                m().adm_shared_availability_title(type.name));
    }

    /** This owner's own per-type date overrides, each with its (transient) windows loaded. */
    private List<DateOverride> ownTypeOverrides(Long typeId) {
        return AdminResource.withWindows(DateOverride.list(
                "ownerId = ?1 and meetingTypeId = ?2 order by overrideDate", currentOwner.id(), typeId));
    }

    @POST
    @Path("/shared/{typeId}/availability/bulk")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance saveAvailability(@PathParam("typeId") Long typeId, MultivaluedMap<String, String> form) {
        QuarkusTransaction.requiringNew().run(() -> {
            requireAcceptedHost(typeId); // 404 a non-host
            // Replace-all for THIS host's own schedule on this type only.
            AvailabilityRule.delete("ownerId = ?1 and meetingTypeId = ?2", currentOwner.id(), typeId);
            AdminResource.persistFrames(currentOwner.id(), typeId, form);
        });
        return availabilityInstance(typeId, null);
    }

    @POST
    @Path("/shared/{typeId}/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance addOverride(
            @PathParam("typeId") Long typeId, @RestForm String date, MultivaluedMap<String, String> form) {
        requireAcceptedHost(typeId);
        var overrideDate = parseDateOrNull(date);
        if (overrideDate == null) {
            // Malformed/crafted date: skip the whole override rather than 500ing the save.
            return availabilityInstance(typeId, null);
        }
        // Persist the override + its windows in one tx that commits before the render (#75).
        QuarkusTransaction.requiringNew().run(() -> {
            DateOverride o = new DateOverride();
            o.ownerId = currentOwner.id();
            o.meetingTypeId = typeId;
            o.overrideDate = overrideDate;
            o.persist(); // need the generated id before persisting child windows
            List<String> starts = form.getOrDefault("windowStart", List.of());
            List<String> ends = form.getOrDefault("windowEnd", List.of());
            for (var i = 0; i < starts.size() && i < ends.size(); i++) {
                if (starts.get(i).isBlank() || ends.get(i).isBlank()) {
                    continue;
                }
                var start = parseTimeOrNull(starts.get(i));
                var end = parseTimeOrNull(ends.get(i));
                if (start == null || end == null) {
                    continue; // unparseable window — skip it, keep the rest of the save
                }
                DateOverrideWindow w = new DateOverrideWindow();
                w.dateOverrideId = o.id;
                w.startTime = start;
                w.endTime = end;
                w.persist();
            }
        });
        return availabilityInstance(typeId, null);
    }

    private static LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static LocalTime parseTimeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    @POST
    @Path("/shared/{typeId}/date-overrides/{oid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance deleteOverride(@PathParam("typeId") Long typeId, @PathParam("oid") Long oid) {
        QuarkusTransaction.requiringNew().run(() -> {
            requireAcceptedHost(typeId);
            DateOverride o = DateOverride.findById(oid);
            if (o != null && typeId.equals(o.meetingTypeId) && currentOwner.id().equals(o.ownerId)) {
                DateOverrideWindow.delete("dateOverrideId = ?1", oid);
                DateOverride.deleteById(oid);
            }
        });
        return availabilityInstance(typeId, null);
    }

    @POST
    @Path("/shared/{typeId}/buffers")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance saveBuffers(
            @PathParam("typeId") Long typeId,
            @RestForm String bufferBeforeMinutes,
            @RestForm String bufferAfterMinutes) {
        // The host row is loaded + dirty-mutated INSIDE the tx so its buffer changes flush on commit,
        // which happens before the render (#75).
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingTypeHost h = requireAcceptedHost(typeId);
            h.bufferBeforeMinutes = parseNonNegativeIntOrNull(bufferBeforeMinutes);
            h.bufferAfterMinutes = parseNonNegativeIntOrNull(bufferAfterMinutes);
        });
        return availabilityInstance(typeId, null);
    }

    /**
     * Blank/non-numeric input → {@code null} (inherit the type's default buffer); a negative
     * number clamps to {@code 0} rather than being persisted (a negative buffer would wrongly
     * widen availability). Never lets a {@link NumberFormatException} escape to a 500.
     */
    private static Integer parseNonNegativeIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var value = Integer.parseInt(raw.trim());
            return Math.max(value, 0);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    // ---- Co-host self-revoke (Task 18's keep-vs-cancel interstitial, from the co-host's side) ----

    @POST
    @Path("/shared/{typeId}/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance revoke(@PathParam("typeId") Long typeId, @QueryParam("choice") String choice) {
        MeetingTypeHost h = requireAcceptedHost(typeId);
        if (MeetingTypeHost.CREATOR.equals(h.role)) {
            // A creator never self-revokes from their own type via the co-host page.
            throw new NotFoundException("No co-host row for type " + typeId);
        }
        MeetingType type = MeetingType.findById(typeId);
        if (type == null) {
            throw new NotFoundException("No meeting type " + typeId);
        }
        // No explicit choice yet + future bookings exist: render the keep-vs-cancel interstitial
        // (read-only — no tx, no connection held across render).
        var cancel = "cancel".equals(choice);
        if (!cancel && !"keep".equals(choice)) {
            long futureCount = meetingHosts.countFutureBookings(type, currentOwner.id());
            if (futureCount > 0) {
                return Templates.revokeConfirm(
                        type, futureCount, pendingCount(), isAdmin(), m().adm_shared_revokeConfirm_title());
            }
        }
        // Removal (and optional cancellation) commit in one tx before the render (#75).
        Long ownerId = currentOwner.id();
        QuarkusTransaction.requiringNew().run(() -> {
            if (cancel) {
                bookingService.cancelFutureGroupBookingsForHost(type, ownerId);
            }
            meetingHosts.removeHost(type, ownerId);
        });
        return requestsInstance();
    }
}
