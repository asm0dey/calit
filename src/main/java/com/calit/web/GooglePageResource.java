package com.calit.web;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.google.CalendarListPort;
import com.calit.google.CalendarSelectionService;
import com.calit.google.GoogleCalendar;
import com.calit.google.GoogleCredential;
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
                                                     Long pendingCount, boolean isAdmin);
    }

    @Inject CalendarListPort calendarListPort;
    @Inject CalendarSelectionService selectionService;
    @Inject com.calit.user.CurrentOwner currentOwner;
    @Inject SecurityIdentity identity;

    private boolean isAdmin() { return identity.hasRole("admin"); }

    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2", currentOwner.id(), BookingStatus.PENDING);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance google() {
        Long ownerId = currentOwner.id();
        List<GoogleCredential> creds = GoogleCredential.listForOwner(ownerId);
        List<AccountView> accounts = new ArrayList<>();
        boolean loadError = false;
        for (GoogleCredential cred : creds) {
            List<CalendarRow> rows = new ArrayList<>();
            boolean holdsWriteTarget = false;
            try {
                Map<String, GoogleCalendar> saved = GoogleCalendar
                        .<GoogleCalendar>list("googleCredentialId", cred.id).stream()
                        .collect(java.util.stream.Collectors.toMap(c -> c.googleCalendarId, c -> c));
                for (CalendarListPort.RemoteCalendar rc : calendarListPort.listCalendars(cred)) {
                    GoogleCalendar s = saved.get(rc.googleCalendarId());
                    boolean read = s == null ? saved.isEmpty() : s.readForBusy; // first-load default: all read
                    boolean write = s != null && s.writeTarget;
                    if (write) holdsWriteTarget = true;
                    rows.add(new CalendarRow(cred.id, rc.googleCalendarId(), rc.summary(), read, write));
                }
            } catch (RuntimeException ex) {
                loadError = true; // Google unreachable for this account; banner, no editable rows
            }
            accounts.add(new AccountView(cred.id, cred.accountEmail, cred.needsReconnect, rows, holdsWriteTarget));
        }
        return Templates.google(accounts, loadError, pendingCount(), isAdmin());
    }

    @POST
    @Path("/calendars")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response saveSelection(MultivaluedMap<String, String> form) {
        Long ownerId = currentOwner.id();
        List<String> readVals = form.getOrDefault("read", List.of());
        String writeVal = form.getFirst("writeTarget");
        List<CalendarSelectionService.Selection> selections = new ArrayList<>();
        for (GoogleCredential cred : GoogleCredential.listForOwner(ownerId)) {
            if (cred.needsReconnect) continue;
            try {
                for (CalendarListPort.RemoteCalendar rc : calendarListPort.listCalendars(cred)) {
                    String key = cred.id + ":" + rc.googleCalendarId();
                    boolean read = readVals.contains(key);
                    boolean write = key.equals(writeVal);
                    if (read || write) {
                        selections.add(new CalendarSelectionService.Selection(
                                cred.id, rc.googleCalendarId(), rc.summary(), read, write));
                    }
                }
            } catch (RuntimeException ignored) {
                // skip an account that went unreachable mid-save
            }
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
