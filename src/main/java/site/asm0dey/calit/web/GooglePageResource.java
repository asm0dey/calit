package site.asm0dey.calit.web;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.google.CalendarListPort;
import site.asm0dey.calit.google.CalendarSelectionService;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/me/google")
@RolesAllowed("user")
public class GooglePageResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance google(List<AccountView> accounts, boolean loadError,
                                                     Long pendingCount, boolean isAdmin, String title);
    }

    @Inject CalendarListPort calendarListPort;
    @Inject CalendarSelectionService selectionService;
    @Inject site.asm0dey.calit.user.CurrentOwner currentOwner;
    @Inject SecurityIdentity identity;
    @Inject AdminMessageResolver adminMsgs;
    @Inject ActiveLocale activeLocale;

    private boolean isAdmin() { return identity.hasRole("admin"); }

    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2", currentOwner.id(), BookingStatus.PENDING);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance google() {
        Long ownerId = currentOwner.id();
        List<GoogleCredential> creds = GoogleCredential.listForOwner(ownerId);
        List<AccountView> accounts = new ArrayList<>();
        boolean loadError = false;
        for (GoogleCredential cred : creds) {
            Map<String, GoogleCalendar> saved = GoogleCalendar
                    .<GoogleCalendar>list("googleCredentialId", cred.id).stream()
                    .collect(java.util.stream.Collectors.toMap(c -> c.googleCalendarId, c -> c, (a, b) -> a));
            List<CalendarRow> rows = new ArrayList<>();
            boolean holdsWriteTarget = false;
            boolean loadFailed = false;

            if (cred.needsReconnect) {
                // Dead token: don't call Google. Show saved config so the owner sees what's configured.
                loadFailed = true;
                for (GoogleCalendar s : saved.values()) {
                    if (s.writeTarget) holdsWriteTarget = true;
                    rows.add(new CalendarRow(cred.id, s.googleCalendarId, s.summary, s.readForBusy, s.writeTarget));
                }
            } else {
                try {
                    for (CalendarListPort.RemoteCalendar rc : calendarListPort.listCalendars(cred)) {
                        GoogleCalendar s = saved.get(rc.googleCalendarId());
                        boolean read = s == null ? saved.isEmpty() : s.readForBusy; // first-load: all read
                        boolean write = s != null && s.writeTarget;
                        if (write) holdsWriteTarget = true;
                        rows.add(new CalendarRow(cred.id, rc.googleCalendarId(), rc.summary(), read, write));
                    }
                } catch (RuntimeException ex) {
                    // Transient failure: banner + fall back to saved rows so config stays visible.
                    loadError = true;
                    loadFailed = true;
                    rows.clear();
                    holdsWriteTarget = false;
                    for (GoogleCalendar s : saved.values()) {
                        if (s.writeTarget) holdsWriteTarget = true;
                        rows.add(new CalendarRow(cred.id, s.googleCalendarId, s.summary, s.readForBusy, s.writeTarget));
                    }
                }
            }
            accounts.add(new AccountView(cred.id, cred.accountEmail, cred.needsReconnect, loadFailed,
                    rows, holdsWriteTarget));
        }
        return Templates.google(accounts, loadError, pendingCount(), isAdmin(), adminMsgs.forLocale(activeLocale.current()).adm_google_title());
    }

    // NOT @Transactional: this loops N calendarListPort.listCalendars() network calls to re-fetch
    // live lists, and must not hold a pooled DB connection across that I/O. Reads run on the
    // request-scoped session; the actual write is one atomic transaction inside selectionService.save.
    @POST
    @Path("/calendars")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response saveSelection(MultivaluedMap<String, String> form) {
        Long ownerId = currentOwner.id();
        List<String> readVals = form.getOrDefault("read", List.of());
        String writeVal = form.getFirst("writeTarget");

        List<CalendarSelectionService.Selection> selections = new ArrayList<>();
        java.util.Set<Long> reachable = new java.util.HashSet<>();
        for (GoogleCredential cred : GoogleCredential.listForOwner(ownerId)) {
            if (cred.needsReconnect) {
                continue; // unreachable: preserved from DB below
            }
            List<CalendarListPort.RemoteCalendar> live;
            try {
                live = calendarListPort.listCalendars(cred);
            } catch (RuntimeException ex) {
                continue; // unreachable mid-save: preserved from DB below
            }
            reachable.add(cred.id);
            for (CalendarListPort.RemoteCalendar rc : live) {
                String key = cred.id + ":" + rc.googleCalendarId();
                boolean read = readVals.contains(key);
                boolean write = key.equals(writeVal);
                if (read || write) {
                    selections.add(new CalendarSelectionService.Selection(
                            cred.id, rc.googleCalendarId(), rc.summary(), read, write, rc.meetSupported()));
                }
            }
        }

        boolean submittedHasWriteTarget =
                selections.stream().anyMatch(CalendarSelectionService.Selection::writeTarget);

        // Preserve existing rows for accounts we could NOT reach (flagged/errored). Keep their read
        // selections; demote a preserved write target only if the form chose a new one, so the
        // single-write-target-per-owner invariant holds.
        for (GoogleCalendar s : GoogleCalendar.<GoogleCalendar>list("ownerId", ownerId)) {
            if (reachable.contains(s.googleCredentialId)) {
                continue; // reachable accounts are fully respecified by the form above
            }
            boolean keepWrite = s.writeTarget && !submittedHasWriteTarget;
            selections.add(new CalendarSelectionService.Selection(
                    s.googleCredentialId, s.googleCalendarId, s.summary,
                    s.readForBusy || keepWrite, keepWrite, s.supportsMeet));
        }

        if (selections.stream().noneMatch(CalendarSelectionService.Selection::writeTarget)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Pick exactly one write-target calendar").build();
        }
        selectionService.save(ownerId, selections);
        return Response.seeOther(java.net.URI.create("/me/google")).build();
    }

    @POST
    @Path("/accounts/{credentialId}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response disconnect(@PathParam("credentialId") Long credentialId) {
        Long ownerId = currentOwner.id();
        GoogleCredential cred = GoogleCredential.findById(credentialId);
        if (cred == null || !ownerId.equals(cred.ownerId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        boolean holdsWriteTarget = GoogleCalendar.count(
                "googleCredentialId = ?1 and writeTarget = true", credentialId) > 0;
        boolean otherAccountsRemain = GoogleCredential.countForOwner(ownerId) > 1;
        if (holdsWriteTarget && otherAccountsRemain) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Pick a new write target on another account before disconnecting this one")
                    .build();
        }
        cred.delete(); // ON DELETE CASCADE removes this account's google_calendar rows
        return Response.seeOther(java.net.URI.create("/me/google")).build();
    }
}
