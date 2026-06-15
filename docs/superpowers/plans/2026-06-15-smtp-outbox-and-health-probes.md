# SMTP Transactional Outbox + Health Probes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make email delivery survive SMTP outages via a fallback transactional outbox, and expose non-gating SMTP/Google health state.

**Architecture:** Every mail goes out via a single `MailSender` seam that tries a direct synchronous `mailer.send()` first; if SMTP throws, it persists the mail to an `email_outbox` table instead of losing it. An `OutboxScheduler` tick (same `SELECT … FOR UPDATE SKIP LOCKED` multi-node pattern as `ReminderScheduler`) retries queued rows with exponential backoff until sent or attempt-capped. The existing SMTP/Google readiness checks become **informational** (always UP, state in `withData`) so a down SMTP no longer pulls a replica from rotation — the outbox handles delivery. Liveness stays Quarkus-default (no external deps — gating liveness on SMTP/Google would let k8s restart healthy pods).

**Tech Stack:** Quarkus 3.36, Java 25, Panache, Postgres, Flyway, quarkus-mailer (`MockMailbox` in test), quarkus-scheduler, SmallRye Health, JUnit5 + Mockito (`@InjectMock`).

---

## Design decisions (locked)

- **Fallback, not always-queue.** Happy path is unchanged: `mailer.send()` runs inline in the same `AFTER_SUCCESS` observers as today. Only a *thrown* send is diverted to the outbox. No latency added when SMTP is healthy.
- **All mail uses the seam.** Booking notifications *and* password reset call `MailSender` — one place touches `Mailer`. Password-reset mail queues + delivers async if SMTP is down at click time (better than a 500 to the user).
- **Backoff + keep rows.** On send failure: `attempts++`, `next_attempt_at = now + min(1h, 1min·2^attempts)`. At `attempts >= 10` the row is *dead*: `next_attempt_at = NULL` (excluded from the claim query, kept for inspection). No auto-purge.
- **Single optional `.ics` attachment.** The only attachment calit sends. `MailSender`/outbox store just the bytes; filename + content-type are constants. `ponytail:` generalize only if a second attachment type ever appears.
- **No new env vars.** Caps/backoff are hardcoded constants — they don't vary per deployment.

## File structure

- Create `src/main/resources/db/migration/V14__email_outbox.sql` — outbox table + partial index.
- Create `src/main/java/com/calit/email/EmailOutbox.java` — Panache entity + `enqueue`/`deadOrBackoff` helpers.
- Create `src/main/java/com/calit/email/MailSender.java` — the send seam (`sendNow` + fallback `send`), owns ICS constants.
- Create `src/main/java/com/calit/email/OutboxScheduler.java` — retry tick.
- Modify `src/main/java/com/calit/email/EmailService.java` — route all sends through `MailSender`; drop direct `Mailer`/`Mail` use.
- Modify `src/main/java/com/calit/health/SmtpHealthCheck.java` + `GoogleHealthCheck.java` — informational (always UP).
- Tests: `EmailOutboxTest`, `MailSenderTest`, `OutboxSchedulerTest`, `SmtpHealthCheckTest` (+ keep `EmailServiceTest` green).
- Docs: `README.md`, `.env.example`, and the `docs-site` branch.

---

### Task 1: email_outbox migration

**Files:**
- Create: `src/main/resources/db/migration/V14__email_outbox.sql`
- Test: `src/test/java/com/calit/email/EmailOutboxSchemaTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Proves the V14 table exists and the entity (Task 2) maps to it under Hibernate validate-only.
@QuarkusTest
class EmailOutboxSchemaTest {
    @Test
    void tableExistsAndMapsCleanly() {
        long n = QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count());
        assertEquals(0L, n);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailOutboxSchemaTest`
Expected: FAIL — `EmailOutbox` symbol not found (entity created in Task 2). This task only adds the migration; the test goes green after Task 2. Confirm the *migration* boots: `mvn test -Dtest=EmailServiceTest` must still pass (Flyway applies V14 at boot).

- [ ] **Step 3: Write the migration**

```sql
-- Fallback transactional outbox. EmailService tries a direct mailer.send() first;
-- on SMTP failure the mail is parked here instead of being lost. OutboxScheduler
-- retries due rows (next_attempt_at <= now) with FOR UPDATE SKIP LOCKED -- the same
-- multi-node-safe, no-leader pattern as the reminder table.
CREATE TABLE email_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    recipient       VARCHAR(320) NOT NULL,        -- single To address
    subject         TEXT         NOT NULL,
    html_body       TEXT         NOT NULL,
    ics_bytes       BYTEA,                         -- optional single .ics attachment; NULL = none
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- due now on enqueue; NULL = dead (attempt-capped)
    sent_at         TIMESTAMPTZ,                   -- NULL = unsent
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The claim scan: WHERE sent_at IS NULL AND next_attempt_at <= now(). A NULL next_attempt_at
-- (dead row) is excluded by the index predicate, so dead rows never reappear in the claim.
CREATE INDEX idx_email_outbox_due ON email_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND next_attempt_at IS NOT NULL;
```

- [ ] **Step 4: Verify the migration applies**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: PASS — boot succeeds, Flyway applies V14, Hibernate validate passes (no entity yet → nothing to validate against the new table).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V14__email_outbox.sql src/test/java/com/calit/email/EmailOutboxSchemaTest.java
git commit -m "feat(email): V14 email_outbox table for SMTP fallback"
```

---

### Task 2: EmailOutbox entity + helpers

**Files:**
- Create: `src/main/java/com/calit/email/EmailOutbox.java`
- Test: `src/test/java/com/calit/email/EmailOutboxTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmailOutboxTest {

    @Test
    void enqueuePersistsADueUnsentRow() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "Subj", "<p>hi</p>", new byte[]{1, 2}, "boom"));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            assertNotNull(r);
            assertEquals("a@b.com", r.recipient);
            assertEquals(0, r.attempts);
            assertNull(r.sentAt);
            assertNotNull(r.nextAttemptAt, "enqueued rows are due immediately");
            assertEquals("boom", r.lastError);
        });
    }

    @Test
    void backoffBumpsAttemptsAndPushesNextAttempt() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            java.time.Instant before = r.nextAttemptAt;
            r.deadOrBackoff("smtp down");
            assertEquals(1, r.attempts);
            assertEquals("smtp down", r.lastError);
            assertTrue(r.nextAttemptAt.isAfter(before), "next attempt pushed into the future");
        });
    }

    @Test
    void attemptCapMarksRowDead() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            r.attempts = 9; // next failure is the 10th -> dead
            r.deadOrBackoff("still down");
            assertEquals(10, r.attempts);
            assertNull(r.nextAttemptAt, "capped row is dead: excluded from the claim query");
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailOutboxTest`
Expected: FAIL — `EmailOutbox` not defined.

- [ ] **Step 3: Write the entity**

```java
package com.calit.email;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * One mail that failed a direct SMTP send and is parked for retry. {@link #sentAt} null = unsent;
 * {@link #nextAttemptAt} null = dead (attempt-capped, kept for inspection, never re-claimed).
 * OutboxScheduler claims due unsent rows with SELECT ... FOR UPDATE SKIP LOCKED.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox extends PanacheEntityBase {

    /** ponytail: hardcoded caps -- they don't vary per deployment. Make config only if ops asks. */
    static final int MAX_ATTEMPTS = 10;
    static final Duration BASE_BACKOFF = Duration.ofMinutes(1);
    static final Duration CAP_BACKOFF = Duration.ofHours(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "recipient", nullable = false, length = 320)
    public String recipient;

    @Column(name = "subject", nullable = false)
    public String subject;

    @Column(name = "html_body", nullable = false)
    public String htmlBody;

    /** Optional single .ics attachment; null = none. */
    @Column(name = "ics_bytes")
    public byte[] icsBytes;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "last_error")
    public String lastError;

    /** Due time; null = dead (attempt-capped). */
    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;

    @Column(name = "sent_at")
    public Instant sentAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Parks a failed send. Must run inside a transaction (caller opens requiringNew). Returns the new id. */
    public static Long enqueue(String recipient, String subject, String htmlBody, byte[] icsBytes, String error) {
        EmailOutbox r = new EmailOutbox();
        r.recipient = recipient;
        r.subject = subject;
        r.htmlBody = htmlBody;
        r.icsBytes = icsBytes;
        r.attempts = 0;
        r.lastError = error;
        r.nextAttemptAt = Instant.now(); // due immediately
        r.sentAt = null;
        r.createdAt = Instant.now();
        r.persist();
        return r.id;
    }

    /** After a failed retry: bump attempts; reschedule with exponential backoff, or mark dead at the cap. */
    public void deadOrBackoff(String error) {
        attempts++;
        lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            nextAttemptAt = null; // dead: excluded by the partial index / claim predicate
            return;
        }
        long secs = Math.min(CAP_BACKOFF.getSeconds(), BASE_BACKOFF.getSeconds() << attempts);
        nextAttemptAt = Instant.now().plusSeconds(secs);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EmailOutboxTest,EmailOutboxSchemaTest`
Expected: PASS (both — Task 1's schema test now compiles + passes).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/EmailOutbox.java src/test/java/com/calit/email/EmailOutboxTest.java
git commit -m "feat(email): EmailOutbox entity with backoff/dead helpers"
```

---

### Task 3: MailSender seam (direct send, fallback to outbox)

**Files:**
- Create: `src/main/java/com/calit/email/MailSender.java`
- Test: `src/test/java/com/calit/email/MailSenderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.email;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class MailSenderTest {

    @Inject
    MailSender mailSender;

    // Replace the (mock-mailbox-backed) mailer so we can force a throw.
    @InjectMock
    Mailer mailer;

    @Test
    void failedSendIsParkedInOutbox() {
        doThrow(new RuntimeException("smtp down")).when(mailer).send(any(Mail[].class));

        mailSender.send("a@b.com", "Subj", "<p>hi</p>", new byte[]{9});

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.find("recipient", "a@b.com").firstResult();
            assertEquals("Subj", r.subject);
            assertEquals("smtp down", r.lastError);
            assertNull(r.sentAt);
        });
    }

    @Test
    void successfulSendLeavesOutboxEmpty() {
        doNothing().when(mailer).send(any(Mail[].class));

        mailSender.send("ok@b.com", "Subj", "<p>hi</p>", null);

        long parked = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.count("recipient", "ok@b.com"));
        assertEquals(0L, parked);
    }
}
```

> Note: Quarkus `Mailer.send` is varargs (`send(Mail...)`), so the Mockito matcher is `any(Mail[].class)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MailSenderTest`
Expected: FAIL — `MailSender` not defined.

- [ ] **Step 3: Write MailSender**

```java
package com.calit.email;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single seam every mail goes through. {@link #send} tries a direct, synchronous SMTP send and,
 * on failure, parks the mail in {@link EmailOutbox} instead of losing it -- so an SMTP outage never
 * fails the booking flow (the observers run AFTER_SUCCESS; the booking is already committed).
 * {@link #sendNow} is the raw send used by OutboxScheduler's retry, which applies its own backoff.
 */
@ApplicationScoped
public class MailSender {

    /** ponytail: the only attachment calit sends. Generalize if a second type ever appears. */
    private static final String ICS_FILENAME = "invite.ics";
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=UTF-8; method=REQUEST";

    @Inject
    Mailer mailer;

    /** Direct send; throws on SMTP failure. */
    public void sendNow(String to, String subject, String html, byte[] ics) {
        Mail mail = Mail.withHtml(to, subject, html);
        if (ics != null) {
            mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
        }
        mailer.send(mail);
    }

    /** Try direct; on any failure, durably queue to the outbox for retry. Never throws. */
    public void send(String to, String subject, String html, byte[] ics) {
        try {
            sendNow(to, subject, html, ics);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew().run(() ->
                    EmailOutbox.enqueue(to, subject, html, ics, e.getMessage()));
            Log.warnf(e, "SMTP send failed, queued to outbox: to=%s subject=%s", to, subject);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=MailSenderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/MailSender.java src/test/java/com/calit/email/MailSenderTest.java
git commit -m "feat(email): MailSender seam with SMTP-fallback to outbox"
```

---

### Task 4: Route EmailService through MailSender

**Files:**
- Modify: `src/main/java/com/calit/email/EmailService.java`
- Test: `src/test/java/com/calit/email/EmailServiceTest.java` (existing — must stay green)
- Test: `src/test/java/com/calit/email/EmailServiceFallbackTest.java` (new)

- [ ] **Step 1: Write the failing test**

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingDeclined;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

// When SMTP is down, a booking notification must NOT throw out of the observer -- it must land in the outbox.
@QuarkusTest
class EmailServiceFallbackTest {

    @Inject
    EmailService emailService;

    @InjectMock
    Mailer mailer;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
            EmailOutbox.deleteAll();
        });
    }

    @Test
    void declinedWithSmtpDownQueuesInsteadOfThrowing() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        doThrow(new RuntimeException("smtp down")).when(mailer).send(any(Mail[].class));
        long bookingId = seedDeclined();

        // Must not throw.
        emailService.handleDeclined(new BookingDeclined(bookingId));

        long queued = QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count());
        // declined notifies invitee + owner -> 2 parked mails.
        assertTrue(queued >= 2, "both recipients' mail parked in outbox, got " + queued);
    }

    private long seedDeclined() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Discovery Call";
            t.slug = "discovery-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "invitee@example.com";
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.DECLINED;
            b.meetLink = null;
            b.answers = Map.of();
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailServiceFallbackTest`
Expected: FAIL — `EmailService` still calls `mailer.send` directly, which throws out of `handleDeclined` (no outbox), so either the call throws or `EmailOutbox.count()` is 0.

- [ ] **Step 3: Rewire EmailService**

In `EmailService.java`:

1. Replace the mailer/Mail imports + injection with `MailSender`. Remove:

```java
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
```
```java
    @Inject
    Mailer mailer;
```

Add the injection (next to the other `@Inject` fields):

```java
    @Inject
    MailSender mailSender;
```

2. Replace `sendPasswordReset`:

```java
    /** Sends a password-reset link. Caller has already resolved the destination address. */
    public void sendPasswordReset(String toEmail, String resetUrl) {
        String body = passwordReset.data("resetUrl", resetUrl).render();
        mailSender.send(toEmail, "Reset your calit password", body, null);
    }
```

3. Replace the send plumbing — `sendForKind` body and delete the now-unused `withIcs` helper + ICS constants:

```java
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             Function<String, String> bodyForRole) {
        boolean sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected(l.owner.ownerId);
        boolean sendOwner = l.owner.ownerNotificationsEnabled;

        byte[] ics = IcsBuilder.build(l.booking.manageToken, l.meetingType.name, icsLocation,
                l.owner.ownerEmail, l.booking.startUtc, l.booking.endUtc)
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            mailSender.send(l.booking.inviteeEmail, subject, bodyForRole.apply("invitee"), ics);
        }
        if (sendOwner) {
            mailSender.send(l.owner.ownerEmail, subject, bodyForRole.apply("owner"), ics);
        }
    }
```

Delete these (moved into `MailSender`):

```java
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=UTF-8; method=REQUEST";
    private static final String ICS_FILENAME = "invite.ics";
```
```java
    private static Mail withIcs(Mail mail, byte[] ics) {
        return mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
    }
```

> `StandardCharsets` import stays (still used for the ICS bytes).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EmailServiceFallbackTest,EmailServiceTest,EmailServiceEventWiringTest`
Expected: PASS — fallback queues; existing `EmailServiceTest` still green (the real mock mailer succeeds, so `send()` delivers and `MockMailbox` captures exactly as before).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/EmailService.java src/test/java/com/calit/email/EmailServiceFallbackTest.java
git commit -m "feat(email): route all sends through MailSender (SMTP fallback)"
```

---

### Task 5: OutboxScheduler retry tick

**Files:**
- Create: `src/main/java/com/calit/email/OutboxScheduler.java`
- Test: `src/test/java/com/calit/email/OutboxSchedulerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.email;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class OutboxSchedulerTest {

    @Inject
    OutboxScheduler scheduler;

    @InjectMock
    Mailer mailer;

    @BeforeEach
    void init() {
        QuarkusTransaction.requiringNew().run(EmailOutbox::deleteAll);
    }

    @Test
    void dueRowIsSentAndMarked() {
        doNothing().when(mailer).send(any(Mail[].class));
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "S", "h", null, "prev"));

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew().run(() ->
                assertNotNull(((EmailOutbox) EmailOutbox.findById(id)).sentAt, "marked sent"));
    }

    @Test
    void failedRetryAppliesBackoffAndStaysUnsent() {
        doThrow(new RuntimeException("still down")).when(mailer).send(any(Mail[].class));
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null));

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            assertNull(r.sentAt);
            assertEquals(1, r.attempts);
            assertEquals("still down", r.lastError);
            assertTrue(r.nextAttemptAt.isAfter(java.time.Instant.now()), "backed off into the future");
        });
    }

    @Test
    void deadRowIsNotClaimed() {
        doNothing().when(mailer).send(any(Mail[].class));
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            Long x = EmailOutbox.enqueue("a@b.com", "S", "h", null, null);
            EmailOutbox r = EmailOutbox.findById(x);
            r.nextAttemptAt = null; // dead
            return x;
        });

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew().run(() ->
                assertNull(((EmailOutbox) EmailOutbox.findById(id)).sentAt, "dead row never re-sent"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OutboxSchedulerTest`
Expected: FAIL — `OutboxScheduler` not defined.

- [ ] **Step 3: Write the scheduler**

```java
package com.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;

/**
 * Retries parked mail. Runs on EVERY replica every 60s, multi-node-safe with NO leader: each tick
 * claims due unsent rows with SELECT ... FOR UPDATE SKIP LOCKED, so a concurrent replica's identical
 * query skips the rows this one locked -- each mail is retried by exactly one replica per tick.
 *
 * ponytail: the row lock is held across the SMTP send (one tx per tick, LIMIT 20). Fine at self-hosted
 * scale; if send latency x volume causes lock contention, switch to a lease-token claim (set
 * next_attempt_at forward in a short claim tx, then send unlocked).
 */
@ApplicationScoped
public class OutboxScheduler {

    private static final int BATCH = 20;

    @Inject
    EntityManager em;

    @Inject
    MailSender mailSender;

    @Scheduled(every = "60s")
    void scheduledTick() {
        dispatchDueMail();
    }

    /** Package-private so tests can drive one tick deterministically. */
    void dispatchDueMail() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM email_outbox "
                            + "WHERE sent_at IS NULL AND next_attempt_at <= now() "
                            + "ORDER BY next_attempt_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT " + BATCH)
                    .getResultList();

            for (Number n : ids) {
                EmailOutbox r = EmailOutbox.findById(n.longValue());
                try {
                    mailSender.sendNow(r.recipient, r.subject, r.htmlBody, r.icsBytes);
                    r.sentAt = Instant.now();        // marked within the lock-holding tx
                } catch (Exception e) {
                    r.deadOrBackoff(e.getMessage()); // bump attempts / reschedule / mark dead
                }
            }
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=OutboxSchedulerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/OutboxScheduler.java src/test/java/com/calit/email/OutboxSchedulerTest.java
git commit -m "feat(email): OutboxScheduler retries parked mail with backoff (SKIP LOCKED)"
```

---

### Task 6: Health checks become informational

**Files:**
- Modify: `src/main/java/com/calit/health/SmtpHealthCheck.java`
- Modify: `src/main/java/com/calit/health/GoogleHealthCheck.java`
- Test: `src/test/java/com/calit/health/SmtpHealthCheckTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Pure unit test -- no Quarkus. SMTP unreachable must report UP (informational), never DOWN,
// so a down mail server can't pull a replica out of rotation now that the outbox covers delivery.
class SmtpHealthCheckTest {

    @Test
    void unreachableHostReportsUpWithState() {
        SmtpHealthCheck c = new SmtpHealthCheck();
        c.mock = false;
        c.host = Optional.of("localhost");
        c.port = 2; // closed port -> connection refused fast, no slow timeout
        HealthCheckResponse r = c.call();
        assertEquals(HealthCheckResponse.Status.UP, r.getStatus(), "informational: always UP");
        assertTrue(r.getData().orElseThrow().containsKey("state"));
    }

    @Test
    void mockedReportsUp() {
        SmtpHealthCheck c = new SmtpHealthCheck();
        c.mock = true;
        c.host = Optional.empty();
        c.port = 587;
        assertEquals(HealthCheckResponse.Status.UP, c.call().getStatus());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SmtpHealthCheckTest`
Expected: FAIL — `unreachableHostReportsUpWithState` gets DOWN (current code returns `r.down()` in the catch).

- [ ] **Step 3: Make the checks informational**

`SmtpHealthCheck.java` — replace the `call()` body (keep the class/fields/annotations) so the failure path reports UP with state, and update the class Javadoc:

```java
    /**
     * Informational readiness data: can we reach the SMTP server? Always reports UP -- with the
     * email outbox covering delivery during outages, a down mail server must NOT pull this replica
     * out of rotation. The reachability state is exposed as data for operators at /q/health.
     *
     * ponytail: bare TCP connect, no SMTP handshake/auth.
     */
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder r = HealthCheckResponse.named("SMTP");
        if (mock || host.isEmpty()) {
            return r.up().withData("state", "mocked-or-unconfigured").build();
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host.get(), port), 2000);
            return r.up().withData("state", "reachable").withData("host", host.get() + ":" + port).build();
        } catch (Exception e) {
            // UP, not DOWN: the outbox queues mail while SMTP is down -- don't drop out of rotation.
            return r.up().withData("state", "unreachable")
                    .withData("host", host.get() + ":" + port)
                    .withData("error", e.getMessage()).build();
        }
    }
```

`GoogleHealthCheck.java` — same treatment for the catch path + Javadoc:

```java
    /**
     * Informational readiness data: can we reach Google's OAuth/token endpoint? Always reports UP --
     * calit runs Google-optional, and a transient Google outage must not pull this replica from
     * rotation. State is exposed as data at /q/health.
     *
     * ponytail: TCP reachability to oauth2.googleapis.com:443, not a real token call.
     */
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder r = HealthCheckResponse.named("Google");
        String clientId = config.oauth().clientId();
        if (clientId == null || clientId.isBlank()) {
            return r.up().withData("state", "not-configured").build();
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("oauth2.googleapis.com", 443), 2000);
            return r.up().withData("state", "reachable").build();
        } catch (Exception e) {
            return r.up().withData("state", "unreachable").withData("error", e.getMessage()).build();
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=SmtpHealthCheckTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/health/SmtpHealthCheck.java src/main/java/com/calit/health/GoogleHealthCheck.java src/test/java/com/calit/health/SmtpHealthCheckTest.java
git commit -m "feat(health): SMTP/Google checks informational (UP-only), never gate readiness"
```

---

### Task 7: Full suite + docs

**Files:**
- Modify: `README.md`
- Modify: `.env.example` (verify — likely no change)
- Docs: `docs-site` branch

- [ ] **Step 1: Run the full suite**

Run: `mvn test`
Expected: PASS — all prior tests + the new email/health tests green. (Docker required.)

- [ ] **Step 2: Update README health/email section**

Add a short subsection under the existing health/config docs (match surrounding heading style):

```markdown
### Health probes

- `GET /q/health/live` — liveness: process is up. Does **not** check SMTP or Google (a flapping
  external dependency must not get a healthy replica restarted).
- `GET /q/health/ready` — readiness. Includes **informational** SMTP and Google checks: they always
  report `UP` and expose reachability under `data.state` (`reachable` / `unreachable` /
  `mocked-or-unconfigured` / `not-configured`). They never mark a replica `DOWN` — a down mail
  server doesn't pull the replica from rotation, because outgoing mail falls back to the outbox.

### Email delivery & SMTP outages

Mail is sent synchronously. If an SMTP send fails, the mail is parked in the `email_outbox` table
instead of being lost; a background tick (every 60s, on every replica, `FOR UPDATE SKIP LOCKED` so
it's multi-node-safe) retries with exponential backoff (1 min doubling up to 1 h, capped at 10
attempts). Booking and password-reset flows never fail because SMTP is unavailable. No configuration
required.
```

- [ ] **Step 3: Check `.env.example`**

This feature adds **no new env vars** (caps/backoff are constants). Confirm nothing to add:

Run: `grep -i outbox .env.example`
Expected: no output → no change needed. If the project convention wants it documented, add a comment line only; otherwise leave it.

- [ ] **Step 4: Update the docs-site branch**

Per CLAUDE.md, user-facing changes land on `docs-site` same effort. Mirror the README additions (health probes + email/outbox behavior) into the relevant Starlight pages (configuration / reverse-proxy health-check guidance). Do this on the `docs-site` branch:

```bash
git stash --include-untracked          # if needed to switch cleanly
git switch docs-site
# edit docs-site/ pages: add the health-probe semantics + outbox behavior
git add docs-site/
git commit -m "docs: health probe semantics + SMTP outbox behavior"
git switch main
git stash pop                           # if you stashed
```

> If editing docs-site in this session isn't practical, note it explicitly as the one remaining task — it is part of "done", not follow-up.

- [ ] **Step 5: Commit the README**

```bash
git add README.md
git commit -m "docs: health probes + SMTP outbox in README"
```

---

## Self-review

- **Spec coverage:**
  - "liveness and readiness probes for smtp and google" → Tasks 1–6: readiness checks made informational (always UP, state in data); liveness deliberately left as Quarkus default with documented rationale (Task 6 + README). ✅
  - "if smtp unavailable, app should not fail" → MailSender swallows send failures (Task 3), EmailService routed through it (Task 4), observers run AFTER_SUCCESS so booking already committed. ✅
  - "use transactional outbox in db to send letters" → V14 table (Task 1), entity (Task 2), retry scheduler with SKIP LOCKED + backoff (Task 5). ✅
  - User refinement "use outbox only when direct send fails" → fallback design, happy path unchanged. ✅
- **Placeholder scan:** every code/test step has complete code; no TBD/TODO. ✅
- **Type consistency:** `EmailOutbox.enqueue(recipient, subject, htmlBody, icsBytes, error)` and `deadOrBackoff(error)` signatures match across Tasks 2/3/5; `MailSender.send`/`sendNow(to, subject, html, ics)` consistent across Tasks 3/4/5; field names (`recipient`, `htmlBody`, `icsBytes`, `nextAttemptAt`, `sentAt`, `attempts`, `lastError`) consistent entity↔migration↔tests. ✅

## Risks / notes

- **Lock held during SMTP send** (Task 5): acceptable at self-hosted scale; upgrade path (lease-token claim) is in the `ponytail:` comment.
- **`Mailer.send` is varargs** — Mockito matcher must be `any(Mail[].class)`; if a test sees "no matching invocation", that's the cause.
- **`byte[]` → `BYTEA`**: relies on the Postgres dialect's default VARBINARY mapping passing Hibernate validate. If validate fails at boot, annotate `icsBytes` with `@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARBINARY)`.
- **DatabaseResetCallback** must truncate `email_outbox` between tests (it truncates all tenant tables by default); if a test sees stale rows, confirm the reset covers the new table.
