# Editable Booking Name, Description & Guests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let both the host (owner, from `/me`) and the invitee (manage-token link) edit a single booking's meeting name, description, and guest list after booking, propagating every change to the other party via email, the Google Calendar event, and the `.ics` invite — with the "Manage" page acting as a hub of independent actions (reschedule / edit-details / cancel), each a no-op when nothing changed.

**Architecture:** A booking gains two nullable override columns, `title` and `description`, that fall back to the meeting type when unset. One new service method `BookingService.updateDetails(...)` mutates them plus the guest set (reusing `reconcileGuests`), guarded by a no-op check; on a real change it bumps the iTIP `SEQUENCE`, patches the Google event, and fires a new `BookingDetailsChanged` event that re-notifies invitee + owner + guests. Reschedule is decoupled from guests (time only) and gains its own no-op guard. Two thin edit-details endpoints (owner + invitee) re-render the Manage hub via a shared `renderManage` helper.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache entities, Qute server-rendered templates, Flyway migrations, CDI events + `@Observes(AFTER_SUCCESS)`, Mockito `@InjectMock CalendarPort`/`MailSender` in tests, RestAssured web tests, type-safe `@Message` i18n (en default + `de`/`he` properties), Tailwind v4 CSS compiled to `/calit.css`.

## Global Constraints

- **Owner scoping:** owner path uses `requireOwnedBooking(id)` (404 if `b.ownerId != currentOwner.id()`); invitee path is authenticated solely by the unguessable `manageToken`. Never cross tenants.
- **i18n parity:** every new/changed user-facing string ships its English `@Message` default **and** a `de` **and** `he` line in `src/main/resources/messages/{msg,adm}_{de,he}.properties`, keyed by method name. Keep `{placeholder}` names identical across locales. (Plain-English messages inside thrown `BookingValidationException`s are **not** i18n keys — match the existing `validateInputBounds` style.)
- **CSRF:** every new POST form embeds `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">`. CSRF is OFF in `%test`, so RestAssured POSTs need no token.
- **Flyway:** never edit an applied migration. Add `V19__*.sql` only. Hibernate is validate-only — entity columns must match the migration exactly.
- **No runtime JS** beyond the existing inline vanilla scripts. Tests can't run JS; assert on stable HTML markers.
- **Formatting:** `bun run format` before committing; `mvn spotless:check` gates CI. Qute `.html` is not prettier-formatted. Rebuild CSS with `bun run css:build` after editing `input.css`.
- **Docker required** for `mvn test`. Admin user is always id 1; write owner-scoped tests against that invariant.

## Design decisions (resolved during grilling — treat as binding)

1. **PENDING (approval) bookings are editable.** `updateDetails` guards only `CANCELLED`/`DECLINED`; PENDING edits update fields, skip Google (no `googleEventId`), keep status, and email as usual (consistent with existing requested-booking `.ics`).
2. **No-op guard on `updateDetails`.** If normalized (title, description, guest-set) all equal current → return early: no sequence bump, no Google, no email.
3. **Google SUMMARY keeps the `" with {invitee}"` suffix**, base swapped to `effectiveTitle`. Backward-compatible.
4. **Length bounds:** `title ≤ 200`, `description ≤ 2000` → `BookingValidationException` (400). Client `maxlength` hints mirror them.
5. **Guests are always re-notified on a real edit** (mirrors reschedule; keeps disconnected calendars correct).
6. **Separation of concerns on Manage:** Reschedule = time only; Edit-details = title + description + guests; Cancel. Reschedule no longer touches guests.
7. **Reschedule no-op guard:** same time + `guestEmails == null` → early return (no bump/Google/email).
8. **Edit-details re-renders the Manage hub** via a shared `renderManage(...)` helper (extracted from the GET handlers). Reschedule + cancel keep their existing bounce.
9. **Prefill = raw override + `placeholder` = type default.** Never prefill the effective value (would break the no-op guard and freeze overrides against future type renames).
10. **Route `effectiveTitle` into `confirmation.html` + `cancelConfirm.html`** (precomputed `meetingName` param) so a renamed booking shows its name on those pages.
11. **Scrollable slots:** cap `.time-column` height + `overflow-y: auto` so a long day's slot list doesn't run past the calendar.

---

### Task 1: Persist per-booking title & description

**Files:**
- Create: `src/main/resources/db/migration/V19__booking_title_description.sql`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java`
- Test: `src/test/java/site/asm0dey/calit/booking/BookingTest.java` (add methods)

**Interfaces:**
- Produces: `public String Booking.title` (nullable), `public String Booking.description` (nullable); `public String Booking.effectiveTitle(MeetingType type)`; `public String Booking.effectiveDescription(MeetingType type)` (may return null).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/site/asm0dey/calit/booking/BookingTest.java` (imports: `site.asm0dey.calit.domain.MeetingType`, `static org.junit.jupiter.api.Assertions.*`):

```java
@Test
void effectiveTitleFallsBackToTypeNameWhenNoOverride() {
    MeetingType t = new MeetingType();
    t.name = "Discovery Call";
    t.description = "A 30-min intro";
    Booking b = new Booking();
    assertEquals("Discovery Call", b.effectiveTitle(t));
    assertEquals("A 30-min intro", b.effectiveDescription(t));
}

@Test
void effectiveTitleAndDescriptionUseOverridesWhenSet() {
    MeetingType t = new MeetingType();
    t.name = "Discovery Call";
    t.description = "A 30-min intro";
    Booking b = new Booking();
    b.title = "Roadmap sync";
    b.description = "Q3 planning";
    assertEquals("Roadmap sync", b.effectiveTitle(t));
    assertEquals("Q3 planning", b.effectiveDescription(t));
}

@Test
void blankOverrideFallsBackAndNullTypeDescriptionStaysNull() {
    MeetingType t = new MeetingType();
    t.name = "Discovery Call";
    t.description = null;
    Booking b = new Booking();
    b.title = "   ";
    assertEquals("Discovery Call", b.effectiveTitle(t));
    assertNull(b.effectiveDescription(t));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=BookingTest -q`
Expected: FAIL — `effectiveTitle`/`effectiveDescription` do not exist (compile error).

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V19__booking_title_description.sql`:

```sql
-- Per-booking overrides for the meeting's displayed name and description. NULL = fall back to the
-- meeting type's name/description. Editable post-booking by both host (/me) and invitee (manage token).
ALTER TABLE booking
    ADD COLUMN title       text,
    ADD COLUMN description text;
```

- [ ] **Step 4: Add the entity fields + helpers**

In `src/main/java/site/asm0dey/calit/booking/Booking.java`, add an import after the existing imports:

```java
import site.asm0dey.calit.domain.MeetingType;
```

Add the two columns immediately after the `answers` field (before `icsSequence`):

```java
/**
 * Per-booking override of the meeting's displayed name. NULL (or blank) means "no override" —
 * {@link #effectiveTitle} falls back to the meeting type's name. Editable post-booking by both the
 * host and the invitee; changes propagate to email, the Google event summary, and the .ics SUMMARY.
 */
@Column(columnDefinition = "text")
public String title;

/**
 * Per-booking override of the meeting description. NULL (or blank) falls back to the meeting type's
 * description (which may itself be null). Propagates to the .ics DESCRIPTION and Google event description.
 */
@Column(columnDefinition = "text")
public String description;
```

Add the helpers after `findByManageToken`:

```java
/** Displayed meeting name: this booking's override when set + non-blank, else the type's name. */
public String effectiveTitle(MeetingType type) {
    return (title != null && !title.isBlank()) ? title : type.name;
}

/** Meeting description: override when set + non-blank, else the type's description (may be null). */
public String effectiveDescription(MeetingType type) {
    if (description != null && !description.isBlank()) {
        return description;
    }
    return (type.description != null && !type.description.isBlank()) ? type.description : null;
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=BookingTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V19__booking_title_description.sql \
        src/main/java/site/asm0dey/calit/booking/Booking.java \
        src/test/java/site/asm0dey/calit/booking/BookingTest.java
git commit -m "feat: add per-booking title/description override columns"
```

---

### Task 2: Emit DESCRIPTION into the .ics

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/IcsEvent.java`
- Modify: `src/main/java/site/asm0dey/calit/email/IcsBuilder.java:47`
- Test: `src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java` (add methods)

**Interfaces:**
- Produces: `IcsEvent.description` (nullable) + `IcsEvent.Builder.description(String)`; `IcsBuilder.build` emits a `DESCRIPTION:` line iff non-blank.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java`:

```java
@Test
void emitsDescriptionLineWhenPresent() {
    String ics = IcsBuilder.build(IcsEvent.builder()
            .uid("tok-d")
            .summary("Roadmap sync")
            .description("Q3 planning agenda")
            .location(null)
            .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Invitee", "invitee@example.com"))
            .start(Instant.parse("2026-06-08T09:00:00Z"))
            .end(Instant.parse("2026-06-08T09:30:00Z"))
            .build());
    assertTrue(ics.contains("DESCRIPTION:Q3 planning agenda"));
}

@Test
void omitsDescriptionLineWhenNullOrBlank() {
    String ics = IcsBuilder.build(IcsEvent.builder()
            .uid("tok-d2")
            .summary("Roadmap sync")
            .description("   ")
            .location(null)
            .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Invitee", "invitee@example.com"))
            .start(Instant.parse("2026-06-08T09:00:00Z"))
            .end(Instant.parse("2026-06-08T09:30:00Z"))
            .build());
    assertFalse(ics.contains("DESCRIPTION:"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=IcsBuilderTest -q`
Expected: FAIL — `description(...)` builder method missing.

- [ ] **Step 3: Add the `description` field to `IcsEvent`**

In `src/main/java/site/asm0dey/calit/email/IcsEvent.java`, insert `String description,` right after `String summary,` in the record component list:

```java
public record IcsEvent(
        String uid,
        String summary,
        String description,
        String location,
        IcsBuilder.Party organizer,
        IcsBuilder.Party attendee,
        Instant start,
        Instant end,
        IcsMethod method,
        int sequence,
        boolean attendeeRsvp) {
```

Add the builder field after `private String summary;`:

```java
private String description;
```

Add the setter after `summary(...)`:

```java
public Builder description(String description) {
    this.description = description;
    return this;
}
```

Update `build()`:

```java
public IcsEvent build() {
    return new IcsEvent(
            uid, summary, description, location, organizer, attendee, start, end, method, sequence, attendeeRsvp);
}
```

- [ ] **Step 4: Emit the DESCRIPTION line**

In `src/main/java/site/asm0dey/calit/email/IcsBuilder.java`, right after the `SUMMARY` append (line 47), before the `LOCATION` block:

```java
if (e.description() != null && !e.description().isBlank()) {
    sb.append("DESCRIPTION:").append(escape(e.description())).append("\r\n");
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=IcsBuilderTest -q`
Expected: PASS (existing cases still green; `description` defaults to null at other call sites).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/email/IcsEvent.java \
        src/main/java/site/asm0dey/calit/email/IcsBuilder.java \
        src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java
git commit -m "feat: carry a DESCRIPTION into the .ics invite"
```

---

### Task 3: `BookingService.updateDetails` + `BookingDetailsChanged` + Google patch

**Files:**
- Create: `src/main/java/site/asm0dey/calit/booking/events/BookingDetailsChanged.java`
- Modify: `src/main/java/site/asm0dey/calit/google/CalendarPort.java`
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`
- Test: `src/test/java/site/asm0dey/calit/booking/UpdateDetailsTest.java`

**Interfaces:**
- Consumes: `Booking.effectiveTitle/effectiveDescription` (Task 1); existing private `reconcileGuests`, `normalizeGuestEmails`, `attendeeEmails`; `GuestRemoved` event; `BookingGuest.activeForBooking`.
- Produces:
  - `record BookingDetailsChanged(Long bookingId, boolean byOwner)`
  - `CalendarPort.updateEventDetails(Long ownerId, String eventId, String summary, String description, List<String> attendeeEmails)`
  - `BookingService.updateDetails(String manageToken, String title, String description, List<String> guestEmails, boolean byOwner)` → returns the `Booking`.
  - private `googleSummary(MeetingType, Booking)`, `googleDescription(MeetingType, Booking)`, `blankToNull(String)`, `sameGuestSet(Booking, List<String>)`, `validateDetailBounds(String, String)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/UpdateDetailsTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class UpdateDetailsTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    Booking seedConfirmed(String slug) {
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
        t.name = "Type Name";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
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
        var slot = bookingService.availableSlots(t, LocalDate.now(), LocalDate.now().plusDays(14)).getFirst();
        return bookingService.book(
                1L, slug, slot.start().toInstant(), "Pat", "pat@example.com", Map.of(), "", "", "en", List.of());
    }

    @Test
    void updateDetailsPersistsOverridesBumpsSequenceAndPatchesGoogle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ud", null, "h"));
        Booking b = seedConfirmed("upd-1");
        int beforeSeq = b.icsSequence;

        bookingService.updateDetails(b.manageToken, "Roadmap sync", "Q3 planning", List.of(), true);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(b.id));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
        assertTrue(after.icsSequence > beforeSeq, "sequence bumped");
        verify(calendarPort, times(1))
                .updateEventDetails(anyLong(), eq("evt-ud"), eq("Roadmap sync with Pat"), eq("Q3 planning"), any());
    }

    @Test
    void updateDetailsReconcilesGuests() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seedConfirmed("upd-2");

        bookingService.updateDetails(b.manageToken, null, null, List.of("ana@example.com"), false);

        List<BookingGuest> guests =
                QuarkusTransaction.requiringNew().call(() -> BookingGuest.activeForBooking(b.id));
        assertEquals(1, guests.size());
        assertEquals("ana@example.com", guests.getFirst().email);
    }

    @Test
    void updateDetailsBlankTitleClearsOverride() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seedConfirmed("upd-3");
        bookingService.updateDetails(b.manageToken, "First", "d", List.of(), true);

        bookingService.updateDetails(b.manageToken, "   ", "  ", List.of(), true);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(b.id));
        assertNull(after.title, "blank title → null → falls back to type name");
        assertNull(after.description);
    }

    @Test
    void updateDetailsNoOpDoesNotBumpOrPatch() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-noop", null, "h"));
        Booking b = seedConfirmed("upd-4"); // no override, no guests
        int beforeSeq = b.icsSequence;
        clearInvocations(calendarPort);

        // Same as current state: null title/description, empty guest set → true no-op.
        bookingService.updateDetails(b.manageToken, null, null, List.of(), true);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(b.id));
        assertEquals(beforeSeq, after.icsSequence, "no-op must not bump the sequence");
        verify(calendarPort, never()).updateEventDetails(anyLong(), any(), any(), any(), any());
    }

    @Test
    void updateDetailsRejectsOverlongTitle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seedConfirmed("upd-5");
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.updateDetails(b.manageToken, "x".repeat(201), null, List.of(), true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=UpdateDetailsTest -q`
Expected: FAIL — `updateDetails` / `updateEventDetails` do not exist.

- [ ] **Step 3: Add the `BookingDetailsChanged` event**

Create `src/main/java/site/asm0dey/calit/booking/events/BookingDetailsChanged.java`:

```java
package site.asm0dey.calit.booking.events;

/** {@code byOwner} = the host edited name/description/guests from /me (vs. the invitee via manage link). */
public record BookingDetailsChanged(Long bookingId, boolean byOwner) {}
```

- [ ] **Step 4: Add `updateEventDetails` to the calendar port**

In `src/main/java/site/asm0dey/calit/google/CalendarPort.java`, after `updateEvent` (line 42):

```java
/**
 * Patch an existing event's summary + description (and re-sync attendees), leaving its time untouched;
 * {@code sendUpdates=all} so Google re-notifies everyone. Used when the host/invitee edits the meeting's
 * name, description, or guest list. A null or empty attendee list leaves attendees unchanged.
 */
void updateEventDetails(
        Long ownerId, String eventId, String summary, String description, List<String> attendeeEmails);
```

In `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java`, after `updateEvent` (line 248):

```java
@Override
@Transactional
public void updateEventDetails(
        Long ownerId, String eventId, String summary, String description, List<String> attendeeEmails) {
    var ctx = writeContext(ownerId);
    GoogleCalendar target = ctx.target();
    GoogleCredential cred = ctx.cred();
    Event patch = new Event().setSummary(summary).setDescription(description);
    if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
        patch.setAttendees(attendeeEmails.stream()
                .map(email -> new EventAttendee().setEmail(email))
                .toList());
    }
    try {
        client(cred)
                .events()
                .patch(target.googleCalendarId, eventId, patch)
                .setSendUpdates("all")
                .execute();
    } catch (IOException e) {
        throw new UncheckedIOException("updateEventDetails failed", e);
    }
}
```

(No unit test for `GoogleCalendarPort` — needs live Google; the mock in `UpdateDetailsTest` verifies the call.)

- [ ] **Step 5: Add helpers + `updateDetails` + wire the event in `BookingService`**

In `src/main/java/site/asm0dey/calit/booking/BookingService.java`:

Add the emitter near the other `@Inject Event<...>` fields (after `guestRemovedEvent`):

```java
@Inject
Event<BookingDetailsChanged> bookingDetailsChangedEvent;
```

Add the Google-field helpers just above `createGoogleEvent` (they replace the hardcoded literals so custom name/description reach Google):

```java
/** Google event SUMMARY for a booking: "{effective title} with {invitee}". */
private static String googleSummary(MeetingType type, Booking booking) {
    return booking.effectiveTitle(type) + " with " + booking.inviteeName;
}

/**
 * Google event DESCRIPTION: the booking's effective description, or "" when none. Empty (not null) so a
 * PATCH actively clears a removed description (a null field is omitted from the patch and would linger).
 */
private static String googleDescription(MeetingType type, Booking booking) {
    String d = booking.effectiveDescription(type);
    return d == null ? "" : d;
}
```

Change `createGoogleEvent` (lines 281-289) to use them:

```java
CreatedEvent created = calendarPort.createEvent(
        type.ownerId,
        googleSummary(type, booking),
        googleDescription(type, booking),
        booking.startUtc,
        booking.endUtc,
        attendeeEmails(booking, owner),
        type.locationType == LocationType.GOOGLE_MEET,
        type.locationDetail);
```

Add `updateDetails` + its helpers after the 4-arg `reschedule(...)` body (after line 518), before `reconcileGuests`:

```java
/**
 * Edits a booking's meeting name, description, and guest list by its manage token — usable by both the
 * host (byOwner=true) and the invitee (byOwner=false). {@code title}/{@code description} are trimmed;
 * blank stores null so the meeting type's value shows through (bounded: title ≤ 200, description ≤ 2000).
 * {@code guestEmails} reconciles the active guest set exactly (empty list removes all). If nothing
 * actually changed (normalized title/description/guest-set all equal current) this is a NO-OP: it
 * returns without bumping the SEQUENCE, patching Google, or emailing anyone. On a real change it bumps
 * the iTIP SEQUENCE, patches the Google event (name/description + attendees), fires GuestRemoved per
 * dropped guest and BookingDetailsChanged so EmailService re-notifies invitee + owner + guests. Never
 * changes status or time.
 */
@Transactional
public Booking updateDetails(
        String manageToken, String title, String description, List<String> guestEmails, boolean byOwner) {
    Booking booking = Booking.findByManageToken(manageToken);
    if (booking == null
            || booking.status == BookingStatus.CANCELLED
            || booking.status == BookingStatus.DECLINED) {
        throw new NotFoundException("No active booking for token " + manageToken);
    }
    MeetingType type = MeetingType.findById(booking.meetingTypeId);

    String newTitle = blankToNull(title);
    String newDescription = blankToNull(description);
    validateDetailBounds(newTitle, newDescription);
    List<String> wanted = normalizeGuestEmails(guestEmails, booking.inviteeEmail);

    // No-op guard: nothing changed → no notification storm, no SEQUENCE churn.
    if (java.util.Objects.equals(newTitle, booking.title)
            && java.util.Objects.equals(newDescription, booking.description)
            && sameGuestSet(booking, wanted)) {
        return booking;
    }

    booking.title = newTitle;
    booking.description = newDescription;
    // Bump SEQUENCE so an updated .ics supersedes the prior one in the recipient's calendar.
    booking.icsSequence = booking.icsSequence + 1;

    List<Long> removedGuestIds = reconcileGuests(booking, guestEmails);
    booking.persistAndFlush();

    for (Long guestId : removedGuestIds) {
        guestRemovedEvent.fire(new GuestRemoved(booking.id, guestId));
    }

    if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
        calendarPort.updateEventDetails(
                type.ownerId,
                booking.googleEventId,
                googleSummary(type, booking),
                googleDescription(type, booking),
                attendeeEmails(booking, owner));
    }

    bookingDetailsChangedEvent.fire(new BookingDetailsChanged(booking.id, byOwner));
    return booking;
}

/** Trim; null for null/blank so a cleared override falls back to the meeting type's value. */
private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
}

/** Enforce the same input-bounds discipline as validateInputBounds (SEC-INPUT): title ≤ 200, desc ≤ 2000. */
private static void validateDetailBounds(String title, String description) {
    if (title != null && title.length() > 200) {
        throw new BookingValidationException("Meeting name is too long.");
    }
    if (description != null && description.length() > 2000) {
        throw new BookingValidationException("Description is too long.");
    }
}

/** True iff the booking's current active guest set equals {@code wanted} (case-insensitive). */
private static boolean sameGuestSet(Booking booking, List<String> wanted) {
    java.util.Set<String> current = new java.util.HashSet<>();
    for (BookingGuest g : BookingGuest.<BookingGuest>activeForBooking(booking.id)) {
        current.add(g.email.toLowerCase());
    }
    java.util.Set<String> want = new java.util.HashSet<>();
    for (String e : wanted) {
        want.add(e.toLowerCase());
    }
    return current.equals(want);
}
```

`BookingDetailsChanged`, `GuestRemoved`, `BookingValidationException` are all in-package or covered by `import site.asm0dey.calit.booking.events.*;`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=UpdateDetailsTest -q`
Expected: PASS. Then confirm the `createGoogleEvent` arg swap didn't regress Google-touching suites:
Run: `mvn test -Dtest=RescheduleCancelTest,BookServiceTest,BookingServiceGuestTest -q`
Expected: PASS (they mock `createEvent` with `anyString()`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/events/BookingDetailsChanged.java \
        src/main/java/site/asm0dey/calit/google/CalendarPort.java \
        src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java \
        src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/UpdateDetailsTest.java
git commit -m "feat: BookingService.updateDetails (no-op-guarded) edits name/description/guests + syncs Google"
```

---

### Task 4: Reschedule = time only (no-op guard + decouple guests + scrollable slots)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (`reschedule` no-op guard)
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (`rescheduleBooking`)
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (`ownerReschedule`)
- Modify: `src/main/resources/templates/PublicResource/manage.html` (remove guest widget from reschedule form)
- Modify: `src/main/css/input.css` (`.time-column` scroll)
- Test: `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java` (add no-op test); `src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java` (rewrite `rescheduleEditsGuestList`)

**Interfaces:**
- Consumes: existing `reschedule(String, Instant, List<String>, boolean)`; new `updateDetails` endpoint path `/booking/{token}/edit-details` (created in Task 7 — the rewritten `GuestBookingFlowTest` targets it, so run this test's rewrite only after Task 7, or land the endpoint stub first; see Step 6 note).
- Produces: reschedule short-circuits on unchanged time; web reschedule handlers pass `guestEmails = null` (guests untouched).

- [ ] **Step 1: Write the failing test (service no-op)**

Add to `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java`:

```java
@Test
@TestTransaction
void rescheduleToSameTimeIsNoOp() {
    seedSettings();
    meetingTypeWithMondayWindow("resched-noop", false);
    when(calendarPort.isConnected(anyLong())).thenReturn(true);
    when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-nn", "https://meet.google.com/n-n-n", "h"));

    Booking b = bookingService.book(
            1L, "resched-noop", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
    int beforeSeq = b.icsSequence;
    clearInvocations(calendarPort);

    // Reschedule to the SAME start with guests untouched (null) → no-op.
    bookingService.reschedule(b.manageToken, SLOT_09, null, false);

    Booking loaded = Booking.findById(b.id);
    assertEquals(SLOT_09, loaded.startUtc);
    assertEquals(beforeSeq, loaded.icsSequence, "no-op must not bump the sequence");
    verify(calendarPort, never()).updateEvent(anyLong(), any(), any(), any(), any());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=RescheduleCancelTest#rescheduleToSameTimeIsNoOp -q`
Expected: FAIL — reschedule currently bumps the sequence + calls `updateEvent` even for the same time.

- [ ] **Step 3: Add the reschedule no-op guard**

In `src/main/java/site/asm0dey/calit/booking/BookingService.java`, inside the 4-arg `reschedule(...)`, immediately after the not-found/cancelled check (after line 461) and before `MeetingType type = ...`:

```java
// No-op: same time and guests untouched (web callers pass null) → nothing to do. Avoids a spurious
// SEQUENCE bump + reschedule email when the invitee re-picks the current slot.
if (newStartUtc.equals(booking.startUtc) && guestEmails == null) {
    return booking;
}
```

- [ ] **Step 4: Decouple guests from the web reschedule handlers**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`, replace `rescheduleBooking` (lines 357-371) with (drops the `form` param + `parseGuests` — guests are now edited only via `/edit-details`):

```java
@POST
@Path("/booking/{manageToken}/reschedule")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.TEXT_HTML)
public TemplateInstance rescheduleBooking(
        @PathParam("manageToken") String manageToken, @RestForm String startUtc) {
    // Time only — guests are managed separately via /booking/{token}/edit-details. Passing the 2-arg
    // overload leaves the guest set untouched (guestEmails=null).
    Booking booking = bookingService.reschedule(manageToken, Instant.parse(startUtc));
    MeetingType type = MeetingType.findById(booking.meetingTypeId);
    return confirmationPage(booking, type);
}
```

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, replace `ownerReschedule` (lines 970-982) with:

```java
@POST
@Path("/bookings/{id}/reschedule")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.TEXT_HTML)
public TemplateInstance ownerReschedule(@PathParam("id") Long id, @RestForm String startUtc) {
    Booking b = requireOwnedBooking(id);
    // Time only — guests untouched (null). Host edits guests via /me/bookings/{id}/edit-details.
    bookingService.reschedule(b.manageToken, Instant.parse(startUtc), null, true); // host-initiated
    return dashboard();
}
```

- [ ] **Step 5: Remove the guest widget from the reschedule form + add slot scroll**

In `src/main/resources/templates/PublicResource/manage.html`, delete line 42 (`{#include PublicResource/_guestschips initial=initialGuests /}`) from inside the reschedule `<form>`. (The `initialGuests` template param stays — the new edit-details form added in Task 7 uses it.)

In `src/main/css/input.css`, extend the `.time-column` rules (add after the `.time-column h3` block, ~line 193):

```css
  .time-column {
    /* ponytail: ~calendar height; scroll the day's slots past it instead of running long. */
    max-height: 22rem;
    overflow-y: auto;
  }
```

- [ ] **Step 6: Rewrite the guest-via-reschedule web test**

In `src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java`, rewrite `rescheduleEditsGuestList` (lines 164-196) to drive guests through the new edit-details endpoint instead of reschedule. Rename it `editDetailsEditsGuestList`:

```java
@Test
void editDetailsEditsGuestList() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    seed();

    given().contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", firstSlot())
            .formParam("inviteeName", "Sam")
            .formParam("inviteeEmail", "sam@example.com")
            .formParam("website", "")
            .formParam("guests", "ana@example.com, bob@example.com")
            .when()
            .post("/gob/g-type")
            .then()
            .statusCode(200);

    Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
    String manageToken = b.manageToken;

    given().contentType("application/x-www-form-urlencoded")
            .formParam("guests", "ana@example.com, cyd@example.com") // drop bob, add cyd
            .when()
            .post("/booking/" + manageToken + "/edit-details")
            .then()
            .statusCode(200);

    assertEquals(GuestStatus.REMOVED, BookingGuest.findInBooking(b.id, "bob@example.com").status);
    assertEquals(2, BookingGuest.activeForBooking(b.id).size());
}
```

> **Ordering note:** the `/edit-details` endpoint is created in Task 7. If executing strictly in order, land this test rewrite together with Task 7 (or temporarily `@Disabled` it here and re-enable in Task 7). The service no-op test (Steps 1-3) is independent and passes now.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -Dtest=RescheduleCancelTest,ManageBookingTest,OwnerManageBookingTest -q`
Expected: PASS. (`ManageBookingTest.rescheduleSubmitsAbsoluteInstantUnaffectedByDisplayZone` still posts only `startUtc` — unaffected by dropping the guest param. If any reschedule test posted `guests`, it simply ignores the now-unused param.) Rebuild CSS: `bun run css:build`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/PublicResource/manage.html \
        src/main/css/input.css \
        src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java
git commit -m "refactor: reschedule is time-only (no-op guarded) + scrollable slot list"
```

---

### Task 5: Email propagation — updated notification, name routing, description in .ics

**Files:**
- Create: `src/main/resources/templates/email/updated.html`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` + `msg_de.properties` + `msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/email/UpdatedEmailTest.java`

**Interfaces:**
- Consumes: `BookingDetailsChanged` (Task 3); `Booking.effectiveTitle/effectiveDescription` (Task 1); `IcsEvent.Builder.description(...)` (Task 2); existing `sendForKindLocaleAware`, `sendGuestInvites`, `guestIcs`, `Loaded`.
- Produces: observer `onDetailsChanged` → `handleDetailsChanged`; the meeting label everywhere now comes from `label(Loaded)`; new `@Message` keys `email_updated_subject`, `email_updated_title`, `email_updated_body_self`, `email_updated_body_by_owner`, `email_updated_body_by_invitee`, `email_updated_description_label`; new data key `DESCRIPTION`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/UpdatedEmailTest.java`:

```java
package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class UpdatedEmailTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    @InjectMock
    MailSender mailSender;

    @Transactional
    Booking seed(String slug) {
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
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Type Name";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
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
        var slot = bookingService.availableSlots(t, LocalDate.now(), LocalDate.now().plusDays(14)).getFirst();
        return bookingService.book(
                1L, slug, slot.start().toInstant(), "Pat", "pat@example.com", Map.of(), "", "", "en", List.of());
    }

    @Test
    void detailsChangeEmailsBothPartiesWithNewNameAndDescription() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seed("upd-mail");
        clearInvocations(mailSender);

        bookingService.updateDetails(b.manageToken, "Roadmap sync", "Q3 planning agenda", List.of(), true);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender, atLeast(2)).send(any(), to.capture(), subject.capture(), body.capture(), any());
        assertTrue(to.getAllValues().contains("pat@example.com"), "invitee notified");
        assertTrue(to.getAllValues().contains("owner@example.com"), "owner notified");
        assertTrue(subject.getAllValues().stream().anyMatch(su -> su.contains("Roadmap sync")), "subject has new name");
        assertTrue(body.getAllValues().stream().anyMatch(bo -> bo.contains("Q3 planning agenda")), "body has description");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=UpdatedEmailTest -q`
Expected: FAIL — no observer handles `BookingDetailsChanged`.

- [ ] **Step 3: Add the i18n keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, after `email_rescheduled_subject` (line 443):

```java
@Message("Booking updated: {meetingTypeName}")
String email_updated_subject(String meetingTypeName);
```

Near the reschedule body group (after `email_reschedule_body_by_owner`, ~line 588):

```java
@Message("Booking updated")
String email_updated_title();

@Message("The meeting details were updated.")
String email_updated_body_self();

@Message("{name} updated the meeting details.")
String email_updated_body_by_owner(String name);

@Message("{name} updated the meeting details.")
String email_updated_body_by_invitee(String name);

@Message("Description:")
String email_updated_description_label();
```

In `msg_de.properties`:

```properties
email_updated_subject=Buchung aktualisiert: {meetingTypeName}
email_updated_title=Buchung aktualisiert
email_updated_body_self=Die Termindetails wurden aktualisiert.
email_updated_body_by_owner={name} hat die Termindetails aktualisiert.
email_updated_body_by_invitee={name} hat die Termindetails aktualisiert.
email_updated_description_label=Beschreibung:
```

In `msg_he.properties`:

```properties
email_updated_subject=ההזמנה עודכנה: {meetingTypeName}
email_updated_title=ההזמנה עודכנה
email_updated_body_self=פרטי הפגישה עודכנו.
email_updated_body_by_owner={name} עדכן/ה את פרטי הפגישה.
email_updated_body_by_invitee={name} עדכן/ה את פרטי הפגישה.
email_updated_description_label=תיאור:
```

- [ ] **Step 4: Add the `updated.html` template**

Create `src/main/resources/templates/email/updated.html`:

```html
{@java.lang.String recipientRole}
{@java.lang.Boolean byOwner}
{@java.lang.String description}
{#include email/layout}
{#title}{msg:email_updated_title}{/title}
{#if byOwner}
  {#if recipientRole == 'owner'}
<p><strong>{msg:email_updated_body_self}</strong></p>
  {#else}
<p><strong>{msg:email_updated_body_by_owner(ownerName)}</strong></p>
  {/if}
{#else}
  {#if recipientRole == 'owner'}
<p><strong>{msg:email_updated_body_by_invitee(inviteeName)}</strong></p>
  {#else}
<p><strong>{msg:email_updated_body_self}</strong></p>
  {/if}
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  {#if description}<li><strong>{msg:email_updated_description_label}</strong> {description}</li>{/if}
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
{#include email/_answers /}
{#if recipientRole == 'owner'}{#include email/_ownerlinks /}{#else}{#include email/_inviteelinks /}{/if}
{/include}
```

- [ ] **Step 5: Route the meeting label + add DESCRIPTION key + description into both .ics builds**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`:

Add near `MEETING_TYPE_NAME` (line 46):

```java
public static final String DESCRIPTION = "description";
```

Add the template injection after `reschedule` (line 100):

```java
@Inject
@Location("email/updated.html")
Template updated;
```

Add a helper near `resolveLocation` (~line 725):

```java
/** The meeting label shown in every mail: the booking's title override, else the type name. */
private static String label(Loaded l) {
    return l.booking.effectiveTitle(l.meetingType);
}
```

Replace **every** `l.meetingType.name` in this file with `label(l)`, then verify:
Run: `grep -n "l.meetingType.name" src/main/java/site/asm0dey/calit/email/EmailService.java`
Expected: no matches. (Routes the custom name into all subjects, bodies, and both `.ics` `summary(...)` calls.)

Add `.description(...)` after `.summary(label(l))` in the `sendForKindLocaleAware` builder (lines 674-683):

```java
.summary(label(l))
.description(l.booking.effectiveDescription(l.meetingType))
```

And in the `guestIcs` builder (lines 619-631):

```java
.summary(label(l))
.description(l.booking.effectiveDescription(l.meetingType))
```

- [ ] **Step 6: Add the observer + handler**

Add the observer after `onRescheduled` (line 200):

```java
void onDetailsChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDetailsChanged e) {
    handleDetailsChanged(e);
}
```

Add the handler after `handleRescheduled` (after line 423):

```java
void handleDetailsChanged(BookingDetailsChanged e) {
    Loaded l = load(e.bookingId());
    if (l == null) return;
    var location = resolveLocation(l);
    Locale inviteeLocale = AppLocales.pick(l.booking.locale);
    Locale ownerLocale = AppLocales.pick(l.owner.locale);
    String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
    String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
    String desc = l.booking.effectiveDescription(l.meetingType);
    sendForKindLocaleAware(
            l,
            location,
            messages.forLocale(inviteeLocale).email_updated_subject(label(l)),
            messages.forLocale(ownerLocale).email_updated_subject(label(l)),
            role -> {
                var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                return updated
                        .instance()
                        .setLocale(locale)
                        .data(RECIPIENT_ROLE, role)
                        .data("byOwner", e.byOwner())
                        .data("lang", locale.getLanguage())
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(OWNER_NAME, l.owner.ownerName)
                        .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                        .data(MEETING_TYPE_NAME, label(l))
                        .data(DESCRIPTION, desc)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))
                        .data(CANCEL_URL, cancelUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render();
            });
    // Re-send the (bumped-sequence) REQUEST .ics to every active guest so their calendar updates too.
    sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_updated_subject(label(l)));
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -Dtest=UpdatedEmailTest -q`
Expected: PASS. Then the broader email suite (guards the `label(l)` refactor):
Run: `mvn test -Dtest=EmailServiceTest,EmailRoleCopyTest,EmailLocaleTest,EmailLocaleHebrewTest,EmailServiceGuestTest -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/templates/email/updated.html \
        src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/email/UpdatedEmailTest.java
git commit -m "feat: email + .ics propagation for booking detail edits"
```

---

### Task 6: Host edit-details — endpoint, hub re-render, form (`/me`)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (`Templates.manageBooking` signature ~line 103; extract `renderManage`; new `ownerEditDetails`; new `parseGuests`)
- Modify: `src/main/resources/templates/AdminResource/manageBooking.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` + `msg_de.properties` + `msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/OwnerEditDetailsTest.java`; fix `OwnerManageBookingTest`

**Interfaces:**
- Consumes: `BookingService.updateDetails(...)` (Task 3).
- Produces: `POST /me/bookings/{id}/edit-details` (form `title`, `description`, `guests`); `Templates.manageBooking` gains trailing `String titleValue, String descriptionValue, String titlePlaceholder, String descPlaceholder`; labels `pub_edit_details_h2`, `pub_edit_details_name_label`, `pub_edit_details_desc_label`, `pub_edit_details_btn`; private `renderManage(Booking)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/OwnerEditDetailsTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
class OwnerEditDetailsTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Transactional
    Long seed() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        var slug = "owner-edit-" + System.nanoTime();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Owner Edit Type";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
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
        return bookingService
                .book(1L, slug, slot.start().toInstant(), "Pat", "pat@example.com", java.util.Map.of(), "", "", "en", List.of())
                .id;
    }

    @Test
    void managePageShowsEditDetailsForm() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed();
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/" + id + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/me/bookings/" + id + "/edit-details"))
                .body(containsString("name=\"title\""))
                .body(containsString("name=\"description\""));
    }

    @Test
    void ownerEditsNameDescriptionAndGuestThenSeesHubReRendered() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed();

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("title", "Roadmap sync")
                .formParam("description", "Q3 planning")
                .formParam("guests", "ana@example.com")
                .when()
                .post("/me/bookings/" + id + "/edit-details")
                .then()
                .statusCode(200)
                // Re-renders the Manage hub with the saved value prefilled (raw override).
                .body(containsString("value=\"Roadmap sync\""))
                .body(containsString("/me/bookings/" + id + "/edit-details"));

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(id));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
    }

    @Test
    void editDetailsOnAnotherOwnersBookingIs404() {
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("title", "x")
                .when()
                .post("/me/bookings/999999/edit-details")
                .then()
                .statusCode(404);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OwnerEditDetailsTest -q`
Expected: FAIL — endpoint + form markers absent.

- [ ] **Step 3: Add the form labels (i18n)**

In `AppMessages.java`, after `pub_manage_btn_reschedule` (~line 398):

```java
@Message("Edit name & description")
String pub_edit_details_h2();

@Message("Meeting name")
String pub_edit_details_name_label();

@Message("Description")
String pub_edit_details_desc_label();

@Message("Save changes")
String pub_edit_details_btn();
```

`msg_de.properties`:

```properties
pub_edit_details_h2=Name & Beschreibung bearbeiten
pub_edit_details_name_label=Terminname
pub_edit_details_desc_label=Beschreibung
pub_edit_details_btn=Änderungen speichern
```

`msg_he.properties`:

```properties
pub_edit_details_h2=עריכת שם ותיאור
pub_edit_details_name_label=שם הפגישה
pub_edit_details_desc_label=תיאור
pub_edit_details_btn=שמירת שינויים
```

- [ ] **Step 4: Extend `Templates.manageBooking` + extract `renderManage` + add endpoint**

In `AdminResource.java`, extend the native template method (add four params after `String title,`):

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
        String title,
        String titleValue,
        String descriptionValue,
        String titlePlaceholder,
        String descPlaceholder);
```

Replace the `manageBooking` GET handler (lines 947-968) with a thin delegate + a shared render helper:

```java
@GET
@Path("/bookings/{id}/manage")
@Produces(MediaType.TEXT_HTML)
public TemplateInstance manageBooking(@PathParam("id") Long id) {
    return renderManage(requireOwnedBooking(id));
}

/** Render the owner's Manage hub for a booking (shared by GET manage and POST edit-details). */
private TemplateInstance renderManage(Booking b) {
    MeetingType type = MeetingType.findById(b.meetingTypeId);
    ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
    String current =
            b.startUtc.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
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
            m().adm_dashboard_h2(),
            b.title == null ? "" : b.title, // raw override (empty when none) — never the effective value
            b.description == null ? "" : b.description,
            type.name, // placeholder = default name
            type.description == null ? "" : type.description);
}
```

Add the endpoint + guest parser after `ownerCancel` (after line 992):

```java
@POST
@Path("/bookings/{id}/edit-details")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.TEXT_HTML)
public TemplateInstance ownerEditDetails(
        @PathParam("id") Long id,
        @RestForm String title,
        @RestForm String description,
        MultivaluedMap<String, String> form) {
    Booking b = requireOwnedBooking(id); // owner-scoped
    bookingService.updateDetails(b.manageToken, title, description, parseGuests(form), true); // host-initiated
    return renderManage(requireOwnedBooking(id)); // reload committed state → back to the hub
}

// ponytail: an 8-line CSV splitter duplicated from PublicResource; not worth a shared util.
private static List<String> parseGuests(MultivaluedMap<String, String> form) {
    String raw = form.getFirst("guests");
    if (raw == null || raw.isBlank()) {
        return List.of();
    }
    return java.util.Arrays.stream(raw.split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
}
```

Ensure `import org.jboss.resteasy.reactive.RestForm;` is present (grep the file; add if missing). `MultivaluedMap` is already imported.

- [ ] **Step 5: Add the form to the host manage template**

In `manageBooking.html`, add the new params to the header (after `{@java.lang.String title}`):

```html
{@java.lang.String titleValue}
{@java.lang.String descriptionValue}
{@java.lang.String titlePlaceholder}
{@java.lang.String descPlaceholder}
```

Insert the edit-details form just before the cancel `<div class="divider">` (before line 49):

```html
      <div class="divider"></div>
      <h2 class="text-lg font-semibold mb-3">{msg:pub_edit_details_h2}</h2>
      <form method="post" action="/me/bookings/{booking.id}/edit-details" class="space-y-4">
        <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
        <div>
          <label class="label" for="edit-title">{msg:pub_edit_details_name_label}</label>
          <input id="edit-title" class="input w-full" type="text" name="title"
                 maxlength="200" value="{titleValue}" placeholder="{titlePlaceholder}">
        </div>
        <div>
          <label class="label" for="edit-desc">{msg:pub_edit_details_desc_label}</label>
          <textarea id="edit-desc" class="textarea w-full" name="description"
                    maxlength="2000" placeholder="{descPlaceholder}">{descriptionValue}</textarea>
        </div>
        {#include PublicResource/_guestschips initial=guestsCsv /}
        <button type="submit" class="btn btn-primary">{msg:pub_edit_details_btn}</button>
      </form>
```

- [ ] **Step 6: Fix the now-stale `OwnerManageBookingTest` assertion**

The host page now intentionally has a guest editor (the `_guestschips` widget uses ids `guest-entry`/`guests-data`). In `src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java`, `managePageShowsGuestAsReadOnlyTextNotInput` asserts those are **absent** — no longer true. Rename it `managePageShowsGuestEmail` and drop the two `not(containsString(...))` lines, keeping only:

```java
@Test
void managePageShowsGuestEmail() {
    when(calendarPort.isConnected(anyLong())).thenReturn(false);
    var bookingId = seedConfirmedBooking();
    seedConfirmedBookingWithGuest(bookingId);

    given().cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/bookings/" + bookingId + "/manage")
            .then()
            .statusCode(200)
            .body(containsString("guest@example.com"));
}
```

(Remove the now-unused `not` import if the compiler flags it.)

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -Dtest=OwnerEditDetailsTest,OwnerManageBookingTest -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/manageBooking.html \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/web/OwnerEditDetailsTest.java \
        src/test/java/site/asm0dey/calit/web/OwnerManageBookingTest.java
git commit -m "feat: host edits booking name/description/guests from /me (Manage hub)"
```

---

### Task 7: Invitee edit-details — endpoint, hub re-render, form + public-page name routing

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (`Templates.manage` / `confirmation` / `cancelConfirm` signatures; extract `renderManage`; new `editDetails`; pass `meetingName` to confirmation/cancel)
- Modify: `src/main/resources/templates/PublicResource/manage.html`, `confirmation.html`, `cancelConfirm.html`
- Test: `src/test/java/site/asm0dey/calit/web/InviteeEditDetailsTest.java`; re-enable `GuestBookingFlowTest.editDetailsEditsGuestList` (from Task 4)

**Interfaces:**
- Consumes: `BookingService.updateDetails(...)` (Task 3); `_guestschips`; `Booking.effectiveTitle`.
- Produces: `POST /booking/{manageToken}/edit-details` (form `title`, `description`, `guests`); `Templates.manage` gains `String titleValue, String descriptionValue, String titlePlaceholder, String descPlaceholder`; `Templates.confirmation` + `Templates.cancelConfirm` gain `String meetingName`; private `renderManage(Booking)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/InviteeEditDetailsTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class InviteeEditDetailsTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Transactional
    String seed() {
        Booking.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "invitee-edit");
        MeetingType.delete("slug", "invitee-edit");
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
        t.name = "Invitee Edit Type";
        t.slug = "invitee-edit";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        return bookingService
                .book(1L, "invitee-edit", slot.start().toInstant(), "Pat", "pat@example.com", java.util.Map.of(), "", "", "en", List.of())
                .manageToken;
    }

    @Test
    void managePageShowsEditDetailsForm() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ie", "https://meet.google.com/ie", "h"));
        var token = seed();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/booking/" + token + "/edit-details"))
                .body(containsString("name=\"title\""))
                .body(containsString("name=\"description\""));
    }

    @Test
    void inviteeEditsNameDescriptionAndGuestThenSeesHub() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Roadmap sync")
                .formParam("description", "Q3 planning")
                .formParam("guests", "ana@example.com")
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(200)
                .body(containsString("value=\"Roadmap sync\""))
                .body(containsString("/booking/" + token + "/edit-details"));

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findByManageToken(token));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
    }

    @Test
    void cancelConfirmPageShowsEditedName() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();
        bookingService.updateDetails(token, "Roadmap sync", null, List.of(), false);

        given().when()
                .get("/booking/" + token + "/cancel")
                .then()
                .statusCode(200)
                .body(containsString("Roadmap sync")); // effectiveTitle, not the type name
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=InviteeEditDetailsTest -q`
Expected: FAIL — endpoint + form absent; cancel page still shows type name.

- [ ] **Step 3: Extend signatures + extract `renderManage` + add endpoint**

In `PublicResource.java`, extend `Templates.manage` (add four params after `String initialGuests`):

```java
public static native TemplateInstance manage(
        String title,
        Booking booking,
        String currentLabel,
        String currentUtcIso,
        List<PublicResource.DaySlots> days,
        String tzBar,
        String tzScript,
        String calScript,
        String initialGuests,
        String titleValue,
        String descriptionValue,
        String titlePlaceholder,
        String descPlaceholder);
```

Extend `Templates.confirmation` (add `String meetingName` — place it right after `MeetingType type`):

```java
public static native TemplateInstance confirmation(
        String title,
        Booking booking,
        MeetingType type,
        String meetingName,
        Boolean pending,
        String location,
        String whenLabel,
        String startUtcIso,
        String tzBar,
        String tzScript);
```

Extend `Templates.cancelConfirm` (add `String meetingName` after `MeetingType type`):

```java
public static native TemplateInstance cancelConfirm(
        String title, Booking booking, MeetingType type, String meetingName, String tzScript);
```

Replace the `manage` GET handler (lines 318-355) with a thin delegate + shared render helper:

```java
@GET
@Path("/booking/{manageToken}/manage")
@Produces(MediaType.TEXT_HTML)
public TemplateInstance manage(@PathParam("manageToken") String manageToken) {
    Booking booking = Booking.findByManageToken(manageToken);
    if (booking == null) {
        throw new NotFoundException("No booking for token " + manageToken);
    }
    return renderManage(booking);
}

/** Render the invitee's Manage hub (shared by GET manage and POST edit-details). */
private TemplateInstance renderManage(Booking booking) {
    var m = messages.forLocale(activeLocale.current());
    MeetingType type = MeetingType.findById(booking.meetingTypeId);
    OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
    if (settings == null) {
        return Templates.notReady(m.pub_not_ready_title());
    }
    ZoneId zone = ZoneId.of(settings.timezone);
    List<DaySlots> byDate;
    try {
        byDate = daySlots(type);
    } catch (CalendarUnavailableException e) {
        return Templates.unavailable(m.pub_unavailable_title());
    }
    String current =
            booking.startUtc.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)"));
    String currentUtcIso = booking.startUtc.toString();
    String guestsCsv = BookingGuest.activeForBooking(booking.id).stream()
            .map(g -> g.email)
            .collect(java.util.stream.Collectors.joining(","));
    return Templates.manage(
            m.pub_manage_title(),
            booking,
            current,
            currentUtcIso,
            byDate,
            Layout.TZ_BAR,
            Layout.TZ_SCRIPT,
            Layout.CALENDAR_SCRIPT,
            guestsCsv,
            booking.title == null ? "" : booking.title, // raw override
            booking.description == null ? "" : booking.description,
            type.name,
            type.description == null ? "" : type.description);
}
```

Add the endpoint after `rescheduleBooking`:

```java
@POST
@Path("/booking/{manageToken}/edit-details")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.TEXT_HTML)
public TemplateInstance editDetails(
        @PathParam("manageToken") String manageToken,
        @RestForm String title,
        @RestForm String description,
        MultivaluedMap<String, String> form) {
    // Authenticated solely by the unguessable manage token. Re-renders the Manage hub with fresh values.
    bookingService.updateDetails(manageToken, title, description, parseGuests(form), false);
    Booking booking = Booking.findByManageToken(manageToken);
    return renderManage(booking);
}
```

Update `confirmationPage` (line 300) to compute + pass `meetingName`:

```java
String meetingName = booking.effectiveTitle(type);
return Templates.confirmation(
        title, booking, type, meetingName, pending, location, when, startUtcIso, Layout.TZ_BAR, Layout.TZ_SCRIPT);
```

Update `cancelConfirmPage` (line 387) similarly:

```java
return Templates.cancelConfirm(
        m.pub_cancel_confirm_title(), booking, type, booking.effectiveTitle(type), Layout.TZ_SCRIPT);
```

- [ ] **Step 4: Update the three public templates**

`manage.html` — add params to the header (after `{@java.lang.String initialGuests}`):

```html
{@java.lang.String titleValue}
{@java.lang.String descriptionValue}
{@java.lang.String titlePlaceholder}
{@java.lang.String descPlaceholder}
```

Insert the edit-details form just before the cancel `<div class="divider">` (before line 47):

```html
      <div class="divider"></div>
      <h2 class="text-lg font-semibold mb-3">{msg:pub_edit_details_h2}</h2>
      <form method="post" action="/booking/{booking.manageToken}/edit-details" class="space-y-4">
        <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
        <div>
          <label class="label" for="edit-title">{msg:pub_edit_details_name_label}</label>
          <input id="edit-title" class="input w-full" type="text" name="title"
                 maxlength="200" value="{titleValue}" placeholder="{titlePlaceholder}">
        </div>
        <div>
          <label class="label" for="edit-desc">{msg:pub_edit_details_desc_label}</label>
          <textarea id="edit-desc" class="textarea w-full" name="description"
                    maxlength="2000" placeholder="{descPlaceholder}">{descriptionValue}</textarea>
        </div>
        {#include PublicResource/_guestschips initial=initialGuests /}
        <button type="submit" class="btn btn-primary">{msg:pub_edit_details_btn}</button>
      </form>
```

`confirmation.html` — add `{@java.lang.String meetingName}` to the header (after the `type` line) and replace `type.name` at line 15:

```html
        <p class="text-base-content/70">{msg:pub_conf_pending_desc(booking.inviteeName, meetingName)}</p>
```

`cancelConfirm.html` — add `{@java.lang.String meetingName}` to the header (after the `type` line) and replace `{type.name}` at line 11:

```html
        <li><strong>{msg:pub_booking_meeting_label}</strong> {meetingName}</li>
```

- [ ] **Step 5: Re-enable the guest-via-edit-details web test**

If `GuestBookingFlowTest.editDetailsEditsGuestList` was `@Disabled` in Task 4, remove that annotation now (the endpoint exists).

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=InviteeEditDetailsTest,ManageBookingTest,GuestBookingFlowTest,CancelConfirmTest -q`
Expected: PASS.

- [ ] **Step 7: Full suite + formatting (cross-cutting regression guard)**

Run: `mvn test -q`
Expected: PASS (all suites — the `label(l)` refactor, template signature changes, and reschedule decoupling are all exercised). Then:
Run: `bun run css:build && mvn spotless:check -q`
Expected: clean (run `bun run format` if spotless flags anything).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/resources/templates/PublicResource/manage.html \
        src/main/resources/templates/PublicResource/confirmation.html \
        src/main/resources/templates/PublicResource/cancelConfirm.html \
        src/test/java/site/asm0dey/calit/web/InviteeEditDetailsTest.java \
        src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java
git commit -m "feat: invitee edits booking name/description/guests + name on public pages"
```

---

### Task 8: Documentation (docs-site branch)

**Files:** (on `docs-site` branch) `docs-site/src/content/docs/releases/changelog.md` + the booking-management usage page.

Per CLAUDE.md, docs are part of "done". User-facing changes: both parties can rename a booking, edit its description, and change guests post-booking (guests no longer require a reschedule); reschedule is now time-only; the Google event description reflects the meeting's description (old `"Booked via calit."` placeholder gone).

- [ ] **Step 1: Check out docs in a worktree**

```bash
git worktree add ../calit-docs docs-site
```

- [ ] **Step 2: Add a changelog entry**

At the top of `../calit-docs/docs-site/src/content/docs/releases/changelog.md`:

```markdown
## Unreleased

- **Edit a booking's name, description, and guests after booking.** From **Manage** — both the host (/me → manage booking) and the invitee (manage link) — you can rename a meeting, set/clear its description, and add or remove guests without rescheduling. Changes are emailed to the other party and pushed to the Google Calendar event and the `.ics` invite.
- **Reschedule is now time-only.** Guest changes moved to the new "Edit name & description" section; rescheduling to the same time is a no-op.
- The Google Calendar event description now reflects the meeting's description (previously a fixed "Booked via calit." placeholder).
```

- [ ] **Step 3: Update the usage page**

In the booking-management usage page, document the "Edit name & description" section on both the owner manage page and the invitee manage link, that guest edits happen there (not via reschedule), and that changes propagate via email + calendar.

- [ ] **Step 4: Commit + push + clean up**

```bash
cd ../calit-docs
git add docs-site/src/content/docs/releases/changelog.md docs-site/src/content/docs/
git commit -m "docs: editable booking name/description/guests"
git push origin docs-site
cd -
git worktree remove ../calit-docs
```

---

## Self-Review

**Spec coverage:**
- Change name → `Booking.title` + `effectiveTitle`, routed everywhere via `label(l)` + Google summary + public pages (Tasks 1, 5, 7). ✓
- Change description → `Booking.description` + `effectiveDescription`, `.ics` DESCRIPTION, Google description, updated email (Tasks 1–3, 5). ✓
- Change guests, both sides → `updateDetails` reconciles guests + guest `.ics` fan-out; editable on both manage forms; reschedule decoupled (Tasks 3–7). ✓
- Both invitee and host → host `/me` (Task 6), invitee manage link (Task 7); both notified via `sendForKindLocaleAware` + Google/`.ics`. ✓

**All ten grilling decisions represented:** PENDING-editable (Task 3 guard), no-op guard (Task 3 + test), Google suffix (Task 3 `googleSummary`), length bounds (Task 3 `validateDetailBounds` + test), guest re-notify (Task 5 `sendGuestInvites`), separation-of-concerns + reschedule-no-op + guest-decouple (Task 4), hub re-render via `renderManage` (Tasks 6–7), raw-override prefill + placeholder (Tasks 6–7 templates + `renderManage`), public-page name routing (Task 7), scrollable slots (Task 4 CSS). ✓

**Placeholder scan:** no TBD/TODO; every code + test step is complete; i18n de+he provided for all new keys. ✓

**Type consistency:** `updateDetails(String,String,String,List<String>,boolean)`, `updateEventDetails(Long,String,String,String,List<String>)`, `effectiveTitle/effectiveDescription`, `label(Loaded)`, `googleSummary/googleDescription`, `blankToNull/sameGuestSet/validateDetailBounds`, `renderManage`, `BookingDetailsChanged(Long,boolean)`, and the template params (`titleValue/descriptionValue/titlePlaceholder/descPlaceholder`, `meetingName`) are used identically across tasks. ✓

**Cross-task ordering:** `GuestBookingFlowTest.editDetailsEditsGuestList` (rewritten in Task 4) targets the `/edit-details` endpoint created in Task 7 — flagged in both tasks (disable-in-4 / re-enable-in-7, or land together). The service-level reschedule no-op test is independent and passes in Task 4.
