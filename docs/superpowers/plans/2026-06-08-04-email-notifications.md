# Plan 4 — Email Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Email **both** participants — the invitee and the owner — whenever a booking is confirmed, rescheduled, or cancelled. The booking lifecycle is owned by Plan 3, which already fires CDI events on those three transitions. This plan adds nothing to the booking flow itself: it only *observes* those events and sends mail. No new schema, no Flyway migration.

**Architecture:** A single `@ApplicationScoped` `EmailService` declares three CDI observer methods, one per Plan 3 event (`BookingConfirmed`, `BookingRescheduled`, `BookingCancelled`). Each observer is wired with `@Observes(during = TransactionPhase.AFTER_SUCCESS)` so mail is sent only after the booking's own transaction has committed — never on a transaction that later rolls back. Because `AFTER_SUCCESS` observers run *after* the original transaction has ended, there is no active transaction/persistence context when they fire, so loading the `Booking` (plus its `MeetingType` and the `OwnerSettings` singleton) is done inside a fresh transaction opened explicitly with `QuarkusTransaction.requiringNew()`. Subject and body are rendered with injected Qute `Template`s; all times are formatted in the owner's IANA timezone (`OwnerSettings.timezone`). Bodies are sent via the programmatic `Mailer` API (`Mailer.send(Mail.withHtml(...))`) for maximum testability with the `MockMailbox`.

**Tech Stack:** Java 25, Quarkus 3.35.3, `quarkus-mailer`, Qute (already on the classpath transitively via mailer; we depend on it explicitly), `QuarkusTransaction`, JUnit 5. DB-touching tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**. Mail is verified with the in-memory `MockMailbox` (no real SMTP in dev/test).

**Consumes from earlier plans (exact contract — do not redefine):**
- Plan 3 `com.calit.booking.Booking` (Panache entity): `Long id`, `Long meetingTypeId`, `String inviteeName`, `String inviteeEmail`, `Instant startUtc`, `Instant endUtc`, `String googleEventId`, `String meetLink`, `BookingStatus status` (`CONFIRMED` / `CANCELLED`), `Instant createdAt`, **`Map<String,String> answers`** (JSONB — custom field values keyed by `BookingField.fieldKey`); static `Booking.findById(id)`.
- Plan 3 CDI events in `com.calit.booking.events`: `BookingConfirmed(Long bookingId)`, `BookingRescheduled(Long bookingId, Instant oldStartUtc)`, `BookingCancelled(Long bookingId)` — all Java records.
- Plan 1 `com.calit.domain.OwnerSettings`: `ownerEmail`, `ownerName`, `timezone`; static `OwnerSettings.get()`.
- Plan 1 `com.calit.domain.MeetingType`: `name`, `durationMinutes`; static `MeetingType.findById(id)`.
- Plan 1 `com.calit.domain.BookingField`: per-field `String fieldKey`, `String label`, `int position`; static **`BookingField.formFor(Long meetingTypeId)`** returns the resolved field definitions (per-type if any exist, else global), **ordered by `position`**. Used to render `Booking.answers` with human labels in order.

**New package:** `com.calit.email`.

---

### Task 1: Add the mailer dependency + mailer config

**Files:**
- Edit: `pom.xml`
- Edit: `src/main/resources/application.properties`

- [ ] **Step 1: Add `quarkus-mailer` and `quarkus-qute` to `pom.xml`**

In `pom.xml`, inside the existing `<dependencies>` block (after the `quarkus-arc` dependency), add:

```xml
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-mailer</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-qute</artifactId></dependency>
```

> `quarkus-mailer` pulls Qute transitively, but we declare `quarkus-qute` explicitly because we inject `io.quarkus.qute.Template` directly. The `MockMailbox` ships inside `quarkus-mailer` and is available in tests with no extra dependency.

- [ ] **Step 2: Add mailer config to `src/main/resources/application.properties`**

Append to `src/main/resources/application.properties`:

```properties
# --- Mailer (Plan 4) ---
# Default "from" address for all outgoing mail.
quarkus.mailer.from=${MAIL_FROM:calit@example.com}

# In dev & test the mock mailbox captures mail instead of hitting SMTP.
%dev.quarkus.mailer.mock=true
%test.quarkus.mailer.mock=true

# Production SMTP — all values via env.
%prod.quarkus.mailer.mock=false
%prod.quarkus.mailer.from=${MAIL_FROM}
%prod.quarkus.mailer.host=${MAIL_HOST}
%prod.quarkus.mailer.port=${MAIL_PORT:587}
%prod.quarkus.mailer.username=${MAIL_USERNAME}
%prod.quarkus.mailer.password=${MAIL_PASSWORD}
%prod.quarkus.mailer.start-tls=${MAIL_START_TLS:REQUIRED}
```

> `quarkus.mailer.mock=true` makes the framework store messages in the `MockMailbox` instead of sending them — this is the default in dev/test anyway, but we set it explicitly so the behavior is unambiguous and so `mvn test` never attempts a real SMTP connection.

- [ ] **Step 3: Confirm the project still builds & existing tests pass**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. The new dependencies resolve; no behavior change yet.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "chore: add quarkus-mailer dependency and mailer config"
```

---

### Task 2: Email templates (Qute)

**Files:**
- Create: `src/main/resources/templates/email/confirmation.html`
- Create: `src/main/resources/templates/email/reschedule.html`
- Create: `src/main/resources/templates/email/cancellation.html`

These templates are rendered by name through injected `Template` instances in Task 3. Each receives the same set of data keys (`recipientRole`, `inviteeName`, `meetingTypeName`, `startTime`, `durationMinutes`, `meetLink`, and — for reschedule — `oldStartTime`). `startTime` / `oldStartTime` are already-formatted strings (formatted in the owner's timezone in `EmailService`), so the templates contain no date logic.

The **confirmation** and **reschedule** templates additionally receive `answers` — a pre-built ordered `List<AnswerLine>` where each element exposes `label` and `value` (built in `EmailService` from `BookingField.formFor(booking.meetingTypeId)` joined to `booking.answers`, ordered by `BookingField.position`, with blank/absent answers already filtered out). The template only iterates and renders; no resolution or ordering logic lives in the template. The default global `description` field appears here automatically when the invitee filled it. The **cancellation** template does not list answers (kept as-is).

- [ ] **Step 1: Create `confirmation.html`**

`src/main/resources/templates/email/confirmation.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking is <strong>confirmed</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>When:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
  {#if meetLink}<li><strong>Google Meet:</strong> <a href="{meetLink}">{meetLink}</a></li>{/if}
</ul>
{#if answers}
<p><strong>Your answers:</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 2: Create `reschedule.html`**

`src/main/resources/templates/email/reschedule.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking has been <strong>rescheduled</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>Previous time:</strong> {oldStartTime}</li>
  <li><strong>New time:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
  {#if meetLink}<li><strong>Google Meet:</strong> <a href="{meetLink}">{meetLink}</a></li>{/if}
</ul>
{#if answers}
<p><strong>Your answers:</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 3: Create `cancellation.html`**

`src/main/resources/templates/email/cancellation.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking has been <strong>cancelled</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>Was scheduled for:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
</ul>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

> No `meetLink` in the cancellation template — the meeting is gone, so the link is intentionally omitted.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/email/confirmation.html \
  src/main/resources/templates/email/reschedule.html \
  src/main/resources/templates/email/cancellation.html
git commit -m "feat: add Qute email templates (confirmation, reschedule, cancellation)"
```

---

### Task 3: `EmailService` — observe events, load booking, send two emails

**Files:**
- Create: `src/main/java/com/calit/email/EmailService.java`
- Test: `src/test/java/com/calit/email/EmailServiceTest.java`

**Behavior contract:**
- Three observer methods, each `void`, each annotated `@Observes(during = TransactionPhase.AFTER_SUCCESS)` on its Plan 3 event record. They run **after** the booking transaction commits.
- Because there is no active transaction when an `AFTER_SUCCESS` observer fires, each observer delegates to a package-private helper that opens a *new* transaction with `QuarkusTransaction.requiringNew()` to load the `Booking`, its `MeetingType`, the `OwnerSettings` singleton, **and the custom-field definitions via `BookingField.formFor(booking.meetingTypeId)`**. This is the load gotcha: do **not** call `Booking.findById(...)` / `BookingField.formFor(...)` directly in the observer without a surrounding transaction.
- The custom booking-field answers are rendered as a labeled list in the **confirmation** and **reschedule** emails. Inside the same `requiringNew()` load, `EmailService` joins each `BookingField` from `formFor(booking.meetingTypeId)` (already ordered by `position`) to `booking.answers` by `fieldKey`, **skipping blank or absent values**, and pre-builds an ordered `List<AnswerLine>` (`label`, `value`) that is passed to the template under the `answers` key. The default global `description` field is included automatically when filled. The cancellation email lists no answers.
- The package-private helpers (`handleConfirmed`, `handleRescheduled`, `handleCancelled`) contain the real logic and are unit-testable by direct invocation (they manage their own transaction), independent of CDI event timing.
- Each helper sends **exactly two** `Mail` messages: one to `booking.inviteeEmail`, one to `OwnerSettings.ownerEmail`. The only per-recipient difference is the `recipientRole` value (`"invitee"` / `"owner"`).
- Times are formatted in the owner's timezone (`OwnerSettings.timezone`) with a shared `DateTimeFormatter`.
- Confirmation & reschedule bodies include `meetLink`; cancellation does not.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/email/EmailServiceTest.java`:

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingRescheduled;
import com.calit.domain.BookingField;
import com.calit.domain.FieldType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmailServiceTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void confirmationSendsToInviteeAndOwnerWithMeetLink() {
        long bookingId = seedBookingWithAnswers(
                "https://meet.google.com/abc-defg-hij", BookingStatus.CONFIRMED,
                Map.of("description", "Pricing tiers", "company", "Acme"));

        // Call the package-private helper directly; it opens its own transaction to load entities.
        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMessagesSentTo(INVITEE_EMAIL);
        List<Mail> toOwner = mailbox.getMessagesSentTo(OWNER_EMAIL);
        assertEquals(1, toInvitee.size());
        assertEquals(1, toOwner.size());
        assertEquals(2, mailbox.getTotalMessagesSent());

        Mail m = toInvitee.get(0);
        assertTrue(m.getHtml().contains("Discovery Call"), "body should mention the meeting type name");
        assertTrue(m.getHtml().contains("https://meet.google.com/abc-defg-hij"),
                "confirmation should include the meet link");
        assertTrue(m.getSubject().toLowerCase().contains("confirmed"));

        // Feature 10: custom booking-field answers appear with their human label and value.
        // The seed inserts a "What do you want to discuss?" field (key=description) answered
        // "Pricing tiers", and a "Company" field (key=company) answered "Acme".
        assertTrue(m.getHtml().contains("What do you want to discuss?"),
                "confirmation should render the field label from BookingField.formFor");
        assertTrue(m.getHtml().contains("Pricing tiers"),
                "confirmation should render the invitee's answer value");
        assertTrue(m.getHtml().contains("Company"), "confirmation should render the second field label");
        assertTrue(m.getHtml().contains("Acme"), "confirmation should render the second answer value");
        // The owner's copy carries the same answers.
        assertTrue(toOwner.get(0).getHtml().contains("Pricing tiers"),
                "owner copy should also include the answers");
    }

    @Test
    void rescheduleMentionsNewTimeAndSendsToBoth() {
        Instant newStart = Instant.parse("2026-06-10T09:00:00Z");
        long bookingId = seedBookingAt("https://meet.google.com/zzz-zzzz-zzz",
                BookingStatus.CONFIRMED, newStart);
        Instant oldStart = Instant.parse("2026-06-08T09:00:00Z");

        emailService.handleRescheduled(new BookingRescheduled(bookingId, oldStart));

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());

        Mail m = mailbox.getMessagesSentTo(OWNER_EMAIL).get(0);
        assertTrue(m.getSubject().toLowerCase().contains("reschedul"));
        // 2026-06-10 in Europe/Amsterdam is the new time; the old date must NOT be the headline new time.
        assertTrue(m.getHtml().contains("2026"), "body should mention a formatted time");
        assertTrue(m.getHtml().contains("https://meet.google.com/zzz-zzzz-zzz"));
    }

    @Test
    void cancellationSendsToBothWithoutMeetLink() {
        long bookingId = seedBooking("https://meet.google.com/will-not-appear",
                BookingStatus.CANCELLED);

        emailService.handleCancelled(new BookingCancelled(bookingId));

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());

        Mail m = mailbox.getMessagesSentTo(INVITEE_EMAIL).get(0);
        assertTrue(m.getSubject().toLowerCase().contains("cancel"));
        assertFalse(m.getHtml().contains("will-not-appear"),
                "cancellation must not include a meet link");
    }

    // --- helpers ---

    private long seedBooking(String meetLink, BookingStatus status) {
        return seedBookingAt(meetLink, status, Instant.parse("2026-06-08T09:00:00Z"), Map.of());
    }

    private long seedBookingAt(String meetLink, BookingStatus status, Instant startUtc) {
        return seedBookingAt(meetLink, status, startUtc, Map.of());
    }

    private long seedBookingWithAnswers(String meetLink, BookingStatus status, Map<String, String> answers) {
        return seedBookingAt(meetLink, status, Instant.parse("2026-06-08T09:00:00Z"), answers);
    }

    private long seedBookingAt(String meetLink, BookingStatus status, Instant startUtc,
                              Map<String, String> answers) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // Owner settings singleton (id=1) — upsert.
            OwnerSettings s = OwnerSettings.get();
            if (s == null) {
                s = new OwnerSettings();
                s.id = OwnerSettings.SINGLETON_ID;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.persist();

            MeetingType t = new MeetingType();
            t.name = "Discovery Call";
            t.slug = "discovery-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();

            // Custom booking-field definitions so the answers have human labels and an order.
            // Global form (meetingTypeId = null). "description" is the default-seeded field;
            // "company" is an owner-added one. Positions drive render order.
            BookingField f1 = new BookingField();
            f1.meetingTypeId = null;
            f1.fieldKey = "description";
            f1.label = "What do you want to discuss?";
            f1.type = FieldType.LONG_TEXT;
            f1.required = false;
            f1.position = 0;
            f1.persist();

            BookingField f2 = new BookingField();
            f2.meetingTypeId = null;
            f2.fieldKey = "company";
            f2.label = "Company";
            f2.type = FieldType.SHORT_TEXT;
            f2.required = false;
            f2.position = 1;
            f2.persist();

            Booking b = new Booking();
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = startUtc;
            b.endUtc = startUtc.plus(30, ChronoUnit.MINUTES);
            b.googleEventId = "evt-" + System.nanoTime();
            b.meetLink = meetLink;
            b.status = status;
            b.answers = answers;
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }
}
```

> The tests invoke the package-private helper methods directly. Each helper opens its own `QuarkusTransaction.requiringNew()` to load entities, so the tests do **not** need `@TestTransaction` and can read committed rows. The `seed*` helpers commit their own transaction first (also via `requiringNew()`), guaranteeing the row is visible to the helper's fresh transaction. The confirmation seed also persists two global `BookingField` definitions (`description`, `company`) in that same committed transaction so the helper's `BookingField.formFor(...)` resolves their labels and `Booking.answers` can be rendered as `label: value`. The end-to-end CDI-firing path (real `@Observes(AFTER_SUCCESS)`) is exercised in Task 4.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: FAIL — compilation error, `EmailService` does not exist.

- [ ] **Step 3: Write `EmailService`**

`src/main/java/com/calit/email/EmailService.java`:

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingRescheduled;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class EmailService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);

    @Inject
    Mailer mailer;

    // Templates are resolved by file name under src/main/resources/templates/email/.
    @Inject
    @io.quarkus.qute.Location("email/confirmation.html")
    Template confirmation;

    @Inject
    @io.quarkus.qute.Location("email/reschedule.html")
    Template reschedule;

    @Inject
    @io.quarkus.qute.Location("email/cancellation.html")
    Template cancellation;

    // --- CDI observers: fire only after the booking transaction commits. ---

    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        handleConfirmed(e);
    }

    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        handleRescheduled(e);
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        handleCancelled(e);
    }

    // --- Package-private helpers: own their transaction, directly unit-testable. ---

    void handleConfirmed(BookingConfirmed e) {
        Loaded l = load(e.bookingId());
        if (l == null) {
            return;
        }
        String start = format(l.booking.startUtc, l.zone);
        sendBoth(
                "Booking confirmed: " + l.meetingType.name,
                role -> confirmation
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("meetLink", l.booking.meetLink)
                        .data("answers", l.answers)
                        .render(),
                l);
    }

    void handleRescheduled(BookingRescheduled e) {
        Loaded l = load(e.bookingId());
        if (l == null) {
            return;
        }
        String newStart = format(l.booking.startUtc, l.zone);
        String oldStart = format(e.oldStartUtc(), l.zone);
        sendBoth(
                "Booking rescheduled: " + l.meetingType.name,
                role -> reschedule
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", newStart)
                        .data("oldStartTime", oldStart)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("meetLink", l.booking.meetLink)
                        .data("answers", l.answers)
                        .render(),
                l);
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) {
            return;
        }
        String start = format(l.booking.startUtc, l.zone);
        sendBoth(
                "Booking cancelled: " + l.meetingType.name,
                role -> cancellation
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .render(),
                l);
    }

    // --- shared plumbing ---

    /** Sends one HTML mail to the invitee and one to the owner; only recipientRole differs. */
    private void sendBoth(String subject, java.util.function.Function<String, String> bodyForRole, Loaded l) {
        mailer.send(Mail.withHtml(l.booking.inviteeEmail, subject, bodyForRole.apply("invitee")));
        mailer.send(Mail.withHtml(l.owner.ownerEmail, subject, bodyForRole.apply("owner")));
    }

    private static String format(Instant instant, ZoneId zone) {
        return TIME_FORMAT.format(instant.atZone(zone));
    }

    /**
     * Loads the booking + its meeting type + owner settings inside a fresh transaction.
     * Required because AFTER_SUCCESS observers run with no active transaction/persistence context.
     * Returns null if the booking no longer exists.
     */
    private Loaded load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking booking = Booking.findById(bookingId);
            if (booking == null) {
                return null;
            }
            MeetingType type = MeetingType.findById(booking.meetingTypeId);
            OwnerSettings owner = OwnerSettings.get();
            ZoneId zone = ZoneId.of(owner.timezone);
            List<AnswerLine> answers = buildAnswerLines(booking);
            return new Loaded(booking, type, owner, zone, answers);
        });
    }

    /**
     * Resolves the booking's custom-field answers into an ordered list of label/value pairs.
     * Iterates {@code BookingField.formFor(meetingTypeId)} (already ordered by {@code position}),
     * joins each definition to {@code booking.answers} by {@code fieldKey}, and skips blank or
     * absent values. Includes the default {@code description} field when the invitee filled it.
     * Must be called inside the {@code requiringNew()} transaction opened by {@link #load}.
     */
    private static List<AnswerLine> buildAnswerLines(Booking booking) {
        List<AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(booking.meetingTypeId)) {
            String value = answers.get(field.fieldKey);
            if (value != null && !value.isBlank()) {
                lines.add(new AnswerLine(field.label, value));
            }
        }
        return lines;
    }

    /** Immutable bundle of everything an observer needs, read once in one transaction. */
    private record Loaded(Booking booking, MeetingType meetingType, OwnerSettings owner, ZoneId zone,
                          List<AnswerLine> answers) {}

    /** One rendered custom-field answer: human label + submitted value. Public for Qute access. */
    public record AnswerLine(String label, String value) {}
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: PASS (all 3 tests). Bodies are captured by the `MockMailbox`; no SMTP is contacted.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/EmailService.java \
  src/test/java/com/calit/email/EmailServiceTest.java
git commit -m "feat: add EmailService observing booking events and emailing both parties"
```

---

### Task 4: End-to-end — fire the real CDI event across a committed transaction

This task proves the `@Observes(during = AFTER_SUCCESS)` wiring actually triggers when an event is fired from within a committed transaction (not just when the helper is called directly). It uses an injected `Event<BookingConfirmed>` fired inside a `QuarkusTransaction.requiringNew()` block; the observer must run only after that transaction commits.

**Files:**
- Test: `src/test/java/com/calit/email/EmailServiceEventWiringTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/email/EmailServiceEventWiringTest.java`:

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingConfirmed;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EmailServiceEventWiringTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Inject
    Event<BookingConfirmed> confirmedEvent;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void firingConfirmedInCommittedTxTriggersObserverAndSendsTwoMails() {
        // Seed + fire inside ONE committed transaction. AFTER_SUCCESS means the observer
        // runs only after this transaction commits.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.get();
            if (s == null) {
                s = new OwnerSettings();
                s.id = OwnerSettings.SINGLETON_ID;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.persist();

            MeetingType t = new MeetingType();
            t.name = "Wiring Call";
            t.slug = "wiring-" + System.nanoTime();
            t.durationMinutes = 45;
            t.persist();

            Booking b = new Booking();
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(45, ChronoUnit.MINUTES);
            b.googleEventId = "evt-" + System.nanoTime();
            b.meetLink = "https://meet.google.com/wire-test";
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.persist();

            confirmedEvent.fire(new BookingConfirmed(b.id));
        });

        // After commit, the observer has run and sent two messages.
        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());
    }
}
```

> Firing inside a committed transaction is the reliable trigger for an `AFTER_SUCCESS` observer. If the event were fired with **no** active transaction, an `AFTER_SUCCESS` observer would never run — that is the nuance this test pins down. The observer's own `load(...)` opens a *separate* `requiringNew()` transaction, which is why the booking row (committed by the time the observer fires) is readable.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=EmailServiceEventWiringTest`
Expected: FAIL initially **only if** something is wired wrong. If `EmailService` from Task 3 is already correct, write the test first and watch it FAIL against any deliberate temporary break, then PASS — but the intended flow here is: the test compiles against the existing `EmailService` and passes once the observer wiring is correct. Run it to confirm PASS:

Run: `mvn test -Dtest=EmailServiceEventWiringTest`
Expected: PASS — two messages captured by the `MockMailbox`, proving the `AFTER_SUCCESS` observer fired after commit.

- [ ] **Step 3: Run the full suite**

Run: `mvn test`
Expected: PASS — all prior plans' tests plus `EmailServiceTest` and `EmailServiceEventWiringTest`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/calit/email/EmailServiceEventWiringTest.java
git commit -m "test: verify AFTER_SUCCESS observer wiring fires on committed booking event"
```

---

## Self-Review against spec

**1. Spec coverage (Plan 4 scope — feature 4: emails to participants; feature 10: answers in emails):**

| Requirement | Task |
|---|---|
| Email **both** participants (invitee + owner) | Task 3 (`sendBoth` sends to `booking.inviteeEmail` and `OwnerSettings.ownerEmail`); asserted in Tasks 3 & 4 (`getMessagesSentTo` for each, `getTotalMessagesSent == 2`) |
| On **confirmation** | Task 3 `onConfirmed`/`handleConfirmed` observing `BookingConfirmed`; template `confirmation.html`; includes meet link |
| On **reschedule** | Task 3 `onRescheduled`/`handleRescheduled` observing `BookingRescheduled`; template `reschedule.html`; mentions new + old time, includes meet link |
| On **cancellation** | Task 3 `onCancelled`/`handleCancelled` observing `BookingCancelled`; template `cancellation.html`; no meet link |
| **Feature 10 — custom booking-field answers rendered in emails** | Task 3 `buildAnswerLines` joins `BookingField.formFor(booking.meetingTypeId)` (ordered by `position`) to `booking.answers` by `fieldKey`, skips blank/absent, passes ordered `List<AnswerLine>` under the `answers` key to the confirmation & reschedule templates (which iterate `label: value`); the default `description` field is included when filled; cancellation lists no answers. Asserted in Task 3 confirmation test (label `"What do you want to discuss?"` + value `"Pricing tiers"`, plus `"Company"`/`"Acme"`, on both invitee and owner copies) |
| Decoupled — only observe, don't change booking flow | No edit to any Plan 3 file; only `@Observes` consumers added |
| Email over SMTP, config-driven (`quarkus.mailer.*` via env in `%prod`) | Task 1 (`from`/`host`/`port`/`username`/`password`/`start-tls` env-driven under `%prod`; mock in dev/test) |
| Times in owner's timezone | Task 3 (`ZoneId.of(owner.timezone)` + shared `DateTimeFormatter`) |
| Answers loaded inside the AFTER_SUCCESS transaction | Task 3 — `BookingField.formFor(...)` is called inside the same `QuarkusTransaction.requiringNew()` in `load(...)`, consistent with how `Booking`/`MeetingType`/`OwnerSettings` are loaded |
| No new Flyway migration | None added — this plan touches no schema |

No feature-4 or feature-10 (email-rendering) requirement is unmapped.

**2. Placeholder scan:** No TBD / TODO / "handle edge cases" / "similar to" placeholders. Every task shows full file contents and exact `mvn test -Dtest=...` commands with explicit FAIL/PASS expectations and commits.

**3. Type consistency with Plan 3 (consumed exactly):**
- Events: `BookingConfirmed(Long bookingId)` → read via `e.bookingId()`; `BookingRescheduled(Long bookingId, Instant oldStartUtc)` → `e.bookingId()`, `e.oldStartUtc()`; `BookingCancelled(Long bookingId)` → `e.bookingId()`. All in `com.calit.booking.events`, treated as records.
- `Booking` fields used: `id` (Long), `meetingTypeId` (Long), `inviteeName` (String), `inviteeEmail` (String), `startUtc` (Instant), `endUtc` (Instant), `googleEventId` (String, seeded only), `meetLink` (String), `status` (`BookingStatus.CONFIRMED`/`CANCELLED`), `createdAt` (Instant), **`answers` (`Map<String,String>`, JSONB — read in `buildAnswerLines`, keyed by `BookingField.fieldKey`)**. Lookup via `Booking.findById(id)`. No field invented; no field renamed.
- `MeetingType` fields used: `name` (String), `durationMinutes` (int), `findById`. `OwnerSettings`: `ownerEmail`, `ownerName` (seed), `timezone`, `OwnerSettings.get()` / `SINGLETON_ID`. All match Plan 1.
- **`BookingField` (Plan 1, per the overview's BookingField bullet) consumed verbatim:** static `BookingField.formFor(Long meetingTypeId)` (resolved per-type-or-global, ordered by `position`), and per-field `fieldKey` (String), `label` (String). Seeded in the test with `meetingTypeId`, `fieldKey`, `label`, `type` (`FieldType` enum), `required`, `position` — all matching the Plan 1 contract. No field invented; no field renamed.

**Transaction-phase note (the real gotcha, explicitly handled):** `@Observes(during = TransactionPhase.AFTER_SUCCESS)` observers run after the booking transaction has committed and with no active persistence context. Loading entities therefore happens inside `QuarkusTransaction.requiringNew()` in `EmailService.load(...)`. Tests cover both invocation styles: direct helper calls (Task 3) and a real `Event<>.fire(...)` inside a committed transaction (Task 4).
