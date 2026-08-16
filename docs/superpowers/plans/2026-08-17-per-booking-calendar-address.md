# Per-booking Google calendar address (calit-rma2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record on each booking which Google calendar (and which connected account) its event was created on, and address every later update/delete by that stored calendar instead of whatever the owner's write target happens to be now.

**Architecture:** Two new nullable columns on `booking` (`google_calendar_id text`, `google_credential_id bigint`) carry the address. A new `CalendarRef(credentialId, googleCalendarId)` record is threaded through `CalendarPort`'s write methods; a null ref means "resolve the owner's default write target", which is exactly today's behaviour and what every pre-migration booking gets. `CreatedEvent` reports the address the event was created on so `BookingService` can persist it.

**Tech Stack:** Quarkus 3.38 / Java 25, Panache entities, Flyway migrations, JUnit 5 + RestAssured + Mockito (`@InjectMock`), Maven Surefire with `reuseForks=true`.

## Global Constraints

- Build JDK: run every Maven command with `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` first — the default `java` on this machine is 21 and the build fails with "release 25 not supported".
- Docker must be running: Quarkus Dev Services provisions the test Postgres. No embedded fallback.
- Never edit an applied Flyway migration. New `V*.sql` only. Latest applied is `V25`; this plan adds `V26`.
- Hibernate runs `schema-management.strategy=validate` — an entity field whose column type doesn't match the migration fails at boot. `String` defaults to `varchar(255)`, so a `text` column needs `@Column(columnDefinition = "text")`.
- No backfill of existing bookings. NULL means "address unknown, resolve as before" — stamping old rows with the current write target would be confidently wrong for exactly the bookings this bug affects.
- Owner scoping: any query added here filters by the owner it belongs to. A credential is only usable for `ownerId` when `ownerId.equals(cred.ownerId)`.
- No new user-facing strings in this change, so no `de`/`he` translation work. If you add one anyway, it needs both.
- Formatting gate: `mvn spotless:apply` before each commit (the lefthook pre-commit hook does this for staged `*.java`, but run it if you commit outside the hook).
- Tests are `@QuarkusTest` against the shared fork; `DatabaseResetCallback` truncates + reseeds per test, and the admin user is always id 1.

---

### Task 1: Schema + entity + CalendarRef

**Files:**
- Create: `src/main/resources/db/migration/V26__booking_calendar_address.sql`
- Create: `src/main/java/site/asm0dey/calit/google/CalendarRef.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java:38-41` (next to `googleEventId`)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `site.asm0dey.calit.google.CalendarRef` — `record CalendarRef(Long credentialId, String googleCalendarId)`; `Booking.googleCalendarId` (`String`), `Booking.googleCredentialId` (`Long`), `Booking.calendarRef()` returning `CalendarRef` or `null`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

/** The event address columns round-trip, and a row without one reports a null ref. */
@QuarkusTest
class BookingCalendarAddressTest {

    @Test
    @TestTransaction
    void storesAndReadsBackTheEventAddress() {
        Booking b = seed();
        b.googleEventId = "evt-1";
        b.googleCalendarId = "work@example.com";
        b.googleCredentialId = 42L;
        b.persistAndFlush();

        Booking loaded = Booking.findById(b.id);
        assertEquals("work@example.com", loaded.calendarRef().googleCalendarId());
        assertEquals(42L, loaded.calendarRef().credentialId());
    }

    @Test
    @TestTransaction
    void preMigrationRowHasNoRef() {
        Booking b = seed();
        b.persistAndFlush();

        assertNull(Booking.<Booking>findById(b.id).calendarRef());
    }

    /** Minimal valid booking row for owner 1 (the always-present admin), on a type it also owns. */
    private static Booking seed() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "address-seed";
        t.slug = "address-seed-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.persist();

        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = t.id;
        b.inviteeName = "Ada";
        b.inviteeEmail = "ada@example.com";
        b.startUtc = Instant.parse("2026-09-01T10:00:00Z");
        b.endUtc = Instant.parse("2026-09-01T10:30:00Z");
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        return b;
    }
}
```

If `Booking`'s required fields differ from the list above, open `src/main/java/site/asm0dey/calit/booking/Booking.java` and set every `nullable = false` field — do not relax the entity to fit the test. `MeetingType.slug` is unique per owner, hence the UUID suffix.

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=BookingCalendarAddressTest
```

Expected: compilation failure — `cannot find symbol: googleCalendarId` / `calendarRef()`.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V26__booking_calendar_address.sql`:

```sql
-- calit-rma2: record WHERE a booking's Google event lives, so later update/delete address that
-- calendar instead of whatever the owner's write target is at the time of the call.
--
-- Both columns are nullable and deliberately NOT backfilled: NULL means "address unknown, resolve
-- the owner's default write target" (exactly the pre-1.21 behaviour). Stamping existing rows with
-- the current write target would be wrong for precisely the bookings this fixes.
--
-- google_calendar_id stores GOOGLE's calendar id (an email or opaque id), not google_calendar.id:
-- CalendarSelectionService.save() deletes and re-inserts every local row on each settings save, so
-- local ids churn. The credential is kept because a calendar id alone carries no OAuth token.
ALTER TABLE booking
    ADD COLUMN google_calendar_id   text,
    ADD COLUMN google_credential_id bigint REFERENCES google_credential (id) ON DELETE SET NULL;
```

- [ ] **Step 4: Add the CalendarRef record**

Create `src/main/java/site/asm0dey/calit/google/CalendarRef.java`:

```java
package site.asm0dey.calit.google;

/**
 * Where a Google event lives: the connected account it was created with plus Google's own calendar
 * id. A null ref — or a ref with a null calendar id — means "resolve the owner's default write
 * target", which is what every write did before calit-rma2 and what pre-V26 bookings still get.
 *
 * @param credentialId     {@link GoogleCredential#id}; null once that account is disconnected
 * @param googleCalendarId Google's calendar id, as stored on {@link GoogleCalendar#googleCalendarId}
 */
public record CalendarRef(Long credentialId, String googleCalendarId) {}
```

- [ ] **Step 5: Add the entity fields**

In `src/main/java/site/asm0dey/calit/booking/Booking.java`, directly below the `googleEventId` field, add:

```java
    /** Google's calendar id for {@link #googleEventId}; null on rows created before V26. */
    @Column(name = "google_calendar_id", columnDefinition = "text")
    public String googleCalendarId;

    /** The connected account the event was created with; nulled when that account is disconnected. */
    @Column(name = "google_credential_id")
    public Long googleCredentialId;
```

and, next to the other instance methods on the class, add:

```java
    /** This row's Google event address, or null when unknown (pre-V26 rows) — see {@link CalendarRef}. */
    public CalendarRef calendarRef() {
        return googleCalendarId == null ? null : new CalendarRef(googleCredentialId, googleCalendarId);
    }
```

Add `import site.asm0dey.calit.google.CalendarRef;` to the imports.

- [ ] **Step 6: Run the test to verify it passes**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=BookingCalendarAddressTest
```

Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply
git add src/main/resources/db/migration/V26__booking_calendar_address.sql \
        src/main/java/site/asm0dey/calit/google/CalendarRef.java \
        src/main/java/site/asm0dey/calit/booking/Booking.java \
        src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java
git commit -m "feat(booking): store the Google calendar address of a booking's event"
```

---

### Task 2: CalendarPort takes a CalendarRef

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/CalendarPort.java:41-58` (the three write method signatures)
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java:226-330` (`updateEvent`, `updateEventDetails`, `deleteEvent`, plus a new `writeAddress` helper)
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (call sites at lines 840, 846, 972, 1024, 1175, 1192, 1242 — pass `null` for now)
- Test: `src/test/java/site/asm0dey/calit/google/StoredCalendarAddressTest.java`

**Interfaces:**
- Consumes: `CalendarRef` from Task 1.
- Produces: `CalendarPort.updateEvent(Long ownerId, CalendarRef ref, String eventId, Instant start, Instant end, List<String> attendeeEmails)`, `CalendarPort.updateEventDetails(Long ownerId, CalendarRef ref, String eventId, String summary, String description, List<String> attendeeEmails)`, `CalendarPort.deleteEvent(Long ownerId, CalendarRef ref, String eventId)`. A null `ref` resolves the owner's default write target.

Behaviour is deliberately unchanged at the end of this task: every caller still passes `null`. Tasks 3–4 make callers pass real refs.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/StoredCalendarAddressTest.java`. It hand-builds the port with mocked collaborators — the same shape as `DeleteEventAlreadyGoneTest`, whose Javadoc explains why the test method carries `@Transactional`:

```java
package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.calendar.Calendar;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * deleteEvent addresses the calendar it is given, not whatever the owner's write target is now.
 * A null ref (pre-V26 booking) still falls back to the write target.
 */
@QuarkusTest
class StoredCalendarAddressTest {

    private Calendar.Events events;

    @Test
    @Transactional
    void deletesOnTheStoredCalendar() throws IOException {
        Long credId = seedWriteTarget("sub-stored", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(credId, "old-work@example.com"), "evt-1");

        verify(events).delete("old-work@example.com", "evt-1");
    }

    @Test
    @Transactional
    void nullRefFallsBackToTheWriteTarget() throws IOException {
        seedWriteTarget("sub-null", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, null, "evt-2");

        verify(events).delete("default@example.com", "evt-2");
    }

    @Test
    @Transactional
    void refOfAnotherOwnersCredentialFallsBackToTheWriteTarget() throws IOException {
        seedWriteTarget("sub-foreign", "default@example.com");
        GoogleCredential foreign = new GoogleCredential();
        foreign.ownerId = 999L;
        foreign.refreshToken = "rt";
        foreign.googleSub = "sub-999";
        foreign.persist();
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(foreign.id, "someone-else@example.com"), "evt-3");

        verify(events).delete("default@example.com", "evt-3");
    }

    /** A port whose events.delete(...).execute() succeeds, capturing the calendar id it was called with. */
    private GoogleCalendarPort port() throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Delete delete = mock(Calendar.Events.Delete.class);
        when(delete.setSendUpdates(anyString())).thenReturn(delete);
        events = mock(Calendar.Events.class);
        when(events.delete(anyString(), anyString())).thenReturn(delete);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
    }

    /** Owner 1 gets one connected account and one default write-target calendar. Returns the credential id. */
    private static Long seedWriteTarget(String sub, String calendarId) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        GoogleCalendar wt = new GoogleCalendar();
        wt.ownerId = 1L;
        wt.googleCredentialId = c.id;
        wt.googleCalendarId = calendarId;
        wt.summary = "Default";
        wt.writeTarget = true;
        wt.persist();
        return c.id;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=StoredCalendarAddressTest
```

Expected: compilation failure — `deleteEvent(Long, CalendarRef, String)` does not exist.

- [ ] **Step 3: Change the port interface**

In `src/main/java/site/asm0dey/calit/google/CalendarPort.java`, replace the three write method declarations with:

```java
    /**
     * Move an existing event to a new time window and replace its attendee list (reschedule / guest
     * sync); {@code sendUpdates=all}. A null or empty attendee list leaves attendees unchanged.
     *
     * @param ref where the event lives; null resolves the owner's default write target (pre-V26 rows)
     */
    void updateEvent(
            Long ownerId, CalendarRef ref, String eventId, Instant start, Instant end, List<String> attendeeEmails);

    /**
     * Patch an existing event's summary + description (and re-sync attendees), leaving its time
     * untouched; {@code sendUpdates=all} so Google re-notifies everyone. Used when the host/invitee
     * edits the meeting's name, description, or guest list. A null or empty attendee list leaves
     * attendees unchanged.
     *
     * @param ref where the event lives; null resolves the owner's default write target (pre-V26 rows)
     */
    void updateEventDetails(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            String summary,
            String description,
            List<String> attendeeEmails);

    /**
     * Remove an existing event (cancel); {@code sendUpdates=all}. This operation is idempotent: an
     * event that is already gone on the provider's side counts as success.
     *
     * @param ref where the event lives; null resolves the owner's default write target (pre-V26 rows)
     */
    void deleteEvent(Long ownerId, CalendarRef ref, String eventId);
```

- [ ] **Step 4: Resolve the address in GoogleCalendarPort**

In `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java`, add next to the existing `writeContext` helper:

```java
    /** A calendar id to write on plus the credential that authenticates it. */
    private record WriteAddress(String calendarId, GoogleCredential cred, boolean stored) {}

    /**
     * The address to write at: the stored one when it is usable, else the owner's default write
     * target. A stored ref is unusable when the account was disconnected (credential id nulled by
     * the FK, or the row is gone) or when it belongs to another owner — both degrade to the
     * pre-calit-rma2 behaviour rather than failing the call.
     */
    private WriteAddress writeAddress(Long ownerId, CalendarRef ref) {
        if (ref != null && ref.googleCalendarId() != null && ref.credentialId() != null) {
            GoogleCredential cred = GoogleCredential.findById(ref.credentialId());
            if (cred != null && ownerId.equals(cred.ownerId)) {
                return new WriteAddress(ref.googleCalendarId(), cred, true);
            }
        }
        var ctx = writeContext(ownerId);
        return new WriteAddress(ctx.target().googleCalendarId, ctx.cred(), false);
    }
```

Then rewrite the three write methods to use it. `updateEvent`:

```java
    @Override
    @Transactional
    public void updateEvent(
            Long ownerId, CalendarRef ref, String eventId, Instant start, Instant end, List<String> attendeeEmails) {
        var addr = writeAddress(ownerId, ref);
        Event patch = new Event().setStart(eventTime(ownerId, start)).setEnd(eventTime(ownerId, end));
        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            patch.setAttendees(attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }
        try {
            // sendUpdates=all so Google emails attendees the new time and notifies anyone added/removed.
            client(addr.cred())
                    .events()
                    .patch(addr.calendarId(), eventId, patch)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEvent failed", e);
        }
    }
```

`updateEventDetails`:

```java
    @Override
    @Transactional
    public void updateEventDetails(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            String summary,
            String description,
            List<String> attendeeEmails) {
        var addr = writeAddress(ownerId, ref);
        Event patch = new Event().setSummary(summary).setDescription(description);
        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            patch.setAttendees(attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }
        try {
            client(addr.cred())
                    .events()
                    .patch(addr.calendarId(), eventId, patch)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEventDetails failed", e);
        }
    }
```

`deleteEvent` — same 410/404 tolerance, but the log line now says whether the address came from the booking or from the fallback, which is the evidence needed to tell "hand-deleted" from "we looked on the wrong calendar":

```java
    @Override
    @Transactional
    public void deleteEvent(Long ownerId, CalendarRef ref, String eventId) {
        var addr = writeAddress(ownerId, ref);
        try {
            // sendUpdates=all so Google emails the attendees the cancellation.
            client(addr.cred())
                    .events()
                    .delete(addr.calendarId(), eventId)
                    .setSendUpdates("all")
                    .execute();
        } catch (GoogleJsonResponseException e) {
            // 410 Gone / 404 Not Found: the event was already deleted on Google (e.g. by the owner,
            // directly in Google Calendar). The end state we wanted already holds, so deleting is
            // idempotent from the caller's side — let the local cancellation proceed. Every other
            // status still fails loudly.
            if (e.getStatusCode() != 410 && e.getStatusCode() != 404) {
                throw new UncheckedIOException("deleteEvent failed", e);
            }
            org.jboss.logging.Logger.getLogger(GoogleCalendarPort.class)
                    .infof(
                            "Google event %s on calendar %s (owner %d, address %s) was already deleted (HTTP %d); treating delete as done",
                            eventId,
                            addr.calendarId(),
                            ownerId,
                            addr.stored() ? "stored" : "default-write-target",
                            e.getStatusCode());
        } catch (IOException e) {
            throw new UncheckedIOException("deleteEvent failed", e);
        }
    }
```

- [ ] **Step 5: Pass null at every existing call site**

In `src/main/java/site/asm0dey/calit/booking/BookingService.java`, add `null` as the second argument at each of these calls — no other change, behaviour stays identical:

- line ~840 `calendarPort.deleteEvent(type.ownerId, null, priorEventId);`
- line ~846 `calendarPort.updateEvent(type.ownerId, null, booking.googleEventId, …)`
- line ~972 `calendarPort.updateEventDetails(type.ownerId, null, booking.googleEventId, …)`
- line ~1024 `calendarPort.updateEventDetails(organizer, null, eventId, …)`
- line ~1175 `calendarPort.deleteEvent(booking.ownerId, null, booking.googleEventId);`
- line ~1192 `calendarPort.deleteEvent(r.ownerId, null, r.googleEventId);`
- line ~1242 `calendarPort.updateEvent(guest.ownerId, null, booking.googleEventId, …)`

Find any you missed:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn -q compile
```

Expected: BUILD SUCCESS. Any "method cannot be applied" error names a call site still on the old signature.

- [ ] **Step 6: Fix the test call sites**

Mockito verifications and stubs of the three methods now need the extra argument. Find them:

```bash
grep -rln "deleteEvent\|updateEvent\|updateEventDetails" src/test/java
```

For each hit, add the ref argument to the matcher. Verifications that don't care which address was used take `any()`; ones asserting today's fallback take `isNull()`. Example, from `RescheduleCancelTest`:

```java
// before
verify(calendarPort).deleteEvent(eq(1L), eq("evt-1"));
// after
verify(calendarPort).deleteEvent(eq(1L), any(), eq("evt-1"));
```

Mockito requires all-or-nothing matchers: if one argument uses a matcher, every argument must.

- [ ] **Step 7: Run the new test plus the write-path suites**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest='StoredCalendarAddressTest+DeleteEventAlreadyGoneTest+RescheduleCancelTest+UpdateDetailsTest+GroupCancelRescheduleTest'
```

Expected: PASS, no failures.

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/google src/main/java/site/asm0dey/calit/booking/BookingService.java src/test/java
git commit -m "refactor(google): address event writes by an explicit CalendarRef"
```

---

### Task 3: Persist the address on create

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/CreatedEvent.java` (add the address component)
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java:107-130` (`createEvent`) and `:186-200` (`retryWithoutConference`)
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:425-437` (group create) and `:507-518` (`createGoogleEvent`)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java` (add a case)

**Interfaces:**
- Consumes: `CalendarRef`, `Booking.googleCalendarId` / `googleCredentialId` from Task 1.
- Produces: `CreatedEvent(String googleEventId, String meetLink, String htmlLink, CalendarRef calendar)` — the `calendar` component names the calendar the event was actually created on.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java` (add the imports it needs: `io.quarkus.test.InjectMock`, `jakarta.inject.Inject`, `site.asm0dey.calit.google.CalendarPort`, `site.asm0dey.calit.google.CalendarRef`, `site.asm0dey.calit.google.CreatedEvent`, `org.mockito.ArgumentMatchers.any`, `org.mockito.Mockito.when`):

```java
    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    // Owner tz Europe/Amsterdam; a slot a week out is never in the past. Mirrors BookServiceTest.
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Test
    @TestTransaction
    void bookingRecordsTheCalendarTheEventWasCreatedOn() {
        stubGoogle(new CalendarRef(7L, "work@example.com"), "evt-created");

        Booking booked = bookAnySlot("addr-created");

        Booking loaded = Booking.findById(booked.id);
        assertEquals("work@example.com", loaded.googleCalendarId);
        assertEquals(7L, loaded.googleCredentialId);
    }

    /** Google connected, no busy time, createEvent returning an event at the given address. */
    private void stubGoogle(CalendarRef address, String eventId) {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent(eventId, null, null, address));
    }

    /** Seed owner settings + a 09:00-11:00 type on DAY, then book the 09:00 slot. */
    private Booking bookAnySlot(String slug) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = MeetingType.LocationType.GOOGLE_MEET;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();

        return bookingService.book(
                1L, slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
    }
```

Imports this adds: `io.quarkus.test.InjectMock`, `jakarta.inject.Inject`, `java.time.{LocalDate, LocalTime, ZoneId}`, `java.util.{List, Map}`, `site.asm0dey.calit.domain.{AvailabilityRule, OwnerSettings}`, `site.asm0dey.calit.google.{CalendarPort, CalendarRef, CreatedEvent}`, and the Mockito statics `any`, `anyBoolean`, `anyLong`, `anyString`, `eq`, `when`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=BookingCalendarAddressTest
```

Expected: compilation failure — `CreatedEvent` takes 3 components, not 4.

- [ ] **Step 3: Add the address to CreatedEvent**

Replace `src/main/java/site/asm0dey/calit/google/CreatedEvent.java` with:

```java
package site.asm0dey.calit.google;

/**
 * Result of creating a Google Calendar event.
 *
 * @param googleEventId Google's event id (used later for update/delete)
 * @param meetLink      the Google Meet join URL (hangoutLink), or null if none was generated
 * @param htmlLink      the calendar.google.com web link to the event
 * @param calendar      where the event was created — persisted on the booking so later
 *                      update/delete address this calendar even if the owner's write target moves
 */
public record CreatedEvent(String googleEventId, String meetLink, String htmlLink, CalendarRef calendar) {}
```

- [ ] **Step 4: Report the address from the port**

In `GoogleCalendarPort.createEvent`, replace the success return with:

```java
            Event created = insert(cred, target, event, createMeetLink);
            String meetLink = createMeetLink ? extractMeetLink(created) : null;
            return new CreatedEvent(
                    created.getId(),
                    meetLink,
                    created.getHtmlLink(),
                    new CalendarRef(cred.id, target.googleCalendarId));
```

and in `retryWithoutConference`:

```java
            Event created = insert(cred, target, event, false);
            return new CreatedEvent(
                    created.getId(), null, created.getHtmlLink(), new CalendarRef(cred.id, target.googleCalendarId));
```

- [ ] **Step 5: Persist it on both create paths**

In `BookingService.createGoogleEvent` (~line 509), after the existing assignments:

```java
        booking.googleEventId = created.googleEventId();
        booking.meetLink = created.meetLink();
        booking.googleCalendarId = created.calendar() == null ? null : created.calendar().googleCalendarId();
        booking.googleCredentialId = created.calendar() == null ? null : created.calendar().credentialId();
```

In `createGroupGoogleEvent` (~line 425), the same four lines against `organizerRow` — the group's event lives on the organizer's row only:

```java
        organizerRow.googleEventId = created.googleEventId();
        organizerRow.meetLink = created.meetLink();
        organizerRow.googleCalendarId = created.calendar() == null ? null : created.calendar().googleCalendarId();
        organizerRow.googleCredentialId = created.calendar() == null ? null : created.calendar().credentialId();
```

The null guard matters: `@InjectMock` CalendarPort stubs across the existing suite return `CreatedEvent`s built without an address.

- [ ] **Step 6: Fix test constructors**

```bash
grep -rn "new CreatedEvent(" src/test/java src/main/java
```

Every test constructing a `CreatedEvent` needs a 4th argument; pass `null` where the test doesn't care about the address.

- [ ] **Step 7: Run the tests**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest='BookingCalendarAddressTest+BookServiceTest+GroupBookingWriteTest+ApproveDeclineTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply
git add src/main/java src/test/java
git commit -m "feat(booking): record which calendar each Google event was created on"
```

---

### Task 4: Address later writes by the stored ref

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` — `reschedule` (~792), `applyRescheduleOutcome` (~833-855), `updateDetails` (~970-978), the group details path (~1021-1030), `groupEventId`/`organizerOwnerOf` (~1040-1062), `cancelSingle` (~1172-1178), `deleteGroupGoogleEvent` (~1188-1198), `declineGuest` (~1240-1248)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java` (add cases)

**Interfaces:**
- Consumes: `Booking.calendarRef()` (Task 1), the `CalendarRef` parameters (Task 2), the persisted address (Task 3).
- Produces: `private Booking groupEventRow(UUID groupId)` on `BookingService` — the group row carrying the shared Google event, or null. Replaces `groupEventId` and `organizerOwnerOf`.

- [ ] **Step 1: Write the failing tests**

Append to `BookingCalendarAddressTest`:

```java
    @Test
    @TestTransaction
    void cancelDeletesOnTheStoredCalendar() {
        stubGoogle(new CalendarRef(7L, "work@example.com"), "evt-cancel");
        Booking booked = bookAnySlot("addr-cancel");

        bookingService.cancel(booked.manageToken, true);

        verify(calendarPort)
                .deleteEvent(eq(booked.ownerId), eq(new CalendarRef(7L, "work@example.com")), eq("evt-cancel"));
    }

    @Test
    @TestTransaction
    void cancelOfAPreMigrationRowPassesNoAddress() {
        stubGoogle(null, "evt-old"); // createEvent reports no address, as pre-V26 rows have none
        Booking booked = bookAnySlot("addr-old");

        bookingService.cancel(booked.manageToken, true);

        verify(calendarPort).deleteEvent(eq(booked.ownerId), isNull(), eq("evt-old"));
    }
```

`CalendarRef` is a record, so `eq(new CalendarRef(...))` compares by value. Add the Mockito statics `verify` and `isNull` to the imports.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=BookingCalendarAddressTest
```

Expected: FAIL — "Argument(s) are different", the actual invocation passing `null` where the stored ref is expected.

- [ ] **Step 3: Pass the stored ref at every write site**

Replace the `null` placeholders from Task 2:

```java
// cancelSingle (~1175)
calendarPort.deleteEvent(booking.ownerId, booking.calendarRef(), booking.googleEventId);

// deleteGroupGoogleEvent (~1192)
calendarPort.deleteEvent(r.ownerId, r.calendarRef(), r.googleEventId);

// applyRescheduleOutcome, patch branch (~846)
calendarPort.updateEvent(
        type.ownerId,
        booking.calendarRef(),
        booking.googleEventId,
        booking.startUtc,
        booking.endUtc,
        attendeeEmails(booking, owner));

// updateDetails (~972)
calendarPort.updateEventDetails(
        type.ownerId,
        booking.calendarRef(),
        booking.googleEventId,
        googleSummary(type, booking),
        googleDescription(type, booking),
        attendeeEmails(booking, owner));

// declineGuest (~1242)
calendarPort.updateEvent(
        guest.ownerId,
        booking.calendarRef(),
        booking.googleEventId,
        booking.startUtc,
        booking.endUtc,
        attendeeEmails(booking, owner));
```

- [ ] **Step 4: Carry the prior address through a re-approval reschedule**

`reschedule` clears the event before `applyRescheduleOutcome` deletes it, so the address must be captured with the id. In `reschedule` (~line 792) replace the `priorEventId` capture and the clearing block with:

```java
        String priorEventId = booking.googleEventId;
        CalendarRef priorRef = booking.calendarRef();
        if (reApproval) {
            // Feature 14: return to the approval queue; drop any existing event.
            booking.status = BookingStatus.PENDING;
            booking.googleEventId = null;
            booking.meetLink = null;
            booking.googleCalendarId = null;
            booking.googleCredentialId = null;
        }
```

Change `applyRescheduleOutcome`'s signature to take the ref alongside the id, and use it:

```java
    private void applyRescheduleOutcome(
            Booking booking,
            MeetingType type,
            Instant oldStart,
            String priorEventId,
            CalendarRef priorRef,
            boolean reApproval,
            boolean byOwner) {
        if (reApproval) {
            if (calendarPort.isConnected(type.ownerId) && priorEventId != null) {
                calendarPort.deleteEvent(type.ownerId, priorRef, priorEventId);
            }
```

and update its call site (~line 821) to `applyRescheduleOutcome(booking, type, oldStart, priorEventId, priorRef, reApproval, byOwner);`.

Do the same wherever else `googleEventId` is set back to null — `deleteGroupGoogleEvent` clears `r.googleEventId` and `r.meetLink`; add:

```java
                r.googleCalendarId = null;
                r.googleCredentialId = null;
```

An address without an event id is stale data waiting to mislead the next reader.

- [ ] **Step 5: Collapse the group helpers into one row lookup**

The group details path needs the organizer row's ref, and the two existing helpers already walk the same rows twice. Replace `groupEventId` and `organizerOwnerOf` with:

```java
    /**
     * The group row carrying the shared Google event (the organizer's — see
     * {@link #createGroupGoogleEvent}), or null when the group never had one.
     */
    private Booking groupEventRow(UUID groupId) {
        for (Booking r : Booking.<Booking>group(groupId)) {
            if (r.googleEventId != null) {
                return r;
            }
        }
        return null;
    }
```

and rewrite the call site (~1021):

```java
        Booking eventRow = groupEventRow(booking.groupId);
        if (eventRow != null && calendarPort.isConnected(eventRow.ownerId)) {
            calendarPort.updateEventDetails(
                    eventRow.ownerId,
                    eventRow.calendarRef(),
                    eventRow.googleEventId,
                    googleSummary(type, lead),
                    googleDescription(type, lead),
                    groupAttendeeEmails(type, booking.groupId, meetingHosts.hostOwnerIds(type)));
        }
```

Then check nothing else referenced the old helpers:

```bash
grep -rn "groupEventId\|organizerOwnerOf" src/main/java src/test/java
```

Expected: no hits outside the code you just wrote.

- [ ] **Step 6: Run the tests**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest='BookingCalendarAddressTest+RescheduleCancelTest+UpdateDetailsTest+GroupEditDetailsTest+GroupCancelRescheduleTest+BookingServiceGuestTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply
git add src/main/java src/test/java
git commit -m "fix(booking): cancel and reschedule address the event's own calendar"
```

---

### Task 5: Full verification and close-out

**Files:**
- Modify: `.beans/calit-rma2--rotating-the-google-write-target-orphans-existing.md` (via the `beans` CLI)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: nothing code-facing.

- [ ] **Step 1: Run the whole suite**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test
```

Expected: BUILD SUCCESS, zero failures, zero errors. Docker must be running. Investigate any failure before continuing — do not proceed on a red suite.

- [ ] **Step 2: Verify formatting passes the CI gate**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:check
```

Expected: BUILD SUCCESS. If it fails, run `mvn spotless:apply` and amend.

- [ ] **Step 3: Confirm the migration applies to an existing database**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn -q quarkus:dev -Dgoogle.oauth.client-id=x -Dgoogle.oauth.client-secret=y -Dgoogle.oauth.state-secret=z
```

Expected: the app boots, the log shows Flyway migrating to version 26, and Hibernate's validate step raises no mismatch on `booking`. Stop it with `q`.

- [ ] **Step 4: Tick the bean's todo items and record the outcome**

```bash
beans update calit-rma2 -s completed \
  --body-replace-old "- [ ] If it does: add a \`google_calendar_id\` column" \
  --body-replace-new "- [x] If it does: add a \`google_calendar_id\` column"
```

Repeat for the remaining `- [ ]` items, then append a `## Summary of Changes` section describing the columns, the `CalendarRef` parameter, and the deliberate no-backfill choice. Only mark the bean completed once no unchecked item remains.

- [ ] **Step 5: Commit the bean**

```bash
git add .beans
git commit -m "chore(beans): close calit-rma2 — per-booking Google event address"
```

---

## Notes for whoever picks up calit-bh5t next

This plan deliberately leaves `createEvent` resolving the owner's default write target. The per-meeting-type override (`calit-bh5t`, spec at `docs/superpowers/specs/2026-08-17-per-meeting-type-write-target-design.md`) is what changes the create side, reusing the `CalendarRef` type introduced here. Its migration is the next free number after `V26`.
