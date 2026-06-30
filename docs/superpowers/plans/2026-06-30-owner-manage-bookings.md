# Owner Manage Bookings + Email Sender Name — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a meeting owner cancel or reschedule a booking from `/me` and from a link in notification emails, and make the email sender display name read `"{Owner} via calit"` instead of `"Notify"`.

**Architecture:** Reuse existing `BookingService.cancel(manageToken)` / `reschedule(manageToken, …)` (already used by the attendee). Add a login-gated owner "manage" page on `AdminResource` (slot picker + cancel) that the dashboard and owner emails link to. Thread a per-message `fromName` through `MailSender` so each booking email is sent From `"{ownerName} via calit <app.mail-from>"`.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute templates, Panache, RestAssured + MockMailbox tests, daisyUI/Tailwind.

## Global Constraints

- Owner scoping: every booking query/action filters by `currentOwner.id()`; reuse `requireOwnedBooking(id)` (404 if not theirs). One user must never act on another's booking.
- Every new/changed user-facing string ships with `@Message` English default **and** `de` **and** `he` values in `messages/{msg,adm}_{de,he}.properties` (same change). Placeholder names identical across locales.
- New `/me` POST forms carry `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">` (CSRF on in prod, off in `%test`).
- `mvn test` requires Docker (Dev Services Postgres). Admin user is always id 1; tests authenticate via `FormAuth.login()` → `cookie("quarkus-credential", …)`.
- Format before commit: `bun run format` (Spotless palantir for Java). Qute `.html` is NOT Prettier-formatted.
- Java entities use public fields, no getters/setters (Panache convention).

---

### Task 1: Email sender display name (`{Owner} via calit`)

Thread a nullable `fromName` through every send path. When non-null, `MailSender` sets the per-message From to `fromName + " <" + app.mail-from + ">"`; the bare address (`app.mail-from`, the ICS ORGANIZER) is unchanged, so SPF/DKIM and `.ics` rendering are unaffected. Outbox retries send with the config-default From (cosmetic-only degradation; not worth a schema column).

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/MailSender.java`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/java/site/asm0dey/calit/email/OutboxScheduler.java:63`
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java` (add methods)

**Interfaces:**
- Produces: `MailSender.sendNow(String fromName, String to, String subject, String html, byte[] ics)`; `MailSender.send(String fromName, String to, String subject, String html, byte[] ics)`; `MailSender.send(String fromName, String to, String subject, String html, byte[] ics, Instant notAfter)`. `fromName == null` → From left to config.
- Consumes (later tasks): none.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java` (the seed sets `s.ownerName = "Owner"`, and `%test` `app.mail-from` defaults to `calit@example.com`):

```java
@Test
void bookingMailFromCarriesOwnerDisplayName() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");

    emailService.handleConfirmed(new BookingConfirmed(bookingId));

    Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
    assertEquals("Owner via calit <calit@example.com>", owner.getFrom());
}

@Test
void passwordResetMailHasNoPerMessageFrom() {
    mailbox.clear();
    emailService.sendPasswordReset("u@example.com", "https://x/reset", Instant.now().plusSeconds(3600), java.util.Locale.ENGLISH);
    assertNull(mailbox.getMailsSentTo("u@example.com").getFirst().getFrom(),
            "no per-message From -> falls back to config default");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailServiceTest#bookingMailFromCarriesOwnerDisplayName`
Expected: FAIL — `getFrom()` is `null` (no per-message From set yet); also a compile error until `MailSender` is updated, so this fails to compile/assert.

- [ ] **Step 3: Update `MailSender` to accept and apply `fromName`**

Rewrite the send methods in `src/main/java/site/asm0dey/calit/email/MailSender.java`. Add the config field and an import for `ConfigProperty`:

```java
import org.eclipse.microprofile.config.inject.ConfigProperty;
```

```java
    @Inject
    Mailer mailer;

    /** Bare sending address; the per-message display name (when present) is prefixed onto this. */
    @ConfigProperty(name = "app.mail-from")
    String mailFrom;

    /** Direct send; throws on SMTP failure. {@code fromName} null → From left to config default. */
    public void sendNow(String fromName, String to, String subject, String html, byte[] ics) {
        Mail mail = Mail.withHtml(to, subject, html);
        if (fromName != null) {
            mail.setFrom(fromName + " <" + mailFrom + ">");
        }
        if (ics != null) {
            mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
        }
        mailer.send(mail);
    }

    /** Try direct; on any failure, durably queue to the outbox for retry (no usefulness deadline). */
    public void send(String fromName, String to, String subject, String html, byte[] ics) {
        send(fromName, to, subject, html, ics, null);
    }

    /**
     * Try direct; on any failure, durably queue to the outbox for retry. Never throws.
     * {@code notAfter} non-null bounds retry. ponytail: the outbox does not persist {@code fromName};
     * a retried mail sends with the config-default From (cosmetic only). Add an email_outbox column
     * only if branded From on the rare retry path is ever required.
     */
    public void send(String fromName, String to, String subject, String html, byte[] ics, Instant notAfter) {
        try {
            sendNow(fromName, to, subject, html, ics);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew()
                    .run(() -> EmailOutbox.enqueue(to, subject, html, ics, notAfter, e.getMessage()));
            Log.warnf(e, "SMTP send failed, queued to outbox: to=%s subject=%s", to, subject);
        }
    }
```

- [ ] **Step 4: Update `OutboxScheduler` retry call**

In `src/main/java/site/asm0dey/calit/email/OutboxScheduler.java:63`, change:

```java
                        mailSender.sendNow(r.recipient, r.subject, r.htmlBody, r.icsBytes);
```
to:
```java
                        mailSender.sendNow(null, r.recipient, r.subject, r.htmlBody, r.icsBytes);
```

- [ ] **Step 5: Add the `fromName` helper and thread it through `EmailService`**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`:

Add the helper (place it near `manageUrl`, around line 700):

```java
    /** Per-message From display name for booking mail: "{owner} via calit", or null if no owner name. */
    private String fromName(Loaded l) {
        // ponytail: "via calit" is the product name; make it config (app.brand-name) only on a real rebrand.
        return l.owner.ownerName == null ? null : l.owner.ownerName + " via calit";
    }
```

Update `MailSink.deliver` to carry `fromName` first:

```java
    @FunctionalInterface
    private interface MailSink {
        void deliver(String fromName, String to, String subject, String html, byte[] ics);
    }
```

Update `enqueueToOutbox` to match (ignore `fromName` — see MailSender ponytail note):

```java
    private static void enqueueToOutbox(String fromName, String to, String subject, String html, byte[] ics) {
        EmailOutbox.enqueue(to, subject, html, ics, null, "scheduled dispatch (transactional outbox)");
    }
```

In `sendForKindLocaleAware(…, MailSink sink)` (the 7-arg overload), compute the name once and pass it to both deliveries:

```java
        var sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected(l.owner.ownerId);
        boolean sendOwner = l.owner.ownerNotificationsEnabled;
        String from = fromName(l);
        // … ics build unchanged …
        if (sendInvitee) {
            sink.deliver(from, l.booking.inviteeEmail, inviteeSubject, bodyForRole.apply(INVITEE_ROLE), ics);
        }
        if (sendOwner) {
            sink.deliver(from, l.owner.ownerEmail, ownerSubject, bodyForRole.apply(OWNER_ROLE), ics);
        }
```

Update every direct `mailSender.send(...)` / `mailSender::send` call:
- `sendPasswordReset` (L141): `mailSender.send(null, toEmail, …, body, null, expiresAt);`
- `sendGoogleDisconnected` (L159): `mailSender.send(null, toEmail, …, body, null);`
- `sendGuestInvites` (L543): `mailSender.send(fromName(l), g.email, subject, body, ics);`
- `sendGuestCancels` (L554): `mailSender.send(fromName(l), g.email, subject, guestCancelBody(l, g, locale), guestIcs(l, g, null, IcsMethod.CANCEL));`
- `handleGuestRemoved` (L564): first arg `fromName(l)`.
- `handleGuestDeclined` (L579 guest cancel, L596 invitee notice): first arg `fromName(l)`.

The two `sendForKindLocaleAware(…)` 6-arg overload sites need no change (they delegate to the 7-arg with `mailSender::send`, whose reference now matches the new `MailSink` signature).

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: PASS (all existing methods + the two new ones).

- [ ] **Step 7: Commit**

```bash
bun run format
git add src/main/java/site/asm0dey/calit/email/ src/test/java/site/asm0dey/calit/email/EmailServiceTest.java
git commit -m "feat(email): per-message From display name '{owner} via calit'"
```

---

### Task 2: Owner manage page (GET) + dashboard link

A login-gated owner-facing page mirroring the attendee `manage.html`: shows the current booking, a reschedule slot picker, and a cancel button. The dashboard links to it. Reuses the public `PublicResource.DaySlots`/`SlotView` records and existing `{msg:pub_manage_*}` strings.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java`
- Create: `src/main/resources/templates/AdminResource/manageBooking.html`
- Modify: `src/main/resources/templates/AdminResource/dashboard.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`
- Modify: `src/main/resources/messages/adm_de.properties`, `src/main/resources/messages/adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java` (create)

**Interfaces:**
- Consumes: `requireOwnedBooking(Long)`, `bookingService.availableSlots(MeetingType, LocalDate, LocalDate)`, `PublicResource.DaySlots`, `PublicResource.SlotView`, `BookingGuest.activeForBooking(Long)`.
- Produces: `GET /me/bookings/{id}/manage` rendering `AdminResource/manageBooking.html`; `AdminResource.Templates.manageBooking(...)`; `AdminMessages.adm_dashboard_btn_manage()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java`. The seed mirrors `AdminPendingTest` but creates an **auto** (no-approval) booking so it lands CONFIRMED:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class OwnerManageBookingTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    /** Seeds an auto (no-approval) PHONE meeting and books it → CONFIRMED. Returns its id. */
    @Transactional
    Long seedConfirmedBooking() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        var slug = "owner-manage-" + System.nanoTime();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Owner Manage Type";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
        t.requiresApproval = false;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        Booking b = bookingService.book(
                1L, slug, slot.start().toInstant(), "Pat", "pat@example.com",
                java.util.Map.of(), "", "", "en", List.of());
        return b.id; // CONFIRMED (no approval)
    }

    @Test
    void managePageShowsRescheduleAndCancelForOwnBooking() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seedConfirmedBooking();

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/" + id + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/me/bookings/" + id + "/reschedule"))
                .body(containsString("/me/bookings/" + id + "/cancel"))
                .body(containsString("Pat"));
    }

    @Test
    void managePageReturns404ForUnknownBooking() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/999999/manage")
                .then()
                .statusCode(404);
    }

    @Test
    void managePageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/bookings/1/manage").then().statusCode(302);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OwnerManageBookingTest#managePageShowsRescheduleAndCancelForOwnBooking`
Expected: FAIL — 404 (route does not exist yet).

- [ ] **Step 3: Add the `manageBooking` template signature**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, add to the `Templates` inner class (after the `pending` declaration, ~L94):

```java
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
                String title);
```

Add imports near the top of `AdminResource.java`:

```java
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.BookingGuest;
```

- [ ] **Step 4: Add the day-grouping helper and GET route to `AdminResource`**

Add formatters as static fields (near the other constants, after `reminderLeadMinutes`):

```java
    // Mirrors PublicResource.daySlots formatting; the client TZ script relabels to the viewer's zone,
    // so this server label is only a fallback. ponytail: extract a shared helper if a 3rd consumer appears.
    private static final DateTimeFormatter MANAGE_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");
    private static final DateTimeFormatter MANAGE_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Available slots for a meeting type as an ordered per-day list (reuses the public view records). */
    private List<PublicResource.DaySlots> daySlots(MeetingType type) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        LocalDate from = LocalDate.now(zone);
        LocalDate to = from.plusDays(type.horizonDays);
        Map<String, PublicResource.DaySlots> byIso = new LinkedHashMap<>();
        for (TimeSlot slot : bookingService.availableSlots(type, from, to)) {
            String isoDate = slot.start().toLocalDate().toString();
            var day = byIso.computeIfAbsent(
                    isoDate,
                    k -> new PublicResource.DaySlots(k, slot.start().format(MANAGE_DATE_FMT), new java.util.ArrayList<>()));
            day.slots()
                    .add(new PublicResource.SlotView(
                            slot.start().format(MANAGE_TIME_FMT),
                            slot.start().toInstant().toString()));
        }
        return new java.util.ArrayList<>(byIso.values());
    }
```

Add the GET route (place after `requireOwnedBooking`, ~L897):

```java
    @GET
    @Path("/bookings/{id}/manage")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance manageBooking(@PathParam("id") Long id) {
        Booking b = requireOwnedBooking(id);
        MeetingType type = MeetingType.findById(b.meetingTypeId);
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        String current = b.startUtc
                .atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
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
                m().adm_dashboard_h2());
    }
```

- [ ] **Step 5: Create the manage template**

Create `src/main/resources/templates/AdminResource/manageBooking.html` (mirrors `PublicResource/manage.html` but uses `adminBase` and posts to `/me/...` with CSRF; reuses `{msg:pub_manage_*}` strings and the shared `PublicResource/_guestschips` partial):

```html
{@site.asm0dey.calit.booking.Booking booking}
{@java.lang.String currentLabel}
{@java.lang.String currentUtcIso}
{@java.util.List<site.asm0dey.calit.web.PublicResource$DaySlots> days}
{@java.lang.String guestsCsv}
{@java.lang.Long pendingCount}
{@java.lang.Boolean isAdmin}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{@java.lang.String calScript}
{@java.lang.String title}
{#include adminBase title=title pendingCount=pendingCount active="dashboard" isAdmin=isAdmin}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-2xl">
    <div class="card-body">
      <h1 class="text-2xl font-bold">{msg:pub_manage_h1}</h1>
      {tzBar.raw}
      <div class="rounded-box bg-base-200 border border-base-300 p-4 my-4 space-y-1">
        <p><strong>{msg:pub_manage_currently_label}</strong> <time data-utc="{currentUtcIso}">{currentLabel}</time></p>
        <p><strong>{msg:pub_manage_for_label}</strong> {booking.inviteeName} ({booking.inviteeEmail})</p>
      </div>

      <h2 class="text-lg font-semibold mt-2 mb-3">{msg:pub_manage_h2_reschedule}</h2>
      {#if days.isEmpty()}
        <p>{msg:pub_manage_no_alternatives}</p>
      {#else}
        <form method="post" action="/me/bookings/{booking.id}/reschedule" class="space-y-4">
          <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
          <div class="booking-grid">
            <div id="calendar" class="calendar"></div>
            <div class="time-column">
              {#for day in days}
                <section class="day-slots" data-date="{day.isoDate}">
                  <h3>{day.label}</h3>
                  {#for slot in day.slots}
                    <label class="slot">
                      <input type="radio" name="startUtc" value="{slot.startUtc}" required>
                      <time data-utc="{slot.startUtc}" data-time-only="1">{slot.label}</time>
                    </label>
                  {/for}
                </section>
              {/for}
            </div>
          </div>
          {#include PublicResource/_guestschips initial=guestsCsv /}
          <button type="submit" class="btn btn-primary">{msg:pub_manage_btn_reschedule}</button>
        </form>
      {/if}

      <div class="divider"></div>
      <h2 class="text-lg font-semibold mb-3">{msg:pub_manage_h2_cancel}</h2>
      <form method="post" action="/me/bookings/{booking.id}/cancel">
        <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
        <button type="submit" class="btn btn-error btn-outline">{msg:pub_manage_btn_cancel}</button>
      </form>
      {tzScript.raw}
      {calScript.raw}
    </div>
  </div>
{/include}
```

- [ ] **Step 6: Add the dashboard "Manage" link**

In `src/main/resources/templates/AdminResource/dashboard.html`, inside the per-booking card (after the meet-link line, L29), add:

```html
        <p><a class="link link-primary" href="/me/bookings/{b.id}/manage">{adm:adm_dashboard_btn_manage}</a></p>
```

- [ ] **Step 7: Add the `adm_dashboard_btn_manage` message + translations**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, after `adm_dashboard_no_upcoming()` (L109):

```java
    @Message("Manage")
    String adm_dashboard_btn_manage();
```

In `src/main/resources/messages/adm_de.properties`, after the `adm_dashboard_no_upcoming` line (L38):

```properties
adm_dashboard_btn_manage=Verwalten
```

In `src/main/resources/messages/adm_he.properties`, after the `adm_dashboard_no_upcoming` line (L38):

```properties
adm_dashboard_btn_manage=ניהול
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `mvn test -Dtest=OwnerManageBookingTest`
Expected: PASS (all three methods).

- [ ] **Step 9: Commit**

```bash
bun run format
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/manageBooking.html \
        src/main/resources/templates/AdminResource/dashboard.html \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties \
        src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java
git commit -m "feat(me): owner manage-booking page (reschedule picker + cancel) linked from dashboard"
```

---

### Task 3: Owner reschedule (POST)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java`
- Test: `src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java` (add methods)

**Interfaces:**
- Consumes: `requireOwnedBooking(Long)`, `bookingService.reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails)`, `BookingGuest.activeForBooking(Long)`, `dashboard()`.
- Produces: `POST /me/bookings/{id}/reschedule` (form param `startUtc`).

- [ ] **Step 1: Write the failing test**

Add to `OwnerManageBookingTest`:

```java
@Test
void rescheduleMovesTheBookingToTheChosenSlot() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    var id = seedConfirmedBooking();
    Booking before = Booking.findById(id);
    // pick a different available slot from the type's availability
    MeetingType type = MeetingType.findById(before.meetingTypeId);
    var slots = bookingService.availableSlots(type, now(), now().plusDays(14));
    var target = slots.stream()
            .map(s -> s.start().toInstant())
            .filter(i -> !i.equals(before.startUtc))
            .findFirst()
            .orElseThrow();

    given().cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", target.toString())
            .when()
            .post("/me/bookings/" + id + "/reschedule")
            .then()
            .statusCode(200);

    Booking after = Booking.findById(id);
    org.junit.jupiter.api.Assertions.assertEquals(target, after.startUtc);
    org.junit.jupiter.api.Assertions.assertTrue(after.icsSequence > before.icsSequence, "sequence bumped");
}

@Test
void rescheduleAnotherOwnersBookingIs404AndDoesNotMutate() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    var id = seedConfirmedBooking();
    var before = ((Booking) Booking.findById(id)).startUtc;

    // Log in as a second, non-owning user (admin id 1 is the owner; create + login a different user).
    given().cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", before.toString())
            .when()
            .post("/me/bookings/999999/reschedule")
            .then()
            .statusCode(404);

    org.junit.jupiter.api.Assertions.assertEquals(before, ((Booking) Booking.findById(id)).startUtc);
}
```

(Note: the 404 case uses a non-existent id to exercise `requireOwnedBooking`'s 404 without needing a second seeded owner; cross-owner isolation for a *real* other owner is covered by the existing `CrossOwnerIsolationTest`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OwnerManageBookingTest#rescheduleMovesTheBookingToTheChosenSlot`
Expected: FAIL — 404/405 (route does not exist).

- [ ] **Step 3: Add the POST route**

In `AdminResource.java`, after `manageBooking` (Task 2), add. Import `org.jboss.resteasy.reactive.RestForm` already present (used elsewhere); `java.time.Instant` — add `import java.time.Instant;` if not present:

```java
    @POST
    @Path("/bookings/{id}/reschedule")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance ownerReschedule(@PathParam("id") Long id, @RestForm String startUtc) {
        Booking b = requireOwnedBooking(id);
        // Preserve the booking's current guests (reschedule reconciles against the passed list;
        // an empty list would remove them). Keyed by the booking's own manageToken.
        List<String> guests = BookingGuest.activeForBooking(b.id).stream()
                .map(g -> g.email)
                .toList();
        bookingService.reschedule(b.manageToken, Instant.parse(startUtc), guests);
        return dashboard(); // re-render /me; rescheduled booking reflects its new time (or moves to pending queue)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OwnerManageBookingTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
bun run format
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java
git commit -m "feat(me): owner can reschedule a booking from the manage page"
```

---

### Task 4: Owner cancel (POST)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java`
- Test: `src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java` (add method)

**Interfaces:**
- Consumes: `requireOwnedBooking(Long)`, `bookingService.cancel(String manageToken)`, `dashboard()`.
- Produces: `POST /me/bookings/{id}/cancel`.

- [ ] **Step 1: Write the failing test**

Add to `OwnerManageBookingTest`:

```java
@Test
void cancelMarksTheBookingCancelled() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    var id = seedConfirmedBooking();

    given().cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/bookings/" + id + "/cancel")
            .then()
            .statusCode(200);

    org.junit.jupiter.api.Assertions.assertEquals(
            site.asm0dey.calit.booking.BookingStatus.CANCELLED, ((Booking) Booking.findById(id)).status);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OwnerManageBookingTest#cancelMarksTheBookingCancelled`
Expected: FAIL — 404/405 (route does not exist).

- [ ] **Step 3: Add the POST route**

In `AdminResource.java`, after `ownerReschedule`:

```java
    @POST
    @Path("/bookings/{id}/cancel")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance ownerCancel(@PathParam("id") Long id) {
        Booking b = requireOwnedBooking(id);
        bookingService.cancel(b.manageToken); // keyed by the booking's own token; fires BookingCancelled
        return dashboard(); // re-render /me; the cancelled booking drops off the upcoming list
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OwnerManageBookingTest`
Expected: PASS (full class).

- [ ] **Step 5: Commit**

```bash
bun run format
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java
git commit -m "feat(me): owner can cancel a booking from the manage page"
```

---

### Task 5: Owner manage link in notification emails

Owner copies of the CONFIRMED-booking emails (confirmation, reschedule, reminder) gain a link to the login-gated `/me/bookings/{id}/manage` page.

**Files:**
- Create: `src/main/resources/templates/email/_ownerlinks.html`
- Modify: `src/main/resources/templates/email/confirmation.html`, `reschedule.html`, `reminder.html`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Modify: `src/main/resources/messages/msg_de.properties`, `src/main/resources/messages/msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java` (add method)

**Interfaces:**
- Consumes: `OWNER_ROLE`, `Loaded.booking.id`, `baseUrl`.
- Produces: `EmailService.OWNER_MANAGE_URL` constant + `ownerManageUrl(Booking)` builder; `AppMessages.email_body_owner_manage_link_text()`.

- [ ] **Step 1: Write the failing test**

Add to `EmailServiceTest`:

```java
@Test
void confirmedOwnerMailContainsManageLink() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");

    emailService.handleConfirmed(new BookingConfirmed(bookingId));

    Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
    assertTrue(owner.getHtml().contains("/me/bookings/" + bookingId + "/manage"),
            "owner copy links to the /me manage page");
    Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
    assertFalse(invitee.getHtml().contains("/me/bookings/"),
            "invitee copy must NOT contain the owner /me link");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailServiceTest#confirmedOwnerMailContainsManageLink`
Expected: FAIL — owner HTML has no `/me/bookings/.../manage` link yet.

- [ ] **Step 3: Add the URL constant, builder, and template data**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`, add the constant near `MANAGE_URL` (L52):

```java
    /** Owner-only login-gated manage (reschedule/cancel) link on /me; rendered only for the owner copy. */
    public static final String OWNER_MANAGE_URL = "ownerManageUrl";
```

Add the builder next to `manageUrl` (~L703):

```java
    private String ownerManageUrl(Booking b) {
        return baseUrl + "/me/bookings/" + b.id + "/manage";
    }
```

In the three owner-facing CONFIRMED handlers, add the data line alongside `.data(MANAGE_URL, manageUrl(l.booking))`:
- `handleConfirmed` (~L294) and `handleApproved` (~L333): add `.data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))`.
- `handleRescheduled` (~L427): add `.data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))`.
- `deliverReminder` (~L502): add `.data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))`.

- [ ] **Step 4: Create the owner-links partial and wire it into the three templates**

Create `src/main/resources/templates/email/_ownerlinks.html`:

```html
<p><a href="{ownerManageUrl}">{msg:email_body_owner_manage_link_text}</a></p>
```

In `confirmation.html`, `reschedule.html`, and `reminder.html`, replace the last content line (currently `{#if recipientRole != 'owner'}{#include email/_inviteelinks /}{/if}`) with:

```html
{#if recipientRole == 'owner'}{#include email/_ownerlinks /}{#else}{#include email/_inviteelinks /}{/if}
```

- [ ] **Step 5: Add the `email_body_owner_manage_link_text` message + translations**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, after `email_body_manage_link_text()` (L491):

```java
    @Message("Reschedule or cancel")
    String email_body_owner_manage_link_text();
```

In `src/main/resources/messages/msg_de.properties`, after the `email_body_manage_link_text` line (L154):

```properties
email_body_owner_manage_link_text=Verschieben oder stornieren
```

In `src/main/resources/messages/msg_he.properties`, after the `email_body_manage_link_text` line (L154):

```properties
email_body_owner_manage_link_text=שינוי מועד או ביטול
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: PASS (existing + new). The existing invitee-link assertions still hold (invitee branch unchanged).

- [ ] **Step 7: Commit**

```bash
bun run format
git add src/main/resources/templates/email/_ownerlinks.html \
        src/main/resources/templates/email/confirmation.html \
        src/main/resources/templates/email/reschedule.html \
        src/main/resources/templates/email/reminder.html \
        src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/email/EmailServiceTest.java
git commit -m "feat(email): owner copies link to the /me manage page (reschedule/cancel)"
```

---

### Task 6: Full suite + docs

**Files:**
- Docs: `docs-site` branch — `docs-site/src/content/docs/releases/changelog.md` and any usage page covering owner booking management.

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: PASS (all classes). Docker must be running.

- [ ] **Step 2: Verify formatting gate**

Run: `mvn spotless:check`
Expected: BUILD SUCCESS (no unformatted Java).

- [ ] **Step 3: Update docs on the `docs-site` branch**

On the `docs-site` branch, add a changelog entry (next release section at the top of `docs-site/src/content/docs/releases/changelog.md`) noting: owners can now reschedule/cancel bookings from `/me` and from a link in notification emails; the email sender display name now reads `"{Owner} via calit"`. Add/update the usage page section describing owner booking management. Commit on `docs-site`.

(Per `CLAUDE.md`, docs are part of "done". The changelog entry is required when the release is cut.)

- [ ] **Step 4: Finish the branch**

Use the `superpowers:finishing-a-development-branch` skill to merge / open a PR for `feat/owner-manage-bookings`.

---

## Self-Review

**Spec coverage:**
- Ask 1 (cancel/reschedule from `/me`) → Tasks 2 (page + GET), 3 (reschedule POST), 4 (cancel POST), dashboard link in Task 2.
- Ask 2 (sender display name) → Task 1.
- Ask 3 (cancel/reschedule from email) → Task 5 (owner email link to the login-gated `/me` manage page). Covered.
- i18n (de+he) → Task 2 (`adm_dashboard_btn_manage`), Task 5 (`email_body_owner_manage_link_text`); page reuses existing `pub_manage_*`.
- Tests → owner-scope 404, reschedule, cancel, From display name, owner-email link.
- Docs → Task 6.

**Placeholder scan:** none — all steps carry concrete code/commands.

**Type consistency:** `daySlots(MeetingType)` returns `List<PublicResource.DaySlots>`, matching the `Templates.manageBooking` param and the template `{@…PublicResource$DaySlots…}` decl. `MailSink.deliver`, `MailSender.sendNow`/`send`, and `enqueueToOutbox` all take `fromName` as the first arg consistently. `ownerReschedule`/`ownerCancel` use `b.manageToken` (String) into `bookingService.reschedule`/`cancel` (String-keyed). `OWNER_MANAGE_URL` constant and `ownerManageUrl(Booking)` builder names match their template `{ownerManageUrl}` usage.
