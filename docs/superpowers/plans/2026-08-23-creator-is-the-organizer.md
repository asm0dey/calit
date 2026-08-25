# The Creator Is Always the Organizer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Google event is written on the Creator's connected account or not at all, which makes the Co-host write override dead code and lets a Booking fall back to the meeting type's stored location when no Meet link can be minted.

**Architecture:** Two of `WriteTargetResolver`'s consumers currently disagree about who writes a shared type's Google event. `BookingService:521` (single-host) always writes on `type.ownerId`; `BookingService:432` (group) writes on whatever `MeetingHosts.chooseOrganizer` returned, which falls back to the lowest-id connected Co-host. Collapsing that fallback to "the Creator if connected, else nobody" makes both call sites identical, which in turn makes `writeOverride(coHostId, type)` unreachable — so the Co-host picker, its save path, and its two `meeting_type_host` columns all get deleted rather than gated. Separately, `GOOGLE_MEET` is the only location kind calit does not store, so when no link is minted the Booking now shows the type's own `location_detail` instead of nothing.

**Tech Stack:** Quarkus 3.38 / Java 25, Panache entities, Qute templates, Flyway migrations, JUnit 5 + RestAssured + Mockito, MockMailbox.

**Spec:** `docs/adr/0007-the-creator-is-always-the-organizer.md` and `docs/adr/0005-the-location-belongs-to-the-meeting-type.md`. Glossary: `CONTEXT.md` (**Location**, **Write override**, **Host**). Bean: `calit-5fw0`.

## Global Constraints

- Branch `feat/creator-is-the-organizer`. **Never push to `main`.** PR only.
- `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` before any `mvn`/`./mvnw` — the default JDK 21 fails with "release 25 not supported".
- Docker must be running: Dev Services provisions Postgres for every `@QuarkusTest`.
- The **whole** suite must be green (`0 failures, 0 errors, BUILD SUCCESS`) before the branch becomes a PR. Not just the classes touched.
- **Never edit an applied migration.** New `V29__*.sql` only.
- No new user-facing strings in this plan, so no `messages/*_{de,he}.properties` changes. If a step tempts you to add one, stop — it is out of scope.
- Run `mvn spotless:apply` before each commit (the pre-commit hook does staged files; a full run is cheaper than a CI round-trip).
- Admin user is always id 1 (`DatabaseResetCallback`). Owner-scoped tests rely on it.

## Decision Recorded Here

The write-calendar picker on `sharedAvailability.html` is shared by both roles — a Creator can reach `/me/shared/{typeId}/availability` through their own `CREATOR` host row, and `SharedWriteCalendarTest:138` pins that behaviour. This plan **removes the picker from that page entirely**, for both roles, because the Creator already has the same picker on their meeting-type detail page (`AdminResource`, `meetingTypeDetail.html`). If the Creator should keep a picker on the shared page, Task 3 instead gains an `isCreator` template flag and `SharedWriteCalendarTest:138` survives — say so before starting Task 3.

---

### Task 1: The Organizer is the Creator or nobody

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java:111-122`
- Test: `src/test/java/site/asm0dey/calit/booking/MeetingHostsTest.java:56-79`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Long MeetingHosts.chooseOrganizer(MeetingType type, List<Long> hostOwnerIds)` — unchanged signature, new contract: returns `type.ownerId` when that account is connected, otherwise `null`. Task 2 relies on the null case meaning "no Google event".

- [ ] **Step 1: Rewrite the existing test to the new contract**

Replace the whole `organizerPrefersCreatorThenLowestConnectedThenNull` method (`MeetingHostsTest.java:56-79`) with:

```java
    @Test
    @TestTransaction
    void organizerIsTheCreatorOrNobody() {
        MeetingType t = multiHostType();
        // Accept the co-host row so hostOwnerIds(t) returns both hosts -- with only the PENDING
        // row it would return just [1] and the co-host case would not be exercised at all.
        MeetingTypeHost.forType(t.id).forEach(h -> h.status = MeetingTypeHost.ACCEPTED);
        em.flush();
        List<Long> hosts = meetingHosts.hostOwnerIds(t);
        assertEquals(2, hosts.size());
        var cohostId = hosts.stream().filter(id -> !id.equals(1L)).findFirst().orElseThrow();

        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(cohostId)).thenReturn(false);
        assertEquals(1L, meetingHosts.chooseOrganizer(t, hosts));

        // ADR-0007: a connected co-host is NOT promoted when the creator is disconnected.
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        assertNull(meetingHosts.chooseOrganizer(t, hosts));

        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        assertNull(meetingHosts.chooseOrganizer(t, hosts));
    }
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=MeetingHostsTest#organizerIsTheCreatorOrNobody
```

Expected: FAIL — `expected: <null> but was: <2>` on the middle assertion.

- [ ] **Step 3: Collapse the fallback**

Replace `MeetingHosts.java:111-122` with:

```java
    /**
     * The Host whose connected account this type's Google event is created on: the Creator, or
     * nobody. A connected Co-host is never promoted -- see
     * {@code docs/adr/0007-the-creator-is-always-the-organizer.md}. A null return means the Booking
     * is calit-only: every scheduling feature still works, only the Google mirror is absent.
     */
    public Long chooseOrganizer(MeetingType type, List<Long> hostOwnerIds) {
        return calendarPort.isConnected(type.ownerId) ? type.ownerId : null;
    }
```

Keep the `hostOwnerIds` parameter: `BookingService:418` already computes the list for `groupAttendeeEmails` and passes it here, and dropping it churns the call site for nothing.

- [ ] **Step 4: Run it and watch it pass**

```bash
mvn test -Dtest=MeetingHostsTest
```

Expected: PASS, whole class.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/booking/MeetingHosts.java src/test/java/site/asm0dey/calit/booking/MeetingHostsTest.java
git commit -m "feat(booking): the Creator is the only Organizer

A connected Co-host is no longer promoted when the Creator's account is
disconnected -- such a booking is calit-only. ADR-0007."
```

---

### Task 2: The group event is always written on the Creator

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:415-449`
- Test: `src/test/java/site/asm0dey/calit/booking/BookingWriteTargetOverrideTest.java:135-189`

**Interfaces:**
- Consumes: `chooseOrganizer` from Task 1 — returns `type.ownerId` or `null`.
- Produces: `createGroupGoogleEvent` writes through `writeTargets.resolve(type.ownerId, type)` and stamps the group's lead row. Task 4 relies on this being the last reader of a non-Creator override.

- [ ] **Step 1: Replace the co-host-organizer test**

Replace `groupOrganizerUsesTheCohostsOwnOverrideNotTheCreators` (`BookingWriteTargetOverrideTest.java:135-189`, everything from `@Test` down to the closing brace before `private void stubGoogle()`) with:

```java
    @Test
    @TestTransaction
    void aDisconnectedCreatorCreatesNoGroupEventEvenWhenACohostIsConnected() {
        Long cohostId = MultiHostFixtures.cohost("cohost-nogoogle");
        MultiHostFixtures.rule(1L, DAY.getDayOfWeek(), 9, 11);
        MultiHostFixtures.rule(cohostId, DAY.getDayOfWeek(), 9, 11);

        MeetingType t = MultiHostFixtures.acceptedTwoHostType(1L, cohostId, "group-no-organizer", 60, false);
        t.locationType = MeetingType.LocationType.PHONE;
        t.persist();

        // ADR-0007: only the creator's account may carry the event. The creator is disconnected
        // here, so no Google event exists at all -- the co-host is NOT promoted to organizer.
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        bookingService.book(1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        verify(calendarPort, never())
                .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }
```

Add `import static org.mockito.Mockito.never;` if it is not already imported.

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn test -Dtest=BookingWriteTargetOverrideTest#aDisconnectedCreatorCreatesNoGroupEventEvenWhenACohostIsConnected
```

Expected: PASS already, because Task 1 landed. That is fine and expected — this test guards Task 1's contract at the booking level. If it FAILS, Task 1 is not actually in the working tree; stop and check.

- [ ] **Step 3: Delete the now-unreachable organizer plumbing**

Replace `BookingService.java:415-449` with:

```java
    /** One Google event on the Creator's account, all other hosts + invitee + guests invited. */
    private void createGroupGoogleEvent(MeetingType type, UUID groupId) {
        List<Long> hostIds = meetingHosts.hostOwnerIds(type);
        if (meetingHosts.chooseOrganizer(type, hostIds) == null) {
            return; // the creator has no Google -> calit-only booking (ADR-0007)
        }
        // The organizer is always the creator (ADR-0007), and the creator's row IS the group's
        // lead row -- so there is no separate organizer row to look up, and no meet link to
        // propagate from one row to the other.
        Booking lead = Booking.leadOfGroup(groupId, type.ownerId);
        List<String> attendees = groupAttendeeEmails(type, groupId, hostIds);
        CreatedEvent created = calendarPort.createEvent(
                type.ownerId,
                writeTargets.resolve(type.ownerId, type),
                googleSummary(type, lead),
                googleDescription(type, lead),
                lead.startUtc,
                lead.endUtc,
                attendees,
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
        lead.googleEventId = created.googleEventId();
        lead.meetLink = created.meetLink();
        lead.googleCalendarId = created.calendar() == null ? null : created.calendar().googleCalendarId();
        lead.googleCredentialId = created.calendar() == null ? null : created.calendar().credentialId();
    }
```

- [ ] **Step 4: Fix the dangling javadoc reference**

`BookingWriteTargetOverrideTest`'s `book(...)` helper carries an `{@link
#groupOrganizerUsesTheCohostsOwnOverrideNotTheCreators}` in its javadoc — the method Step 1 replaced.
Rewrite that sentence to state the current model instead: the creator's override lives on the
`MeetingType` row and is the only one, because the creator is always the Organizer (ADR-0007).

- [ ] **Step 5: Run the booking suite**

```bash
mvn test -Dtest='Booking*Test'
```

Expected: PASS. If a test asserting "the organizer's row carries the event" fails, read it — the comments at `BookingService:1010`, `:1058` and `:1198` describe the same invariant and are now simply "the lead row"; update those comments, not the behaviour.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/booking/BookingService.java src/test/java/site/asm0dey/calit/booking/BookingWriteTargetOverrideTest.java
git commit -m "refactor(booking): the group event's row is always the lead row

With the Creator the only Organizer, the organizer-row lookup and the
meet-link propagation between rows are both unreachable. ADR-0007."
```

---

### Task 3: Remove the write-calendar picker from the shared page

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java` (`Templates.sharedAvailability` signature ~`:63-77`, `availabilityInstance` `:217-260`, `saveBuffers` `:360-390`, `applyWriteCalendar` `:398-430`)
- Modify: `src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html:6-8, 24-26, 40-49`
- Delete: `src/test/java/site/asm0dey/calit/web/SharedWriteCalendarTest.java`

**Interfaces:**
- Consumes: nothing — this task is pure deletion of UI that Task 2 made pointless.
- Produces: `saveBuffers(Long typeId, String bufferBeforeMinutes, String bufferAfterMinutes)` — the `writeCalendar` form param is gone. `Templates.sharedAvailability` loses its `writeCalendars`, `writeCalendarValue` and `writeCalendarDangling` parameters. Task 4 relies on nothing writing `MeetingTypeHost.googleCalendarId` any more.

- [ ] **Step 1: Delete the test class that pins the old behaviour**

```bash
git rm src/test/java/site/asm0dey/calit/web/SharedWriteCalendarTest.java
```

Every one of its ten tests asserts the picker, its save path, or its dangling-override warning. There is nothing in it to keep: the buffers form's own behaviour is covered elsewhere in the shared-meetings tests. Do **not** delete `AdminWriteCalendarTest` or `AdminMeetGatingOverrideTest` — those cover the Creator's picker on the meeting-type detail page, which stays.

- [ ] **Step 2: Run the web tests and watch them fail to compile / fail**

```bash
mvn test -Dtest='Shared*Test'
```

Expected: PASS (the class is gone). This step exists to confirm no *other* shared test depended on it before you start cutting main code.

- [ ] **Step 3: Cut the resource**

In `SharedMeetingsResource.java`:

1. `Templates.sharedAvailability` — delete the three parameters:

```java
        public static native TemplateInstance sharedAvailability(
                MeetingType type,
                MeetingTypeHost host,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String error,
                String notice,
                String title);
```

2. `availabilityInstance` — delete the four `var override / writeCalendars / writeCalendarDangling / writeCalendarValue` lines and its long explanatory comment, and the three arguments from the `Templates.sharedAvailability(...)` call.

3. `saveBuffers` — drop the `writeCalendar` param and everything that read it:

```java
    @POST
    @Path("/shared/{typeId}/buffers")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance saveBuffers(
            @PathParam("typeId") Long typeId,
            @RestForm String bufferBeforeMinutes,
            @RestForm String bufferAfterMinutes) {
        // The host row is loaded + dirty-mutated INSIDE the tx so its changes flush on commit,
        // which happens before the render (#75).
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingTypeHost h = requireAcceptedHost(typeId);
            h.bufferBeforeMinutes = parseNonNegativeIntOrNull(bufferBeforeMinutes);
            h.bufferAfterMinutes = parseNonNegativeIntOrNull(bufferAfterMinutes);
        });
        return availabilityInstance(typeId, null);
    }
```

4. Delete `applyWriteCalendar` entirely, and the now-unused `AtomicLong` / `Objects` / `CalendarRef` / `GoogleCalendar` / `WriteTargetResolver` imports and the `writeTargets` field **only if** nothing else in the class still uses them — check with `grep -n "writeTargets\|CalendarRef\|GoogleCalendar" src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java` before removing any of them.

- [ ] **Step 4: Cut the template**

In `sharedAvailability.html`, delete the three parameter declarations at lines 6-8, the `{#if writeCalendarDangling}` alert block at lines 24-26, and the whole `{#if writeCalendars.size > 0}` … `{/if}` block containing the `<select id="sh-write-calendar">` (lines 40-49). Leave the buffers inputs and the submit button exactly as they are.

- [ ] **Step 5: Run the web suite**

```bash
mvn test -Dtest='Shared*Test,Admin*Test'
```

Expected: PASS. A Qute build failure naming a removed parameter means one of the three declarations or a usage was missed.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply
git add -A src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html src/test/java/site/asm0dey/calit/web/SharedWriteCalendarTest.java
git commit -m "feat(shared): drop the write-calendar picker from the shared page

Nothing reads a Co-host's override any more; the Creator picks their write
calendar on the meeting-type detail page. ADR-0007."
```

---

### Task 4: Drop the Co-host override storage

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java:44-53`
- Modify: `src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java:47-52`
- Create: `src/main/resources/db/migration/V29__drop_meeting_type_host_write_override.sql`
- Test: `src/test/java/site/asm0dey/calit/google/WriteTargetResolverTest.java`

**Interfaces:**
- Consumes: Tasks 2 and 3 — no reader and no writer of the host-row override is left.
- Produces: `WriteTargetResolver.writeOverride(Long ownerId, MeetingType type)` returns the type's own columns for the Creator and `null` for anyone else.

- [ ] **Step 1: Write the failing test**

Add to `WriteTargetResolverTest`:

```java
    @Test
    @TestTransaction
    void aNonCreatorHasNoOverride() {
        MeetingType t = seedType(1L);
        t.googleCredentialId = 7L;
        t.googleCalendarId = "creator-override@example.com";
        t.persist();

        // ADR-0007: only the Creator's override exists. Any other owner resolves to their own
        // write target, never to a per-type choice of their own.
        assertNull(resolver.writeOverride(2L, t));
        assertEquals(new CalendarRef(7L, "creator-override@example.com"), resolver.writeOverride(1L, t));
    }
```

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn test -Dtest=WriteTargetResolverTest#aNonCreatorHasNoOverride
```

Expected: FAIL — the host-row branch still returns a `CalendarRef` when a `meeting_type_host` row carries one; with no such row it returns null and the test passes vacuously. If it passes, seed a host-row override first so the assertion has teeth.

- [ ] **Step 3: Delete the host-row branch**

Replace `WriteTargetResolver.java:44-53` with:

```java
    /**
     * The stored write override for this meeting type, or null when unset or when {@code ownerId}
     * is not its Creator. Only the Creator has one -- every Google event is written on their
     * account ({@code docs/adr/0007-the-creator-is-always-the-organizer.md}). A row whose
     * credential was nulled by disconnecting the account keeps its calendar id, and that half-row
     * IS an override -- a dangling one. Reading it as "unset" would hide the very case the Creator
     * needs to be told about.
     */
    public CalendarRef writeOverride(Long ownerId, MeetingType type) {
        if (ownerId == null || type == null || !ownerId.equals(type.ownerId)) {
            return null;
        }
        return ref(type.googleCredentialId, type.googleCalendarId);
    }
```

Then delete the now-unused `MeetingTypeHost` import if nothing else in the file uses it.

- [ ] **Step 4: Delete the entity fields and write the migration**

Remove `googleCalendarId` and `googleCredentialId` (and their javadoc) from `MeetingTypeHost.java:47-52`. Then:

```sql
-- V29__drop_meeting_type_host_write_override.sql
-- ADR-0007: the Creator is always the Organizer, so only the Creator's write override
-- (meeting_type.google_credential_id / google_calendar_id) is ever read. A Co-host's
-- per-type calendar choice had no reader left; drop the columns rather than leave dead
-- schema that Hibernate's validate-only strategy would keep asserting.
alter table meeting_type_host drop column if exists google_credential_id;
alter table meeting_type_host drop column if exists google_calendar_id;
```

- [ ] **Step 5: Run the full suite**

```bash
mvn test
```

Expected: PASS. Hibernate is validate-only, so a column left on the entity but dropped in SQL (or the reverse) fails at boot with a schema-validation error naming the column — that is the check working, not a flake.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply
git add -A src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java src/main/resources/db/migration/V29__drop_meeting_type_host_write_override.sql src/test/java/site/asm0dey/calit/google/WriteTargetResolverTest.java
git commit -m "feat(db): drop the Co-host write-override columns

Only the Creator's override is ever read. V29 removes the dead
meeting_type_host columns. ADR-0007."
```

---

### Task 5: A missing Meet link falls back to the type's stored location

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java:882-888`
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java:390-391`
- Modify: `src/main/resources/templates/PublicResource/book.html:41-44`
- Test: `src/test/java/site/asm0dey/calit/booking/MeetLinkFallbackTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks — independent, and could ship on its own.
- Produces: no new API. Both readers of a Booking's location answer "the minted link, else the meeting type's `locationDetail`".

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/MeetLinkFallbackTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

/** ADR-0005: a GOOGLE_MEET booking with no minted link shows the meeting type's own location. */
@QuarkusTest
class MeetLinkFallbackTest {

    private static final String STANDING_LINK = "https://meet.example.com/standing-room";
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Inject
    MockMailbox mailbox;

    @Test
    @TestTransaction
    void aMeetTypeWithNoMintedLinkMailsTheTypesOwnLocation() {
        mailbox.clear();
        MeetingType t = seedMeetType();

        // The calendar accepted the event but minted no conference -- the real path is
        // GoogleCalendarPort.retryWithoutConference clearing supportsMeet mid-booking.
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", null, null, null)); // null meetLink

        Booking b = bookingService.book(
                1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        assertNull(b.meetLink, "the mint failed -- this test is meaningless if a link exists");
        assertTrue(
                mailbox.getMailsSentTo("sam@example.com").stream()
                        .anyMatch(m -> m.getText() != null && m.getText().contains(STANDING_LINK)),
                "the invitee's mail must carry the type's own location when no link was minted");
    }

    private MeetingType seedMeetType() {
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
        t.name = "meet-fallback";
        t.slug = "meet-fallback-" + UUID.randomUUID();
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = MeetingType.LocationType.GOOGLE_MEET;
        t.locationDetail = STANDING_LINK;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }
}
```

`mailbox.getMailsSentTo(...)` needs the mail to have been sent synchronously; if the enqueue is
event-driven and the assertion races, mirror how `EmailEnqueueTest` waits — do not add a sleep.

For the `PublicResource` half, find the handler that renders `confirmation.html`
(`grep -n "confirmation(" src/main/java/site/asm0dey/calit/web/PublicResource.java`) and post the
booking form to it with RestAssured, asserting the response body contains `STANDING_LINK`. The
seeded admin's username is `admin` (`DatabaseResetCallback:43`), so the public path is
`/admin/{slug}`. If that handler has no existing test to mirror, the email assertion above plus a
direct reading of the changed expression is acceptable coverage — say so in the commit body rather
than leaving it unsaid.

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn test -Dtest=MeetLinkFallbackTest
```

Expected: FAIL — the page and the mail both render an empty location, because `resolveLocation` returns the null `meetLink` and `PublicResource:391` does the same.

- [ ] **Step 3: Add the fallback in both readers**

`EmailService.java:882-888`:

```java
    /**
     * The Booking's location: the minted Meet link for GOOGLE_MEET types, falling back to the
     * meeting type's own locationDetail when no link could be minted (a disconnected Creator, or
     * a calendar that turned out not to support conferences). The location belongs to the meeting
     * type -- see docs/adr/0005-the-location-belongs-to-the-meeting-type.md -- so an Owner may
     * publish a Meet type and paste their own standing link there. Non-Meet types read the same
     * field directly.
     */
    private static String resolveLocation(Loaded l) {
        if (l.meetingType.locationType == LocationType.GOOGLE_MEET && l.booking.meetLink != null) {
            return l.booking.meetLink;
        }
        return l.meetingType.locationDetail;
    }
```

`PublicResource.java:390-391`:

```java
        // Minted Meet link, else the type's own locationDetail (ADR-0005: the location belongs to
        // the meeting type, so an Owner may publish a standing link of their own).
        String location = (type.locationType == MeetingType.LocationType.GOOGLE_MEET && booking.meetLink != null)
                ? booking.meetLink
                : type.locationDetail;
```

- [ ] **Step 4: Run it and watch it pass**

```bash
mvn test -Dtest='MeetLinkFallbackTest,Email*Test,Public*Test'
```

Expected: PASS. A pre-existing test asserting an empty location for a link-less Meet booking is asserting the bug — update it to the new expectation and say so in the commit body.

- [ ] **Step 5: Show the standing link on the booking form too**

`book.html:41-44` currently shows a Meet hint only when Google is connected, and shows nothing at all
for a `GOOGLE_MEET` type on a disconnected Owner — so an Invitee choosing a slot sees no location
where one exists. Change that block to:

```html
          {#if type.locationType.name == 'GOOGLE_MEET'}
            {#if googleConnected}<p class="text-sm mb-2"><strong>{msg:pub_book_location_label}</strong> {msg:pub_book_meet_hint}</p>
            {#else if type.locationDetail}<p class="text-sm mb-2"><strong>{msg:pub_book_location_label}</strong> {type.locationDetail}</p>{/if}
```

Reuses `pub_book_location_label`, which already carries its `de` and `he` translations — no new key.

Known cosmetic limit, deliberately not fixed: `confirmation.html:25` renders a Meet type's location as
`<a href="{location}">`, so a fallback value that is not a URL becomes a broken link. The field is
documented as a place to paste a link; a validator for it is a separate decision.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply
git add -A src/main/java/site/asm0dey/calit/email/EmailService.java src/main/java/site/asm0dey/calit/web/PublicResource.java src/main/resources/templates/PublicResource/book.html src/test/java/site/asm0dey/calit/booking/MeetLinkFallbackTest.java
git commit -m "feat(booking): fall back to the type's location when no Meet link exists

An Invitee is never shown an empty location, and an Owner with no connected
account can publish a Meet type with their own standing link. ADR-0005."
```

---

### Task 6: Docs, changelog and the PR

**Files:**
- Modify: `docs-site/src/content/docs/releases/changelog.md` (on the **`docs-site`** branch, not this one)
- Modify: any docs page describing the Co-host's per-type calendar, if one exists

- [ ] **Step 1: Run the whole suite, and read the output**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test
```

Required: `Tests run: N, Failures: 0, Errors: 0` and `BUILD SUCCESS`. A red suite is not a PR, whatever the cause.

- [ ] **Step 2: Verify the formatting gate**

```bash
mvn spotless:check
```

Expected: PASS. `verify` (hence CI) fails on unformatted code.

- [ ] **Step 3: Add the changelog entry on `docs-site`**

```bash
git worktree add ../calit-docs docs-site
```

Under `## Unreleased` in `docs-site/src/content/docs/releases/changelog.md` (create the section with its standing subtitle "Merged but not yet in a tagged release." if absent):

```markdown
- **A shared meeting type's Google event is always written on the creator's calendar.** Before, if
  the creator had disconnected their Google account, calit promoted a connected co-host to write the
  event, which put the meeting on that co-host's calendar and — on a Google Meet type — minted the
  join link from a calendar that might not support conferences. Now the event is written on the
  creator's account or not at all: with the creator disconnected, a booking is calit-only. calit's
  own confirmations and `.ics` attachments still reach every host and the invitee, and every
  scheduling feature keeps working. Co-hosts no longer choose a per-type write calendar — that
  setting was only ever read when a co-host became the organizer, and it has been removed.
  ([#N](https://github.com/asm0dey/calit/pull/N))
- **A Google Meet booking with no join link now shows the meeting type's own location.** Paste a
  standing meeting link into a Meet type's location field and it is used whenever Google could not
  mint one — including when no Google account is connected at all. Before, such bookings showed an
  empty location on the confirmation page and in every email.
  ([#N](https://github.com/asm0dey/calit/pull/N))
```

Close the section with the upgrade note: co-hosts who had set a per-type write calendar lose that setting (the columns are dropped by `V29`); no configuration change is needed.

- [ ] **Step 4: Grep the docs site for anything the removal contradicts**

```bash
grep -rin "write calendar\|co-host" ../calit-docs/docs-site/src/content/docs/ | head -20
```

Fix any page that tells a co-host they can pick a calendar for a shared type.

- [ ] **Step 5: Close the bean and open the PR**

Tick every remaining `- [ ]` in the bean as you go, then close it with what actually shipped:

```bash
beans update calit-5fw0 -s completed --body-append "## Summary of Changes

chooseOrganizer returns the Creator or null; createGroupGoogleEvent stamps the lead
row directly; the shared page's write-calendar picker, its save path and
SharedWriteCalendarTest are gone; V29 drops the two meeting_type_host columns; and a
GOOGLE_MEET booking with no minted link now shows the meeting type's own
locationDetail on the booking form, the confirmation page and every email.
Full suite green: <paste the Tests run: line>."

gh pr create --base main --head feat/creator-is-the-organizer \
  --title "The Creator is always the Organizer" \
  --body-file /tmp/pr-body.md
```

The PR body must contain, concretely: the `Tests run: N, Failures: 0, Errors: 0` line from the full
suite; the user-visible change (with the Creator disconnected, Co-hosts stop receiving the Google
invitation and keep calit's own mail and `.ics`); the note that `V29` drops columns, so a Co-host's
per-type calendar choice is discarded on upgrade; and a link to the changelog commit on `docs-site`.
Replace every `#N` in that changelog entry with the real PR number once `gh pr create` prints it.
