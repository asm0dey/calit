# High-Severity Bugs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the six open bugs triaged as high severity on 2026-11-16: #331, #309, #327, #325, #310, #283.

**Architecture:** Six independent fixes, one task each, one commit each, on one branch. Tasks 1-5 are
small and touch disjoint code; Task 6 (#283) is the only one that adds a type (`EventGoneException`)
and changes `CalendarPort` behaviour. Order is smallest-risk first, so a reviewer can stop after any task.

**Tech Stack:** Quarkus 3 / Java 25, Panache, Flyway, `java.net.http`, Google Calendar API client, JUnit 5 + AssertJ + Mockito.

**Spec:** the GitHub issues themselves are the spec: `gh issue view <n>` for 331, 309, 327, 325, 310, 283.
For #283 also read `docs/adr/0001-google-event-is-a-mirror-of-the-booking.md` and
`docs/adr/0013-no-write-calendar-means-no-mirror-event.md`.

### Triage (2026-11-16)

| Severity | Issues | Why |
|---|---|---|
| High (this plan) | #331, #309, #327, #325, #283, #310 | DoS on a shared instance; a rescheduled booking auto-declined ~60 s later; Co-hosts dropped from a group event; a cancelled event resurrected in the invitee's calendar; 500 on reschedule with no mail sent; duplicate cancellation mail |
| Medium | #311, #308, #332, #306, #303 | wrong but narrow (removed guest can decline, stale guest event after expiry, two-provider owners only, latency, misleading banner) |
| Low | #295, #294 | pre-bootstrap only / cosmetic unfurl |

## Global Constraints

- Branch from `origin/main`, not local `main`: `git switch -c fix/high-severity-bugs origin/main`.
- Assertions: AssertJ only (`assertThat`, `assertThatThrownBy`, `assertThatCode`); Hamcrest only inside RestAssured `.body(...)`.
- JDK imports are module imports (`import module java.base;`, `import module java.net.http;`).
- Never edit an applied migration; the next migration is `V41__…`. Migrations are expand-only: the previous release must run on the new schema.
- Every new `booking` column must be added to `PersonalData.TABLES` (`src/main/java/site/asm0dey/calit/privacy/PersonalData.java`), or `PersonalDataInventoryTest` fails.
- No new user-facing strings in this plan. If one gets added anyway, it needs `de` and `he` values in `src/main/resources/messages/*.properties` in the same commit.
- Tests run with Docker up. Per task: `mvn test -Dtest=<Class>`. Before the PR: full `mvn test`, 0 failures, `BUILD SUCCESS`.
- Format before commit: `mvn spotless:apply` (lefthook does it for staged `*.java`).
- Commit message trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; each commit body ends `Fixes #N`.

## Review Focus

1. **#331: a server that sends headers and then nothing at all** (stall, not drip) must fail within the same deadline. Covered by Task 1's drip test: the deadline is on the whole response, so a stall is the zero-rate case of a drip.
2. **#309: a host-initiated group reschedule** sets the *other* hosts' rows to PENDING (`SlotFit.groupRowStatusAfterMove`). Those rows need `pendingSince` too, not only the invitee path. Task 2 Step 1 covers it.
3. **#327: a group booked before ADR 0007**, whose Mirror event sits on a Co-host's row, not the lead's. The guest-decline resync must patch the row that holds the event (`groupEventRow`). Task 3 Step 1 covers it.
4. **#310: cancel via a Co-host's manage token** after the group was already cancelled through the lead's. Must be a no-op too. Task 5 Step 1 covers it.
5. **#283: the recreate finds no write calendar** (owner removed it). `createGoogleEvent` gets a null target → `stampCreatedEvent(row, null)` → the row keeps no address, no 500 (ADR 0013). Task 6 Step 5 covers it.

---

### Task 1: #331 — deadline on the CalDAV response body

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/caldav/CaldavHttp.java` (`execute`, `readCapped`, `charset`)
- Test: `src/test/java/site/asm0dey/calit/caldav/CaldavHttpTest.java`

**Interfaces:** none change. `CaldavHttp.send(...)` keeps its signature and `Response` record.

- [ ] **Step 1: Write the failing test**

Add a `/drip` context in `start()`: `sendResponseHeaders(200, 0)`, then write one byte and `flush()` every 100 ms, at most 100 times, catching `IOException` (client hung up) and `InterruptedException`. Then:

```java
@Test
void slowDripBodyFailsWithinTheTimeout() {
    var http = new CaldavHttp(config(true)); // timeout() is 2 s
    long t0 = System.nanoTime();
    assertThatThrownBy(() -> http.send("u", "p", "GET", url("/drip"), Map.of(), null))
        .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
            .isEqualTo(CaldavException.Reason.UNREACHABLE));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
}
```

`HttpServer` with no executor runs handlers on its dispatcher thread, so give the server `server.setExecutor(Executors.newCachedThreadPool())` in `start()`, or the drip handler blocks `stop()`.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=CaldavHttpTest#slowDripBodyFailsWithinTheTimeout`
Expected: FAIL. Elapsed ≈ 10 s (the drip finishes), and no exception is thrown.

- [ ] **Step 3: Bound the whole response in `execute(HttpRequest)`**

- Send with `client.sendAsync(request, BodyHandlers.limiting(BodyHandlers.ofString(), MAX_BODY))`.
- Wait with `.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS)`. `ofString()` already decodes with the `Content-Type` charset and defaults to UTF-8, so `readCapped` and `charset` are deleted.
- `Fetched` becomes `HttpResponse<String>`; `send` reads `res.body()`.
- `TimeoutException` → `future.cancel(true)`, then throw `CaldavException(UNREACHABLE, 0, "CalDAV server too slow", e)`.
- `InterruptedException` → keep today's handling.
- `ExecutionException` → unwrap the cause:
  - an `IOException` whose message starts with `"body exceeds capacity"` → `HTTP_ERROR`, `"CalDAV response too large"`;
  - any other `IOException` → `UNREACHABLE`;
  - anything else → rethrow wrapped in `CaldavException(HTTP_ERROR, …)`.
- Put this comment on the message check: `// shortcut: JDK's LimitingSubscriber signals overflow only by message; oversizedBodyIsRefused pins it, swap for a counting subscriber if a JDK changes it`.

- [ ] **Step 4: Run the class**

Run: `mvn test -Dtest=CaldavHttpTest`
Expected: PASS, all 8 tests (`oversizedBodyIsRefused` still `HTTP_ERROR`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/caldav/CaldavHttp.java src/test/java/site/asm0dey/calit/caldav/CaldavHttpTest.java
git commit -m "fix(caldav): bound the whole response, not just headers, by the timeout"
```

---

### Task 2: #309 — pending deadline counts from when approval was requested

**Files:**
- Create: `src/main/resources/db/migration/V41__booking_pending_since.sql`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java` (field next to `createdAt`, ~line 54)
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`: the single reschedule `if (reApproval)` block (~line 1253) and `moveGroupRows` (~line 1380)
- Modify: `src/main/java/site/asm0dey/calit/scheduler/PendingExpiryScheduler.java:126` and its javadoc at `:107`
- Modify: `src/main/java/site/asm0dey/calit/privacy/PersonalData.java` (booking column set)
- Test: `src/test/java/site/asm0dey/calit/scheduler/PendingExpiryTest.java`, `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java`, `src/test/java/site/asm0dey/calit/booking/GroupCancelRescheduleTest.java`

**Interfaces:**
- Produces: `Booking.pendingSince` (`Instant`, nullable, column `pending_since TIMESTAMPTZ`). Null means "since `createdAt`". It is set only when a reschedule puts a row back into PENDING; creation leaves it null.

- [ ] **Step 1: Write the failing tests**

`PendingExpiryTest`: give `seedBooking` an overload that also takes `Instant pendingSince`, then:

```java
@Test
void holdCountsFromPendingSinceWhenSet() {
    var mt = seedMeetingType();
    Long reRequested = seedBooking(mt, Instant.now().minus(25, ChronoUnit.HOURS), uniqueFutureStart(),
            BookingStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));
    Long staleReRequest = seedBooking(mt, Instant.now().minus(50, ChronoUnit.HOURS), uniqueFutureStart(),
            BookingStatus.PENDING, Instant.now().minus(25, ChronoUnit.HOURS));
    scheduler.expirePendingBookings();
    assertThat(reloadStatus(reRequested)).isEqualTo(BookingStatus.PENDING);
    assertThat(reloadStatus(staleReRequest)).isEqualTo(BookingStatus.DECLINED);
}
```

`RescheduleCancelTest`: `inviteeRescheduleToPendingStampsPendingSince`. Use the same setup as `rescheduleApprovalTypeReturnsToPendingAndDeletesEvent`, but set the booking's `createdAt` to 48 h ago before the reschedule. Assert `status == PENDING` and `pendingSince` is `isAfter(Instant.now().minusSeconds(60))`.

`GroupCancelRescheduleTest`: add an assertion to both `inviteeRescheduleOfApprovalGroupResetsAllToPendingAndDeletesEvent` and `hostInitiatedRescheduleOfApprovalGroupKeepsInitiatorConfirmedOthersPending`: every row with `status == PENDING` has a non-null `pendingSince` after the move.

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn test -Dtest='PendingExpiryTest,RescheduleCancelTest,GroupCancelRescheduleTest'`
Expected: compile failure (`pendingSince` undefined).

- [ ] **Step 3: Implement**

- `V41__booking_pending_since.sql`:
  ```sql
  -- #309: when the booking last (re)entered PENDING; NULL = since created_at. Expand-only.
  ALTER TABLE booking ADD COLUMN pending_since TIMESTAMPTZ;
  ```
  No backfill: the `COALESCE` in the query handles NULL, and so does the previous release, which never reads the column.
- `Booking`: `@Column(name = "pending_since") public Instant pendingSince;`
- `BookingService`:
  - Single reschedule: next to `booking.status = BookingStatus.PENDING`, set `booking.pendingSince = Instant.now()`.
  - `moveGroupRows`: inside `if (reApproval && r.status == BookingStatus.PENDING …)`, set `r.pendingSince = Instant.now()`, unconditionally, before the `approvalToken` check.
- Expiry query: `LEAST(COALESCE(pending_since, created_at) + …, start_utc)`. Update the javadoc formula at `:107` to match.
- `PersonalData`: add `"pending_since"` to the booking column set, in alphabetical order.

- [ ] **Step 4: Run the tests**

Run: `mvn test -Dtest='PendingExpiryTest,RescheduleCancelTest,GroupCancelRescheduleTest,PersonalDataInventoryTest,SetPendingExpiryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V41__booking_pending_since.sql src/main/java/site/asm0dey/calit/booking/Booking.java src/main/java/site/asm0dey/calit/booking/BookingService.java src/main/java/site/asm0dey/calit/scheduler/PendingExpiryScheduler.java src/main/java/site/asm0dey/calit/privacy/PersonalData.java src/test/java/site/asm0dey/calit/scheduler/PendingExpiryTest.java src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java src/test/java/site/asm0dey/calit/booking/GroupCancelRescheduleTest.java
git commit -m "fix(approval): count the pending hold from the re-request, not creation"
```

---

### Task 3: #327 — a guest declining keeps the Co-hosts on the group event

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`: `resyncGuests` (~line 1876)
- Test: `src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java` (reuse its `groupType()`, `stubOrganizerOnCreator()`, `seedCredential`)

**Interfaces:**
- Consumes: the existing private `groupAttendeeEmails(MeetingType, UUID, List<Long>)`, `groupEventRow(UUID)` and `meetingHosts.hostOwnerIds(MeetingType)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
@io.quarkus.test.TestTransaction
void decliningAGroupGuestKeepsEveryCoHostOnTheEvent() {
    stubOrganizerOnCreator();
    groupType();
    Booking lead = bookingService.book(1L, "intro", nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en",
            List.of("ana@x.com"));
    BookingGuest ana = BookingGuest.<BookingGuest>activeForBooking(lead.id).getFirst();

    bookingService.declineGuest(ana.declineToken);

    verify(calendarPort).updateEvent(eq(1L), any(), eq("grp-evt"), any(), any(), argThat(list ->
            list.contains("volodya@x.com") && list.contains("pasha@x.com") && !list.contains("ana@x.com")));
}
```

Add `decliningAGuestOfALegacyCohostEventGroupPatchesTheCohostRow`. Seed it the way `legacyCohostEventRowIsPatchedViaTheCohostOwnerId` does, with a guest added. Verify `updateEvent(eq(cohostId), eq(ref), eq("legacy-evt"), …)`.

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn test -Dtest=GroupEditDetailsTest`
Expected: FAIL. The first test sees an attendee list without `volodya@x.com`. The second sees no call on the Co-host's id.

- [ ] **Step 3: Implement**

In `resyncGuests(Booking booking, Long ownerId)`:
- When `booking.groupId != null`, resolve `row = groupEventRow(booking.groupId)` and `type = MeetingType.findById(booking.meetingTypeId)`.
- If `row != null && calendarPort.isConnected(row.ownerId)`, call `updateEvent(row.ownerId, row.calendarRef(), row.eventId, row.startUtc, row.endUtc, groupAttendeeEmails(type, booking.groupId, meetingHosts.hostOwnerIds(type)))`.
- The single-host branch stays as is.

- [ ] **Step 4: Run the tests**

Run: `mvn test -Dtest='GroupEditDetailsTest,BookingServiceGuestTest,EmailSetTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/BookingService.java src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java
git commit -m "fix(group): a guest decline re-syncs the group attendee list, Co-hosts included"
```

---

### Task 4: #325 — the invitee .ics download of a dead booking is a CANCEL

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`: `inviteeIcs(Long)` (~line 1193)
- Test: `src/test/java/site/asm0dey/calit/email/InviteeIcsMethodTest.java`

**Interfaces:** `EmailService.inviteeIcs(Long) -> Optional<byte[]>` keeps its signature; `PublicResource.inviteIcs` does not change.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void downloadOfCancelledBookingIsCancel() {
    long id = seed(BookingStatus.CANCELLED, 2);
    var ics = new String(emailService.inviteeIcs(id).orElseThrow(), StandardCharsets.UTF_8);
    assertThat(ics).contains("METHOD:CANCEL", "SEQUENCE:2").doesNotContain("METHOD:REQUEST");
}

@Test
void downloadOfDeclinedBookingIsCancel() {
    long id = seed(BookingStatus.DECLINED, 0);
    var ics = new String(emailService.inviteeIcs(id).orElseThrow(), StandardCharsets.UTF_8);
    assertThat(ics).contains("METHOD:CANCEL");
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn test -Dtest=InviteeIcsMethodTest`
Expected: the 2 new tests FAIL with `METHOD:REQUEST`.

- [ ] **Step 3: Implement**

In `inviteeIcs`:
- `var events = heldOrSelf(l)`.
- The method is `IcsMethod.REQUEST` when any event's status is PENDING or CONFIRMED, else `IcsMethod.CANCEL`.
- `heldOrSelf` already returns only held rows when the set has any, so a set with one live Booking still gets a REQUEST for the live ones.
- Update the javadoc's first sentence to say a cancelled or declined booking downloads as a CANCEL.

- [ ] **Step 4: Run the tests**

Run: `mvn test -Dtest='InviteeIcsMethodTest,GuestIcsDownloadTest,BookSetFlowTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/email/EmailService.java src/test/java/site/asm0dey/calit/email/InviteeIcsMethodTest.java
git commit -m "fix(ics): serve a CANCEL, not a REQUEST, for a cancelled or declined booking"
```

---

### Task 5: #310 — cancelling a dead booking is a no-op

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`: private `cancel(String, boolean, boolean)` (~line 1637)
- Test: `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java`, `src/test/java/site/asm0dey/calit/booking/GroupCancelRescheduleTest.java`

**Interfaces:** no signature changes. The guard sits in the one private method that the invitee route, the owner route and the privacy paths all reach.

- [ ] **Step 1: Write the failing tests**

In `RescheduleCancelTest`, add an `@Observes BookingCancelled` counter (`static final AtomicInteger CANCELLED`), the way `GroupEditDetailsTest` counts `BookingDetailsChanged`. Then:
- `cancellingTwiceFiresOnceAndDeletesOnce`: book, cancel, cancel. The counter rises by exactly 1. `verify(calendarPort, times(1)).deleteEvent(...)`. Status is `CANCELLED`.
- `cancellingADeclinedBookingKeepsItDeclined`: seed a DECLINED booking and cancel it. Status stays `DECLINED`, the counter does not move, no exception.

In `GroupCancelRescheduleTest`, add `cancellingViaCohostTokenAfterLeadCancelIsNoOp`. Cancel through the lead's token, then through the Co-host row's token. One `BookingCancelled` in total.

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn test -Dtest='RescheduleCancelTest,GroupCancelRescheduleTest'`
Expected: FAIL. The counter rises by 2; the DECLINED booking flips to CANCELLED.

- [ ] **Step 3: Implement**

In `cancel(String, boolean, boolean)`, right after the null/erased check: `if (booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.DECLINED) return false;`.

- [ ] **Step 4: Run the tests**

Run: `mvn test -Dtest='RescheduleCancelTest,GroupCancelRescheduleTest,InviteeErasureRouteTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/BookingService.java src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java src/test/java/site/asm0dey/calit/booking/GroupCancelRescheduleTest.java
git commit -m "fix(cancel): a cancelled or declined booking cancels as a no-op, no second mail"
```

---

### Task 6: #283 — a stored Mirror event that 404s is recreated, not a 500

Follows the issue's decision as amended by ADR 0001:
- **Stored ref + 404/410** → the provider reports it gone, and `BookingService` re-projects the Mirror event, whoever triggered the write.
- **Fallback address + 404** → log and swallow inside the port, no recreate.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/calendar/EventGoneException.java`
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java`: `updateEvent` (:260), `updateEventDetails` (:283)
- Modify: `src/main/java/site/asm0dey/calit/caldav/CaldavCalendarPort.java`: `modify` (~line 172, the `fetched == null` branch; replace the `calit-o69e` comment)
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`: `applyRescheduleOutcome` (:1310), `updateDetails` (:1451), `updateGroupDetails` (:1505), `resyncGuests` (after Task 3)
- Test: `src/test/java/site/asm0dey/calit/google/UpdateEventGoneTest.java` (new, modelled on `DeleteEventAlreadyGoneTest`), `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java`, `src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java`

**Interfaces:**
- Produces: `public class EventGoneException extends RuntimeException { public EventGoneException(String eventId, Throwable cause) }` in `site.asm0dey.calit.calendar`. Thrown by `CalendarPort.updateEvent` and `updateEventDetails` only when the event was addressed through the booking's stored `CalendarRef` and the provider says it is gone. Never thrown by `deleteEvent`.
- Produces (private, `BookingService`): `void patchMirror(Booking row, MeetingType type, Runnable patch)`. It runs `patch`. On `EventGoneException` it clears the row's `eventId`, `meetLink`, `calendarRemoteId` and `calendarAccountId`, then recreates: `createGroupGoogleEvent(type, row.groupId)` for a group, `createGoogleEvent(type, row)` otherwise. It logs at INFO with the booking id.

- [ ] **Step 1: Write the failing port tests**

`UpdateEventGoneTest`, a `@QuarkusTest` with a hand-built port like `DeleteEventAlreadyGoneTest`, mocking `events().patch(...)`:
- `storedRefGoneThrowsEventGone`: `updateEvent(1L, new CalendarRef(cred.id, "wt@example.com"), "evt", …)` with a 404 → `assertThatThrownBy(...).isInstanceOf(EventGoneException.class)`. Repeat for 410 and for `updateEventDetails`.
- `fallbackAddressGoneIsSwallowed`: same with `ref = null` and a 404 → `assertThatCode(...).doesNotThrowAnyException()`.
- `otherStatusStillThrowsUnchecked`: 500 → `UncheckedIOException`, message `"updateEvent failed"`.

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn test -Dtest=UpdateEventGoneTest`
Expected: compile failure (`EventGoneException` undefined).

- [ ] **Step 3: Implement the port side**

- `GoogleCalendarPort`: in both methods, add a `catch (GoogleJsonResponseException e)` before the `IOException` catch.
  - Status 404 or 410 with `addr.stored()` → throw `new EventGoneException(eventId, e)`.
  - Status 404 or 410 without `addr.stored()` → `infof` in the same shape as `deleteEvent`'s, then return.
  - Any other status → today's `UncheckedIOException`.
  - Extract the 404/410 check into `private static boolean gone(GoogleJsonResponseException e)` and reuse it in `deleteEvent`.
- `CaldavCalendarPort.modify`, `fetched == null` branch:
  - With `ref != null && ref.remoteCalendarId() != null`, throw `EventGoneException(eventId, null)`. This is the stored-address condition of `writeAddress`.
  - Otherwise keep the WARN and `return null`.
  - The throw happens inside `call(...)`, so check that `call` lets a non-`CaldavException` through unwrapped. If it wraps it, throw after `call` returns instead.

- [ ] **Step 4: Run the port tests**

Run: `mvn test -Dtest='UpdateEventGoneTest,DeleteEventAlreadyGoneTest,CaldavCalendarPortTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing service tests**

In `RescheduleCancelTest`, `calendarPort` is an `@InjectMock`, so stub `updateEvent` / `updateEventDetails` to throw `new EventGoneException("evt", null)` and stub `createEvent` to return `new CreatedEvent("evt-2", null, null, ref)`.
- `rescheduleOfHandDeletedEventRecreatesAndRestamps`: owner reschedule of a CONFIRMED auto-type booking. No exception; `createEvent` is called once; the row's `eventId` is `"evt-2"`; `BookingRescheduled` still fires, so the invitee gets mail.
- `inviteeRescheduleOfHandDeletedEventAlsoRecreates`: the same with `byOwner = false`.
- `detailsEditOfHandDeletedEventRecreates`: `updateDetails(token, "T", "D", List.of(), true)` → `createEvent` once, `eventId == "evt-2"`.
- `recreateWithNoWriteCalendarLeavesNoAddress`: `createEvent` returns `null`. No exception; `eventId`, `calendarRemoteId` and `calendarAccountId` are all null.

In `GroupEditDetailsTest`, add `groupDetailsEditOfHandDeletedEventRecreatesOnTheLead`. Stub `updateEventDetails` to throw `EventGoneException`. Expect `createEvent` called twice (the original booking plus the recreate) and the lead's `eventId` restamped.

- [ ] **Step 6: Run them to verify they fail**

Run: `mvn test -Dtest='RescheduleCancelTest,GroupEditDetailsTest'`
Expected: the new tests FAIL with `EventGoneException` propagating.

- [ ] **Step 7: Implement `patchMirror` and route the four call sites through it**

- Wrap each existing `calendarPort.updateEvent(...)` / `updateEventDetails(...)` call: `patchMirror(row, type, () -> calendarPort.updateEvent(...))`.
- The `row` is the booking whose `eventId` was passed in: `booking` in the single paths, `eventRow` in `updateGroupDetails`, and the resolved event row in `resyncGuests`.
- Callers keep their `isConnected` guards.

- [ ] **Step 8: Run the affected classes**

Run: `mvn test -Dtest='RescheduleCancelTest,GroupEditDetailsTest,GroupCancelRescheduleTest,BookingServiceGuestTest,MirrorEventMailLifecycleTest,BookingCalendarAddressTest,NoWriteCalendarBookingTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/site/asm0dey/calit/calendar/EventGoneException.java src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java src/main/java/site/asm0dey/calit/caldav/CaldavCalendarPort.java src/main/java/site/asm0dey/calit/booking/BookingService.java src/test/java/site/asm0dey/calit/google/UpdateEventGoneTest.java src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java
git commit -m "fix(calendar): recreate a stored Mirror event the provider reports gone, instead of a 500"
```

---

### Task 7: Full suite, changelog, PR

- [ ] **Step 1: Full suite**

Run: `mvn test`
Expected: 0 failures, 0 errors, `BUILD SUCCESS`. A red test is fixed on this branch, even if it is unrelated.

- [ ] **Step 2: Format check**

Run: `mvn spotless:check`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Changelog**

On the `docs-site` branch, add one bullet per issue under `## Unreleased` in `docs-site/src/content/docs/releases/changelog.md`. Use the terse style from CLAUDE.md: 20-35 words, no bold lead-in, ending in the PR link. The #309 bullet names `V41__booking_pending_since.sql`. Close the section with `Upgrade: nothing to do.` Commit and push straight to `docs-site`, without a PR.

- [ ] **Step 4: PR**

Push `fix/high-severity-bugs` and open one PR:
- Its body has `Fixes #331`, `Fixes #309`, `Fixes #327`, `Fixes #325`, `Fixes #310` and `Fixes #283`, each on its own line.
- It includes a show-me diagram (memory: show-me in PRs).
