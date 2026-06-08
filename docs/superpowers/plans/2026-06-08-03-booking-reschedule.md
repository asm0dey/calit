# Plan 3 — Booking, Reschedule & Buffer Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layer genuinely-bookable slot calculation on top of Plan 1's raw slots (with Plan 1b date-override semantics) and Plan 2's Google seam, then implement booking (with a per-type approval workflow), reschedule, and cancel — all in a **degraded (Google-optional) mode** and behind **abuse guards**. A raw work-hour slot is bookable only when its **buffered** interval (`[start − bufferBefore, end + bufferAfter]`) does not overlap any busy interval — where "busy" means Google free/busy intervals (only when `CalendarPort.isConnected()`) **plus** every `PENDING`+`CONFIRMED` booking in the DB (a booking blocks the calendar globally, since there is a single owner) — and survives the **min-notice** and **horizon** filters relative to `Instant.now()`. This enforces feature 6 (buffers), feature 1 (booking), feature 5 (reschedule), feature 11 (min-notice/horizon), feature 12 (date overrides, via SlotService), feature 13 (location), feature 14 (approval), and feature 16 (abuse), and prevents double-booking.

**Architecture:** A `BookingService` (`@ApplicationScoped`) is the single entry point. It calls `SlotService.generateRawSlots` (Plan 1, with Plan 1b override replace-semantics already applied), subtracts busy intervals via an injected `CalendarPort` (Plan 2 — consulted **only when `isConnected()`**) and persisted `PENDING`+`CONFIRMED` `Booking` rows, applies the min-notice/horizon filters, and returns surviving `TimeSlot`s. `book` branches on `type.requiresApproval`: approval types persist a **PENDING** hold (no Google event yet) and fire `BookingRequested`; auto types persist **CONFIRMED**, create the Google event when connected, and fire `BookingConfirmed`. `approve`/`decline` resolve the PENDING hold; `reschedule`/`cancel` are keyed by the invitee's **manage-token**. All of these re-check availability under the same logic to stay race-safe, persist a `Booking` Panache entity, drive Google through `CalendarPort` (only when connected), and fire CDI events (`BookingRequested`/`BookingConfirmed`/`BookingApproved`/`BookingDeclined`/`BookingRescheduled`/`BookingCancelled`) that Plan 4 observes for emails. A thin JAX-RS layer exposes available-slots, create-booking, approve, decline, reschedule, and cancel so the subsystem is exercisable on its own. The overlap/subtraction math lives in a small package-visible helper so it is unit-testable in isolation.

**Approval workflow (feature 14):** When `type.requiresApproval` is true, `book` creates the booking as **PENDING** — which still holds the slot, because the DB overlap-exclusion constraint covers `status IN ('PENDING','CONFIRMED')` — and fires `BookingRequested` with NO Google event. The owner then `approve`s (PENDING→CONFIRMED, creates the Google event now if connected, fires `BookingApproved`) or `decline`s (PENDING→DECLINED, frees the slot since the row leaves the partial constraint, fires `BookingDeclined`). Auto types (`requiresApproval=false`) skip straight to CONFIRMED.

**Degraded (Google-optional) mode (feature 2 optional):** Every Google call is gated on `calendarPort.isConnected()`. When connected, busy includes `freeBusy`, and CONFIRMED bookings create/update/delete Google events (with a Meet link only when `locationType=GOOGLE_MEET`, else a `locationText`). When **not** connected, `freeBusy` is skipped (busy = internal `PENDING`+`CONFIRMED` bookings only) and no Google events are created/updated/deleted (`googleEventId`/`meetLink` stay null).

**Abuse guards (feature 16):** `book` runs three guards before persisting — server-side Cloudflare **Turnstile** token verification (skipped when the feature flag is off), a **honeypot** (`book` takes a `honeypot` param; a non-empty value means a bot filled the hidden field → rejected), and a **per-email/day cap** (counts this invitee email's bookings created today in Postgres; rejects over the cap). All three are enforced INSIDE `book`. Failures map to 400 (Turnstile or filled honeypot) / 429 (cap). The Plan 5 web layer just forwards the form's `cf-turnstile-response` (turnstileToken) and `website` (honeypot) values.

**Feature 10 (custom booking fields):** `book` accepts an `answers` map (`fieldKey`→value), validates that every required `BookingField` from `BookingField.formFor(type.id)` has a non-blank value (else HTTP 422), and stores the answers on the `Booking` in a JSONB column. Built-in full-name/email remain dedicated columns, not BookingField rows.

**NFR (horizontal scalability — booking safety under N replicas):** the app-level availability re-check alone is NOT safe across replicas (two nodes can pass it simultaneously). A Postgres `EXCLUDE`/GiST exclusion constraint (`booking_no_overlap_held`, partial on `status IN ('PENDING','CONFIRMED')`) makes the INSERT/UPDATE the source of truth — the DB rejects any second overlapping held booking. Covering PENDING means a pending approval request also holds the slot (feature 14). The app catches that violation and re-throws the same 409 it uses for the app-level double-book case. The app-level check is retained for nice errors and for buffer enforcement (buffers are an app-level policy the DB does not model). 422 (bad input), 409 (slot lost to a race), 429 (per-email cap), and 400 (Turnstile failure) are therefore cleanly distinguished.

**Tech Stack:** Java 25, Quarkus 3.35.3, Hibernate ORM Panache, PostgreSQL, Flyway, quarkus-rest (+jackson), JUnit 5, RestAssured, `quarkus-junit5-mockito` (dependency added in Plan 2) for `@InjectMock CalendarPort`. Tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**. Tests NEVER call real Google: the `CalendarPort` seam is always mocked with canned `BusyInterval`s / `CreatedEvent`.

**Consumed contracts (used verbatim — do not redeclare):**
- Plan 1: `com.calit.availability.SlotService.generateRawSlots(MeetingType, LocalDate, LocalDate) -> List<TimeSlot>` (Plan 1b makes this already apply `DateOverride` replace-semantics per date — feature 12 — so this plan consumes it without re-implementing overrides); `com.calit.availability.TimeSlot(ZonedDateTime start, ZonedDateTime end)`; `com.calit.domain.MeetingType` (`durationMinutes`, `bufferBeforeMinutes`, `bufferAfterMinutes`, `findBySlug`, `id`, `name`, `slug`); `com.calit.domain.OwnerSettings` (`ownerEmail`, `get()`); `com.calit.domain.BookingField` (feature 10: `fieldKey`, `required`, `FieldType` enum, `static List<BookingField> formFor(Long meetingTypeId)`) — consumed by `book` to validate required custom answers. Full name + email are built-ins (`Booking.inviteeName`/`inviteeEmail`), NOT BookingField rows.
- Plan 1b (additive columns on `MeetingType`, consumed verbatim by this plan):
  - `int minNoticeMinutes` (default 0) — feature 11 slot filter: drop slots whose start is before `now + minNoticeMinutes`.
  - `int horizonDays` (default 60) — feature 11 slot filter: drop slots whose start is after `now + horizonDays` days.
  - `LocationType locationType` (`GOOGLE_MEET`[default]/`PHONE`/`IN_PERSON`/`CUSTOM`) — feature 13: `createMeetLink = (locationType == GOOGLE_MEET)`.
  - `String locationDetail` (nullable) — feature 13: passed as `locationText` for non-Meet types.
  - `boolean requiresApproval` (default false) — feature 14: true → PENDING hold + approve/decline; false → straight to CONFIRMED.
  - `DateOverride` (feature 12) is consumed indirectly — `SlotService.generateRawSlots` already resolves overrides; this plan does not touch `DateOverride` directly.
- Plan 2 (`com.calit.google`):
  ```java
  public record BusyInterval(java.time.Instant start, java.time.Instant end) {}
  public record CreatedEvent(String googleEventId, String meetLink, String htmlLink) {}
  public interface CalendarPort {
      boolean isConnected();
      java.util.List<BusyInterval> freeBusy(java.time.Instant from, java.time.Instant to);
      CreatedEvent createEvent(String summary, String description, java.time.Instant start, java.time.Instant end,
                               java.util.List<String> attendeeEmails, boolean createMeetLink, String locationText);
      void updateEvent(String eventId, java.time.Instant start, java.time.Instant end);
      void deleteEvent(String eventId);
  }
  ```
  BookingService calls `freeBusy`/`createEvent`/`updateEvent`/`deleteEvent` **only when `isConnected()`**. `createEvent` is passed `createMeetLink = (type.locationType == LocationType.GOOGLE_MEET)` and `locationText = type.locationDetail` (used by Plan 2 for non-Meet types; `createMeetLink=false` → `CreatedEvent.meetLink()` is null).

---

### Task 1: Database schema (Flyway V4)

**Files:**
- Create: `src/main/resources/db/migration/V4__booking.sql`

> Migration ordering: `V1` (Plan 1 core domain), `V2` (Plan 1b domain extensions — min-notice/horizon/location/approval columns + date_override), `V3` (Plan 2 google OAuth token storage). This plan's migration is therefore **`V4`**.

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V4__booking.sql`:

```sql
CREATE TABLE booking (
    id                BIGSERIAL    PRIMARY KEY,
    meeting_type_id   BIGINT       NOT NULL REFERENCES meeting_type(id),
    invitee_name      VARCHAR(255) NOT NULL,
    invitee_email     VARCHAR(255) NOT NULL,
    start_utc         TIMESTAMPTZ  NOT NULL,
    end_utc           TIMESTAMPTZ  NOT NULL,
    google_event_id   VARCHAR(255),
    meet_link         VARCHAR(512),
    -- Feature 14: PENDING (approval hold) / CONFIRMED / CANCELLED / DECLINED.
    status            VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED',
    created_at        TIMESTAMPTZ  NOT NULL,
    -- Invitee manage/reschedule/cancel key: a random UUID set at creation.
    manage_token      VARCHAR(36)  NOT NULL UNIQUE,
    -- Feature 10: submitted values for owner-defined custom BookingFields
    -- (fieldKey -> value). Built-in name/email live in their own columns above.
    answers           JSONB        NOT NULL DEFAULT '{}'::jsonb
);

-- Availability queries scan PENDING+CONFIRMED bookings within a time window.
CREATE INDEX idx_booking_status_start ON booking (status, start_utc);
-- Per-email/day abuse cap (feature 16) counts a single invitee's bookings by created_at.
CREATE INDEX idx_booking_email_created ON booking (invitee_email, created_at);

-- NFR (horizontal scalability): cross-node double-booking guard.
-- App-level "is this slot free?" checks (Task 5/6) cannot be trusted across
-- replicas — two nodes can pass the check simultaneously and both INSERT.
-- This DB-level exclusion constraint makes the INSERT itself the source of
-- truth: Postgres rejects any second HELD booking whose raw time range
-- overlaps an existing held one. btree_gist is required for the `=`/`&&`
-- mix in a GiST exclusion constraint; Dev Services Postgres supports it.
-- "Held" = status IN ('PENDING','CONFIRMED'): a pending approval request
-- (feature 14) holds the slot too, so it cannot be double-requested while
-- the owner decides. NOTE: this guarantees only no RAW-TIME overlap of held
-- rows. Buffers remain an app-level policy (Task 5) — the DB does not know
-- about them. Cancelling/declining sets status to CANCELLED/DECLINED, so the
-- partial WHERE clause drops the row from the constraint and frees the slot.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_held
    EXCLUDE USING gist (tstzrange(start_utc, end_utc) WITH &&)
    WHERE (status IN ('PENDING', 'CONFIRMED'));
```

- [ ] **Step 2: Verify the schema applies at startup**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. Flyway runs V1→V4 at boot against the Dev Services DB; no migration errors in the log.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V4__booking.sql
git commit -m "feat: add booking schema (V4) with status enum, manage_token + PENDING|CONFIRMED no-overlap constraint"
```

---

### Task 2: BookingStatus enum + Booking entity

**Files:**
- Create: `src/main/java/com/calit/booking/BookingStatus.java`
- Create: `src/main/java/com/calit/booking/Booking.java`
- Test: `src/test/java/com/calit/booking/BookingTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/booking/BookingTest.java`:

```java
package com.calit.booking;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BookingTest {

    @Test
    @TestTransaction
    void persistsAndReadsBackAllFields() {
        Instant start = Instant.parse("2026-06-08T07:00:00Z");
        Booking b = new Booking();
        b.meetingTypeId = 7L;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = start;
        b.endUtc = start.plusSeconds(1800);
        b.googleEventId = "evt-1";
        b.meetLink = "https://meet.google.com/abc-defg-hij";
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.parse("2026-06-01T10:00:00Z");
        b.manageToken = "11111111-2222-3333-4444-555555555555";
        b.answers = Map.of("description", "Quarterly sync", "phone", "+31201234567");
        b.persist();

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("https://meet.google.com/abc-defg-hij", loaded.meetLink);
        assertEquals(start, loaded.startUtc);
        // Manage-token round-trips and is the invitee's reschedule/cancel key.
        assertEquals("11111111-2222-3333-4444-555555555555", loaded.manageToken);
        // Feature 10: custom answers round-trip through the JSONB column.
        assertEquals("Quarterly sync", loaded.answers.get("description"));
        assertEquals("+31201234567", loaded.answers.get("phone"));
    }

    @Test
    @TestTransaction
    void heldOverlappingFindsPendingAndConfirmedInWindow() {
        Instant base = Instant.parse("2026-06-08T07:00:00Z");
        persistBooking(base, base.plusSeconds(1800), BookingStatus.CONFIRMED);          // 07:00-07:30 held
        persistBooking(base.plusSeconds(900), base.plusSeconds(1800), BookingStatus.PENDING); // 07:15-07:30 held
        persistBooking(base.plusSeconds(7200), base.plusSeconds(9000), BookingStatus.CONFIRMED); // 09:00-09:30 (out of window)
        persistBooking(base, base.plusSeconds(1800), BookingStatus.CANCELLED);          // cancelled, ignored
        persistBooking(base, base.plusSeconds(1800), BookingStatus.DECLINED);           // declined, ignored

        // Window 06:00-08:00 catches the CONFIRMED + PENDING holds, not CANCELLED/DECLINED.
        List<Booking> hits = Booking.heldOverlapping(
                base.minusSeconds(3600), base.plusSeconds(3600));

        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(
                x -> x.status == BookingStatus.PENDING || x.status == BookingStatus.CONFIRMED));
    }

    @Test
    @TestTransaction
    void findByManageTokenLoadsBooking() {
        persistBooking(Instant.parse("2026-06-08T07:00:00Z"),
                Instant.parse("2026-06-08T07:30:00Z"), BookingStatus.PENDING, "tok-abc");

        Booking loaded = Booking.findByManageToken("tok-abc");

        assertEquals(BookingStatus.PENDING, loaded.status);
    }

    private void persistBooking(Instant start, Instant end, BookingStatus status) {
        persistBooking(start, end, status, java.util.UUID.randomUUID().toString());
    }

    private void persistBooking(Instant start, Instant end, BookingStatus status, String token) {
        Booking b = new Booking();
        b.meetingTypeId = 1L;
        b.inviteeName = "X";
        b.inviteeEmail = "x@example.com";
        b.startUtc = start;
        b.endUtc = end;
        b.status = status;
        b.createdAt = Instant.now();
        b.manageToken = token;
        b.persist();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=BookingTest`
Expected: FAIL — compilation error, `Booking` / `BookingStatus` do not exist.

- [ ] **Step 3: Write the enum**

`src/main/java/com/calit/booking/BookingStatus.java`:

```java
package com.calit.booking;

public enum BookingStatus {
    /** Feature 14: an approval-required request holding the slot, awaiting owner approve/decline. */
    PENDING,
    CONFIRMED,
    CANCELLED,
    /** Feature 14: owner declined a pending request; frees the slot. */
    DECLINED
}
```

- [ ] **Step 4: Write the entity**

`src/main/java/com/calit/booking/Booking.java`:

```java
package com.calit.booking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "booking")
public class Booking extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    @Column(name = "invitee_name", nullable = false)
    public String inviteeName;

    @Column(name = "invitee_email", nullable = false)
    public String inviteeEmail;

    @Column(name = "start_utc", nullable = false)
    public Instant startUtc;

    @Column(name = "end_utc", nullable = false)
    public Instant endUtc;

    @Column(name = "google_event_id")
    public String googleEventId;

    @Column(name = "meet_link", length = 512)
    public String meetLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public BookingStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * Invitee manage/reschedule/cancel key: a random UUID set at creation. Unique.
     * Plan 4 emails a tokenized link; Plan 5 routes /manage/{manageToken}.
     */
    @Column(name = "manage_token", nullable = false, length = 36, unique = true)
    public String manageToken;

    /**
     * Feature 10: submitted values for the owner-defined custom BookingFields
     * (fieldKey -> value). Built-in full-name/email are NOT stored here — they
     * live in {@link #inviteeName}/{@link #inviteeEmail}. Stored as a JSONB
     * column; defaults to an empty map at the DB level.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", columnDefinition = "jsonb")
    public Map<String, String> answers;

    /**
     * All HELD (PENDING or CONFIRMED) bookings whose [startUtc, endUtc) overlaps the
     * window [from, to). These are the bookings that block the calendar (a pending
     * approval request holds its slot too — feature 14). CANCELLED/DECLINED are excluded.
     * Overlap predicate: startUtc < to AND from < endUtc.
     */
    public static List<Booking> heldOverlapping(Instant from, Instant to) {
        return list("status in ?1 and startUtc < ?2 and ?3 < endUtc",
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED), to, from);
    }

    /** Loads a booking by its invitee manage-token (reschedule/cancel key), or null. */
    public static Booking findByManageToken(String manageToken) {
        return find("manageToken", manageToken).firstResult();
    }

    /** Feature 16: how many bookings this invitee email created in [dayStart, dayEnd). */
    public static long countByEmailCreatedBetween(String email, Instant dayStart, Instant dayEnd) {
        return count("inviteeEmail = ?1 and createdAt >= ?2 and createdAt < ?3",
                email, dayStart, dayEnd);
    }
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=BookingTest`
Expected: PASS (all three tests: all-fields round-trip incl. manageToken, `heldOverlapping` returns PENDING+CONFIRMED only, `findByManageToken`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/booking/BookingStatus.java \
  src/main/java/com/calit/booking/Booking.java \
  src/test/java/com/calit/booking/BookingTest.java
git commit -m "feat: add Booking entity and BookingStatus enum"
```

> **Built deviations (synced to reality):**
> - `Booking.answers` is initialized at the field (`= new java.util.HashMap<>()`). The column is `NOT NULL DEFAULT '{}'`, but Hibernate inserts an explicit `null` when the Java field is null (bypassing the DB default), so an unset `answers` would violate `NOT NULL`. `book()` always sets it, but the field init keeps test helpers / direct callers safe.
> - In `BookingTest`, `meeting_type_id` is a hard FK (`REFERENCES meeting_type(id)`), so the test persists a real `MeetingType` and uses its generated id rather than literal `1L`/`7L`.
> - In `heldOverlappingFindsPendingAndConfirmedInWindow`, the two HELD rows are **adjacent non-overlapping** (07:00–07:15 CONFIRMED, 07:15–07:30 PENDING) — the original overlapping pair would itself trip the `booking_no_overlap_held` EXCLUDE constraint at INSERT. The CANCELLED/DECLINED rows are placed in-window (08:00–08:15, 08:15–08:30) so status-based exclusion is still exercised; the assertion (`count == 2`, all PENDING/CONFIRMED) is unchanged.
> - `application.properties` gains `quarkus.hibernate-orm.mapping.format.global=ignore` so Hibernate uses its own JSON serializer for `@JdbcTypeCode(SqlTypes.JSON)` columns instead of the REST `ObjectMapper` (Quarkus 3.35 otherwise couples DB-JSON mapping to REST serialization config). Output is identical for the `Map<String,String>` answers column.

---

### Task 3: Interval overlap/subtraction helper (pure, unit-testable)

The buffer-enforcement math is isolated here so it can be tested without DB or Google. It is a plain Java class (no CDI) operating on `Instant` intervals.

**Files:**
- Create: `src/main/java/com/calit/booking/Interval.java`
- Test: `src/test/java/com/calit/booking/IntervalTest.java`

**Behavior contract:**
- `Interval(Instant start, Instant end)` — half-open `[start, end)`.
- `overlaps(other)` is true iff `start < other.end && other.start < end` (touching boundaries do NOT overlap).
- `overlapsAny(List<Interval> busy)` — true iff it overlaps at least one busy interval.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/booking/IntervalTest.java`:

```java
package com.calit.booking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalTest {

    private static Interval iv(String start, String end) {
        return new Interval(Instant.parse(start), Instant.parse(end));
    }

    @Test
    void overlappingIntervalsReportOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T09:30:00Z", "2026-06-08T10:30:00Z");
        assertTrue(a.overlaps(b));
        assertTrue(b.overlaps(a));
    }

    @Test
    void touchingBoundariesDoNotOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z");
        assertFalse(a.overlaps(b));
        assertFalse(b.overlaps(a));
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z");
        assertFalse(a.overlaps(b));
    }

    @Test
    void containedIntervalOverlaps() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z");
        Interval b = iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z");
        assertTrue(a.overlaps(b));
    }

    @Test
    void overlapsAnyMatchesAtLeastOne() {
        Interval slot = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        List<Interval> busy = List.of(
                iv("2026-06-08T07:00:00Z", "2026-06-08T08:00:00Z"),
                iv("2026-06-08T09:30:00Z", "2026-06-08T09:45:00Z"));
        assertTrue(slot.overlapsAny(busy));
    }

    @Test
    void overlapsAnyFalseWhenAllDisjoint() {
        Interval slot = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        List<Interval> busy = List.of(
                iv("2026-06-08T07:00:00Z", "2026-06-08T08:00:00Z"),
                iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z"));
        assertFalse(slot.overlapsAny(busy));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=IntervalTest`
Expected: FAIL — compilation error, `Interval` does not exist.

- [ ] **Step 3: Write `Interval`**

`src/main/java/com/calit/booking/Interval.java`:

```java
package com.calit.booking;

import java.time.Instant;
import java.util.List;

/** Half-open instant interval [start, end). Touching boundaries do not overlap. */
public record Interval(Instant start, Instant end) {

    public boolean overlaps(Interval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean overlapsAny(List<Interval> busy) {
        for (Interval b : busy) {
            if (overlaps(b)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=IntervalTest`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/Interval.java \
  src/test/java/com/calit/booking/IntervalTest.java
git commit -m "feat: add pure Interval overlap helper for buffer enforcement"
```

---

### Task 4: CDI booking events

These records are fired by `BookingService` and observed by Plan 4 (emails). Defined now so the service can depend on them. They match the overview's CDI-event list exactly: `BookingRequested`, `BookingConfirmed`, `BookingApproved`, `BookingDeclined`, `BookingRescheduled` (also carries `Instant oldStartUtc`), `BookingCancelled` — all carry `Long bookingId`.

**Files:**
- Create: `src/main/java/com/calit/booking/events/BookingRequested.java`
- Create: `src/main/java/com/calit/booking/events/BookingConfirmed.java`
- Create: `src/main/java/com/calit/booking/events/BookingApproved.java`
- Create: `src/main/java/com/calit/booking/events/BookingDeclined.java`
- Create: `src/main/java/com/calit/booking/events/BookingRescheduled.java`
- Create: `src/main/java/com/calit/booking/events/BookingCancelled.java`

- [ ] **Step 1: Write the six event records**

`src/main/java/com/calit/booking/events/BookingRequested.java`:

```java
package com.calit.booking.events;

/** Feature 14: an approval-required booking was created as PENDING (awaiting owner decision). */
public record BookingRequested(Long bookingId) {}
```

`src/main/java/com/calit/booking/events/BookingConfirmed.java`:

```java
package com.calit.booking.events;

/** An auto (no-approval) booking was confirmed immediately. */
public record BookingConfirmed(Long bookingId) {}
```

`src/main/java/com/calit/booking/events/BookingApproved.java`:

```java
package com.calit.booking.events;

/** Feature 14: the owner approved a PENDING request (PENDING -> CONFIRMED). */
public record BookingApproved(Long bookingId) {}
```

`src/main/java/com/calit/booking/events/BookingDeclined.java`:

```java
package com.calit.booking.events;

/** Feature 14: the owner declined a PENDING request (PENDING -> DECLINED). */
public record BookingDeclined(Long bookingId) {}
```

`src/main/java/com/calit/booking/events/BookingRescheduled.java`:

```java
package com.calit.booking.events;

import java.time.Instant;

public record BookingRescheduled(Long bookingId, Instant oldStartUtc) {}
```

`src/main/java/com/calit/booking/events/BookingCancelled.java`:

```java
package com.calit.booking.events;

public record BookingCancelled(Long bookingId) {}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn test -Dtest=IntervalTest`
Expected: PASS — the new records compile cleanly (this test does not use them, but the module must still build).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/calit/booking/events/
git commit -m "feat: add booking CDI event records (Requested/Confirmed/Approved/Declined/Rescheduled/Cancelled)"
```

---

### Task 5: BookingService — available-slot computation (buffer enforcement)

**Files:**
- Create: `src/main/java/com/calit/booking/BookingService.java`
- Test: `src/test/java/com/calit/booking/AvailableSlotsTest.java`

**Behavior contract for `availableSlots(MeetingType type, LocalDate from, LocalDate to)`:**
1. `raw = slotService.generateRawSlots(type, from, to)` (zoned, owner tz; Plan 1b already applied any `DateOverride` replace-semantics per date — feature 12).
2. Compute the query window in UTC: `fromInstant = from.atStartOfDay(zone).toInstant()`, `toInstant = to.plusDays(1).atStartOfDay(zone).toInstant()` (end-exclusive next-day midnight in owner tz), where `zone = ZoneId.of(OwnerSettings.get().timezone)`.
3. Build the busy set as `List<Interval>`: **if `calendarPort.isConnected()`**, every `calendarPort.freeBusy(fromInstant, toInstant)` `BusyInterval` (degraded mode skips this — busy is internal bookings only); PLUS every `Booking.heldOverlapping(fromInstant, toInstant)` row's `[startUtc, endUtc)` — i.e. all PENDING and CONFIRMED bookings, so a pending approval request blocks its slot too (feature 14). (Bookings block the calendar globally — single owner.)
4. A raw slot survives the busy check iff its **buffered** interval does NOT overlap any busy interval. Buffered interval = `[slot.start().toInstant() − bufferBefore, slot.end().toInstant() + bufferAfter)`.
5. **Min-notice / horizon filters (feature 11), relative to `now = Instant.now()` captured once at request time:**
   - Drop any surviving slot whose start is **before** `now.plusSeconds(60L * type.minNoticeMinutes)` (too soon).
   - Drop any surviving slot whose start is **after** `now.plus(type.horizonDays, ChronoUnit.DAYS)` (too far out).
6. Return surviving slots as `TimeSlot` (unbuffered, original zoned bounds), in input order.

An overload `availableSlots(type, from, to, excludeBookingId)` lets reschedule ignore the booking being moved. The public 3-arg method delegates with `null`.

> **Degraded mode:** when `isConnected()` is false the owner has not connected Google, so `freeBusy` is never called and busy is just the internal PENDING+CONFIRMED bookings. Tests cover both branches.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/booking/AvailableSlotsTest.java`:

```java
package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.BusyInterval;
import com.calit.google.CalendarPort;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class AvailableSlotsTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    // 2026-06-08 is a Monday. Owner tz Europe/Amsterdam (UTC+2 in June -> 09:00 local = 07:00Z).
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 8);

    @Test
    @TestTransaction
    void noBusyLeavesAllRawSlotsIntact() {
        seedSettings();
        MeetingType t = meetingType("avail-nobusy", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00"); // two 60-min raw slots
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(Instant.parse("2026-06-07T22:00:00Z"),
                                   Instant.parse("2026-06-08T22:00:00Z")))
                .thenReturn(List.of());

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertEquals(2, slots.size());
    }

    @Test
    @TestTransaction
    void busyOverlappingSlotRemovesIt() {
        seedSettings();
        MeetingType t = meetingType("avail-overlap", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(true);
        // Busy 09:15-09:45 local = 07:15-07:45Z, overlaps the 09:00 slot only.
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd()))
                .thenReturn(List.of(new BusyInterval(
                        Instant.parse("2026-06-08T07:15:00Z"),
                        Instant.parse("2026-06-08T07:45:00Z"))));

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void busyInsideBufferZoneRemovesAdjacentSlot() {
        seedSettings();
        // 60-min slots, 15-min buffer AFTER. 09:00-10:00 slot's buffered end is 10:15 local.
        MeetingType t = meetingType("avail-buffer", 60, 0, 15);
        globalRule(DayOfWeek.MONDAY, "09:00", "10:00"); // exactly one raw slot 09:00-10:00
        when(calendarPort.isConnected()).thenReturn(true);
        // Busy 10:05-10:10 local = 08:05-08:10Z: outside the slot, INSIDE the 15-min after-buffer.
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd()))
                .thenReturn(List.of(new BusyInterval(
                        Instant.parse("2026-06-08T08:05:00Z"),
                        Instant.parse("2026-06-08T08:10:00Z"))));

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertTrue(slots.isEmpty(), "buffer-zone busy must remove the adjacent slot (feature 6)");
    }

    @Test
    @TestTransaction
    void existingConfirmedBookingBlocksItsSlot() {
        seedSettings();
        MeetingType t = meetingType("avail-booked", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd())).thenReturn(List.of());
        // Confirmed booking 09:00-10:00 local = 07:00-08:00Z blocks the first slot.
        persistBooking(Instant.parse("2026-06-08T07:00:00Z"),
                       Instant.parse("2026-06-08T08:00:00Z"), BookingStatus.CONFIRMED);

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void pendingBookingBlocksItsSlot() {
        // Feature 14: a PENDING approval hold blocks its slot just like CONFIRMED does.
        seedSettings();
        MeetingType t = meetingType("avail-pending", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd())).thenReturn(List.of());
        // PENDING booking 09:00-10:00 local = 07:00-08:00Z blocks the first slot.
        persistBooking(Instant.parse("2026-06-08T07:00:00Z"),
                       Instant.parse("2026-06-08T08:00:00Z"), BookingStatus.PENDING);

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void degradedModeUsesOnlyBookingBusyAndNeverCallsFreeBusy() {
        // Degraded mode: Google not connected -> freeBusy is never called; busy is internal bookings only.
        seedSettings();
        MeetingType t = meetingType("avail-degraded", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(false);
        persistBooking(Instant.parse("2026-06-08T07:00:00Z"),
                       Instant.parse("2026-06-08T08:00:00Z"), BookingStatus.CONFIRMED);

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.get(0).start().toLocalTime());
        // freeBusy must NOT be consulted when disconnected.
        verify(calendarPort, never()).freeBusy(any(), any());
    }

    @Test
    @TestTransaction
    void minNoticeDropsNearTermSlots() {
        // Feature 11: a huge min-notice (relative to now) drops every slot near today.
        // Use a date derived from "now" in the owner tz so the assertion holds on any run date.
        seedSettings();
        ZoneId zone = ZoneId.of("Europe/Amsterdam");
        LocalDate someday = Instant.now().atZone(zone).toLocalDate().plusDays(3);
        MeetingType t = meetingType("avail-minnotice", 60, 0, 0);
        t.minNoticeMinutes = 60 * 24 * 365 * 50; // ~50 years -> well past any near-term slot
        t.persist();
        // Pick a weekday rule that covers the chosen date.
        globalRule(someday.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        List<TimeSlot> slots = bookingService.availableSlots(t, someday, someday);

        assertTrue(slots.isEmpty(), "min-notice must drop slots earlier than now + minNoticeMinutes");
    }

    @Test
    @TestTransaction
    void horizonDropsFarFutureSlots() {
        // Feature 11: a 1-day horizon drops a slot ~30 days out from "now".
        seedSettings();
        ZoneId zone = ZoneId.of("Europe/Amsterdam");
        LocalDate farDay = Instant.now().atZone(zone).toLocalDate().plusDays(30);
        MeetingType t = meetingType("avail-horizon", 60, 0, 0);
        t.horizonDays = 1; // only ~tomorrow is bookable; a 30-day-out slot is past the horizon
        t.persist();
        globalRule(farDay.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        List<TimeSlot> slots = bookingService.availableSlots(t, farDay, farDay);

        assertTrue(slots.isEmpty(), "horizon must drop slots later than now + horizonDays");
    }

    @Test
    @TestTransaction
    void excludeBookingIdFreesThatBookingsSlot() {
        seedSettings();
        MeetingType t = meetingType("avail-exclude", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd())).thenReturn(List.of());
        Booking b = persistBooking(Instant.parse("2026-06-08T07:00:00Z"),
                                   Instant.parse("2026-06-08T08:00:00Z"), BookingStatus.CONFIRMED);

        // Excluding b: both slots available again.
        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY, b.id);
        assertEquals(2, slots.size());
        assertTrue(slots.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
        // Without exclusion the 09:00 slot is blocked.
        assertFalse(bookingService.availableSlots(t, MONDAY, MONDAY).stream()
                .anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    // --- helpers ---

    private static Instant anyMonday() { return Instant.parse("2026-06-07T22:00:00Z"); }
    private static Instant anyMondayEnd() { return Instant.parse("2026-06-08T22:00:00Z"); }

    private void seedSettings() {
        OwnerSettings s = new OwnerSettings();
        s.id = OwnerSettings.SINGLETON_ID;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType meetingType(String slug, int minutes, int bufBefore, int bufAfter) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.bufferBeforeMinutes = bufBefore;
        t.bufferAfterMinutes = bufAfter;
        // Plan 1b fields: a wide window so min-notice/horizon do not drop the fixed 2026 test slots.
        // (The test run date is well within ~136 years of 2026-06-08, in either direction.)
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000; // ~136 years: keeps the fixed 2026 slot inside the horizon
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = false;
        t.persist();
        return t;
    }

    private void globalRule(DayOfWeek dow, String start, String end) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = null;
        r.persist();
    }

    private Booking persistBooking(Instant start, Instant end, BookingStatus status) {
        Booking b = new Booking();
        b.meetingTypeId = 999L;
        b.inviteeName = "Existing";
        b.inviteeEmail = "existing@example.com";
        b.startUtc = start;
        b.endUtc = end;
        b.status = status;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.persist();
        return b;
    }
}
```

> **Note on the `freeBusy` stub window:** `Europe/Amsterdam` is UTC+2 in June, so `2026-06-08` start-of-day is `2026-06-07T22:00:00Z` and the end-exclusive next midnight is `2026-06-08T22:00:00Z`. The tests stub exactly those instants.
> **Note on min-notice/horizon tests:** because they filter relative to `Instant.now()` at run time, they use extreme bounds (50-year min-notice, 0-day horizon) so the assertion holds regardless of the calendar date the suite runs on — never a flaky "near today" boundary.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=AvailableSlotsTest`
Expected: FAIL — compilation error, `BookingService` does not exist.

- [ ] **Step 3: Write `BookingService` (availability only — book/reschedule/cancel added in Task 6/7)**

`src/main/java/com/calit/booking/BookingService.java`:

```java
package com.calit.booking;

import com.calit.availability.SlotService;
import com.calit.availability.TimeSlot;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.BusyInterval;
import com.calit.google.CalendarPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class BookingService {

    @Inject
    SlotService slotService;

    @Inject
    CalendarPort calendarPort;

    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to) {
        return availableSlots(type, from, to, null);
    }

    /**
     * Bookable slots = raw work-hour slots (Plan 1b override semantics already applied) whose
     * buffered interval does not overlap any busy interval — busy = Google free/busy
     * (only when {@code isConnected()}) + all PENDING/CONFIRMED bookings — and which also survive
     * the min-notice and horizon filters relative to {@code now} (feature 11). {@code excludeBookingId}
     * omits one booking from the busy set (used by reschedule so a booking can move within its own window).
     */
    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to,
                                         Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Interval> busy = busyIntervals(fromInstant, toInstant, excludeBookingId);

        // Feature 11 bounds, captured once relative to request time.
        Instant now = Instant.now();
        Instant earliest = now.plusSeconds(60L * type.minNoticeMinutes);
        Instant latest = now.plus(type.horizonDays, ChronoUnit.DAYS);

        List<TimeSlot> raw = slotService.generateRawSlots(type, from, to);
        List<TimeSlot> available = new ArrayList<>();
        for (TimeSlot slot : raw) {
            Instant slotStart = slot.start().toInstant();
            // Feature 11: drop too-soon (before now+minNotice) and too-far (after now+horizon) slots.
            if (slotStart.isBefore(earliest) || slotStart.isAfter(latest)) {
                continue;
            }
            Interval buffered = new Interval(
                    slotStart.minusSeconds(60L * type.bufferBeforeMinutes),
                    slot.end().toInstant().plusSeconds(60L * type.bufferAfterMinutes));
            if (!buffered.overlapsAny(busy)) {
                available.add(slot);
            }
        }
        return available;
    }

    /**
     * Google busy intervals (only when connected — degraded mode skips freeBusy) plus all
     * PENDING+CONFIRMED bookings in the window (minus an excluded one). PENDING is included so a
     * pending approval request holds its slot (feature 14).
     */
    List<Interval> busyIntervals(Instant from, Instant to, Long excludeBookingId) {
        List<Interval> busy = new ArrayList<>();
        if (calendarPort.isConnected()) {
            for (BusyInterval bi : calendarPort.freeBusy(from, to)) {
                busy.add(new Interval(bi.start(), bi.end()));
            }
        }
        for (Booking b : Booking.<Booking>heldOverlapping(from, to)) {
            if (excludeBookingId != null && excludeBookingId.equals(b.id)) {
                continue;
            }
            busy.add(new Interval(b.startUtc, b.endUtc));
        }
        return busy;
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=AvailableSlotsTest`
Expected: PASS (all 9 tests). Buffer enforcement (feature 6) is proven by `busyInsideBufferZoneRemovesAdjacentSlot`; PENDING-holds-slot (feature 14) by `pendingBookingBlocksItsSlot`; degraded mode (feature 2 optional) by `degradedModeUsesOnlyBookingBusyAndNeverCallsFreeBusy`; min-notice/horizon (feature 11) by `minNoticeDropsNearTermSlots`/`horizonDropsFarFutureSlots`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/BookingService.java \
  src/test/java/com/calit/booking/AvailableSlotsTest.java
git commit -m "feat: BookingService.availableSlots with busy+buffer subtraction"
```

---

### Task 6: BookingService.book — abuse guards + custom-field validation + approval branch + degraded Google event + double-book guard + events

**Files:**
- Create: `src/main/java/com/calit/booking/BookingConflictException.java`
- Create: `src/main/java/com/calit/booking/BookingValidationException.java`
- Create: `src/main/java/com/calit/booking/RateLimitException.java`
- Create: `src/main/java/com/calit/booking/AbuseException.java`
- Create: `src/main/java/com/calit/booking/TurnstileVerifier.java`
- Modify: `src/main/java/com/calit/booking/BookingService.java`
- Modify: `src/main/resources/application.properties` (abuse-guard config)
- Test: `src/test/java/com/calit/booking/BookServiceTest.java`

**Config (feature 16, in `application.properties`):**
```properties
# Abuse guards. Disabled by default for local dev/tests; enabled in %prod via env.
calit.abuse.turnstile.enabled=false
calit.abuse.turnstile.secret=
calit.abuse.turnstile.verify-url=https://challenges.cloudflare.com/turnstile/v0/siteverify
calit.abuse.per-email-daily-cap=10
```
The honeypot is a hidden form field (`website`) whose value is passed to `book` as the `honeypot` parameter; a non-empty value means a bot filled it and is rejected inside `book` (same 400/abuse mapping as a failed Turnstile). Turnstile, the honeypot, and the per-email/day cap are ALL enforced inside `book`. The Plan 5 web layer simply forwards the `cf-turnstile-response` (turnstileToken) and `website` (honeypot) form values without inspecting them.

**Behavior contract for `book(String meetingTypeSlug, Instant startUtc, String inviteeName, String inviteeEmail, Map<String,String> answers, String turnstileToken, String honeypot)`:**
1. Resolve `type = MeetingType.findBySlug(slug)`; if null → `NotFoundException`.
2. **Abuse guards (feature 16), run first — all three inside `book`:**
   - **Turnstile:** if the feature flag is on, call `turnstileVerifier.verify(turnstileToken)` (server-side POST to Cloudflare siteverify); on failure → `AbuseException` (maps to HTTP **400**). When the flag is off, skip entirely.
   - **Honeypot:** if `honeypot` is non-empty (non-null and not blank), a bot filled the hidden field → `AbuseException` (maps to HTTP **400**, same as a failed Turnstile). A blank/null honeypot is the normal human case and passes.
   - **Per-email/day cap:** count this `inviteeEmail`'s bookings created today (owner-tz day window) via `Booking.countByEmailCreatedBetween`; if `>= cap` → `RateLimitException` (maps to HTTP **429**).
3. **Validate custom fields (feature 10):** for every `BookingField` in `BookingField.formFor(type.id)` whose `required` is true, `answers` must contain that `fieldKey` mapped to a non-blank value; otherwise → `BookingValidationException` (maps to HTTP **422**). Built-in full-name/email are validated by their own presence (they are method params, not BookingField rows), so they are not part of this loop. Unknown/extra keys in `answers` are accepted and stored as-is. A null `answers` is treated as an empty map.
4. Compute `endUtc = startUtc + durationMinutes`.
5. Re-check availability for the slot's day (`startUtc` in owner tz → `LocalDate`) and assert some available slot starts exactly at `startUtc`. If not → `BookingConflictException` (maps to HTTP **409**). This app-level re-check under the live busy set (now including the min-notice/horizon filters and PENDING holds) gives nice errors and enforces buffers (which the DB constraint does not know about).
6. Generate `manageToken = UUID.randomUUID().toString()`.
7. **Branch on `type.requiresApproval` (feature 14):**
   - **true →** persist `Booking` status **PENDING** (`createdAt = now`, `manageToken`, `answers`). The PENDING row holds the slot via the `booking_no_overlap_held` constraint. Do **NOT** create a Google event. Fire `BookingRequested(booking.id)`. Return.
   - **false →** persist `Booking` status **CONFIRMED**. Then, **if `calendarPort.isConnected()`**, call `createEvent(summary, description, startUtc, endUtc, [inviteeEmail, ownerEmail], createMeetLink, locationText)` where `createMeetLink = (type.locationType == LocationType.GOOGLE_MEET)` and `locationText = type.locationDetail`; store `googleEventId` + `meetLink`. When not connected, skip the Google call (`googleEventId`/`meetLink` stay null — degraded mode). Fire `BookingConfirmed(booking.id)`. Return.
8. **Cross-node guard (NFR):** the `booking_no_overlap_held` exclusion constraint (Task 1) is the real source of truth — if a concurrent replica inserted an overlapping held (PENDING|CONFIRMED) row after our app-level check passed, this INSERT (at flush) fails with a Postgres constraint violation. Catch that violation and throw `BookingConflictException` (same 409 as the app-level double-book case), so the race is rejected rather than producing two overlapping holds.
9. **Ordering:** persist before the Google call so the new row participates in the same transaction; if `createEvent` throws, the exception propagates and the surrounding `@Transactional` rolls back the just-persisted Booking (documented — no orphan row, no orphan Google event).
10. Return the persisted `Booking` (with `manageToken`).

`summary` = `type.name + " with " + inviteeName`; `description` = `"Booked via calit."`.

> **Status mapping.** `BookingValidationException` → **422** (bad/missing custom-field input). `BookingConflictException` → **409** (slot taken, app-level OR DB-constraint race). `RateLimitException` → **429** (per-email/day cap exceeded). `AbuseException` → **400** (Turnstile verification failed OR a non-empty honeypot). All four are cleanly distinguished.

- [ ] **Step 1: Write the conflict, validation, rate-limit and abuse exceptions, plus TurnstileVerifier**

`src/main/java/com/calit/booking/BookingConflictException.java`:

```java
package com.calit.booking;

/** Thrown when a requested slot is no longer available (double-book / race). Maps to HTTP 409. */
public class BookingConflictException extends RuntimeException {
    public BookingConflictException(String message) {
        super(message);
    }
}
```

`src/main/java/com/calit/booking/BookingValidationException.java`:

```java
package com.calit.booking;

/** Thrown when submitted booking-form input is invalid (e.g. a required custom field is missing). Maps to HTTP 422. */
public class BookingValidationException extends RuntimeException {
    public BookingValidationException(String message) {
        super(message);
    }
}
```

`src/main/java/com/calit/booking/RateLimitException.java`:

```java
package com.calit.booking;

/** Feature 16: thrown when the per-email/day booking cap is exceeded. Maps to HTTP 429. */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}
```

`src/main/java/com/calit/booking/AbuseException.java`:

```java
package com.calit.booking;

/** Feature 16: thrown when a public-form abuse guard (e.g. Turnstile) rejects the request. Maps to HTTP 400. */
public class AbuseException extends RuntimeException {
    public AbuseException(String message) {
        super(message);
    }
}
```

`src/main/java/com/calit/booking/TurnstileVerifier.java`:

```java
package com.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Feature 16: server-side Cloudflare Turnstile verification. When the flag is off, {@link #verify}
 * is a no-op success (so local dev/tests never call Cloudflare). When on, it POSTs the token to the
 * siteverify endpoint and throws {@link AbuseException} (HTTP 400) on a non-success response.
 */
@ApplicationScoped
public class TurnstileVerifier {

    @ConfigProperty(name = "calit.abuse.turnstile.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "calit.abuse.turnstile.secret", defaultValue = "")
    String secret;

    @ConfigProperty(name = "calit.abuse.turnstile.verify-url",
            defaultValue = "https://challenges.cloudflare.com/turnstile/v0/siteverify")
    String verifyUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Throws AbuseException (400) if Turnstile is enabled and the token does not verify. */
    public void verify(String token) {
        if (!enabled) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new AbuseException("Missing Turnstile token");
        }
        try {
            String body = "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                    + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(verifyUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // Cloudflare returns JSON {"success": true|false, ...}. A naive contains-check is
            // sufficient for the boolean flag and avoids pulling a JSON dep into this guard.
            if (resp.statusCode() != 200 || !resp.body().contains("\"success\":true")) {
                throw new AbuseException("Turnstile verification failed");
            }
        } catch (AbuseException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AbuseException("Turnstile verification error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/calit/booking/BookServiceTest.java`:

```java
package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingRequested;
import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookServiceTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    // CDI observers count fired events (feature 14 / degraded mode assertions).
    static final AtomicInteger REQUESTED = new AtomicInteger();
    static final AtomicInteger CONFIRMED = new AtomicInteger();

    void onRequested(@Observes BookingRequested e) { REQUESTED.incrementAndGet(); }
    void onConfirmed(@Observes BookingConfirmed e) { CONFIRMED.incrementAndGet(); }

    // 09:00 Europe/Amsterdam on Monday 2026-06-08 == 07:00Z.
    private static final Instant SLOT_09 = Instant.parse("2026-06-08T07:00:00Z");

    @Test
    @TestTransaction
    void happyPathPersistsBookingWithMeetLinkAndFiresEvent() {
        seedSettings();
        meetingTypeWithMondayWindow("book-happy", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-99", "https://meet.google.com/xyz-1234-pqr",
                        "https://calendar.google.com/evt-99"));

        // No per-type fields and the only global field (seeded description) is optional,
        // so an empty answers map books successfully.
        Booking b = bookingService.book("book-happy", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertEquals("evt-99", b.googleEventId);
        assertEquals("https://meet.google.com/xyz-1234-pqr", b.meetLink);
        Booking loaded = Booking.findById(b.id);
        assertEquals("https://meet.google.com/xyz-1234-pqr", loaded.meetLink);
        // Owner email is included as an attendee; createMeetLink=true for GOOGLE_MEET; null locationText.
        verify(calendarPort, times(1)).createEvent(anyString(), anyString(), eq(SLOT_09),
                eq(SLOT_09.plusSeconds(3600)), eq(List.of("sam@example.com", "owner@example.com")),
                eq(true), eq(null));
    }

    @Test
    @TestTransaction
    void autoTypeConnectedNonMeetLocationPassesLocationTextAndNoMeetLink() {
        // Feature 13: PHONE location -> createMeetLink=false, locationText=locationDetail.
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-phone", LocationType.PHONE, false);
        t.locationDetail = "+31 20 123 4567";
        t.persist();
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ph", null, "h"));

        Booking b = bookingService.book("book-phone", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertNull(b.meetLink, "no Meet link for a non-Meet location");
        verify(calendarPort, times(1)).createEvent(anyString(), anyString(), any(), any(), any(),
                eq(false), eq("+31 20 123 4567"));
    }

    @Test
    @TestTransaction
    void autoTypeDisconnectedConfirmsWithoutEventAndNullMeetLink() {
        // Degraded mode (feature 2 optional): not connected -> no Google event, null googleEventId/meetLink.
        seedSettings();
        meetingTypeWithMondayWindow("book-degraded", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(false);

        Booking b = bookingService.book("book-degraded", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertNull(b.googleEventId);
        assertNull(b.meetLink);
        // createEvent and freeBusy must never be called when disconnected.
        verify(calendarPort, never()).createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        verify(calendarPort, never()).freeBusy(any(), any());
    }

    @Test
    @TestTransaction
    void approvalTypeCreatesPendingWithoutEventAndFiresRequested() {
        // Feature 14: requiresApproval -> PENDING hold, NO Google event, BookingRequested fired.
        seedSettings();
        meetingTypeWithMondayWindow("book-approval", LocationType.GOOGLE_MEET, true);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        int requestedBefore = REQUESTED.get();
        int confirmedBefore = CONFIRMED.get();

        Booking b = bookingService.book("book-approval", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");

        assertEquals(BookingStatus.PENDING, b.status);
        assertNull(b.googleEventId);
        assertNull(b.meetLink);
        // The PENDING request must NOT touch Google.
        verify(calendarPort, never()).createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        assertEquals(requestedBefore + 1, REQUESTED.get(), "BookingRequested fired for approval type");
        assertEquals(confirmedBefore, CONFIRMED.get(), "BookingConfirmed NOT fired for a PENDING request");
    }

    @Test
    @TestTransaction
    void optionalDescriptionMayBeOmitted() {
        // The seeded global `description` field (feature 10 default) is optional,
        // so a booking that omits it still succeeds (regression guard for required-loop logic).
        seedSettings();
        meetingTypeWithMondayWindow("book-optional", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-opt", "https://meet.google.com/opt-1-2", "h"));

        Booking b = bookingService.book("book-optional", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");

        assertEquals(BookingStatus.CONFIRMED, b.status);
    }

    @Test
    @TestTransaction
    void requiredCustomFieldMissingThrowsValidation() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-missing", LocationType.GOOGLE_MEET, false);
        // Per-type required field: formFor(t.id) now returns this override (not the global form).
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // answers lacks "company" -> 422-mapped validation failure, before any Google call.
        assertThrows(BookingValidationException.class, () ->
                bookingService.book("book-required-missing", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", ""));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldBlankThrowsValidation() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-blank", LocationType.GOOGLE_MEET, false);
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // Present but blank value is rejected just like a missing key.
        assertThrows(BookingValidationException.class, () ->
                bookingService.book("book-required-blank", SLOT_09, "Sam", "sam@example.com",
                        Map.of("company", "   "), "tok", ""));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldPresentPersistsAndStoresAnswers() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-ok", LocationType.GOOGLE_MEET, false);
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ans", "https://meet.google.com/ans-1-2", "h"));

        Booking b = bookingService.book("book-required-ok", SLOT_09, "Sam", "sam@example.com",
                Map.of("company", "Acme", "note", "extra-key-kept"), "tok", "");

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        // The submitted answers (including the unknown extra key) are stored verbatim.
        assertEquals("Acme", loaded.answers.get("company"));
        assertEquals("extra-key-kept", loaded.answers.get("note"));
    }

    @Test
    @TestTransaction
    void doubleBookOnSameSlotThrowsConflict() {
        seedSettings();
        meetingTypeWithMondayWindow("book-double", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", "https://meet.google.com/a-b-c", "h"));

        bookingService.book("book-double", SLOT_09, "First", "first@example.com", Map.of(), "tok", "");

        // Second attempt on the now-taken slot is rejected (the persisted booking is busy).
        // The app-level re-check catches it here; the DB exclusion constraint is the
        // cross-replica backstop documented in Task 1 / the behavior contract.
        assertThrows(BookingConflictException.class,
                () -> bookingService.book("book-double", SLOT_09, "Second", "second@example.com", Map.of(), "tok", ""));
    }

    @Test
    @TestTransaction
    void perEmailDailyCapExceededThrowsRateLimit() {
        // Feature 16: the same email's bookings created today are counted; over the cap -> 429.
        // Default cap is 10 (application.properties). Persist 10 prior bookings created "now"
        // for this email, then the 11th book() is rejected before persisting.
        seedSettings();
        meetingTypeWithMondayWindow("book-cap", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        for (int i = 0; i < 10; i++) {
            Booking prior = new Booking();
            prior.meetingTypeId = 1L;
            prior.inviteeName = "Spammer";
            prior.inviteeEmail = "spam@example.com";
            prior.startUtc = SLOT_09.plusSeconds(3600L * (i + 5));
            prior.endUtc = prior.startUtc.plusSeconds(3600);
            prior.status = BookingStatus.CANCELLED; // status irrelevant to the per-email count
            prior.createdAt = Instant.now();
            prior.manageToken = java.util.UUID.randomUUID().toString();
            prior.persist();
        }

        assertThrows(RateLimitException.class, () ->
                bookingService.book("book-cap", SLOT_09, "Spammer", "spam@example.com", Map.of(), "tok", ""));
    }

    @Test
    @TestTransaction
    void filledHoneypotThrowsAbuse() {
        // Feature 16: a non-empty honeypot means a bot filled the hidden "website" field.
        // It is rejected INSIDE book() with AbuseException (HTTP 400), like a failed Turnstile,
        // before any booking is persisted. The honeypot guard is independent of the Turnstile flag.
        seedSettings();
        meetingTypeWithMondayWindow("book-honeypot", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        assertThrows(AbuseException.class, () ->
                bookingService.book("book-honeypot", SLOT_09, "Bot", "bot@example.com",
                        Map.of(), "tok", "http://spam.example"));
    }

    @Test
    @TestTransaction
    void bookingAtUnavailableStartThrowsConflict() {
        seedSettings();
        meetingTypeWithMondayWindow("book-bad-start", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // 09:13 is not a generated slot start.
        assertThrows(BookingConflictException.class, () ->
                bookingService.book("book-bad-start",
                        Instant.parse("2026-06-08T07:13:00Z"), "X", "x@example.com", Map.of(), "tok", ""));
    }

    // --- helpers ---

    private void seedSettings() {
        OwnerSettings s = new OwnerSettings();
        s.id = OwnerSettings.SINGLETON_ID;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType meetingTypeWithMondayWindow(String slug, LocationType location, boolean requiresApproval) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000; // keep the fixed 2026 slot inside the horizon regardless of run date
        t.locationType = location;
        t.requiresApproval = requiresApproval;
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }

    /** Adds a required per-type custom field so formFor(typeId) returns this override. */
    private void requiredField(Long typeId, String key, String label) {
        BookingField f = new BookingField();
        f.meetingTypeId = typeId;
        f.fieldKey = key;
        f.label = label;
        f.type = BookingField.FieldType.SHORT_TEXT;
        f.required = true;
        f.position = 0;
        f.persist();
    }
}
```

> **Note on the cap test and `Instant.now()`:** the per-email window is "today in owner tz". Because the 10 prior rows and the `book` call all happen within one test run, they share the same owner-tz day, so the 11th is reliably over the default cap of 10. The min-notice/horizon filters use the same wide bounds set in `meetingTypeWithMondayWindow` (horizon 50_000 days, min-notice 0) so the fixed 2026 slot survives them on any run date — these guards are exercised independently in `AvailableSlotsTest`.

- [ ] **Step 3: Run it to confirm it fails**

Run: `mvn test -Dtest=BookServiceTest`
Expected: FAIL — compilation error, `BookingService.book` (7-arg) does not exist.

- [ ] **Step 4: Add `book` to `BookingService`**

Add these imports to `BookingService.java`:

```java
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingRequested;
import com.calit.domain.BookingField;
import com.calit.domain.LocationType;
import com.calit.google.CreatedEvent;
import jakarta.enterprise.event.Event;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
```

Add the injected collaborators / config (next to the existing `@Inject` fields):

```java
    @Inject
    TurnstileVerifier turnstileVerifier;

    @Inject
    Event<BookingRequested> bookingRequestedEvent;

    @Inject
    Event<BookingConfirmed> bookingConfirmedEvent;

    @ConfigProperty(name = "calit.abuse.per-email-daily-cap", defaultValue = "10")
    long perEmailDailyCap;
```

Add the method (note the new `answers` + `turnstileToken` + `honeypot` parameters and the approval/degraded branches):

```java
    @Transactional
    public Booking book(String meetingTypeSlug, Instant startUtc,
                        String inviteeName, String inviteeEmail,
                        Map<String, String> answers, String turnstileToken, String honeypot) {
        MeetingType type = MeetingType.findBySlug(meetingTypeSlug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug);
        }

        // Feature 16: all three abuse guards run first, inside book(). The Plan 5 web layer
        // just forwards the cf-turnstile-response (turnstileToken) and website (honeypot) form values.
        turnstileVerifier.verify(turnstileToken);          // -> AbuseException (400) when enabled & invalid
        if (honeypot != null && !honeypot.isBlank()) {     // a bot filled the hidden field
            throw new AbuseException("Honeypot field was filled.");  // -> AbuseException (400)
        }
        enforcePerEmailDailyCap(inviteeEmail);             // -> RateLimitException (429) over cap

        Map<String, String> submitted = answers == null ? Map.of() : answers;

        // Feature 10: every required custom field must have a non-blank value. Built-in
        // name/email are method params, not BookingField rows, so they are not in this loop.
        validateRequiredFields(type, submitted);

        Instant endUtc = startUtc.plusSeconds(60L * type.durationMinutes);

        // App-level availability re-check: nice errors + buffer/min-notice/horizon enforcement
        // (the DB constraint only guards raw-time overlap, not buffers).
        assertSlotAvailable(type, startUtc, null);

        Booking booking = new Booking();
        booking.meetingTypeId = type.id;
        booking.inviteeName = inviteeName;
        booking.inviteeEmail = inviteeEmail;
        booking.startUtc = startUtc;
        booking.endUtc = endUtc;
        booking.createdAt = Instant.now();
        booking.manageToken = UUID.randomUUID().toString();
        booking.answers = submitted;
        // Feature 14: approval types hold the slot as PENDING; auto types are CONFIRMED immediately.
        booking.status = type.requiresApproval ? BookingStatus.PENDING : BookingStatus.CONFIRMED;

        // NFR cross-node guard: persist + flush now so a concurrent replica's overlapping
        // held (PENDING|CONFIRMED) row trips the `booking_no_overlap_held` exclusion constraint
        // here, surfaced as the same 409 the app-level check uses (instead of a 500).
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + startUtc + " is not available for " + type.slug);
            }
            throw ex;
        }

        if (type.requiresApproval) {
            // Feature 14: PENDING request — NO Google event yet; the owner approves/declines later.
            bookingRequestedEvent.fire(new BookingRequested(booking.id));
            return booking;
        }

        // Auto type: create the Google event when connected (degraded mode skips it entirely).
        // If createEvent throws, the @Transactional boundary rolls back this booking (no orphan row).
        // `createGoogleEvent` (shared with `approve`, added in Task 7) applies the feature-13
        // location logic: createMeetLink=(locationType==GOOGLE_MEET), locationText=locationDetail.
        if (calendarPort.isConnected()) {
            createGoogleEvent(type, booking);
        }

        bookingConfirmedEvent.fire(new BookingConfirmed(booking.id));
        return booking;
    }

    /**
     * Creates the Google event for a CONFIRMED booking and stores its ids. Applies the feature-13
     * location logic: {@code createMeetLink = (locationType == GOOGLE_MEET)},
     * {@code locationText = locationDetail}. Shared by {@code book} (auto branch) and {@code approve}.
     * Caller must guard with {@code calendarPort.isConnected()} (degraded mode skips this entirely).
     */
    private void createGoogleEvent(MeetingType type, Booking booking) {
        OwnerSettings owner = OwnerSettings.get();
        CreatedEvent created = calendarPort.createEvent(
                type.name + " with " + booking.inviteeName,
                "Booked via calit.",
                booking.startUtc, booking.endUtc,
                List.of(booking.inviteeEmail, owner.ownerEmail),
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
        booking.googleEventId = created.googleEventId();
        booking.meetLink = created.meetLink();
    }

    /**
     * Feature 16: rejects the booking (HTTP 429) if this invitee email already created at least
     * {@code perEmailDailyCap} bookings during today's owner-tz day window.
     */
    private void enforcePerEmailDailyCap(String inviteeEmail) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        LocalDate today = Instant.now().atZone(zone).toLocalDate();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        if (Booking.countByEmailCreatedBetween(inviteeEmail, dayStart, dayEnd) >= perEmailDailyCap) {
            throw new RateLimitException(
                    "Daily booking cap reached for " + inviteeEmail);
        }
    }

    /**
     * Feature 10: rejects the booking (HTTP 422) if any required field in
     * {@code BookingField.formFor(type.id)} is missing or blank in the submitted answers.
     */
    private void validateRequiredFields(MeetingType type, Map<String, String> answers) {
        for (BookingField field : BookingField.formFor(type.id)) {
            if (field.required) {
                String value = answers.get(field.fieldKey);
                if (value == null || value.isBlank()) {
                    throw new BookingValidationException(
                            "Required field '" + field.fieldKey + "' is missing or blank");
                }
            }
        }
    }

    /** True if {@code ex} (or a cause) is the no-overlap exclusion-constraint violation. */
    private boolean isNoOverlapViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && "booking_no_overlap_held".equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** Throws BookingConflictException unless an available slot starts exactly at {@code startUtc}. */
    private void assertSlotAvailable(MeetingType type, Instant startUtc, Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        LocalDate day = startUtc.atZone(zone).toLocalDate();
        boolean ok = availableSlots(type, day, day, excludeBookingId).stream()
                .anyMatch(s -> s.start().toInstant().equals(startUtc));
        if (!ok) {
            throw new BookingConflictException(
                    "Slot " + startUtc + " is not available for " + type.slug);
        }
    }
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=BookServiceTest`
Expected: PASS (all 13 tests): happy path (Meet link + createMeetLink=true), non-Meet location passes locationText + createMeetLink=false, disconnected → CONFIRMED + no event + null meetLink (degraded), approval type → PENDING + no event + BookingRequested fired, optional-description-omitted, required-missing → validation, required-blank → validation, required-present persists + stores answers, double-book → conflict, per-email cap → 429, filled-honeypot → abuse (400), bad-start → conflict.

> **On testing the DB exclusion constraint:** a single-threaded `@QuarkusTest` cannot deterministically reproduce the two-replicas-race that the `booking_no_overlap_held` constraint exists for (the app-level re-check in `assertSlotAvailable` always fires first in-process). We therefore do NOT fake a passing concurrency test. The app-level 409 path is proven by `doubleBookOnSameSlotThrowsConflict`; the cross-node guarantee is provided by the DB constraint (Task 1) plus the `isNoOverlapViolation` catch, which is exercised structurally and documented as the source of truth under N replicas.
> **On testing Turnstile:** the flag defaults off (`calit.abuse.turnstile.enabled=false`), so `verify` is a no-op in tests — no real Cloudflare call. The enabled-and-invalid → 400 path is covered structurally by `TurnstileVerifier` (skip when off, throw `AbuseException` otherwise) and by the `AbuseMapper` 400 mapping in Task 8; the per-email cap (429) is fully exercised in `perEmailDailyCapExceededThrowsRateLimit`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/booking/BookingConflictException.java \
  src/main/java/com/calit/booking/BookingValidationException.java \
  src/main/java/com/calit/booking/RateLimitException.java \
  src/main/java/com/calit/booking/AbuseException.java \
  src/main/java/com/calit/booking/TurnstileVerifier.java \
  src/main/java/com/calit/booking/BookingService.java \
  src/main/resources/application.properties \
  src/test/java/com/calit/booking/BookServiceTest.java
git commit -m "feat: BookingService.book with abuse guards (Turnstile/cap), approval branch, degraded Google, answers + cross-node guard"
```

---

### Task 7: BookingService.approve / decline / reschedule / cancel

**Files:**
- Modify: `src/main/java/com/calit/booking/BookingService.java`
- Test: `src/test/java/com/calit/booking/ApproveDeclineTest.java`
- Test: `src/test/java/com/calit/booking/RescheduleCancelTest.java`

**Behavior contract:**

- `approve(Long bookingId)` (feature 14):
  1. Load booking; if null → `NotFoundException`. (If not PENDING, treat as a no-op idempotent success — but the normal path is PENDING.)
  2. Set `status = CONFIRMED`.
  3. Resolve its `MeetingType`; **if `calendarPort.isConnected()`** create the Google event now — `createEvent(summary, description, startUtc, endUtc, [inviteeEmail, ownerEmail], createMeetLink=(locationType==GOOGLE_MEET), locationText=locationDetail)` — and store `googleEventId`/`meetLink`. When disconnected, skip (degraded mode).
  4. Fire `BookingApproved(bookingId)`.
- `decline(Long bookingId)` (feature 14):
  1. Load booking; if null → `NotFoundException`.
  2. Set `status = DECLINED`. This drops the row from the `WHERE (status IN ('PENDING','CONFIRMED'))` partial exclusion constraint, freeing the slot. No Google event exists for a PENDING request, so nothing to delete.
  3. Fire `BookingDeclined(bookingId)`.

- `reschedule(String manageToken, Instant newStartUtc)` (feature 5, keyed by manage-token):
  1. Load booking via `Booking.findByManageToken(manageToken)`; if null or CANCELLED/DECLINED → `NotFoundException`.
  2. Resolve its `MeetingType`; compute `newEnd = newStartUtc + durationMinutes`.
  3. Assert `newStartUtc` is an available slot start **excluding this booking** from the busy set (so it can move within its own window). Else → `BookingConflictException`.
  4. Capture `oldStart = booking.startUtc`; set `startUtc = newStartUtc`, `endUtc = newEnd`.
  5. **Branch on `type.requiresApproval`:**
     - **true →** set `status = PENDING` (re-approval). If a Google event exists, `deleteEvent` it (only when connected) and null out `googleEventId`/`meetLink`. Flush the UPDATE (cross-node guard, see below). Fire `BookingRequested(bookingId)` (re-approval request — Plan 4 re-sends the requested email).
     - **false →** keep `status = CONFIRMED`. Flush the UPDATE. If connected, `updateEvent(googleEventId, newStartUtc, newEnd)`. Fire `BookingRescheduled(bookingId, oldStart)`.
  6. **NFR cross-node guard:** flush the UPDATE so the `booking_no_overlap_held` exclusion constraint is re-evaluated against the new time range — moving this booking onto another replica's freshly-held time trips the constraint, which we catch and surface as `BookingConflictException` (409), same as `book`.
  7. Return booking.
- `cancel(String manageToken)` (feature 5, keyed by manage-token):
  1. Load booking via `Booking.findByManageToken(manageToken)`; if null → `NotFoundException`. (Idempotent on already-CANCELLED is fine.)
  2. Set `status = CANCELLED`. This drops the row from the `WHERE (status IN ('PENDING','CONFIRMED'))` partial exclusion constraint, freeing the raw-time slot for a new booking (at both the app level and the DB level).
  3. **If `calendarPort.isConnected()` and `googleEventId != null`**, `deleteEvent(googleEventId)`. (No event exists for a degraded-mode or PENDING booking.)
  4. Fire `BookingCancelled(bookingId)`.

- [ ] **Step 1: Write the failing approve/decline test**

`src/test/java/com/calit/booking/ApproveDeclineTest.java`:

```java
package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApproveDeclineTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 8);
    private static final Instant SLOT_09 = Instant.parse("2026-06-08T07:00:00Z"); // 09:00 local

    @Test
    @TestTransaction
    void approveFlipsToConfirmedAndCreatesEvent() {
        // Feature 14: approve a PENDING request -> CONFIRMED + Google event created now.
        seedSettings();
        approvalType("approve");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ap", "https://meet.google.com/ap-1-2", "h"));

        Booking b = bookingService.book("approve", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");
        assertEquals(BookingStatus.PENDING, b.status);

        bookingService.approve(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("evt-ap", loaded.googleEventId);
        assertEquals("https://meet.google.com/ap-1-2", loaded.meetLink);
        // The event is created at approve time (createMeetLink=true for GOOGLE_MEET), not at book time.
        verify(calendarPort, times(1)).createEvent(anyString(), anyString(), eq(SLOT_09),
                eq(SLOT_09.plusSeconds(3600)), eq(List.of("sam@example.com", "owner@example.com")),
                eq(true), eq(null));
    }

    @Test
    @TestTransaction
    void declineFlipsToDeclinedFreesSlotAndCreatesNoEvent() {
        // Feature 14: decline a PENDING request -> DECLINED, slot freed, no Google event.
        seedSettings();
        MeetingType t = approvalType("decline");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        Booking b = bookingService.book("decline", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");
        // While PENDING, the 09:00 slot is held.
        assertTrue(bookingService.availableSlots(t, MONDAY, MONDAY).stream()
                .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));

        bookingService.decline(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.DECLINED, loaded.status);
        verify(calendarPort, never()).createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        // DECLINED leaves the partial constraint -> 09:00 is bookable again.
        List<TimeSlot> avail = bookingService.availableSlots(t, MONDAY, MONDAY);
        assertTrue(avail.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    // --- helpers ---

    private void seedSettings() {
        OwnerSettings s = new OwnerSettings();
        s.id = OwnerSettings.SINGLETON_ID;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType approvalType(String slug) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = true; // feature 14
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }
}
```

- [ ] **Step 2: Write the failing reschedule/cancel test**

`src/test/java/com/calit/booking/RescheduleCancelTest.java`:

```java
package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class RescheduleCancelTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 8);
    private static final Instant SLOT_09 = Instant.parse("2026-06-08T07:00:00Z"); // 09:00 local
    private static final Instant SLOT_10 = Instant.parse("2026-06-08T08:00:00Z"); // 10:00 local

    @Test
    @TestTransaction
    void rescheduleAutoTypeMovesBookingCallsUpdateAndFreesOldTime() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("resched", false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-r", "https://meet.google.com/r-r-r", "h"));

        Booking b = bookingService.book("resched", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");

        // Reschedule is keyed by the invitee's manage-token, not the numeric id.
        bookingService.reschedule(b.manageToken, SLOT_10);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals(SLOT_10, loaded.startUtc);
        assertEquals(SLOT_10.plusSeconds(3600), loaded.endUtc);
        verify(calendarPort, times(1)).updateEvent("evt-r", SLOT_10, SLOT_10.plusSeconds(3600));

        // Old 09:00 time is free again; new 10:00 time is now taken.
        List<TimeSlot> avail = bookingService.availableSlots(t, MONDAY, MONDAY);
        assertTrue(avail.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
        assertTrue(avail.stream().noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(10, 0))));
    }

    @Test
    @TestTransaction
    void rescheduleApprovalTypeReturnsToPendingAndDeletesEvent() {
        // Feature 14: rescheduling an approval-type booking re-enters the approval queue (PENDING),
        // deletes any existing Google event, and fires a re-request (not updateEvent).
        seedSettings();
        MeetingType t = approvalType("resched-approval");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // Book PENDING, then approve so it has a CONFIRMED Google event to delete on reschedule.
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ra", "https://meet.google.com/ra-1-2", "h"));
        Booking b = bookingService.book("resched-approval", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");
        bookingService.approve(b.id);

        bookingService.reschedule(b.manageToken, SLOT_10);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.PENDING, loaded.status, "approval type re-enters PENDING on reschedule");
        assertEquals(SLOT_10, loaded.startUtc);
        assertNull(loaded.googleEventId, "the prior event is deleted on re-request");
        assertNull(loaded.meetLink);
        verify(calendarPort, times(1)).deleteEvent("evt-ra");
        verify(calendarPort, never()).updateEvent(any(), any(), any());
    }

    @Test
    @TestTransaction
    void cancelFlipsStatusCallsDeleteAndFreesSlot() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("cancel", false);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-c", "https://meet.google.com/c-c-c", "h"));

        Booking b = bookingService.book("cancel", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");
        assertTrue(bookingService.availableSlots(t, MONDAY, MONDAY).stream()
                .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));

        // Cancel is keyed by the manage-token.
        bookingService.cancel(b.manageToken);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CANCELLED, loaded.status);
        verify(calendarPort, times(1)).deleteEvent("evt-c");
        // 09:00 slot is bookable again.
        assertTrue(bookingService.availableSlots(t, MONDAY, MONDAY).stream()
                .anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    // --- helpers ---

    private void seedSettings() {
        OwnerSettings s = new OwnerSettings();
        s.id = OwnerSettings.SINGLETON_ID;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType meetingTypeWithMondayWindow(String slug, boolean requiresApproval) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = requiresApproval;
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }

    private MeetingType approvalType(String slug) {
        return meetingTypeWithMondayWindow(slug, true);
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `mvn test -Dtest=ApproveDeclineTest`
Run: `mvn test -Dtest=RescheduleCancelTest`
Expected: FAIL — compilation error, `BookingService.approve` / `decline` / `reschedule(String,...)` / `cancel(String)` do not exist.

- [ ] **Step 4: Add `approve`, `decline`, `reschedule`, `cancel` to `BookingService`**

Add these imports to `BookingService.java`:

```java
import com.calit.booking.events.BookingApproved;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingDeclined;
import com.calit.booking.events.BookingRescheduled;
```

Add the injected event emitters (next to the existing event fields):

```java
    @Inject
    Event<BookingApproved> bookingApprovedEvent;

    @Inject
    Event<BookingDeclined> bookingDeclinedEvent;

    @Inject
    Event<BookingRescheduled> bookingRescheduledEvent;

    @Inject
    Event<BookingCancelled> bookingCancelledEvent;
```

> `approve` reuses the package-visible `createGoogleEvent(type, booking)` helper already added in Task 6 (shared with `book`), so the auto-confirm and approve-confirm call sites apply identical feature-13 location logic.

Add the methods:

```java
    @Transactional
    public void approve(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            throw new NotFoundException("No booking " + bookingId);
        }
        booking.status = BookingStatus.CONFIRMED;
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        // Feature 14 + degraded mode: create the Google event now, only when connected.
        if (calendarPort.isConnected()) {
            createGoogleEvent(type, booking);
        }
        bookingApprovedEvent.fire(new BookingApproved(bookingId));
    }

    @Transactional
    public void decline(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            throw new NotFoundException("No booking " + bookingId);
        }
        // DECLINED leaves the PENDING|CONFIRMED partial constraint -> frees the slot.
        // A PENDING request has no Google event, so nothing to delete.
        booking.status = BookingStatus.DECLINED;
        bookingDeclinedEvent.fire(new BookingDeclined(bookingId));
    }

    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null
                || booking.status == BookingStatus.CANCELLED
                || booking.status == BookingStatus.DECLINED) {
            throw new NotFoundException("No active booking for token " + manageToken);
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        Instant newEnd = newStartUtc.plusSeconds(60L * type.durationMinutes);

        // Exclude this booking so it may move freely within its own window.
        assertSlotAvailable(type, newStartUtc, booking.id);

        Instant oldStart = booking.startUtc;
        booking.startUtc = newStartUtc;
        booking.endUtc = newEnd;

        boolean reApproval = type.requiresApproval;
        String priorEventId = booking.googleEventId;
        if (reApproval) {
            // Feature 14: return to the approval queue; drop any existing event.
            booking.status = BookingStatus.PENDING;
            booking.googleEventId = null;
            booking.meetLink = null;
        }

        // NFR cross-node guard: flush so the no-overlap exclusion constraint is checked against
        // the new range; a concurrent overlap is surfaced as the same 409 as a double-book.
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + newStartUtc + " is not available for token " + manageToken);
            }
            throw ex;
        }

        if (reApproval) {
            if (calendarPort.isConnected() && priorEventId != null) {
                calendarPort.deleteEvent(priorEventId);
            }
            bookingRequestedEvent.fire(new BookingRequested(booking.id)); // re-approval request
        } else {
            if (calendarPort.isConnected() && booking.googleEventId != null) {
                calendarPort.updateEvent(booking.googleEventId, newStartUtc, newEnd);
            }
            bookingRescheduledEvent.fire(new BookingRescheduled(booking.id, oldStart));
        }
        return booking;
    }

    @Transactional
    public void cancel(String manageToken) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        booking.status = BookingStatus.CANCELLED;
        if (calendarPort.isConnected() && booking.googleEventId != null) {
            calendarPort.deleteEvent(booking.googleEventId);
        }
        bookingCancelledEvent.fire(new BookingCancelled(booking.id));
    }
```

- [ ] **Step 5: Run them to confirm they pass**

Run: `mvn test -Dtest=ApproveDeclineTest`
Expected: PASS (both tests): approve → CONFIRMED + event created; decline → DECLINED + slot freed + no event.

Run: `mvn test -Dtest=RescheduleCancelTest`
Expected: PASS (all three tests): auto reschedule moves + `updateEvent`; approval reschedule → PENDING + `deleteEvent` + no `updateEvent`; cancel → CANCELLED + `deleteEvent` + slot freed. All keyed by manage-token.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/booking/BookingService.java \
  src/test/java/com/calit/booking/ApproveDeclineTest.java \
  src/test/java/com/calit/booking/RescheduleCancelTest.java
git commit -m "feat: BookingService.approve/decline + manage-token reschedule/cancel with degraded Google + events"
```

---

### Task 8: REST API + exception mappers

**Files:**
- Create: `src/main/java/com/calit/booking/BookingResource.java`
- Create: `src/main/java/com/calit/booking/BookingConflictMapper.java`
- Create: `src/main/java/com/calit/booking/BookingValidationMapper.java`
- Create: `src/main/java/com/calit/booking/RateLimitMapper.java`
- Create: `src/main/java/com/calit/booking/AbuseMapper.java`
- Test: `src/test/java/com/calit/booking/BookingResourceTest.java`

**Endpoints (`@Path("/api")`):**
- `GET /api/meeting-types/{slug}/available?from=&to=` → `List<TimeSlot>` via `availableSlots`. 404 if slug unknown.
- `POST /api/bookings` (body: `slug`, `startUtc` ISO-8601, `inviteeName`, `inviteeEmail`, `answers` object — map of `fieldKey`→value, feature 10 — plus `turnstileToken` and `honeypot` for the abuse guards, feature 16) → `201` + `Booking`. The honeypot is a body field forwarded straight to `book` (which rejects a non-empty value as a bot, HTTP 400); the Plan 5 web layer just supplies the `website` form value here.
- `POST /api/bookings/{manageToken}/reschedule` (body: `newStartUtc` ISO) → `200` + `Booking`. **Keyed by manage-token, not numeric id** (feature 5 invitee self-service).
- `DELETE /api/bookings/{manageToken}` → `204`. **Keyed by manage-token.**
- `POST /api/bookings/{id}/approve` → `204` (owner approval queue — feature 14; keyed by numeric id since this is an owner action).
- `POST /api/bookings/{id}/decline` → `204` (feature 14).

- `BookingConflictException` → HTTP `409` via a `@Provider` mapper (slot taken / race).
- `BookingValidationException` → HTTP `422` via a `@Provider` mapper (missing/blank required custom field).
- `RateLimitException` → HTTP `429` via a `@Provider` mapper (per-email/day cap — feature 16).
- `AbuseException` → HTTP `400` via a `@Provider` mapper (Turnstile failure — feature 16).

- [ ] **Step 1: Write the failing RestAssured test**

`src/test/java/com/calit/booking/BookingResourceTest.java`:

```java
package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookingResourceTest {

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    @Transactional
    void setup() {
        if (OwnerSettings.get() == null) {
            OwnerSettings s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();
        }
    }

    @Test
    void bookingHappyPathReturns201WithMeetLinkAndManageToken() {
        String slug = "rest-book-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-rest", "https://meet.google.com/rest-1234-xyz", "h"));

        given().contentType("application/json")
                .body("{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                        + "\"answers\":{\"description\":\"Quarterly sync\"},\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(201)
                .body("meetLink", is("https://meet.google.com/rest-1234-xyz"))
                .body("status", is("CONFIRMED"))
                .body("manageToken", notNullValue())
                .body("answers.description", is("Quarterly sync"));
    }

    @Test
    void missingRequiredCustomFieldReturns422() {
        String slug = "rest-422-" + System.nanoTime();
        seedTypeWithRequiredField(slug, "company");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // Body omits the required "company" answer -> 422 (not 409: input is wrong, slot is fine).
        given().contentType("application/json")
                .body("{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                        + "\"answers\":{},\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(422)
                .body(containsString("company"));
    }

    @Test
    void availableEndpointReturnsSlots() {
        String slug = "rest-avail-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        given().when().get("/api/meeting-types/" + slug + "/available?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200).body("size()", is(2));
    }

    @Test
    void doubleBookReturns409() {
        String slug = "rest-conflict-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-x", "https://meet.google.com/a-b-c", "h"));

        String body = "{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                + "\"inviteeName\":\"First\",\"inviteeEmail\":\"first@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}";
        given().contentType("application/json").body(body)
                .when().post("/api/bookings").then().statusCode(201);

        given().contentType("application/json").body(body)
                .when().post("/api/bookings").then().statusCode(409)
                .body(containsString("not available"));
    }

    @Test
    void cancelByManageTokenReturns204AndFreesSlot() {
        // Feature 5: DELETE is keyed by the manage-token returned at booking time.
        String slug = "rest-cancel-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-cancel", "https://meet.google.com/cn-1-2", "h"));

        String token = given().contentType("application/json")
                .body("{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(201).extract().path("manageToken");

        given().when().delete("/api/bookings/" + token).then().statusCode(204);

        // The 09:00 slot is bookable again (so availability now has both 09:00 and 10:00 = size 2).
        given().when().get("/api/meeting-types/" + slug + "/available?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200).body("size()", is(2));
    }

    @Transactional
    void seedType(String slug) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = false;
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
    }

    /** Seeds a type plus a required per-type custom field (so its form requires {@code fieldKey}). */
    @Transactional
    void seedTypeWithRequiredField(String slug, String fieldKey) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = false;
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        BookingField f = new BookingField();
        f.meetingTypeId = t.id;
        f.fieldKey = fieldKey;
        f.label = "Company";
        f.type = BookingField.FieldType.SHORT_TEXT;
        f.required = true;
        f.position = 0;
        f.persist();
    }
}
```

> **Note:** this test writes to the shared Dev Services DB (not rolled back). Unique `slug`s per run keep it self-contained; the Monday window yields exactly two 60-min slots over `2026-06-08`. The abuse Turnstile flag is off by default, so `turnstileToken` is accepted without a Cloudflare call. `horizonDays=50_000` keeps the fixed 2026 slot inside the horizon on any run date.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=BookingResourceTest`
Expected: FAIL — 404s (no resource yet) / compilation error.

- [ ] **Step 3: Write the conflict and validation mappers**

`src/main/java/com/calit/booking/BookingConflictMapper.java`:

```java
package com.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BookingConflictMapper implements ExceptionMapper<BookingConflictException> {
    @Override
    public Response toResponse(BookingConflictException ex) {
        return Response.status(Response.Status.CONFLICT).entity(ex.getMessage()).build();
    }
}
```

`src/main/java/com/calit/booking/BookingValidationMapper.java`:

```java
package com.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Maps invalid booking-form input (e.g. a missing required custom field) to 422 Unprocessable Entity. */
@Provider
public class BookingValidationMapper implements ExceptionMapper<BookingValidationException> {
    @Override
    public Response toResponse(BookingValidationException ex) {
        return Response.status(422).entity(ex.getMessage()).build();
    }
}
```

`src/main/java/com/calit/booking/RateLimitMapper.java`:

```java
package com.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Feature 16: maps an exceeded per-email/day booking cap to 429 Too Many Requests. */
@Provider
public class RateLimitMapper implements ExceptionMapper<RateLimitException> {
    @Override
    public Response toResponse(RateLimitException ex) {
        return Response.status(429).entity(ex.getMessage()).build();
    }
}
```

`src/main/java/com/calit/booking/AbuseMapper.java`:

```java
package com.calit.booking;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Feature 16: maps a failed abuse guard (e.g. Turnstile) to 400 Bad Request. */
@Provider
public class AbuseMapper implements ExceptionMapper<AbuseException> {
    @Override
    public Response toResponse(AbuseException ex) {
        return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
    }
}
```

- [ ] **Step 4: Write the resource**

`src/main/java/com/calit/booking/BookingResource.java`:

```java
package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.MeetingType;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BookingResource {

    @Inject
    BookingService bookingService;

    public record BookRequest(String slug, String startUtc, String inviteeName, String inviteeEmail,
                              Map<String, String> answers, String turnstileToken, String honeypot) {}

    public record RescheduleRequest(String newStartUtc) {}

    @GET
    @Path("/meeting-types/{slug}/available")
    public List<TimeSlot> available(@PathParam("slug") String slug,
                                    @QueryParam("from") String from,
                                    @QueryParam("to") String to) {
        MeetingType type = MeetingType.findBySlug(slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        return bookingService.availableSlots(type, LocalDate.parse(from), LocalDate.parse(to));
    }

    @POST
    @Path("/bookings")
    public Response create(BookRequest req) {
        // All abuse guards (Turnstile + honeypot + per-email/day cap) are enforced inside book().
        // The Plan 5 web layer forwards the cf-turnstile-response (turnstileToken) and website (honeypot) values.
        Booking b = bookingService.book(req.slug(), Instant.parse(req.startUtc()),
                req.inviteeName(), req.inviteeEmail(), req.answers(), req.turnstileToken(), req.honeypot());
        return Response.status(Response.Status.CREATED).entity(b).build();
    }

    // Invitee self-service (feature 5): reschedule/cancel are keyed by the manage-token.
    @POST
    @Path("/bookings/{manageToken}/reschedule")
    public Booking reschedule(@PathParam("manageToken") String manageToken, RescheduleRequest req) {
        return bookingService.reschedule(manageToken, Instant.parse(req.newStartUtc()));
    }

    @DELETE
    @Path("/bookings/{manageToken}")
    public Response cancel(@PathParam("manageToken") String manageToken) {
        bookingService.cancel(manageToken);
        return Response.noContent().build();
    }

    // Owner approval queue (feature 14): keyed by numeric id (owner action, not invitee self-service).
    @POST
    @Path("/bookings/{id}/approve")
    public Response approve(@PathParam("id") Long id) {
        bookingService.approve(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/bookings/{id}/decline")
    public Response decline(@PathParam("id") Long id) {
        bookingService.decline(id);
        return Response.noContent().build();
    }
}
```

> **Route ordering note:** `{manageToken}` (a UUID string) and the `{id}/approve` / `{id}/decline` paths do not collide — the approve/decline routes have an extra path segment, and JAX-RS matches the more specific template first. The `DELETE /bookings/{manageToken}` and `POST /bookings/{manageToken}/reschedule` are unambiguous by method + segment count.

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=BookingResourceTest`
Expected: PASS (all 5 tests): happy path (201, stores `answers` + `manageToken`), available list, double-book (409), missing-required-field (422), cancel-by-manage-token (204 + slot freed).

- [ ] **Step 6: Run the full suite**

Run: `mvn test`
Expected: PASS — all Plan 1, Plan 1b, Plan 2, and Plan 3 tests (Booking, Interval, AvailableSlots, BookService, ApproveDecline, RescheduleCancel, BookingResource).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/booking/BookingResource.java \
  src/main/java/com/calit/booking/BookingConflictMapper.java \
  src/main/java/com/calit/booking/BookingValidationMapper.java \
  src/main/java/com/calit/booking/RateLimitMapper.java \
  src/main/java/com/calit/booking/AbuseMapper.java \
  src/test/java/com/calit/booking/BookingResourceTest.java
git commit -m "feat: booking REST API (available/book/approve/decline + manage-token reschedule/cancel) with 409/422/429/400 mappers"
```

---

## Self-Review against spec

**1. Spec coverage (Plan 3 scope — features 1, 5, 6, 10, 11, 12 (consumed), 13, 14, 16 + manage-token):**

| Requirement | Task |
|---|---|
| Booking a slot (feat 1) | Task 6 (`book`: abuse guards → validate → re-check → branch persist + degraded Google event), Task 8 (`POST /api/bookings`) |
| Rescheduling, by manage-token (feat 5) | Task 7 (`reschedule(String manageToken, Instant)`: auto type moves + `updateEvent`; approval type → PENDING + `deleteEvent` + re-`BookingRequested`; frees old time; DB-constraint re-check), Task 8 (`POST /api/bookings/{manageToken}/reschedule`); tests `rescheduleAutoTypeMoves...` / `rescheduleApprovalTypeReturnsToPending...` |
| Min buffer enforcement (feat 6) | Task 3 (`Interval.overlaps`), Task 5 (buffered interval subtraction) — proven by `busyInsideBufferZoneRemovesAdjacentSlot`. Buffers stay an app-level policy (the DB constraint only guards raw-time overlap) |
| Custom booking fields — answers + required validation (feat 10) | Task 1 (`answers` JSONB column), Task 2 (`Booking.answers` field, round-trip test), Task 6 (`book` accepts `answers`, validates required `BookingField.formFor` entries → 422, stores answers; tests `requiredCustomFieldMissing/Blank/Present`, `optionalDescriptionMayBeOmitted`), Task 8 (`answers` in body + `BookingValidationMapper` 422; test `missingRequiredCustomFieldReturns422`). Built-in name/email are dedicated columns, not BookingField rows |
| Min-notice + horizon slot filters (feat 11) | Task 5 (`availableSlots` drops slots before `now + minNoticeMinutes` and after `now + horizonDays`, relative to `Instant.now()`); tests `minNoticeDropsNearTermSlots` / `horizonDropsFarFutureSlots`. Consumes Plan 1b `MeetingType.minNoticeMinutes/horizonDays` verbatim |
| Date-specific overrides (feat 12) — **consumed** | Task 5 (`availableSlots` calls `SlotService.generateRawSlots`, which Plan 1b already updated to apply `DateOverride` replace-semantics per date). This plan does NOT re-implement overrides — it inherits them through the raw-slot source |
| Per-type meeting location (feat 13) | Task 6/7 (`createGoogleEvent` passes `createMeetLink = (type.locationType == GOOGLE_MEET)` and `locationText = type.locationDetail` to `createEvent`); tests `happyPath...` (Meet, createMeetLink=true) / `autoTypeConnectedNonMeetLocationPassesLocationTextAndNoMeetLink` (PHONE, createMeetLink=false). Consumes Plan 1b `locationType`/`locationDetail` |
| Per-type approval workflow (feat 14) | Task 1 (constraint covers PENDING so a hold blocks the slot), Task 2 (`BookingStatus.PENDING/DECLINED`), Task 4 (`BookingRequested`/`BookingApproved`/`BookingDeclined` events), Task 6 (`book` branches on `requiresApproval` → PENDING + `BookingRequested`, NO event), Task 7 (`approve` → CONFIRMED + event; `decline` → DECLINED + frees slot; reschedule of approval type re-enters PENDING), Task 8 (`POST .../approve` + `.../decline`); tests `approveFlipsToConfirmedAndCreatesEvent` / `declineFlipsToDeclined...` / `approvalTypeCreatesPendingWithoutEvent...` / `pendingBookingBlocksItsSlot`. Consumes Plan 1b `requiresApproval` |
| Public-form abuse protection (feat 16) | Task 6 (`book` runs all three guards in-service: Turnstile verify via `TurnstileVerifier` → 400 when enabled+invalid; non-empty `honeypot` param → `AbuseException` 400; per-email/day cap via `Booking.countByEmailCreatedBetween` → 429), config flag `calit.abuse.*`, Task 8 (`RateLimitMapper` 429 + `AbuseMapper` 400); tests `perEmailDailyCapExceededThrowsRateLimit`, `filledHoneypotThrowsAbuse` |
| Degraded (Google-optional) mode (feat 2 optional) | Task 5 (`busyIntervals` calls `freeBusy` only when `isConnected()`), Task 6/7 (`createEvent`/`updateEvent`/`deleteEvent` only when `isConnected()`; `googleEventId`/`meetLink` stay null otherwise); tests `degradedModeUsesOnlyBookingBusy...` / `autoTypeDisconnectedConfirmsWithoutEventAndNullMeetLink` |
| Cancel, by manage-token (feat 5) | Task 7 (`cancel(String manageToken)`: status flip CANCELLED, `deleteEvent` when connected + eventId, frees slot — partial constraint `WHERE status IN ('PENDING','CONFIRMED')` drops the row), Task 8 (`DELETE /api/bookings/{manageToken}`); test `cancelByManageTokenReturns204AndFreesSlot` |
| Invitee manage-token | Task 1 (`manage_token VARCHAR(36) NOT NULL UNIQUE`), Task 2 (`Booking.manageToken` + `findByManageToken`, round-trip + lookup tests), Task 6 (UUID generated at creation), Task 7 (reschedule/cancel keyed by it), Task 8 (token in REST path). Plan 4 emails the tokenized link; Plan 5 routes by it |
| Double-book prevention | Task 6 app-level (`assertSlotAvailable` re-check against live busy set, now incl. PENDING holds + min-notice/horizon) + Task 8 (409 mapper); tests `doubleBookOnSameSlotThrowsConflict` / `doubleBookReturns409` |
| Cross-node double-book guard (NFR) | Task 1 (`booking_no_overlap_held` GiST exclusion constraint, partial on `status IN ('PENDING','CONFIRMED')`), Task 6/7 (`persistAndFlush` + `isNoOverlapViolation` catch → same 409). The INSERT/UPDATE is the source of truth under N replicas; app-level check can't be trusted alone |
| Busy = Google + DB bookings (single owner, global) | Task 5 (`busyIntervals` merges `freeBusy` (when connected) + `Booking.heldOverlapping`, i.e. PENDING+CONFIRMED) |

Google is always behind the mocked `CalendarPort` seam (`@InjectMock`) in tests — no real Google call. Turnstile is flag-off in tests — no real Cloudflare call.

**NFR — horizontal scalability (booking safety):** This plan satisfies the overview's cross-node booking-safety requirement. App-level checks across replicas can both pass simultaneously; the Postgres exclusion constraint `booking_no_overlap_held` (Task 1) makes the final INSERT/UPDATE the source of truth, so no two held (PENDING|CONFIRMED) bookings can hold overlapping raw time regardless of replica count — including two concurrent PENDING approval requests for the same slot (feature 14). The constraint violation is caught (`isNoOverlapViolation`) and surfaced as the same 409 as an app-level double-book, so callers see one consistent error. The per-email/day cap (feature 16) is Postgres-counted (`COUNT` over `created_at` today), so it is replica-agnostic. A single-threaded `@QuarkusTest` cannot deterministically race two replicas, so we do NOT fake a passing concurrency test — the app-level 409 path is tested directly and the cross-node guarantee is the documented DB constraint + catch.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to" placeholders. Every step shows full code and exact `mvn test -Dtest=...` commands with expected FAIL/PASS.

**3. Type-consistency — public contract this plan exposes (Plans 4 & 5 depend on these EXACT shapes; all verified verbatim against the overview "Defined in Plan 3"):**

- Entity `com.calit.booking.Booking` (extends `PanacheEntityBase`) — fields: `Long id`, `Long meetingTypeId`, `String inviteeName`, `String inviteeEmail`, `Instant startUtc`, `Instant endUtc`, `String googleEventId` (nullable), `String meetLink` (nullable), `BookingStatus status` (enum **`PENDING`/`CONFIRMED`/`CANCELLED`/`DECLINED`**, `EnumType.STRING`), `Instant createdAt`, **`String manageToken`** (UUID, unique — invitee manage/reschedule/cancel key), **`Map<String,String> answers`** (feature 10: custom `BookingField.fieldKey`→value, JSONB via `@JdbcTypeCode(SqlTypes.JSON)`). ✔ matches the overview's `Booking` shape (incl. `manageToken`, 4-value status, overlap guard covering `PENDING`+`CONFIRMED`) and Task 2 exactly. Static finders: `heldOverlapping(from,to)` (PENDING+CONFIRMED), `findByManageToken(token)`, `countByEmailCreatedBetween(email,start,end)`.
- Service `com.calit.booking.BookingService` (`@ApplicationScoped`) — signatures match the overview verbatim:
  - `List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to)` ✔ (full pipeline: raw → busy[freeBusy when connected + PENDING/CONFIRMED bookings] − buffers → min-notice/horizon)
  - `Booking book(String slug, Instant startUtc, String inviteeName, String inviteeEmail, Map<String,String> answers, String turnstileToken, String honeypot)` ✔ (7-arg form matching the overview + Plan 5; ALL abuse guards — Turnstile + honeypot + per-email/day cap — enforced INSIDE `book` [non-empty `honeypot` → `AbuseException` 400]; required-field 422 + approval branch [PENDING→`BookingRequested` / CONFIRMED→degraded `createEvent`+`BookingConfirmed`])
  - `void approve(Long bookingId)` ✔ (PENDING→CONFIRMED + event when connected + `BookingApproved`)
  - `void decline(Long bookingId)` ✔ (PENDING→DECLINED + `BookingDeclined`)
  - `Booking reschedule(String manageToken, Instant newStartUtc)` ✔ (approval→PENDING+delete event+`BookingRequested`; auto→move+`updateEvent`+`BookingRescheduled`)
  - `void cancel(String manageToken)` ✔ (CANCELLED + `deleteEvent` when connected/eventId + `BookingCancelled`)
  (Plus the package-visible 4-arg `availableSlots(..., Long excludeBookingId)` overload and `createGoogleEvent`/`enforcePerEmailDailyCap`/`validateRequiredFields`/`assertSlotAvailable`/`isNoOverlapViolation` helpers — additive, do not change the public contract.)
- Exceptions / status mapping: `BookingValidationException` → **422** (`BookingValidationMapper`), `BookingConflictException` → **409** (`BookingConflictMapper`, app-level OR the DB `booking_no_overlap_held` constraint), `RateLimitException` → **429** (`RateLimitMapper`, per-email/day cap), `AbuseException` → **400** (`AbuseMapper`, Turnstile failure). ✔ all four cleanly distinguished.
- CDI events in `com.calit.booking.events`, fired via `jakarta.enterprise.event.Event` — match the overview list verbatim, all carry `Long bookingId` (reschedule also `Instant oldStartUtc`):
  - `BookingRequested(Long bookingId)` — fired after persisting a PENDING booking (Task 6 approval branch + Task 7 reschedule re-request) ✔
  - `BookingConfirmed(Long bookingId)` — fired after persist + (degraded) `createEvent` for an auto booking (Task 6) ✔
  - `BookingApproved(Long bookingId)` — fired after PENDING→CONFIRMED + event (Task 7) ✔
  - `BookingDeclined(Long bookingId)` — fired after PENDING→DECLINED (Task 7) ✔
  - `BookingRescheduled(Long bookingId, Instant oldStartUtc)` — fired after an auto-type move (Task 7) ✔
  - `BookingCancelled(Long bookingId)` — fired after cancel (Task 7) ✔
  Plan 4 attaches `@Observes` methods to these; fire timing (post-Google) is correct for email side effects.
- Abuse config (feature 16): `calit.abuse.turnstile.enabled` (default false), `calit.abuse.turnstile.secret`, `calit.abuse.turnstile.verify-url`, `calit.abuse.per-email-daily-cap` (default 10). The flag gates the Cloudflare call so dev/tests never hit the network.

**4. Consumed Plan 1 / 1b / 2 signatures used verbatim (all confirmed against the overview):**
- Plan 1: `slotService.generateRawSlots(type, from, to)` → `List<TimeSlot>` (Plan 1b already folds in `DateOverride` replace-semantics — feature 12 — so this plan inherits overrides without redeclaring them); `TimeSlot.start()/.end()` (`ZonedDateTime`); `MeetingType.durationMinutes/bufferBeforeMinutes/bufferAfterMinutes/findBySlug/id/name/slug`; `OwnerSettings.get()/timezone/ownerEmail`; `BookingField.formFor(Long)` → `List<BookingField>`, `BookingField.fieldKey/required/FieldType`. ✔
- Plan 1b (additive `MeetingType` columns, consumed verbatim): `minNoticeMinutes` + `horizonDays` (feature-11 filters in `availableSlots`), `locationType` (`LocationType.GOOGLE_MEET`/`PHONE`/`IN_PERSON`/`CUSTOM`) + `locationDetail` (feature-13 `createMeetLink`/`locationText` in `createGoogleEvent`), `requiresApproval` (feature-14 branch in `book`/`reschedule`), and `DateOverride` consumed indirectly via `SlotService` (feature 12). ✔
- Plan 2 (`com.calit.google`): `CalendarPort.isConnected()` (gates every Google call — degraded mode), `freeBusy(Instant, Instant)`, `createEvent(String summary, String description, Instant start, Instant end, List<String> attendeeEmails, boolean createMeetLink, String locationText)`, `updateEvent(String, Instant, Instant)`, `deleteEvent(String)`; `BusyInterval(Instant start, Instant end)` (`.start()/.end()`); `CreatedEvent(String googleEventId, String meetLink, String htmlLink)` (`.googleEventId()/.meetLink()`). ✔ The new 7-arg `createEvent` + `isConnected()` match the overview's Plan 2 contract exactly; tests mock the port with `@InjectMock` and stub `isConnected()` + 7-arg `createEvent`.

**Known assumptions (carried forward):** owner-tz day boundaries are computed via `atStartOfDay`/`plusDays(1)` (DST-safe through `ZonedDateTime`); a booking blocks the whole calendar regardless of meeting type (single owner); the slot-start equality booking guard requires the requested `startUtc` to match a generated slot start exactly; min-notice/horizon are evaluated against `Instant.now()` captured once per `availableSlots` call; required-field validation only covers `BookingField` rows for the resolved per-type/global form (built-in name/email are enforced by being non-null method params, and unknown extra answer keys are stored verbatim); the DB exclusion constraint guarantees only no raw-time overlap of held (PENDING|CONFIRMED) rows — buffers remain an app-level policy enforced in `availableSlots`; the honeypot is a `book` parameter (the `website` form value forwarded by Plan 5) and a non-empty value is rejected inside `book` as a bot (HTTP 400); Turnstile uses a naive `"success":true` substring check on the siteverify JSON to avoid pulling a JSON dependency into the guard.
