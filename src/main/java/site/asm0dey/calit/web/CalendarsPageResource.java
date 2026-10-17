package site.asm0dey.calit.web;

import module java.base;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.caldav.CaldavCalendarPort;
import site.asm0dey.calit.caldav.CaldavClient;
import site.asm0dey.calit.caldav.CaldavException;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarAccount;
import site.asm0dey.calit.calendar.CalendarListPort;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.google.CalendarSelectionService;
import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AdminMessageResolver;
import site.asm0dey.calit.i18n.AdminMessages;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * The owner's Connected accounts of every provider: connect, re-enter a CalDAV password, disconnect, and pick
 * which calendars are read for busy time and which one gets the Mirror events.
 */
@Path("/me/calendars")
@RolesAllowed("user")
public class CalendarsPageResource {
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(CalendarsPageResource.class);
    private static final URI PAGE = URI.create("/me/calendars");

    /**
     * What the owner typed into the Connect CalDAV form, echoed back on an error (never the password).
     */
    public record ConnectForm(String serverUrl, String username) {}

    /**
     * What the last submit sends back: a page-level error, a Connect CalDAV form error, and that form's echo.
     */
    public record Feedback(String error, String connectError, ConnectForm connect) {}

    @CheckedTemplate
    public static class Templates {
        private Templates() {
        }

        public static native TemplateInstance calendars(
                List<AccountView> accounts,
                boolean loadError,
                boolean noWriteTarget,
                Feedback feedback,
                Long pendingCount,
                boolean isAdmin,
                String title
        );
    }

    final CalendarListPort calendarListPort;
    final CalendarSelectionService selectionService;
    final CaldavClient caldav;
    final CurrentOwner currentOwner;
    final SecurityIdentity identity;
    final AdminMessageResolver adminMsgs;
    final ActiveLocale activeLocale;

    @Inject
    public CalendarsPageResource(
            CalendarListPort calendarListPort,
            CalendarSelectionService selectionService,
            CaldavClient caldav,
            CurrentOwner currentOwner,
            SecurityIdentity identity,
            AdminMessageResolver adminMsgs,
            ActiveLocale activeLocale
    ) {
        this.calendarListPort = calendarListPort;
        this.selectionService = selectionService;
        this.caldav = caldav;
        this.currentOwner = currentOwner;
        this.identity = identity;
        this.adminMsgs = adminMsgs;
        this.activeLocale = activeLocale;
    }

    private AdminMessages m() {
        return adminMsgs.forLocale(activeLocale.current());
    }

    private static List<CalendarAccount> accounts(Long ownerId) {
        return CalendarAccount.list("ownerId = ?1 order by id", ownerId);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance calendars() {
        return page(null, null, new ConnectForm("", ""));
    }

    private TemplateInstance page(String error, String connectError, ConnectForm connect) {
        Long ownerId = currentOwner.id();
        List<AccountView> accounts = accounts(ownerId).stream().map(this::accountView).toList();
        // the listing threw, as opposed to an account already flagged for reconnect
        var loadError = accounts
            .stream()
            .anyMatch(a -> a.loadFailed() && !a.needsReconnect());
        return Templates.calendars(
                accounts,
                loadError,
                !accounts.isEmpty() && SelectedCalendar.writeTargetOf(ownerId) == null,
                new Feedback(error, connectError, connect),
                Booking.count("ownerId = ?1 and status = ?2", ownerId, BookingStatus.PENDING),
                identity.hasRole("admin"),
                m().adm_google_title()
        );
    }

    private AccountView accountView(CalendarAccount account) {
        Map<String, SelectedCalendar> saved = SelectedCalendar
            .<SelectedCalendar>list("accountId", account.id)
            .stream()
            .collect(Collectors.toMap(c -> c.remoteId, c -> c, (a, b) -> a));
        // The UI only says "couldn't load", so this WARN is the only way an operator learns WHY
        // (e.g. Calendar API not enabled, 403, revoked scope, CalDAV server down).
        Optional<List<CalendarListPort.RemoteCalendar>> live = account.needsReconnect
                ? Optional.empty()
                : listCalendars(account, "Calendar list failed for owner %d, account %d");
        List<CalendarRow> rows = live
            .map(calendars -> liveRows(account.id, calendars, saved))
            // Dead or unreachable account: show the saved config so the owner sees what's configured.
            .orElseGet(() -> saved
                .values()
                .stream()
                .map(s -> new CalendarRow(account.id, s.remoteId, s.summary, s.readForBusy, s.writeTarget))
                .toList());
        return new AccountView(
                account.id,
                account.label() == null ? m().calendars_account_unnamed() : account.label(),
                account.provider().name(),
                account.needsReconnect,
                live.isEmpty(),
                rows
        );
    }

    private static List<CalendarRow> liveRows(
            Long accountId,
            List<CalendarListPort.RemoteCalendar> calendars,
            Map<String, SelectedCalendar> saved
    ) {
        List<CalendarRow> rows = new ArrayList<>();
        for (CalendarListPort.RemoteCalendar rc : calendars) {
            SelectedCalendar s = saved.get(rc.remoteId());
            // first-load: all read
            boolean read = s == null ? saved.isEmpty() : s.readForBusy;
            rows.add(new CalendarRow(accountId, rc.remoteId(), rc.summary(), read, s != null && s.writeTarget));
        }
        return rows;
    }

    /**
     * The account's live calendar list, or empty after logging {@code failure} (formatted with owner and account id).
     */
    private Optional<List<CalendarListPort.RemoteCalendar>> listCalendars(CalendarAccount account, String failure) {
        try {
            return Optional.of(calendarListPort.listCalendars(account));
        } catch (RuntimeException ex) {
            LOG.warnf(ex, failure, account.ownerId, account.id);
            return Optional.empty();
        }
    }

    private Response badRequest(String error, String connectError, ConnectForm connect) {
        return Response.status(Response.Status.BAD_REQUEST).entity(page(error, connectError, connect)).build();
    }

    private String caldavError(CaldavException ex) {
        var m = m();
        return switch (ex.reason()) {
            case PRIVATE_HOST -> m.calendars_caldav_error_private_host();
            case UNAUTHORIZED -> m.calendars_caldav_error_unauthorized();
            case NOT_CALDAV -> m.calendars_caldav_error_not_caldav();
            case UNREACHABLE -> m.calendars_caldav_error_unreachable();
            case HTTP_ERROR -> m.calendars_caldav_error_http();
        };
    }

    // NOT @Transactional: discovery and listing are network calls; the write is its own short transaction.
    @POST
    @Path("/caldav")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response connectCaldav(
            @FormParam("serverUrl") String serverUrl,
            @FormParam("username") String username,
            @FormParam("password") String password
    ) {
        Long ownerId = currentOwner.id();
        var echo = new ConnectForm(serverUrl == null ? "" : serverUrl.strip(), username == null ? "" : username.strip());
        if (echo.serverUrl().isEmpty() || echo.username().isEmpty() || password == null || password.isEmpty()) {
            return badRequest(null, m().calendars_caldav_error_missing(), echo);
        }
        var account = new CaldavAccount();
        account.ownerId = ownerId;
        account.username = echo.username();
        account.password = password;
        try {
            account.serverUrl = CaldavClient.serverUri(echo.serverUrl()).toASCIIString();
        } catch (CaldavException ex) {
            // the rejected text may hold userinfo, so it is not echoed back
            return badRequest(null, caldavError(ex), new ConnectForm("", echo.username()));
        }
        try {
            var found = caldav.discover(account.serverUrl, account.username, password);
            account.principalUrl = found.principalUrl().toString();
            account.homeUrl = found.homeUrl().toString();
            // proves the home is listable before anything is stored
            caldav.listCalendars(account);
        } catch (CaldavException ex) {
            // reason only: messages and URLs can carry what the owner typed, userinfo included
            LOG.infof("CalDAV connect refused for owner %d: %s", ownerId, ex.reason());
            return badRequest(null, caldavError(ex), echo);
        } catch (RuntimeException ex) {
            LOG.warnf("CalDAV connect failed for owner %d: %s", ownerId, ex.getClass().getName());
            return badRequest(null, m().calendars_caldav_error_http(), echo);
        }
        QuarkusTransaction.requiringNew().run(() -> {
            CaldavAccount existing =
                    CaldavAccount
                .find("ownerId = ?1 and principalUrl = ?2", ownerId, account.principalUrl)
                .firstResult();
            if (existing == null) {
                account.persist();
            } else {
                existing.password = password;
                existing.needsReconnect = false;
                existing.reconnectNotifiedAt = null;
            }
        });
        return Response.seeOther(PAGE).build();
    }

    @POST
    @Path("/caldav/{accountId}/password")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response updatePassword(@PathParam("accountId") Long accountId, @FormParam("password") String password) {
        Long ownerId = currentOwner.id();
        if (!(CalendarAccount.findOwned(ownerId, accountId) instanceof CaldavAccount account)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (password == null || password.isEmpty()) {
            return badRequest(m().calendars_caldav_error_missing(), null, new ConnectForm("", ""));
        }
        try {
            caldav.discover(account.serverUrl, account.username, password);
        } catch (CaldavException ex) {
            return badRequest(caldavError(ex), null, new ConnectForm("", ""));
        } catch (RuntimeException ex) {
            LOG.warnf("CalDAV password check failed for account %d: %s", accountId, ex.getClass().getName());
            return badRequest(m().calendars_caldav_error_http(), null, new ConnectForm("", ""));
        }
        // disconnected while we were talking to the server
        boolean stored = QuarkusTransaction.requiringNew().call(() -> {
            CaldavAccount a = CaldavAccount.findById(accountId);
            if (a == null) {
                return false;
            }
            a.password = password;
            a.needsReconnect = false;
            a.reconnectNotifiedAt = null;
            return true;
        });
        return stored ? Response.seeOther(PAGE).build() : Response.status(Response.Status.NOT_FOUND).build();
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
        Set<Long> covered = new HashSet<>();
        Set<Long> reachable = new HashSet<>();
        for (CalendarAccount account : accounts(ownerId)) {
            covered.add(account.id);
            if (account.needsReconnect) {
                // unreachable: preserved from DB below
                continue;
            }
            var live = listCalendars(
                    account,
                    "Calendar list failed while saving selection for owner %d, account %d; keeping saved rows"
            );
            if (live.isEmpty()) {
                // unreachable mid-save: preserved from DB below
                continue;
            }
            reachable.add(account.id);
            for (CalendarListPort.RemoteCalendar rc : live.get()) {
                String key = account.id + ":" + rc.remoteId();
                var read = readVals.contains(key);
                var write = key.equals(writeVal);
                if (write && account instanceof CaldavAccount && !CaldavCalendarPort.fitsEventIds(rc.remoteId())) {
                    return badRequest(m().calendars_error_write_href_too_long(), null, new ConnectForm("", ""));
                }
                if (read || write) {
                    selections.add(
                            new CalendarSelectionService.Selection(
                                    account.id,
                                    rc.remoteId(),
                                    rc.summary(),
                                    read,
                                    write,
                                    rc.meetSupported()
                            )
                    );
                }
            }
        }

        boolean submittedHasWriteTarget = selections
            .stream()
            .anyMatch(CalendarSelectionService.Selection::writeTarget);
        // an explicit "None" (empty value) gives up the write calendar even on an unreachable account
        selections.addAll(preserved(ownerId, covered, reachable, !submittedHasWriteTarget && writeVal == null));

        selectionService.save(ownerId, covered, selections);
        return Response.seeOther(PAGE).build();
    }

    /**
     * Existing rows for accounts we could NOT reach (flagged/errored). Keeps their read selections; keeps a
     * write target only when {@code keepWriteTarget} (the form chose no new one), so the single-write-target-per-owner
     * invariant holds.
     */
    private static List<CalendarSelectionService.Selection> preserved(
            Long ownerId,
            Set<Long> covered,
            Set<Long> reachable,
            boolean keepWriteTarget
    ) {
        List<CalendarSelectionService.Selection> kept = new ArrayList<>();
        for (SelectedCalendar s : SelectedCalendar.<SelectedCalendar>list("ownerId", ownerId)) {
            if (reachable.contains(s.accountId) || !covered.contains(s.accountId)) {
                // reachable accounts are fully respecified by the form
                continue;
            }
            var keepWrite = s.writeTarget && keepWriteTarget;
            kept.add(
                    new CalendarSelectionService.Selection(
                            s.accountId,
                            s.remoteId,
                            s.summary,
                            s.readForBusy || keepWrite,
                            keepWrite,
                            s.supportsMeet
                    )
            );
        }
        return kept;
    }

    @POST
    @Path("/accounts/{accountId}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response disconnect(@PathParam("accountId") Long accountId) {
        Long ownerId = currentOwner.id();
        CalendarAccount account = CalendarAccount.findOwned(ownerId, accountId);
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // ON DELETE CASCADE removes this account's calendar rows; losing the write calendar just means no
        // Mirror events until another is picked (calit sends the .ics)
        account.delete();
        return Response.seeOther(PAGE).build();
    }
}
