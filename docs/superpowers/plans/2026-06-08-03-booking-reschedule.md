# Plan 3 — Booking, Reschedule & Buffer Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layer genuinely-bookable slot calculation on top of Plan 1's raw slots and Plan 2's Google seam, then implement booking, reschedule, and cancel. A raw work-hour slot is bookable only when its **buffered** interval (`[start − bufferBefore, end + bufferAfter]`) does not overlap any busy interval — where "busy" means Google free/busy intervals **plus** every CONFIRMED booking in the DB (a booking blocks the calendar globally, since there is a single owner). This enforces feature 6 (buffers), feature 1 (booking), and feature 5 (reschedule), and prevents double-booking.

**Architecture:** A `BookingService` (`@ApplicationScoped`) is the single entry point. It calls `SlotService.generateRawSlots` (Plan 1), subtracts busy intervals via an injected `CalendarPort` (Plan 2) and persisted CONFIRMED `Booking` rows, and returns surviving `TimeSlot`s. Booking/reschedule/cancel re-check availability under the same logic to stay race-safe, persist a `Booking` Panache entity, drive Google through `CalendarPort`, and fire CDI events (`BookingConfirmed`/`BookingRescheduled`/`BookingCancelled`) that Plan 4 observes for emails. A thin JAX-RS layer exposes available-slots, create-booking, reschedule, and cancel so the subsystem is exercisable on its own. The overlap/subtraction math lives in a small package-visible helper so it is unit-testable in isolation.

**Feature 10 (custom booking fields):** `book` accepts an `answers` map (`fieldKey`→value), validates that every required `BookingField` from `BookingField.formFor(type.id)` has a non-blank value (else HTTP 422), and stores the answers on the `Booking` in a JSONB column. Built-in full-name/email remain dedicated columns, not BookingField rows.

**NFR (horizontal scalability — booking safety under N replicas):** the app-level availability re-check alone is NOT safe across replicas (two nodes can pass it simultaneously). A Postgres `EXCLUDE`/GiST exclusion constraint (`booking_no_overlap_confirmed`, partial on `status='CONFIRMED'`) makes the INSERT/UPDATE the source of truth — the DB rejects any second overlapping CONFIRMED booking. The app catches that violation and re-throws the same 409 it uses for the app-level double-book case. The app-level check is retained for nice errors and for buffer enforcement (buffers are an app-level policy the DB does not model). 422 (bad input) and 409 (slot lost to a race) are therefore cleanly distinguished.

**Tech Stack:** Java 25, Quarkus 3.35.3, Hibernate ORM Panache, PostgreSQL, Flyway, quarkus-rest (+jackson), JUnit 5, RestAssured, `quarkus-junit5-mockito` (dependency added in Plan 2) for `@InjectMock CalendarPort`. Tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**. Tests NEVER call real Google: the `CalendarPort` seam is always mocked with canned `BusyInterval`s / `CreatedEvent`.

**Consumed contracts (used verbatim — do not redeclare):**
- Plan 1: `com.calit.availability.SlotService.generateRawSlots(MeetingType, LocalDate, LocalDate) -> List<TimeSlot>`; `com.calit.availability.TimeSlot(ZonedDateTime start, ZonedDateTime end)`; `com.calit.domain.MeetingType` (`durationMinutes`, `bufferBeforeMinutes`, `bufferAfterMinutes`, `findBySlug`, `id`, `name`, `slug`); `com.calit.domain.OwnerSettings` (`ownerEmail`, `get()`); `com.calit.domain.BookingField` (feature 10: `fieldKey`, `required`, `FieldType` enum, `static List<BookingField> formFor(Long meetingTypeId)`) — consumed by `book` to validate required custom answers. Full name + email are built-ins (`Booking.inviteeName`/`inviteeEmail`), NOT BookingField rows.
- Plan 2 (`com.calit.google`):
  ```java
  public record BusyInterval(java.time.Instant start, java.time.Instant end) {}
  public record CreatedEvent(String googleEventId, String meetLink, String htmlLink) {}
  public interface CalendarPort {
      java.util.List<BusyInterval> freeBusy(java.time.Instant from, java.time.Instant to);
      CreatedEvent createEvent(String summary, String description, java.time.Instant start, java.time.Instant end, java.util.List<String> attendeeEmails);
      void updateEvent(String eventId, java.time.Instant start, java.time.Instant end);
      void deleteEvent(String eventId);
  }
  ```

---

### Task 1: Database schema (Flyway V3)

**Files:**
- Create: `src/main/resources/db/migration/V3__booking.sql`

> Plan 2 owns `V2__*.sql` (OAuth token storage). This plan's migration is `V3`.

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V3__booking.sql`:

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
    status            VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED',
    created_at        TIMESTAMPTZ  NOT NULL,
    -- Feature 10: submitted values for owner-defined custom BookingFields
    -- (fieldKey -> value). Built-in name/email live in their own columns above.
    answers           JSONB        NOT NULL DEFAULT '{}'::jsonb
);

-- Availability queries scan CONFIRMED bookings within a time window.
CREATE INDEX idx_booking_status_start ON booking (status, start_utc);

-- NFR (horizontal scalability): cross-node double-booking guard.
-- App-level "is this slot free?" checks (Task 5/6) cannot be trusted across
-- replicas — two nodes can pass the check simultaneously and both INSERT.
-- This DB-level exclusion constraint makes the INSERT itself the source of
-- truth: Postgres rejects any second CONFIRMED booking whose raw time range
-- overlaps an existing CONFIRMED one. btree_gist is required for the `=`/`&&`
-- mix in a GiST exclusion constraint; Dev Services Postgres supports it.
-- NOTE: this guarantees only no RAW-TIME overlap of CONFIRMED rows. Buffers
-- remain an app-level policy (Task 5) — the DB does not know about them.
-- Cancelling sets status='CANCELLED', so the partial WHERE clause drops the
-- row from the constraint and frees the slot.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_confirmed
    EXCLUDE USING gist (tstzrange(start_utc, end_utc) WITH &&)
    WHERE (status = 'CONFIRMED');
```

- [ ] **Step 2: Verify the schema applies at startup**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. Flyway runs V1→V3 at boot against the Dev Services DB; no migration errors in the log.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__booking.sql
git commit -m "feat: add booking schema (V3) with answers column + no-overlap exclusion constraint"
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
        b.answers = Map.of("description", "Quarterly sync", "phone", "+31201234567");
        b.persist();

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("https://meet.google.com/abc-defg-hij", loaded.meetLink);
        assertEquals(start, loaded.startUtc);
        // Feature 10: custom answers round-trip through the JSONB column.
        assertEquals("Quarterly sync", loaded.answers.get("description"));
        assertEquals("+31201234567", loaded.answers.get("phone"));
    }

    @Test
    @TestTransaction
    void confirmedOverlappingFindsBookingsInWindow() {
        Instant base = Instant.parse("2026-06-08T07:00:00Z");
        persistBooking(base, base.plusSeconds(1800), BookingStatus.CONFIRMED);          // 07:00-07:30
        persistBooking(base.plusSeconds(7200), base.plusSeconds(9000), BookingStatus.CONFIRMED); // 09:00-09:30
        persistBooking(base, base.plusSeconds(1800), BookingStatus.CANCELLED);          // cancelled, ignored

        // Window 06:00-08:00 catches only the first CONFIRMED booking.
        List<Booking> hits = Booking.confirmedOverlapping(
                base.minusSeconds(3600), base.plusSeconds(3600));

        assertEquals(1, hits.size());
        assertEquals(BookingStatus.CONFIRMED, hits.get(0).status);
        assertTrue(hits.stream().allMatch(x -> x.status == BookingStatus.CONFIRMED));
    }

    private void persistBooking(Instant start, Instant end, BookingStatus status) {
        Booking b = new Booking();
        b.meetingTypeId = 1L;
        b.inviteeName = "X";
        b.inviteeEmail = "x@example.com";
        b.startUtc = start;
        b.endUtc = end;
        b.status = status;
        b.createdAt = Instant.now();
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
    CONFIRMED,
    CANCELLED
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
     * Feature 10: submitted values for the owner-defined custom BookingFields
     * (fieldKey -> value). Built-in full-name/email are NOT stored here — they
     * live in {@link #inviteeName}/{@link #inviteeEmail}. Stored as a JSONB
     * column; defaults to an empty map at the DB level.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", columnDefinition = "jsonb")
    public Map<String, String> answers;

    /**
     * All CONFIRMED bookings whose [startUtc, endUtc) overlaps the window [from, to).
     * Overlap predicate: startUtc < to AND from < endUtc.
     */
    public static List<Booking> confirmedOverlapping(Instant from, Instant to) {
        return list("status = ?1 and startUtc < ?2 and ?3 < endUtc",
                BookingStatus.CONFIRMED, to, from);
    }
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=BookingTest`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/booking/BookingStatus.java \
  src/main/java/com/calit/booking/Booking.java \
  src/test/java/com/calit/booking/BookingTest.java
git commit -m "feat: add Booking entity and BookingStatus enum"
```

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

These records are fired by `BookingService` and observed by Plan 4 (emails). Defined now so the service can depend on them.

**Files:**
- Create: `src/main/java/com/calit/booking/events/BookingConfirmed.java`
- Create: `src/main/java/com/calit/booking/events/BookingRescheduled.java`
- Create: `src/main/java/com/calit/booking/events/BookingCancelled.java`

- [ ] **Step 1: Write the three event records**

`src/main/java/com/calit/booking/events/BookingConfirmed.java`:

```java
package com.calit.booking.events;

public record BookingConfirmed(Long bookingId) {}
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
git commit -m "feat: add booking CDI event records (Confirmed/Rescheduled/Cancelled)"
```

---

### Task 5: BookingService — available-slot computation (buffer enforcement)

**Files:**
- Create: `src/main/java/com/calit/booking/BookingService.java`
- Test: `src/test/java/com/calit/booking/AvailableSlotsTest.java`

**Behavior contract for `availableSlots(MeetingType type, LocalDate from, LocalDate to)`:**
1. `raw = slotService.generateRawSlots(type, from, to)` (zoned, owner tz).
2. Compute the query window in UTC: `fromInstant = from.atStartOfDay(zone).toInstant()`, `toInstant = to.plusDays(1).atStartOfDay(zone).toInstant()` (end-exclusive next-day midnight in owner tz), where `zone = ZoneId.of(OwnerSettings.get().timezone)`.
3. Build the busy set as `List<Interval>`: every `calendarPort.freeBusy(fromInstant, toInstant)` `BusyInterval` plus every `Booking.confirmedOverlapping(fromInstant, toInstant)` row's `[startUtc, endUtc)`. (Bookings block the calendar globally — single owner.)
4. A raw slot survives iff its **buffered** interval does NOT overlap any busy interval. Buffered interval = `[slot.start().toInstant() − bufferBefore, slot.end().toInstant() + bufferAfter)`.
5. Return surviving slots as `TimeSlot` (unbuffered, original zoned bounds), in input order.

An overload `availableSlots(type, from, to, excludeBookingId)` lets reschedule ignore the booking being moved. The public 3-arg method delegates with `null`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/booking/AvailableSlotsTest.java`:

```java
package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.AvailabilityRule;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd())).thenReturn(List.of());
        // Confirmed booking 09:00-10:00 local = 07:00-08:00Z blocks the first slot.
        persistConfirmed(Instant.parse("2026-06-08T07:00:00Z"),
                         Instant.parse("2026-06-08T08:00:00Z"));

        List<TimeSlot> slots = bookingService.availableSlots(t, MONDAY, MONDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void excludeBookingIdFreesThatBookingsSlot() {
        seedSettings();
        MeetingType t = meetingType("avail-exclude", 60, 0, 0);
        globalRule(DayOfWeek.MONDAY, "09:00", "11:00");
        when(calendarPort.freeBusy(anyMonday(), anyMondayEnd())).thenReturn(List.of());
        Booking b = persistConfirmed(Instant.parse("2026-06-08T07:00:00Z"),
                                     Instant.parse("2026-06-08T08:00:00Z"));

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

    private Booking persistConfirmed(Instant start, Instant end) {
        Booking b = new Booking();
        b.meetingTypeId = 999L;
        b.inviteeName = "Existing";
        b.inviteeEmail = "existing@example.com";
        b.startUtc = start;
        b.endUtc = end;
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.persist();
        return b;
    }
}
```

> **Note on the `freeBusy` stub window:** `Europe/Amsterdam` is UTC+2 in June, so `2026-06-08` start-of-day is `2026-06-07T22:00:00Z` and the end-exclusive next midnight is `2026-06-08T22:00:00Z`. The tests stub exactly those instants.

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
     * Bookable slots = raw work-hour slots whose buffered interval does not overlap any busy
     * interval (Google free/busy + CONFIRMED bookings). {@code excludeBookingId} omits one
     * booking from the busy set (used by reschedule so a booking can move within its own window).
     */
    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to,
                                         Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Interval> busy = busyIntervals(fromInstant, toInstant, excludeBookingId);

        List<TimeSlot> raw = slotService.generateRawSlots(type, from, to);
        List<TimeSlot> available = new ArrayList<>();
        for (TimeSlot slot : raw) {
            Interval buffered = new Interval(
                    slot.start().toInstant().minusSeconds(60L * type.bufferBeforeMinutes),
                    slot.end().toInstant().plusSeconds(60L * type.bufferAfterMinutes));
            if (!buffered.overlapsAny(busy)) {
                available.add(slot);
            }
        }
        return available;
    }

    /** Google busy intervals plus all CONFIRMED bookings in the window (minus an excluded one). */
    List<Interval> busyIntervals(Instant from, Instant to, Long excludeBookingId) {
        List<Interval> busy = new ArrayList<>();
        for (BusyInterval bi : calendarPort.freeBusy(from, to)) {
            busy.add(new Interval(bi.start(), bi.end()));
        }
        for (Booking b : Booking.<Booking>confirmedOverlapping(from, to)) {
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
Expected: PASS (all 5 tests). Buffer enforcement (feature 6) is proven by `busyInsideBufferZoneRemovesAdjacentSlot`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/BookingService.java \
  src/test/java/com/calit/booking/AvailableSlotsTest.java
git commit -m "feat: BookingService.availableSlots with busy+buffer subtraction"
```

---

### Task 6: BookingService.book — persist + custom-field validation + Google event + double-book guard + event

**Files:**
- Create: `src/main/java/com/calit/booking/BookingConflictException.java`
- Create: `src/main/java/com/calit/booking/BookingValidationException.java`
- Modify: `src/main/java/com/calit/booking/BookingService.java`
- Test: `src/test/java/com/calit/booking/BookServiceTest.java`

**Behavior contract for `book(String meetingTypeSlug, Instant startUtc, String inviteeName, String inviteeEmail, Map<String,String> answers)`:**
1. Resolve `type = MeetingType.findBySlug(slug)`; if null → `NotFoundException`.
2. **Validate custom fields (feature 10):** for every `BookingField` in `BookingField.formFor(type.id)` whose `required` is true, `answers` must contain that `fieldKey` mapped to a non-blank value; otherwise → `BookingValidationException` (maps to HTTP **422**). Built-in full-name/email are validated by their own presence (they are method params, not BookingField rows), so they are not part of this loop. Unknown/extra keys in `answers` are accepted and stored as-is. A null `answers` is treated as an empty map.
3. Compute `endUtc = startUtc + durationMinutes`.
4. Re-check availability for the slot's day (`startUtc` in owner tz → `LocalDate`) and assert some available slot starts exactly at `startUtc`. If not → `BookingConflictException` (maps to HTTP **409**). This app-level re-check under the live busy set gives nice errors and enforces buffers (which the DB constraint does not know about).
5. Persist `Booking` (status CONFIRMED, `createdAt = Instant.now()`, `answers` = the provided map or empty). **Cross-node guard (NFR):** the `booking_no_overlap_confirmed` exclusion constraint (Task 1) is the real source of truth — if a concurrent replica inserted an overlapping CONFIRMED row after our app-level check passed, this INSERT (at flush/commit) fails with a Postgres constraint violation. Catch that violation and throw `BookingConflictException` (same 409 as the app-level double-book case), so the race is rejected rather than producing two overlapping bookings.
6. Call `calendarPort.createEvent(summary, description, startUtc, endUtc, [inviteeEmail, ownerEmail])`. Store `googleEventId` + `meetLink` from the returned `CreatedEvent`. **Ordering:** persist before the Google call so the new row participates in the same transaction; if `createEvent` throws, the exception propagates and the surrounding `@Transactional` rolls back the just-persisted Booking (documented — no orphan row, no orphan Google event).
7. Fire `BookingConfirmed(booking.id)` after the Google call succeeds.
8. Return the persisted `Booking`.

`summary` = `type.name + " with " + inviteeName`; `description` = `"Booked via calit."`.

> **422 vs 409.** A `BookingValidationException` (bad/missing required custom-field input) maps to **422 Unprocessable Entity** — the request is malformed and retrying unchanged will not help. A `BookingConflictException` (slot taken, app-level OR DB-constraint race) maps to **409 Conflict** — the input was fine, the slot was lost to someone else.

- [ ] **Step 1: Write the conflict and validation exceptions**

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

- [ ] **Step 2: Write the failing test**

`src/test/java/com/calit/booking/BookServiceTest.java`:

```java
package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookServiceTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    // 09:00 Europe/Amsterdam on Monday 2026-06-08 == 07:00Z.
    private static final Instant SLOT_09 = Instant.parse("2026-06-08T07:00:00Z");

    @Test
    @TestTransaction
    void happyPathPersistsBookingWithMeetLinkAndFiresEvent() {
        seedSettings();
        meetingTypeWithMondayWindow("book-happy");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), eq(SLOT_09), any(), any()))
                .thenReturn(new CreatedEvent("evt-99", "https://meet.google.com/xyz-1234-pqr",
                        "https://calendar.google.com/evt-99"));

        // No per-type fields and the only global field (seeded description) is optional,
        // so an empty answers map books successfully.
        Booking b = bookingService.book("book-happy", SLOT_09, "Sam", "sam@example.com", Map.of());

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertEquals("evt-99", b.googleEventId);
        assertEquals("https://meet.google.com/xyz-1234-pqr", b.meetLink);
        Booking loaded = Booking.findById(b.id);
        assertEquals("https://meet.google.com/xyz-1234-pqr", loaded.meetLink);
        // Owner email is included as an attendee alongside the invitee.
        verify(calendarPort, times(1)).createEvent(anyString(), anyString(), eq(SLOT_09),
                eq(SLOT_09.plusSeconds(3600)), eq(List.of("sam@example.com", "owner@example.com")));
    }

    @Test
    @TestTransaction
    void optionalDescriptionMayBeOmitted() {
        // The seeded global `description` field (feature 10 default) is optional,
        // so a booking that omits it still succeeds (regression guard for required-loop logic).
        seedSettings();
        meetingTypeWithMondayWindow("book-optional");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-opt", "https://meet.google.com/opt-1-2", "h"));

        Booking b = bookingService.book("book-optional", SLOT_09, "Sam", "sam@example.com", Map.of());

        assertEquals(BookingStatus.CONFIRMED, b.status);
    }

    @Test
    @TestTransaction
    void requiredCustomFieldMissingThrowsValidation() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-missing");
        // Per-type required field: formFor(t.id) now returns this override (not the global form).
        requiredField(t.id, "company", "Company");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // answers lacks "company" -> 422-mapped validation failure, before any Google call.
        assertThrows(BookingValidationException.class, () ->
                bookingService.book("book-required-missing", SLOT_09, "Sam", "sam@example.com", Map.of()));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldBlankThrowsValidation() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-blank");
        requiredField(t.id, "company", "Company");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // Present but blank value is rejected just like a missing key.
        assertThrows(BookingValidationException.class, () ->
                bookingService.book("book-required-blank", SLOT_09, "Sam", "sam@example.com",
                        Map.of("company", "   ")));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldPresentPersistsAndStoresAnswers() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-ok");
        requiredField(t.id, "company", "Company");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-ans", "https://meet.google.com/ans-1-2", "h"));

        Booking b = bookingService.book("book-required-ok", SLOT_09, "Sam", "sam@example.com",
                Map.of("company", "Acme", "note", "extra-key-kept"));

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
        meetingTypeWithMondayWindow("book-double");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-1", "https://meet.google.com/a-b-c", "h"));

        bookingService.book("book-double", SLOT_09, "First", "first@example.com", Map.of());

        // Second attempt on the now-taken slot is rejected (the persisted booking is busy).
        // The app-level re-check catches it here; the DB exclusion constraint is the
        // cross-replica backstop documented in Task 1 / the behavior contract.
        assertThrows(BookingConflictException.class,
                () -> bookingService.book("book-double", SLOT_09, "Second", "second@example.com", Map.of()));
    }

    @Test
    @TestTransaction
    void bookingAtUnavailableStartThrowsConflict() {
        seedSettings();
        meetingTypeWithMondayWindow("book-bad-start");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // 09:13 is not a generated slot start.
        assertThrows(BookingConflictException.class, () ->
                bookingService.book("book-bad-start",
                        Instant.parse("2026-06-08T07:13:00Z"), "X", "x@example.com", Map.of()));
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

    private MeetingType meetingTypeWithMondayWindow(String slug) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
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

- [ ] **Step 3: Run it to confirm it fails**

Run: `mvn test -Dtest=BookServiceTest`
Expected: FAIL — compilation error, `BookingService.book` does not exist.

- [ ] **Step 4: Add `book` to `BookingService`**

Add these imports to `BookingService.java`:

```java
import com.calit.booking.events.BookingConfirmed;
import com.calit.domain.BookingField;
import com.calit.google.CreatedEvent;
import jakarta.enterprise.event.Event;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import java.time.LocalDate;
import java.util.Map;
```

Add the injected event emitter field (next to the existing `@Inject` fields):

```java
    @Inject
    Event<BookingConfirmed> bookingConfirmedEvent;
```

Add the method (note the new `answers` parameter — feature 10):

```java
    @Transactional
    public Booking book(String meetingTypeSlug, Instant startUtc,
                        String inviteeName, String inviteeEmail,
                        Map<String, String> answers) {
        MeetingType type = MeetingType.findBySlug(meetingTypeSlug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug);
        }
        Map<String, String> submitted = answers == null ? Map.of() : answers;

        // Feature 10: every required custom field must have a non-blank value. Built-in
        // name/email are method params, not BookingField rows, so they are not in this loop.
        validateRequiredFields(type, submitted);

        Instant endUtc = startUtc.plusSeconds(60L * type.durationMinutes);

        // App-level availability re-check: nice errors + buffer enforcement (the DB
        // constraint only guards raw-time overlap, not buffers).
        assertSlotAvailable(type, startUtc, null);

        OwnerSettings owner = OwnerSettings.get();

        Booking booking = new Booking();
        booking.meetingTypeId = type.id;
        booking.inviteeName = inviteeName;
        booking.inviteeEmail = inviteeEmail;
        booking.startUtc = startUtc;
        booking.endUtc = endUtc;
        booking.status = BookingStatus.CONFIRMED;
        booking.createdAt = Instant.now();
        booking.answers = submitted;

        // NFR cross-node guard: persist + flush now so a concurrent replica's overlapping
        // CONFIRMED row trips the `booking_no_overlap_confirmed` exclusion constraint here,
        // and is surfaced as the same 409 the app-level check uses (instead of a 500).
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + startUtc + " is not available for " + type.slug);
            }
            throw ex;
        }

        // If createEvent throws, the @Transactional boundary rolls back this booking (no orphan row).
        CreatedEvent created = calendarPort.createEvent(
                type.name + " with " + inviteeName,
                "Booked via calit.",
                startUtc, endUtc,
                List.of(inviteeEmail, owner.ownerEmail));
        booking.googleEventId = created.googleEventId();
        booking.meetLink = created.meetLink();

        bookingConfirmedEvent.fire(new BookingConfirmed(booking.id));
        return booking;
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
                    && "booking_no_overlap_confirmed".equals(cve.getConstraintName())) {
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
Expected: PASS (all 8 tests): happy path, optional-description-omitted, required-missing → validation, required-blank → validation, required-present persists + stores answers, double-book → conflict, bad-start → conflict.

> **On testing the DB exclusion constraint:** a single-threaded `@QuarkusTest` cannot deterministically reproduce the two-replicas-race that the `booking_no_overlap_confirmed` constraint exists for (the app-level re-check in `assertSlotAvailable` always fires first in-process). We therefore do NOT fake a passing concurrency test. The app-level 409 path is proven by `doubleBookOnSameSlotThrowsConflict`; the cross-node guarantee is provided by the DB constraint (Task 1) plus the `isNoOverlapViolation` catch, which is exercised structurally and documented as the source of truth under N replicas.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/booking/BookingConflictException.java \
  src/main/java/com/calit/booking/BookingValidationException.java \
  src/main/java/com/calit/booking/BookingService.java \
  src/test/java/com/calit/booking/BookServiceTest.java
git commit -m "feat: BookingService.book with required-field validation, answers, double-book + cross-node guard"
```

---

### Task 7: BookingService.reschedule + cancel

**Files:**
- Modify: `src/main/java/com/calit/booking/BookingService.java`
- Test: `src/test/java/com/calit/booking/RescheduleCancelTest.java`

**Behavior contract:**
- `reschedule(Long bookingId, Instant newStartUtc)`:
  1. Load booking; if null or CANCELLED → `NotFoundException`.
  2. Resolve its `MeetingType` by id; compute `newEnd = newStartUtc + durationMinutes`.
  3. Assert `newStartUtc` is an available slot start **excluding this booking** from the busy set (so it can move within its own window). Else → `BookingConflictException`.
  4. Capture `oldStart = booking.startUtc`; set `startUtc = newStartUtc`, `endUtc = newEnd`.
  5. **NFR cross-node guard:** flush the UPDATE so the `booking_no_overlap_confirmed` exclusion constraint is re-evaluated against the new time range — moving this booking onto another replica's freshly-CONFIRMED time trips the constraint, which we catch and surface as `BookingConflictException` (409), same as `book`.
  6. `calendarPort.updateEvent(googleEventId, newStartUtc, newEnd)`.
  7. Fire `BookingRescheduled(bookingId, oldStart)`; return booking.
- `cancel(Long bookingId)`:
  1. Load booking; if null → `NotFoundException`. (Idempotent on already-CANCELLED is fine; we still call delete + fire.)
  2. Set `status = CANCELLED`. This drops the row from the `WHERE (status = 'CONFIRMED')` partial exclusion constraint, freeing the raw-time slot for a new booking (at both the app level and the DB level).
  3. `calendarPort.deleteEvent(googleEventId)`.
  4. Fire `BookingCancelled(bookingId)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/booking/RescheduleCancelTest.java`:

```java
package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.AvailabilityRule;
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
import static org.mockito.ArgumentMatchers.anyString;
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
    void rescheduleMovesBookingCallsUpdateAndFreesOldTime() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("resched");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-r", "https://meet.google.com/r-r-r", "h"));

        Booking b = bookingService.book("resched", SLOT_09, "Sam", "sam@example.com", Map.of());

        bookingService.reschedule(b.id, SLOT_10);

        Booking loaded = Booking.findById(b.id);
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
    void cancelFlipsStatusCallsDeleteAndFreesSlot() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("cancel");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-c", "https://meet.google.com/c-c-c", "h"));

        Booking b = bookingService.book("cancel", SLOT_09, "Sam", "sam@example.com", Map.of());
        assertTrue(bookingService.availableSlots(t, MONDAY, MONDAY).stream()
                .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));

        bookingService.cancel(b.id);

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

    private MeetingType meetingTypeWithMondayWindow(String slug) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
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

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=RescheduleCancelTest`
Expected: FAIL — compilation error, `BookingService.reschedule` / `cancel` do not exist.

- [ ] **Step 3: Add `reschedule` and `cancel` to `BookingService`**

Add these imports to `BookingService.java`:

```java
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingRescheduled;
```

Add the injected event emitters (next to the existing event field):

```java
    @Inject
    Event<BookingRescheduled> bookingRescheduledEvent;

    @Inject
    Event<BookingCancelled> bookingCancelledEvent;
```

Add the methods:

```java
    @Transactional
    public Booking reschedule(Long bookingId, Instant newStartUtc) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null || booking.status == BookingStatus.CANCELLED) {
            throw new NotFoundException("No active booking " + bookingId);
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        Instant newEnd = newStartUtc.plusSeconds(60L * type.durationMinutes);

        // Exclude this booking so it may move freely within its own window.
        assertSlotAvailable(type, newStartUtc, bookingId);

        Instant oldStart = booking.startUtc;
        booking.startUtc = newStartUtc;
        booking.endUtc = newEnd;

        // NFR cross-node guard: flush so the no-overlap exclusion constraint is checked against
        // the new range; a concurrent overlap is surfaced as the same 409 as a double-book.
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + newStartUtc + " is not available for booking " + bookingId);
            }
            throw ex;
        }

        calendarPort.updateEvent(booking.googleEventId, newStartUtc, newEnd);

        bookingRescheduledEvent.fire(new BookingRescheduled(bookingId, oldStart));
        return booking;
    }

    @Transactional
    public void cancel(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            throw new NotFoundException("No booking " + bookingId);
        }
        booking.status = BookingStatus.CANCELLED;
        calendarPort.deleteEvent(booking.googleEventId);
        bookingCancelledEvent.fire(new BookingCancelled(bookingId));
    }
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=RescheduleCancelTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/BookingService.java \
  src/test/java/com/calit/booking/RescheduleCancelTest.java
git commit -m "feat: BookingService.reschedule and cancel with Google + events"
```

---

### Task 8: REST API + exception mappers

**Files:**
- Create: `src/main/java/com/calit/booking/BookingResource.java`
- Create: `src/main/java/com/calit/booking/BookingConflictMapper.java`
- Create: `src/main/java/com/calit/booking/BookingValidationMapper.java`
- Test: `src/test/java/com/calit/booking/BookingResourceTest.java`

**Endpoints (`@Path("/api")`):**
- `GET /api/meeting-types/{slug}/available?from=&to=` → `List<TimeSlot>` via `availableSlots`. 404 if slug unknown.
- `POST /api/bookings` (body: `slug`, `startUtc` ISO-8601, `inviteeName`, `inviteeEmail`, `answers` object — map of `fieldKey`→value for the custom fields, feature 10) → `201` + `Booking`.
- `POST /api/bookings/{id}/reschedule` (body: `newStartUtc` ISO) → `200` + `Booking`.
- `DELETE /api/bookings/{id}` → `204`.

- `BookingConflictException` → HTTP `409` via a `@Provider` mapper (slot taken / race).
- `BookingValidationException` → HTTP `422` via a `@Provider` mapper (missing/blank required custom field).

- [ ] **Step 1: Write the failing RestAssured test**

`src/test/java/com/calit/booking/BookingResourceTest.java`:

```java
package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
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
import static org.mockito.ArgumentMatchers.any;
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
    void bookingHappyPathReturns201WithMeetLink() {
        String slug = "rest-book-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-rest", "https://meet.google.com/rest-1234-xyz", "h"));

        given().contentType("application/json")
                .body("{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                        + "\"answers\":{\"description\":\"Quarterly sync\"}}")
                .when().post("/api/bookings")
                .then().statusCode(201)
                .body("meetLink", is("https://meet.google.com/rest-1234-xyz"))
                .body("status", is("CONFIRMED"))
                .body("answers.description", is("Quarterly sync"));
    }

    @Test
    void missingRequiredCustomFieldReturns422() {
        String slug = "rest-422-" + System.nanoTime();
        seedTypeWithRequiredField(slug, "company");
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        // Body omits the required "company" answer -> 422 (not 409: input is wrong, slot is fine).
        given().contentType("application/json")
                .body("{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                        + "\"answers\":{}}")
                .when().post("/api/bookings")
                .then().statusCode(422)
                .body(containsString("company"));
    }

    @Test
    void availableEndpointReturnsSlots() {
        String slug = "rest-avail-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        given().when().get("/api/meeting-types/" + slug + "/available?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200).body("size()", is(2));
    }

    @Test
    void doubleBookReturns409() {
        String slug = "rest-conflict-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreatedEvent("evt-x", "https://meet.google.com/a-b-c", "h"));

        String body = "{\"slug\":\"" + slug + "\",\"startUtc\":\"2026-06-08T07:00:00Z\","
                + "\"inviteeName\":\"First\",\"inviteeEmail\":\"first@example.com\"}";
        given().contentType("application/json").body(body)
                .when().post("/api/bookings").then().statusCode(201);

        given().contentType("application/json").body(body)
                .when().post("/api/bookings").then().statusCode(409)
                .body(containsString("not available"));
    }

    @Transactional
    void seedType(String slug) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
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

> **Note:** this test writes to the shared Dev Services DB (not rolled back). Unique `slug`s per run keep it self-contained; the Monday window yields exactly two 60-min slots over `2026-06-08`.

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
                              Map<String, String> answers) {}

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
        Booking b = bookingService.book(req.slug(), Instant.parse(req.startUtc()),
                req.inviteeName(), req.inviteeEmail(), req.answers());
        return Response.status(Response.Status.CREATED).entity(b).build();
    }

    @POST
    @Path("/bookings/{id}/reschedule")
    public Booking reschedule(@PathParam("id") Long id, RescheduleRequest req) {
        return bookingService.reschedule(id, Instant.parse(req.newStartUtc()));
    }

    @DELETE
    @Path("/bookings/{id}")
    public Response cancel(@PathParam("id") Long id) {
        bookingService.cancel(id);
        return Response.noContent().build();
    }
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=BookingResourceTest`
Expected: PASS (all 4 tests): happy path (201, stores `answers`), available list, double-book (409), missing-required-field (422).

- [ ] **Step 6: Run the full suite**

Run: `mvn test`
Expected: PASS — all Plan 1, Plan 2, and Plan 3 tests (Booking, Interval, AvailableSlots, BookService, RescheduleCancel, BookingResource).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/booking/BookingResource.java \
  src/main/java/com/calit/booking/BookingConflictMapper.java \
  src/main/java/com/calit/booking/BookingValidationMapper.java \
  src/test/java/com/calit/booking/BookingResourceTest.java
git commit -m "feat: booking REST API (available/book/reschedule/cancel) with 409 + 422 mappers and answers body"
```

---

## Self-Review against spec

**1. Spec coverage (Plan 3 scope):**

| Requirement | Task |
|---|---|
| Booking a slot (feat 1) | Task 6 (`book`: persist + Google event + Meet link), Task 8 (`POST /api/bookings`) |
| Custom booking fields — answers + required validation (feat 10) | Task 1 (`answers` JSONB column), Task 2 (`Booking.answers` field, round-trip test), Task 6 (`book` accepts `answers`, validates required `BookingField.formFor` entries → 422, stores answers; tests `requiredCustomFieldMissing/Blank/Present`, `optionalDescriptionMayBeOmitted`), Task 8 (`answers` in body + `BookingValidationMapper` 422; test `missingRequiredCustomFieldReturns422`). Built-in name/email are dedicated columns, not BookingField rows |
| Rescheduling (feat 5) | Task 7 (`reschedule`: move, `updateEvent`, frees old time, DB-constraint re-check), Task 8 (`POST /api/bookings/{id}/reschedule`) |
| Min buffer enforcement (feat 6) | Task 3 (`Interval.overlaps`), Task 5 (buffered interval subtraction) — proven by `busyInsideBufferZoneRemovesAdjacentSlot`. Buffers stay an app-level policy (the DB constraint only guards raw-time overlap) |
| Double-book prevention | Task 6 app-level (`assertSlotAvailable` re-check against live busy set) + Task 8 (409 mapper); tests `doubleBookOnSameSlotThrowsConflict` / `doubleBookReturns409` |
| Cross-node double-book guard (NFR) | Task 1 (`booking_no_overlap_confirmed` GiST exclusion constraint, partial on CONFIRMED), Task 6/7 (`persistAndFlush` + `isNoOverlapViolation` catch → same 409). The INSERT/UPDATE is the source of truth under N replicas; app-level check can't be trusted alone |
| Cancel | Task 7 (`cancel`: status flip, `deleteEvent`, frees slot — partial-index `WHERE status='CONFIRMED'` drops the row), Task 8 (`DELETE /api/bookings/{id}`) |
| Busy = Google + DB bookings (single owner, global) | Task 5 (`busyIntervals` merges `freeBusy` + `confirmedOverlapping`) |

Google is always behind the mocked `CalendarPort` seam (`@InjectMock`) in tests — no real Google call.

**NFR — horizontal scalability (booking safety):** This plan satisfies the overview's cross-node booking-safety requirement. App-level checks across replicas can both pass simultaneously; the Postgres exclusion constraint `booking_no_overlap_confirmed` (Task 1) makes the final INSERT/UPDATE the source of truth, so no two CONFIRMED bookings can hold overlapping raw time regardless of replica count. The constraint violation is caught (`isNoOverlapViolation`) and surfaced as the same 409 as an app-level double-book, so callers see one consistent error. A single-threaded `@QuarkusTest` cannot deterministically race two replicas, so we do NOT fake a passing concurrency test — the app-level 409 path is tested directly and the cross-node guarantee is the documented DB constraint + catch.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to" placeholders. Every step shows full code and exact `mvn test -Dtest=...` commands with expected FAIL/PASS.

**3. Type-consistency — public contract this plan exposes (Plans 4 & 5 depend on these EXACT signatures):**

- Entity `com.calit.booking.Booking` (extends `PanacheEntityBase`) — fields: `Long id`, `Long meetingTypeId`, `String inviteeName`, `String inviteeEmail`, `Instant startUtc`, `Instant endUtc`, `String googleEventId`, `String meetLink`, `BookingStatus status` (enum `CONFIRMED`/`CANCELLED`, `EnumType.STRING`), `Instant createdAt`, **`Map<String,String> answers`** (feature 10: custom `BookingField.fieldKey`→value, JSONB column via `@JdbcTypeCode(SqlTypes.JSON)`). ✔ matches Task 2 exactly. Plans 4 (answers in emails) & 5 (read back submitted answers) depend on `Booking.answers`.
- Service `com.calit.booking.BookingService` (`@ApplicationScoped`):
  - `List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to)` ✔
  - `Booking book(String meetingTypeSlug, Instant startUtc, String inviteeName, String inviteeEmail, Map<String,String> answers)` ✔ (feature 10 `answers` param added; validates required fields → 422, stores answers; null answers treated as empty)
  - `Booking reschedule(Long bookingId, Instant newStartUtc)` ✔
  - `void cancel(Long bookingId)` ✔
  (Plus the extra package-visible 4-arg `availableSlots(..., Long excludeBookingId)` overload used by reschedule — additive, does not change the public contract.)
- Exceptions: `BookingConflictException` → 409 (`BookingConflictMapper`), `BookingValidationException` → 422 (`BookingValidationMapper`). 422 = bad/missing required custom-field input; 409 = slot lost to a double-book/race (app-level OR the DB `booking_no_overlap_confirmed` constraint). ✔
- CDI events in `com.calit.booking.events`, fired via `jakarta.enterprise.event.Event`:
  - `BookingConfirmed(Long bookingId)` — fired after persist + successful `createEvent` (Task 6) ✔
  - `BookingRescheduled(Long bookingId, Instant oldStartUtc)` — fired after `updateEvent` (Task 7) ✔
  - `BookingCancelled(Long bookingId)` — fired after `deleteEvent` (Task 7) ✔
  Plan 4 attaches `@Observes` methods to these; fire timing (post-Google) is correct for email side effects.

**4. Consumed Plan 1/2 signatures used verbatim:**
- Plan 1: `slotService.generateRawSlots(type, from, to)` → `List<TimeSlot>`; `TimeSlot.start()/.end()` (`ZonedDateTime`); `MeetingType.durationMinutes/bufferBeforeMinutes/bufferAfterMinutes/findBySlug/id/name/slug`; `OwnerSettings.get()/timezone/ownerEmail`; `BookingField.formFor(Long)` → `List<BookingField>`, `BookingField.fieldKey/required/FieldType` (feature-10 required-answer validation in `book`). ✔
- Plan 2 (`com.calit.google`): `CalendarPort.freeBusy(Instant, Instant)`, `createEvent(String, String, Instant, Instant, List<String>)`, `updateEvent(String, Instant, Instant)`, `deleteEvent(String)`; `BusyInterval(Instant start, Instant end)` (accessed via `.start()/.end()`); `CreatedEvent(String googleEventId, String meetLink, String htmlLink)` (accessed via `.googleEventId()/.meetLink()`). ✔ All used exactly as declared; tests mock the port with `@InjectMock`.

**Known assumptions (carried forward):** owner-tz day boundaries are computed via `atStartOfDay`/`plusDays(1)` (DST-safe through `ZonedDateTime`); a booking blocks the whole calendar regardless of meeting type (single owner); slot-start equality booking guard requires the requested `startUtc` to match a generated slot start exactly; required-field validation only covers `BookingField` rows for the resolved per-type/global form (built-in name/email are enforced by being non-null method params, and unknown extra answer keys are stored verbatim); the DB exclusion constraint guarantees only no raw-time overlap of CONFIRMED rows — buffers remain an app-level policy enforced in `availableSlots`.
