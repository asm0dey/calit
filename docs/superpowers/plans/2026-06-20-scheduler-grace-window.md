# Scheduler Grace Window + Crash-Safe Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (1) Let the reminder-dispatch and pending-expiry ticks treat a row as "due" up to a configurable N seconds early (default 30), and (2) make those two ticks crash-safe by enqueuing the outgoing email into the existing `email_outbox` *inside the claim transaction* instead of firing a post-commit CDI event that a crash can drop.

**Architecture:**
- *Grace window* — both schedulers compare against Postgres `now()` (the single clock authority; app-node clocks never enter the comparison). We add one config property `calit.scheduler.grace-seconds` and widen each due-check from `<= now()` to `<= now() + make_interval(secs => :graceSeconds)`. Default 30.
- *Crash-safe dispatch (closes gap #4)* — today each tick commits its claim (`reminder.sent_at` stamped / `booking → DECLINED`) and then fires a CDI event (`ReminderDue` / `BookingDeclined`) in a *separate* post-commit transaction; the observer renders + sends the email. A crash between the claim commit and the event-fire loses the email (the outbox can't help — `MailSender.send` was never called). Fix: in the **same transaction** as the claim, render the email and `EmailOutbox.enqueue(...)` it (a fast INSERT, no SMTP), then let the existing `OutboxScheduler` deliver it with its retry/backoff. Claim + intent-to-send now commit atomically: crash after = outbox row survives and is sent; crash before = nothing changed, row reclaimed next tick.
- *Scope of #4:* the **two schedulers only** (reminder dispatch, pending-expiry). The manual-decline path (owner clicks decline in `BookingService`) keeps its existing tiny `AFTER_SUCCESS` micro-gap — out of scope by decision.
- *Tradeoff accepted:* scheduler emails now always route through the outbox, so the first SMTP attempt happens on the next `OutboxScheduler` tick (≤60s) rather than immediately. Irrelevant for a 24h-lead reminder.
- *Not touched:* `GoogleConnectionScheduler` (its half-interval grace is a different-purpose dedup); all five non-scheduler `AFTER_SUCCESS` emails (requested/confirmed/approved/rescheduled/cancelled) keep firing direct via the event path.

**Tech Stack:** Quarkus 3.36 / Java 25, Hibernate native query, Postgres `make_interval`, MicroProfile Config, Qute templates, existing `EmailOutbox` + `OutboxScheduler`, JUnit 5 + `@QuarkusTest` + `QuarkusTestProfile` + `@InjectMock`.

## Global Constraints

- Default `calit.scheduler.grace-seconds` is `30`. Existing `ReminderTickTest` / `PendingExpiryTest` boundaries (±1h, 30min, 23h) sit far outside a 30s window, so a 30s shift never reclassifies them. `0` restores exact `<= now()`.
- `make_interval(secs => ...)` takes double precision — bind as `(double) graceSeconds`, matching `GoogleConnectionScheduler.claimDueForProbe` (`src/main/java/com/calit/scheduler/GoogleConnectionScheduler.java:61-72`).
- Per-row resilience: in both schedulers the email-enqueue is wrapped in try/catch so a single bad/poison booking (e.g. missing `OwnerSettings`) cannot roll back the whole `LIMIT 50` batch and loop forever. The claim stamp (`sent_at` / `DECLINED`) happens unconditionally; a caught render error logs and drops that one mail (same data-loss as today's swallowed observer error, but never blocks the batch). A *crash* is not a caught exception — it kills the process before commit, so the tx rolls back and the row is reclaimed. That is exactly the #4 guarantee.
- The enqueue must run in the caller's active transaction: use `EmailService.read(...)` (plain `findById`, no `requiringNew`) and `EmailOutbox.enqueue(...)` (joins the active tx). Never open `requiringNew` on the in-tx path — that would break atomicity.
- Never edit an applied Flyway migration. (No schema change here — `email_outbox` already exists.)
- Tests require Docker (Dev Services Postgres). `@TestProfile` triggers an in-JVM Quarkus restart — expected.
- Docs are part of "done": the new env var lands on the `docs-site` branch in the same effort.

---

### Task 1: Add grace config + apply to ReminderScheduler due-check

**Files:**
- Modify: `src/main/resources/application.properties` (add property after `calit.approval.hold-hours`, ~`:143`)
- Modify: `src/main/java/com/calit/scheduler/ReminderScheduler.java:29-30` (add field) and `:132-138` (widen SQL)
- Test: `src/test/java/com/calit/scheduler/ReminderGraceWindowTest.java` (new)

**Interfaces:**
- Consumes: nothing.
- Produces: config property `calit.scheduler.grace-seconds` (env `SCHEDULER_GRACE_SECONDS`, default `30`), reused by Tasks 2/4/5.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/scheduler/ReminderGraceWindowTest.java`:

```java
package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(ReminderGraceWindowTest.Grace120Profile.class)
class ReminderGraceWindowTest {

    public static class Grace120Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.scheduler.grace-seconds", "120");
        }
    }

    @Inject
    ReminderScheduler scheduler;

    @Test
    void dispatchSendsRemindersDueWithinGraceWindow() {
        Long bookingId = seedBooking();
        Long withinGrace = persistReminder(bookingId, Instant.now().plus(60, ChronoUnit.SECONDS)); // +60s, grace=120s -> due
        Long beyondGrace = persistReminder(bookingId, Instant.now().plus(1, ChronoUnit.HOURS));     // +1h -> not due

        scheduler.dispatchDueReminders();

        assertNotNull(reloadSentAt(withinGrace), "reminder due within the grace window must be marked sent");
        assertNull(reloadSentAt(beyondGrace),    "reminder beyond the grace window must stay unsent");
    }

    private Long seedBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "grace-" + System.nanoTime();
            t.slug = "grace-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            Instant start = Instant.now().plus(200, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plusSeconds(1800);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private Long persistReminder(Long bookingId, Instant sendAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = sendAt;
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
            return r.id;
        });
    }

    private Instant reloadSentAt(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> ((Reminder) Reminder.findById(id)).sentAt);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ReminderGraceWindowTest`
Expected: FAIL — `withinGrace` stays unsent under the current `send_at <= now()`, so the first assertion fails (`expected: not <null>`).

- [ ] **Step 3: Add the config property**

In `src/main/resources/application.properties`, after the `calit.approval.hold-hours` line, add:

```properties
# Scheduler tick grace window (seconds). A reminder or pending-expiry row is treated as "due" up
# to this many seconds early, so replicas ticking on independent (unsynchronised) timers fire on
# time instead of waiting a whole extra 60s tick. Postgres now() is the clock authority, so this
# shifts the due-boundary uniformly on every node. Default 30. 0 = exact match on now().
calit.scheduler.grace-seconds=${SCHEDULER_GRACE_SECONDS:30}
```

- [ ] **Step 4: Add the field + widen the SQL**

In `src/main/java/com/calit/scheduler/ReminderScheduler.java`, add after the `leadMinutes` field (line 30):

```java
    @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30")
    int graceSeconds;
```

Change the query in `claimAndMarkDueReminders()` (lines 132-138) from:

```java
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM reminder "
                            + "WHERE sent_at IS NULL AND send_at <= now() "
                            + "ORDER BY send_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .getResultList();
```

to:

```java
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM reminder "
                            + "WHERE sent_at IS NULL AND send_at <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY send_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();
```

- [ ] **Step 5: Run grace + existing tick test**

Run: `mvn test -Dtest=ReminderGraceWindowTest,ReminderTickTest`
Expected: PASS. `ReminderTickTest` runs at default grace 30 — its boundaries (due −1min, future +1h) are far outside a 30s window, classification unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.properties \
        src/main/java/com/calit/scheduler/ReminderScheduler.java \
        src/test/java/com/calit/scheduler/ReminderGraceWindowTest.java
git commit -m "feat(scheduler): configurable grace window for reminder dispatch"
```

---

### Task 2: Apply grace window to PendingExpiryScheduler due-check

**Files:**
- Modify: `src/main/java/com/calit/scheduler/PendingExpiryScheduler.java:28-29` (add field) and `:55-63` (widen SQL)
- Test: `src/test/java/com/calit/scheduler/PendingExpiryGraceWindowTest.java` (new)

**Interfaces:**
- Consumes: `calit.scheduler.grace-seconds` (Task 1).
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/scheduler/PendingExpiryGraceWindowTest.java`:

```java
package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(PendingExpiryGraceWindowTest.Grace120Profile.class)
class PendingExpiryGraceWindowTest {

    public static class Grace120Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.scheduler.grace-seconds", "120");
        }
    }

    @Inject
    PendingExpiryScheduler scheduler;

    @BeforeEach
    void clearBookings() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();
        });
    }

    @Test
    void expiresPendingWhoseExpiryIsWithinGraceWindow() {
        Long meetingTypeId = seedMeetingType();

        // Created now, starts in 60s -> expiry = LEAST(createdAt+24h, startUtc) = +60s. Grace 120s -> expire.
        Long withinGrace = seedBooking(meetingTypeId, Instant.now(),
                Instant.now().plus(60, ChronoUnit.SECONDS), BookingStatus.PENDING);
        // Created now, starts in 1h -> expiry = +1h, beyond grace -> keep.
        Long beyondGrace = seedBooking(meetingTypeId, Instant.now(),
                Instant.now().plus(1, ChronoUnit.HOURS), BookingStatus.PENDING);

        scheduler.expirePendingBookings();

        assertEquals(BookingStatus.DECLINED, reloadStatus(withinGrace), "expiry within grace must be declined");
        assertEquals(BookingStatus.PENDING, reloadStatus(beyondGrace),  "expiry beyond grace must stay pending");
    }

    private Long seedMeetingType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "expgrace-" + System.nanoTime();
            t.slug = "expgrace-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            return t.id;
        });
    }

    private Long seedBooking(Long meetingTypeId, Instant createdAt, Instant startUtc, BookingStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = meetingTypeId;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = startUtc;
            b.endUtc = startUtc.plusSeconds(1800);
            b.status = status;
            b.createdAt = createdAt;
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private BookingStatus reloadStatus(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> ((Booking) Booking.findById(id)).status);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PendingExpiryGraceWindowTest`
Expected: FAIL — `withinGrace` stays PENDING under `<= now()`, so "expiry within grace must be declined" fails (`expected: DECLINED but was: PENDING`).

- [ ] **Step 3: Add the field + widen the SQL**

In `src/main/java/com/calit/scheduler/PendingExpiryScheduler.java`, add after the `holdHours` field (line 29):

```java
    @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30")
    int graceSeconds;
```

Change the query in `claimAndDeclineExpired()` (lines 55-63) from:

```java
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) <= now() "
                            + "ORDER BY created_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .getResultList();
```

to:

```java
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) "
                            + "    <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY created_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();
```

- [ ] **Step 4: Run grace + existing expiry test**

Run: `mvn test -Dtest=PendingExpiryGraceWindowTest,PendingExpiryTest`
Expected: PASS. `PendingExpiryTest` at default grace 30 — its kept rows expire 30min / 23h out, far outside a 30s window.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/scheduler/PendingExpiryScheduler.java \
        src/test/java/com/calit/scheduler/PendingExpiryGraceWindowTest.java
git commit -m "feat(scheduler): apply grace window to pending-expiry tick"
```

---

### Task 3: EmailService — render-and-enqueue path (delivery sink refactor)

Add an in-transaction "render the email, enqueue to the outbox" path that reuses the existing rendering, selectable via a delivery sink. Pure addition + a DRY refactor; no scheduler change yet, all existing behavior preserved (event handlers still send direct).

**Files:**
- Modify: `src/main/java/com/calit/email/EmailService.java`
- Test: `src/test/java/com/calit/email/EmailEnqueueTest.java` (new)

**Interfaces:**
- Consumes: `EmailOutbox.enqueue(String recipient, String subject, String htmlBody, byte[] icsBytes, Instant notAfter, String error)` (`src/main/java/com/calit/email/EmailOutbox.java:70`).
- Produces:
  - `public void EmailService.enqueueReminder(Long bookingId)` — renders the reminder email and `EmailOutbox.enqueue`s it for each recipient **in the caller's active transaction**; no direct SMTP. No-op if the booking is gone.
  - `public void EmailService.enqueueDeclined(Long bookingId)` — same for the declined email.
  - Both consumed by Tasks 4/5.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/email/EmailEnqueueTest.java`. Asserts `enqueueReminder` produces outbox rows for invitee + owner, subject says "reminder", `sent_at` null (queued, not sent), and nothing went to the mock mailbox directly.

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailEnqueueTest {

    private static final String OWNER_EMAIL = "owner-enq@example.com";
    private static final String INVITEE_EMAIL = "invitee-enq@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void enqueueReminderWritesOutboxRowsAndDoesNotSendDirectly() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false); // Google off -> invitee fallback
        Long bookingId = seed();
        mailbox.clear();

        QuarkusTransaction.requiringNew().run(() -> emailService.enqueueReminder(bookingId));

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL), "invitee reminder enqueued");
            assertEquals(1, EmailOutbox.count("recipient", OWNER_EMAIL), "owner reminder enqueued");
            EmailOutbox r = EmailOutbox.find("recipient", INVITEE_EMAIL).firstResult();
            assertTrue(r.subject.toLowerCase().contains("reminder"), "subject identifies the reminder");
            assertNull(r.sentAt, "queued, not sent");
        });
        assertEquals(0, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "no direct SMTP send on the enqueue path");

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", OWNER_EMAIL);
            Booking.deleteById(bookingId);
        });
    }

    private Long seed() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Enqueue Call";
            t.slug = "enq-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0123";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            Instant start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailEnqueueTest`
Expected: FAIL — compile error, `EmailService.enqueueReminder(Long)` does not exist.

- [ ] **Step 3: Add the delivery sink + the static outbox sink**

In `src/main/java/com/calit/email/EmailService.java`, add the `EmailOutbox` is in the same package (no import needed). Add inside the class, near `InviteeRule`:

```java
    /** Where a rendered mail goes: either a direct SMTP send or an outbox enqueue. */
    @FunctionalInterface
    private interface MailSink {
        void deliver(String to, String subject, String html, byte[] ics);
    }

    /**
     * In-transaction sink: persist the rendered mail to the outbox (a fast INSERT, no SMTP) so it
     * commits atomically with the caller's transaction. OutboxScheduler delivers it with retry/backoff.
     * Static so it can be used as a method reference with no captured state.
     */
    private static void enqueueToOutbox(String to, String subject, String html, byte[] ics) {
        EmailOutbox.enqueue(to, subject, html, ics, null, "scheduled dispatch (transactional outbox)");
    }
```

- [ ] **Step 4: Give `sendForKind` a sink (keep direct as the default overload)**

Replace the existing `sendForKind` method (lines 290-305) with a sink-taking version plus a direct-default overload:

```java
    /** Default delivery: direct SMTP via MailSender (outbox fallback on failure). Used by event handlers. */
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             Function<String, String> bodyForRole) {
        sendForKind(rule, subject, l, icsLocation, bodyForRole, mailSender::send);
    }

    /**
     * Renders the body (per recipient role) and delivers it through {@code sink} to the selected
     * recipients, each with the .ics. Owner included iff {@code ownerNotificationsEnabled}; invitee
     * per {@code rule} and {@code calendarPort.isConnected()}. No mail if the recipient set is empty.
     */
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             Function<String, String> bodyForRole, MailSink sink) {
        boolean sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected(l.owner.ownerId);
        boolean sendOwner = l.owner.ownerNotificationsEnabled;

        byte[] ics = IcsBuilder.build(l.booking.manageToken, l.meetingType.name, icsLocation,
                l.owner.ownerEmail, l.booking.startUtc, l.booking.endUtc)
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            sink.deliver(l.booking.inviteeEmail, subject, bodyForRole.apply("invitee"), ics);
        }
        if (sendOwner) {
            sink.deliver(l.owner.ownerEmail, subject, bodyForRole.apply("owner"), ics);
        }
    }
```

(The five other handlers and the two below call the 5-arg overload → unchanged direct behavior.)

- [ ] **Step 5: Extract `deliverReminder` / `deliverDeclined`; split `load` into `read` + `load`; add the enqueue methods**

Replace `handleDeclined` (lines 213-226) and `handleReminder` (lines 264-281) with extracted body-builders + thin handlers, and add the public enqueue methods:

```java
    void handleDeclined(BookingDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverDeclined(l, mailSender::send);
    }

    /** Renders + delivers the declined email through the given sink (direct or outbox). */
    private void deliverDeclined(Loaded l, MailSink sink) {
        String start = format(l.booking.startUtc, l.zone);
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKind(InviteeRule.ALWAYS, "Booking declined: " + l.meetingType.name, l, resolveLocation(l),
                role -> declined
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .render(),
                sink);
    }

    /** Renders the declined email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueDeclined(Long bookingId) {
        Loaded l = read(bookingId);
        if (l == null) return;
        deliverDeclined(l, EmailService::enqueueToOutbox);
    }
```

```java
    void handleReminder(ReminderDue e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverReminder(l, mailSender::send);
    }

    /** Renders + delivers the reminder email through the given sink (direct or outbox). */
    private void deliverReminder(Loaded l, MailSink sink) {
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.FALLBACK, "Reminder: " + l.meetingType.name, l, location,
                role -> reminder
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render(),
                sink);
    }

    /** Renders the reminder email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueReminder(Long bookingId) {
        Loaded l = read(bookingId);
        if (l == null) return;
        deliverReminder(l, EmailService::enqueueToOutbox);
    }
```

Then split `load` (lines 332-344) so the read logic can run in the caller's existing transaction:

```java
    /**
     * Loads the booking + meeting type + owner settings + answers in the CALLER's active transaction.
     * Use from an already-transactional caller (the scheduler claim tx). Returns null if gone.
     */
    private Loaded read(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            return null;
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
        ZoneId zone = ZoneId.of(owner.timezone);
        List<AnswerLine> answers = buildAnswerLines(booking, type);
        return new Loaded(booking, type, owner, zone, answers);
    }

    /** As {@link #read} but opens its own transaction — for AFTER_SUCCESS observers (no active tx). */
    private Loaded load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> read(bookingId));
    }
```

- [ ] **Step 6: Run the new test + the existing email tests**

Run: `mvn test -Dtest=EmailEnqueueTest,EmailServiceTest,EmailServiceFallbackTest,EmailServiceEventWiringTest`
Expected: PASS. The event handlers still send direct (5-arg overload), so wiring/fallback tests are unaffected; the new test sees outbox rows and no direct send.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/email/EmailService.java \
        src/test/java/com/calit/email/EmailEnqueueTest.java
git commit -m "feat(email): in-transaction render-and-enqueue path for reminders/declines"
```

---

### Task 4: ReminderScheduler — crash-safe dispatch via in-tx outbox enqueue

Replace the post-commit `ReminderDue` fire with an in-claim-transaction `enqueueReminder`. Closes #4 for reminders.

**Files:**
- Modify: `src/main/java/com/calit/scheduler/ReminderScheduler.java` (imports, fields, `dispatchDueReminders`, `claimAndMarkDueReminders`)
- Modify (rewrite): `src/test/java/com/calit/scheduler/ReminderEmailEndToEndTest.java`

**Interfaces:**
- Consumes: `EmailService.enqueueReminder(Long)` (Task 3); grace SQL (Task 1).
- Produces: `claimAndMarkDueReminders()` now returns `void` and enqueues each reminder's email in-tx (no event fired).

- [ ] **Step 1: Rewrite the end-to-end test (now asserts durable outbox rows)**

The dispatch no longer sends directly, so assert the outbox rows it enqueues. Replace the body of `src/test/java/com/calit/scheduler/ReminderEmailEndToEndTest.java` with:

```java
package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.email.EmailOutbox;
import com.calit.google.CalendarPort;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The dispatch tick claims due reminders and, in the SAME transaction, enqueues the reminder email
 * to the outbox (crash-safe: claim + intent-to-send commit atomically). OutboxScheduler delivers it.
 * This asserts both recipients (invitee via Google-disconnected fallback + owner) get a durable
 * outbox row.
 */
@QuarkusTest
class ReminderEmailEndToEndTest {

    private static final String OWNER_EMAIL = "owner-e2e@example.com";
    private static final String INVITEE_EMAIL = "invitee-e2e@example.com";

    @Inject
    ReminderScheduler scheduler;

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void dispatchTickEnqueuesReminderForInviteeAndOwner() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false); // Google off -> invitee fallback

        Long bookingId = seedConfirmedBookingWithOwner();
        seedDueUnsentReminder(bookingId);

        scheduler.dispatchDueReminders();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL),
                    "invitee reminder enqueued (Google disconnected -> fallback)");
            assertEquals(1, EmailOutbox.count("recipient", OWNER_EMAIL),
                    "owner reminder enqueued (ownerNotificationsEnabled=true)");
            EmailOutbox r = EmailOutbox.find("recipient", INVITEE_EMAIL).firstResult();
            assertTrue(r.subject.toLowerCase().contains("reminder"), "subject identifies the reminder email");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Reminder.delete("bookingId", bookingId);
            Booking.deleteById(bookingId);
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", OWNER_EMAIL);
        });
    }

    private Long seedConfirmedBookingWithOwner() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "E2E Reminder Call";
            t.slug = "e2e-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0123";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            Instant start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }

    private void seedDueUnsentReminder(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = Instant.now().minus(1, ChronoUnit.MINUTES); // due
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;                                       // unsent
            r.persist();
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=ReminderEmailEndToEndTest`
Expected: FAIL — current dispatch fires the event (direct send), so no outbox rows exist; `count("recipient", …)` is 0, first assertion fails.

- [ ] **Step 3: Rewrite the scheduler dispatch to enqueue in-tx**

In `src/main/java/com/calit/scheduler/ReminderScheduler.java`:

Remove these imports (no longer used): `com.calit.booking.events.ReminderDue`, `jakarta.enterprise.event.Event`, `java.util.ArrayList`. Add: `io.quarkus.logging.Log`.

Remove the field:

```java
    @Inject
    Event<ReminderDue> reminderDueEvent;
```

and add:

```java
    @Inject
    EmailService emailService;
```

(`EmailService` is in `com.calit.email` — add `import com.calit.email.EmailService;`.)

Replace `dispatchDueReminders()` (lines 113-123) and `claimAndMarkDueReminders()` (lines 129-150) with:

```java
    /**
     * Feature 15 dispatch tick. Runs on EVERY replica every 60s. Multi-node-safe with NO leader:
     * each tick claims due unsent reminders with SELECT ... FOR UPDATE SKIP LOCKED. The reminder
     * email is enqueued to the outbox in the SAME transaction as the claim (crash-safe), so a node
     * dying mid-tick never loses a reminder: either both the sent_at stamp and the outbox row commit,
     * or neither does and the row is reclaimed next tick. OutboxScheduler delivers, with retry/backoff.
     */
    @Scheduled(every = "60s")
    void dispatchDueReminders() {
        claimAndMarkDueReminders();
    }

    /**
     * Claims up to 50 due unsent reminders FOR UPDATE SKIP LOCKED, and for each, in the SAME tx:
     * stamps sent_at (exactly-once claim) and enqueues the reminder email to the outbox. A render
     * failure for one poison booking is caught and logged so it can't roll back the whole batch.
     */
    void claimAndMarkDueReminders() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM reminder "
                            + "WHERE sent_at IS NULL AND send_at <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY send_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            Instant now = Instant.now();
            for (Number n : ids) {
                Reminder r = Reminder.findById(n.longValue());
                r.sentAt = now; // claim: marked within the lock-holding transaction
                try {
                    emailService.enqueueReminder(r.bookingId); // durable intent, same tx
                } catch (Exception ex) {
                    Log.errorf(ex, "reminder enqueue failed for booking %d (marked sent, mail dropped)",
                            r.bookingId);
                }
            }
        });
    }
```

(Keep all the lifecycle observers and `scheduleReminder`/`onCancelledOrDeclined` unchanged — including `onDeclined`, which still handles `BookingDeclined` from manual declines. The `ReminderDue` record may now be unused by production code; leave it — removing it is a separate cleanup.)

- [ ] **Step 4: Run dispatch e2e + claim + grace tests**

Run: `mvn test -Dtest=ReminderEmailEndToEndTest,ReminderTickTest,ReminderGraceWindowTest`
Expected: PASS. `ReminderTickTest` still asserts only `sent_at` (stamped unconditionally); its booking has no `OwnerSettings`, so `enqueueReminder` throws inside the per-row try/catch — caught and logged, the claim still commits. `ReminderGraceWindowTest` likewise asserts only `sent_at`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/scheduler/ReminderScheduler.java \
        src/test/java/com/calit/scheduler/ReminderEmailEndToEndTest.java
git commit -m "feat(scheduler): crash-safe reminder dispatch via in-tx outbox enqueue"
```

---

### Task 5: PendingExpiryScheduler — crash-safe decline via in-tx enqueue + cleanup

Replace the post-commit `BookingDeclined` fire with in-claim-transaction work: enqueue the declined email AND delete unsent reminders (the two things its observers did). Closes #4 for expiry.

**Files:**
- Modify: `src/main/java/com/calit/scheduler/PendingExpiryScheduler.java` (imports, fields, `expirePendingBookings`, `claimAndDeclineExpired`)
- Test: `src/test/java/com/calit/scheduler/PendingExpiryDispatchTest.java` (new)

**Interfaces:**
- Consumes: `EmailService.enqueueDeclined(Long)` (Task 3); `Reminder.deleteUnsentFor(Long)` (`src/main/java/com/calit/scheduler/Reminder.java:44`); grace SQL (Task 2).
- Produces: `claimAndDeclineExpired()` now returns `void`; per expired booking it flips status, deletes unsent reminders, and enqueues the declined email — all in one tx, no event fired.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/scheduler/PendingExpiryDispatchTest.java`. Asserts an expired booking is declined AND its declined email is enqueued AND its unsent reminder is gone — all from one tick.

```java
package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.email.EmailOutbox;
import com.calit.google.CalendarPort;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class PendingExpiryDispatchTest {

    private static final String OWNER_EMAIL = "owner-exp@example.com";
    private static final String INVITEE_EMAIL = "invitee-exp@example.com";

    @Inject
    PendingExpiryScheduler scheduler;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void reset() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", OWNER_EMAIL);
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();
        });
    }

    @Test
    void expiryDeclinesEnqueuesDeclinedEmailAndDropsReminder() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Long bookingId = seedExpiredPendingWithReminder();

        scheduler.expirePendingBookings();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(BookingStatus.DECLINED, ((Booking) Booking.findById(bookingId)).status,
                    "expired PENDING flips to DECLINED");
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL), "invitee declined email enqueued");
            assertEquals(1, EmailOutbox.count("recipient", OWNER_EMAIL), "owner declined email enqueued");
            assertEquals(0, Reminder.count("bookingId", bookingId), "unsent reminder removed on decline");
        });
    }

    private Long seedExpiredPendingWithReminder() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Expiry Call";
            t.slug = "exp-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0123";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            Instant start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.PENDING;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now().minus(25, ChronoUnit.HOURS); // past the 24h hold -> expire
            b.persist();

            Reminder r = new Reminder();
            r.bookingId = b.id;
            r.sendAt = Instant.now().plus(100, ChronoUnit.HOURS);
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
            return b.id;
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=PendingExpiryDispatchTest`
Expected: FAIL — current expiry fires the event (direct send), so no outbox rows: `count("recipient", …)` is 0.

- [ ] **Step 3: Rewrite the scheduler decline to enqueue + clean up in-tx**

In `src/main/java/com/calit/scheduler/PendingExpiryScheduler.java`:

Remove unused imports: `com.calit.booking.events.BookingDeclined`, `jakarta.enterprise.event.Event`. Add: `io.quarkus.logging.Log`, `import com.calit.email.EmailService;`, and `import java.time.Instant;` is not needed (no `Instant.now()` here).

Remove the field:

```java
    @Inject
    Event<BookingDeclined> bookingDeclinedEvent;
```

and add:

```java
    @Inject
    EmailService emailService;
```

Replace `expirePendingBookings()` (lines 37-46) and `claimAndDeclineExpired()` (lines 52-74) with:

```java
    /**
     * Feature 14 expiry tick. Runs on EVERY replica every 60s, leaderless (FOR UPDATE SKIP LOCKED).
     * Per expired booking, in the SAME transaction as the claim: flip PENDING -> DECLINED, drop its
     * unsent reminder, and enqueue the declined email to the outbox (crash-safe). OutboxScheduler
     * delivers it. A node dying mid-tick loses nothing: all three commit together or not at all.
     */
    @Scheduled(every = "60s")
    void expirePendingBookings() {
        claimAndDeclineExpired();
    }

    /**
     * Claims expired PENDING bookings FOR UPDATE SKIP LOCKED and, per booking in the SAME tx:
     * flips to DECLINED, deletes any unsent reminder, enqueues the declined email. Expiry =
     * min(createdAt + holdHours, startUtc) <= now() + grace. A render failure for one poison
     * booking is caught/logged so it can't roll back the whole batch.
     */
    void claimAndDeclineExpired() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) "
                            + "    <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY created_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            for (Number n : ids) {
                Long id = n.longValue();
                Booking b = Booking.findById(id);
                b.status = BookingStatus.DECLINED;   // flipped within the lock-holding transaction
                Reminder.deleteUnsentFor(id);        // was ReminderScheduler.onDeclined observer
                try {
                    emailService.enqueueDeclined(id); // was EmailService.onDeclined observer; durable, same tx
                } catch (Exception ex) {
                    Log.errorf(ex, "declined enqueue failed for booking %d (declined, mail dropped)", id);
                }
            }
        });
    }
```

- [ ] **Step 4: Run dispatch + existing expiry + grace tests**

Run: `mvn test -Dtest=PendingExpiryDispatchTest,PendingExpiryTest,PendingExpiryGraceWindowTest`
Expected: PASS. `PendingExpiryTest`/`PendingExpiryGraceWindowTest` still assert only status (`DECLINED`/`PENDING`); they seed `OwnerSettings`, so the in-tx enqueue succeeds without tripping the guard.

- [ ] **Step 5: Run the scheduler + email suites together (no regression)**

Run: `mvn test -Dtest='com.calit.scheduler.*,com.calit.email.*'`
Expected: PASS. Manual-decline paths still fire `BookingDeclined` (event handlers unchanged); only the expiry and reminder ticks switched to in-tx enqueue.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/scheduler/PendingExpiryScheduler.java \
        src/test/java/com/calit/scheduler/PendingExpiryDispatchTest.java
git commit -m "feat(scheduler): crash-safe pending-expiry decline via in-tx enqueue + cleanup"
```

---

### Task 6: Document the new config

**Files:**
- Modify: `.env.example` (after `APPROVAL_HOLD_HOURS=24`, ~`:39`)
- Modify: `README.md` (env-var reference table)
- Modify (on `docs-site` branch): configuration reference page + changelog (on release)

**Interfaces:** consumes the property/env names from Tasks 1-2. No code.

- [ ] **Step 1: Add the env var to `.env.example`**

After `APPROVAL_HOLD_HOURS=24`, add:

```dotenv
# Scheduler grace window (seconds): reminder + pending-expiry ticks treat a row as due up to this
# many seconds early, so replicas on independent timers fire on time instead of a tick late. Default 30, 0 = exact.
SCHEDULER_GRACE_SECONDS=30
```

- [ ] **Step 2: Add the env var to the README reference table**

In `README.md`, find the env-var table (search `APPROVAL_HOLD_HOURS`) and add below it:

```markdown
| `SCHEDULER_GRACE_SECONDS` | `30` | Treat reminder / pending-expiry rows as due up to N seconds early, so replicas on unsynchronised tick timers fire on time. `0` = exact. |
```

(Adapt to the table's actual columns; copy the default `30` and env name verbatim.)

- [ ] **Step 3: Commit in-repo docs**

```bash
git add .env.example README.md
git commit -m "docs: document SCHEDULER_GRACE_SECONDS"
```

- [ ] **Step 4: Update the docs-site branch**

On a checkout/worktree of `docs-site`, add `SCHEDULER_GRACE_SECONDS` (default `30`, same description) to the configuration reference page, and — when this ships in a release — add the changelog entry at the top of `docs-site/src/content/docs/releases/changelog.md` (note both the grace window and the crash-safe dispatch change). Per CLAUDE.md the changelog entry is part of the release.

```bash
git worktree add ../calit-docs docs-site
# edit configuration page (+ changelog on release); commit + push on docs-site
```

---

## Self-Review

**Spec coverage:**
- "deviate up to N seconds, configurable" → grace window + `SCHEDULER_GRACE_SECONDS` (Tasks 1-2, default 30).
- "#4 fix (schedulers only)" → in-tx outbox enqueue for reminder dispatch (Task 4) and pending-expiry decline (Task 5), enabled by the EmailService render-and-enqueue path (Task 3). Manual-decline path intentionally unchanged.
- `GoogleConnectionScheduler` excluded (different-purpose grace).

**Placeholder scan:** none — every SQL clause, field, method, and test is shown in full.

**Type consistency:**
- `graceSeconds` (`int`) bound as `(double) graceSeconds` for `make_interval(secs => :graceSeconds)` — identical across both schedulers and matching `GoogleConnectionScheduler`.
- `EmailService.enqueueReminder(Long)` / `enqueueDeclined(Long)` defined in Task 3, consumed verbatim in Tasks 4/5.
- `EmailOutbox.enqueue(String,String,String,byte[],Instant,String)` matched in `enqueueToOutbox` (Task 3 Step 3).
- `Reminder.deleteUnsentFor(Long)` (static) used in Task 5; `Reminder.count(...)`, `EmailOutbox.count(...)`/`find(...)` are Panache statics.
- Both `claimAndMarkDueReminders` and `claimAndDeclineExpired` change return type to `void`; their only callers (`dispatchDueReminders` / `expirePendingBookings`) are updated in the same task.

**Ponytail notes:**
- Grace window: 1 property + 2 SQL clauses. No new table, no migration.
- #4 fix reuses the existing `EmailOutbox` + `OutboxScheduler` (retry/backoff/dead-letter already built) rather than adding a scheduler dependency (JobRunr/db-scheduler/ElasticJob all rejected — they'd add a leader and/or node-clock sensitivity, undoing calit's DB-`now()` drift-immunity). The only new code is a delivery-sink seam in `EmailService`.
- Per-row try/catch is deliberate: it bounds blast radius (one poison booking can't stall the batch) and is what keeps the claim's exactly-once stamp independent of render success. A crash (not a caught exception) still rolls the tx back — that is the crash-safety guarantee.
- `ReminderDue` record left in place though now unused by production — deletion is a trivial separate cleanup, not worth coupling to this change.
