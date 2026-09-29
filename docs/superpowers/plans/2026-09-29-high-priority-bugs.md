# High-Priority Bugs (calit-avqc, calit-wsab) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two open `high` bugs: an inactive meeting type stays bookable by direct link (`calit-avqc`), and in-app approve/decline act on bookings that are no longer PENDING (`calit-wsab`).

**Architecture:** Two guards, no new types. `calit-avqc`: a `!type.active` → 404 check in `PublicResource.resolveBookingTarget` (the GET page and form POST) and in `BookingService.book` (every booking write, JSON API included). `calit-wsab`: `BookingService.approve`/`decline` return early unless the clicked row is `PENDING`. That replaces today's group-only guards, so single and group bookings follow one rule.

**Tech Stack:** Quarkus 3.39.5, Java 25, Panache, RestAssured + Mockito (`@InjectMock CalendarPort`), JUnit 5, Postgres Dev Services.

**Spec:** the bean bodies are the spec. Read `beans show calit-avqc calit-wsab`. They cite UC-010 BR-007, UC-001 A1 and UC-014 BR-006.

## Global Constraints

- JDK: `export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` (or wherever Liberica 26 lives) before `./mvnw`. The default JDK 21 fails with "release 25 not supported".
- Docker must be running (Dev Services Postgres).
- JDK imports are module imports (`import module java.base;`). Don't add single-type `java.*` imports.
- Owner scoping: every query filters by owner. Nothing here adds a query.
- No new user-facing strings. Both bugs resolve to an existing 404 or a silent no-op, so no `messages/*.properties` changes.
- `@QuarkusTest` classes: `DatabaseResetCallback` truncates and reseeds before **each** test method. The admin is always id 1, username `admin`. No per-test cleanup is needed.
- Branch from `origin/main`, not local `main`: `git fetch origin && git switch -c fix/high-priority-bugs origin/main`.
- Stage explicit paths only. Never `git add -A`: the working tree has 16 untracked unrelated bean files.
- Before a PR: full `./mvnw test` must be green (0 failures, 0 errors), and `./mvnw spotless:check` must pass.

## Review Focus

1. **Existing bookings on a type that gets deactivated.** The invitee's manage link (`/booking/{token}/manage`) must still load. Deactivating stops *new* bookings, not the handling of existing ones. Pinned in Task 1 (`manageLinkStillWorksAfterDeactivation`).
2. **Co-host alias URL of an inactive multi-host type.** `resolveForAlias` returns the creator's type for `/{cohost}/{slug}`. The guard sits after that lookup, so it covers aliases too. Pinned by construction: one check on the resolved `type`. No extra test, because the multi-host fixture setup costs more than the one-line guard it would cover.
3. **Double-click approve on a single booking.** Before this fix, a second approve on a CONFIRMED single booking re-ran `createGoogleEvent` and made a duplicate calendar event. After the fix it is a no-op. Pinned in Task 2 (`approveOnConfirmedBookingIsNoOp`).
4. **Host re-declines their own already-approved row while the other host is still PENDING.** This becomes a no-op; the group stays alive. The other host can still decline, and either host can cancel the whole booking. Pinned in Task 2 (`declineOwnApprovedRowInPendingGroupIsNoOp`).
5. **Declining a cancelled group.** It must stay CANCELLED and not flip to DECLINED, which would send a second, contradictory email. Pinned in Task 2 (`declineOnCancelledGroupIsNoOp`).

---

### Task 1: Inactive meeting type is not bookable (calit-avqc)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java:447-450` (`resolveBookingTarget`)
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:353-356` (12-arg `book`)
- Create: `src/test/java/site/asm0dey/calit/web/PublicInactiveTypeTest.java`
- Bean: `.beans/calit-avqc--inactive-meeting-type-is-still-bookable-by-direct.md`

**Interfaces:**
- Consumes: `MeetingType.active` (public boolean, default `true`), `MeetingType.resolveForAlias(Long, String)`, `MeetingType.findBySlug(Long, String)`, `AppUser.create(String username, String hash, boolean admin)`.
- Produces: nothing new. Behaviour only: an inactive type → `NotFoundException` (404) from both entry points.

- [ ] **Step 1: Mark the bean in progress**

```bash
beans update calit-avqc -s in-progress
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/PublicInactiveTypeTest.java`:

```java
package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

/**
 * A deactivated meeting type is hidden from the landing page, and must not stay bookable by typing its
 * URL -- neither the page, the form POST, nor the JSON API (calit-avqc, UC-010 BR-007).
 */
@QuarkusTest
class PublicInactiveTypeTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    // Inside the default 60-day horizon and the 09:00-18:00 hours seeded below.
    private static final String START_UTC =
            LocalDate.now(ZONE).plusDays(7).atTime(10, 0).atZone(ZONE).toInstant().toString();

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.create("typeowner", "x", false);
            u.settingsComplete = true;
            u.persist();
            OwnerSettings s = new OwnerSettings();
            s.ownerId = u.id;
            s.ownerName = "Type Owner";
            s.ownerEmail = "typeowner@example.com";
            s.timezone = ZONE.getId();
            s.persist();
            type(u.id, "live", true);
            type(u.id, "retired", false);
            for (DayOfWeek d : DayOfWeek.values()) {
                AvailabilityRule r = new AvailabilityRule();
                r.ownerId = u.id;
                r.dayOfWeek = d;
                r.startTime = LocalTime.of(9, 0);
                r.endTime = LocalTime.of(18, 0);
                r.persist();
            }
        });
    }

    private static void type(Long ownerId, String slug, boolean active) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.active = active;
        t.persist();
    }

    @Test
    void activeTwinIsBookable() {
        // Control: the fixture is bookable, so a 404 below is the guard, not a broken seed.
        given().when().get("/typeowner/live").then().statusCode(200);
    }

    @Test
    void bookingPageIs404() {
        given().when().get("/typeowner/retired").then().statusCode(404);
    }

    @Test
    void bookingFormPostIs404() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", START_UTC)
            .formParam("inviteeName", "Stranger")
            .formParam("inviteeEmail", "stranger@example.com")
            .when()
            .post("/typeowner/retired")
            .then()
            .statusCode(404);
    }

    @Test
    void apiBookingPostIs404() {
        given()
            .contentType("application/json")
            .body(apiBody("retired"))
            .when()
            .post("/api/bookings")
            .then()
            .statusCode(404);
    }

    @Test
    void manageLinkStillWorksAfterDeactivation() {
        // Deactivating stops NEW bookings; an invitee who already booked can still reach their booking.
        String token = given()
            .contentType("application/json")
            .body(apiBody("live"))
            .when()
            .post("/api/bookings")
            .then()
            .statusCode(201)
            .body("manageToken", notNullValue())
            .extract()
            .path("manageToken");
        QuarkusTransaction.requiringNew().run(() ->
            MeetingType.update("active = false where slug = ?1", "live")
        );
        given().when().get("/booking/" + token + "/manage").then().statusCode(200);
    }

    private static String apiBody(String slug) {
        return """
                {"user":"typeowner","slug":"%s","startUtc":"%s",\
                "inviteeName":"Stranger","inviteeEmail":"stranger@example.com",\
                "answers":{},"turnstileToken":"tok","honeypot":""}"""
            .formatted(slug, START_UTC);
    }
}
```

- [ ] **Step 3: Run it and confirm the three guard tests fail**

Run: `./mvnw test -Dtest=PublicInactiveTypeTest`
Expected: `bookingPageIs404`, `bookingFormPostIs404` and `apiBookingPostIs404` FAIL (200/201/303 instead of 404). `activeTwinIsBookable` and `manageLinkStillWorksAfterDeactivation` PASS. If the control fails, fix the fixture before touching production code.

- [ ] **Step 4: Add the guard to `resolveBookingTarget`**

In `PublicResource.java`, replace:

```java
        MeetingType type = MeetingType.resolveForAlias(urlUser.id, slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
```

with:

```java
        MeetingType type = MeetingType.resolveForAlias(urlUser.id, slug);
        // Inactive = unlisted AND unbookable (UC-010 BR-007); secret types stay reachable by link.
        if (type == null || !type.active) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
```

- [ ] **Step 5: Add the same guard to `BookingService.book` (12-arg overload)**

In `BookingService.java`, replace:

```java
        MeetingType type = MeetingType.findBySlug(ownerId, meetingTypeSlug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug + " for owner " + ownerId);
        }
```

with:

```java
        MeetingType type = MeetingType.findBySlug(ownerId, meetingTypeSlug);
        // Every booking write lands here (form + JSON API), so an inactive type is refused once for all.
        if (type == null || !type.active) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug + " for owner " + ownerId);
        }
```

Leave the 11-arg overload (`:316`) alone: it only reads `durationMinutes` and delegates to this method.

- [ ] **Step 6: Run the test, then the neighbours**

Run: `./mvnw test -Dtest='PublicInactiveTypeTest,PublicDisabledOwnerTest,BookingResourceTest,BookServiceTest,PublicLandingTest'`
Expected: all PASS.

- [ ] **Step 7: Tick the bean and commit**

```bash
beans update calit-avqc --body-replace-old "- [ ] 404 when !type.active in resolveBookingTarget" --body-replace-new "- [x] 404 when !type.active in resolveBookingTarget"
beans update calit-avqc --body-replace-old "- [ ] Same guard in BookingService.book (covers JSON API)" --body-replace-new "- [x] Same guard in BookingService.book (covers JSON API)"
beans update calit-avqc --body-replace-old "- [ ] Tests: GET page, form POST, JSON POST" --body-replace-new "- [x] Tests: GET page, form POST, JSON POST"
beans update calit-avqc -s completed --body-append "## Summary of Changes

404 for inactive types in PublicResource.resolveBookingTarget (page + form POST) and BookingService.book (all writes, JSON API included). PublicInactiveTypeTest pins all three entry points plus the manage link staying live after deactivation."
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/web/PublicInactiveTypeTest.java \
        .beans/calit-avqc--inactive-meeting-type-is-still-bookable-by-direct.md
git commit -m "fix(booking): refuse bookings on inactive meeting types (calit-avqc)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(Use one `beans update` per replacement. Repeated `--body-replace-*` pairs in one call keep only the last.)

---

### Task 2: Approve/decline act only on PENDING bookings (calit-wsab)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` — `approve` (~`:837-846`) and `decline` (~`:874-883`)
- Test: `src/test/java/site/asm0dey/calit/booking/ApproveDeclineTest.java` (single-host)
- Test: `src/test/java/site/asm0dey/calit/booking/GroupApprovalTest.java` (group)
- Bean: `.beans/calit-wsab--in-app-approvedecline-dont-require-pending.md`

**Interfaces:**
- Consumes: `BookingService.approve(Long)`, `decline(Long)`, `cancel(String manageToken)`, `cancel(String manageToken, boolean byOwner)`. Test helpers already in those classes: `ApproveDeclineTest.seedSettings()` and `approvalType(String slug)`, `SLOT_09`; `GroupApprovalTest.type(boolean approval)` and `nextMonday10()`.
- Produces: `approve`/`decline` become silent no-ops when the row's status is not `PENDING`. `AdminResource` needs no change: it re-renders the pending list either way, and `actFromEmail` already shows "already handled".

- [ ] **Step 1: Mark the bean in progress**

```bash
beans update calit-wsab -s in-progress
```

- [ ] **Step 2: Write the failing single-host tests**

Append to `ApproveDeclineTest` (before the `// --- helpers ---` line):

```java
    @Test
    @TestTransaction
    void approveOnCancelledBookingIsNoOp() {
        // calit-wsab: a stale /me/pending tab must not resurrect a cancelled request.
        seedSettings();
        approvalType("ap-cancelled");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("ap-cancelled");
        bookingService.cancel(b.manageToken);

        bookingService.approve(b.id);

        assertEquals(BookingStatus.CANCELLED, Booking.<Booking>findById(b.id).status);
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void approveOnDeclinedBookingIsNoOp() {
        seedSettings();
        approvalType("ap-declined");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("ap-declined");
        bookingService.decline(b.id);

        bookingService.approve(b.id);

        assertEquals(BookingStatus.DECLINED, Booking.<Booking>findById(b.id).status);
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void approveOnConfirmedBookingIsNoOp() {
        // Double-click on Approve: the second click must not create a second Google event.
        seedSettings();
        approvalType("ap-twice");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-once", "https://meet.google.com/x", "h", null));
        Booking b = pendingBooking("ap-twice");
        bookingService.approve(b.id);

        bookingService.approve(b.id);

        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void declineOnConfirmedBookingIsNoOp() {
        // A confirmed meeting is cancelled (Google event deleted, cancel email), never "declined".
        seedSettings();
        approvalType("de-confirmed");
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("de-confirmed");
        bookingService.approve(b.id);

        bookingService.decline(b.id);

        assertEquals(BookingStatus.CONFIRMED, Booking.<Booking>findById(b.id).status);
    }

    @Test
    @TestTransaction
    void declineOnCancelledBookingIsNoOp() {
        seedSettings();
        approvalType("de-cancelled");
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("de-cancelled");
        bookingService.cancel(b.manageToken);

        bookingService.decline(b.id);

        assertEquals(BookingStatus.CANCELLED, Booking.<Booking>findById(b.id).status);
    }

    private Booking pendingBooking(String slug) {
        Booking b = bookingService.book(1L, slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
        assertEquals(BookingStatus.PENDING, b.status);
        return b;
    }
```

The existing `approvalType` helper persists an `AvailabilityRule` with `meetingTypeId = null` each call. That is fine: each test method gets a freshly truncated DB.

- [ ] **Step 3: Write the failing group tests**

Append to `GroupApprovalTest` (inside the class, after `anyDeclineKillsWholeGroup`):

```java
    @Test
    @TestTransaction
    void declineOnCancelledGroupIsNoOp() {
        // calit-wsab: a cancelled group must not flip to DECLINED (second, contradictory email).
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        type(true);
        Booking lead = bookingService.book(1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        bookingService.cancel(lead.manageToken, true);

        bookingService.decline(rows.get(1).id);

        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertEquals(BookingStatus.CANCELLED, r.status));
    }

    @Test
    @TestTransaction
    void declineOwnApprovedRowInPendingGroupIsNoOp() {
        // A host who already approved can't decline their own row; the other host still can.
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        type(true);
        Booking lead = bookingService.book(1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        bookingService.approve(rows.get(0).id);

        bookingService.decline(rows.get(0).id);

        assertEquals(BookingStatus.CONFIRMED, Booking.<Booking>findById(rows.get(0).id).status);
        assertEquals(BookingStatus.PENDING, Booking.<Booking>findById(rows.get(1).id).status);

        bookingService.decline(rows.get(1).id);

        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertEquals(BookingStatus.DECLINED, r.status));
    }
```

If Prince of Space reflows the long `book(...)` lines, that's the pre-commit hook doing its job. Leave it.

- [ ] **Step 4: Run and confirm failures**

Run: `./mvnw test -Dtest='ApproveDeclineTest,GroupApprovalTest'`
Expected FAIL: `approveOnCancelledBookingIsNoOp` (CONFIRMED), `approveOnDeclinedBookingIsNoOp` (CONFIRMED), `approveOnConfirmedBookingIsNoOp` (createEvent ×2), `declineOnConfirmedBookingIsNoOp` (DECLINED), `declineOnCancelledBookingIsNoOp` (DECLINED), `declineOnCancelledGroupIsNoOp` (DECLINED), `declineOwnApprovedRowInPendingGroupIsNoOp` (group DECLINED after the first decline). The existing tests PASS.

- [ ] **Step 5: Replace the group-only guard in `approve`**

In `BookingService.approve`, replace:

```java
        // Group idempotency guard: a double-submit (double-click / back-button replay) of approve
        // on an already-processed group row must not re-run createGroupGoogleEvent / re-fire
        // BookingConfirmed. Single-host is unaffected (groupId == null -> guard is false).
        if (booking.groupId != null && booking.status != BookingStatus.PENDING) {
            return;
        }
```

with:

```java
        // Only a PENDING request can be approved (UC-014 BR-006). Covers double-submits (no second
        // Google event / BookingConfirmed) and stale tabs acting on a cancelled or declined booking.
        if (booking.status != BookingStatus.PENDING) {
            return;
        }
```

- [ ] **Step 6: Replace the group-only guard in `decline`**

In `BookingService.decline`, replace:

```java
        // Group idempotency guard: a double-submit of decline on an already-DECLINED group row is
        // a no-op (the group was already killed by the first decline). Single-host is unaffected.
        if (booking.groupId != null && booking.status == BookingStatus.DECLINED) {
            return;
        }
```

with:

```java
        // Only a PENDING request can be declined (UC-014 BR-006): a confirmed booking is cancelled,
        // never declined, and a cancelled/declined one is already settled. Also absorbs double-submits.
        if (booking.status != BookingStatus.PENDING) {
            return;
        }
```

Leave the single-host comment below it (`"Single-host: unchanged. DECLINED leaves the PENDING|CONFIRMED partial constraint…"`) as is. With the new guard it's more accurate than before.

- [ ] **Step 7: Run the approval tests plus everything that calls approve/decline**

Run: `./mvnw test -Dtest='ApproveDeclineTest,GroupApprovalTest,GroupCancelRescheduleTest,RescheduleCancelTest,BookingCalendarAddressTest,AdminPendingTest,ApprovalLinkTest,MultiHostEmailFanoutTest,EmailRoleCopyTest,EmailServiceGuestTest,CrossOwnerIsolationTest,GuestBookingFlowTest,SharedMeetingsResourceTest'`
Expected: all PASS. If one fails, it was approving or declining a non-PENDING row on purpose. Read it before changing anything: the test may encode the bug.

- [ ] **Step 8: Tick the bean and commit**

```bash
beans update calit-wsab --body-replace-old "- [ ] approve/decline: no-op or refuse when status != PENDING, single and group" --body-replace-new "- [x] approve/decline: no-op or refuse when status != PENDING, single and group"
beans update calit-wsab --body-replace-old "- [ ] Tests" --body-replace-new "- [x] Tests"
beans update calit-wsab -s completed --body-append "## Summary of Changes

BookingService.approve/decline now return early unless the clicked row is PENDING, replacing the group-only guards. Single-host double-approve no longer creates a second Google event. A host who already approved a still-pending group can no longer decline their own row (the other host can). Tests in ApproveDeclineTest and GroupApprovalTest."
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/ApproveDeclineTest.java \
        src/test/java/site/asm0dey/calit/booking/GroupApprovalTest.java \
        .beans/calit-wsab--in-app-approvedecline-dont-require-pending.md
git commit -m "fix(booking): approve/decline only PENDING bookings (calit-wsab)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Full suite, PR, changelog

**Files:**
- Modify (on `docs-site` branch, after merge): `docs-site/src/content/docs/releases/changelog.md`

- [ ] **Step 1: Full suite and format gate**

Run: `./mvnw spotless:check && ./mvnw test`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors. A red test anywhere, related or not, gets fixed on this branch first.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin fix/high-priority-bugs
gh pr create --title "fix(booking): inactive types unbookable; approve/decline require PENDING" --body-file <body.md>
```

The PR body must include a show-me diagram. Read the show-me SKILL.md and apply it by hand, since it is model-invocation-disabled. End the body with the Claude Code attribution line.

- [ ] **Step 3: After merge, add the changelog bullets under `## Unreleased` on `docs-site`**

```markdown
- Inactive meeting types now return 404 on their booking page, form POST and `POST /api/bookings`;
  existing bookings stay manageable. ([#N](https://github.com/asm0dey/calit/pull/N))
- Approve and decline act only on pending requests; a cancelled, declined or confirmed booking is
  left unchanged, and a double-click no longer creates a second calendar event. ([#N](https://github.com/asm0dey/calit/pull/N))
```

Create `## Unreleased` (subtitle "Merged but not yet in a tagged release.") if it's absent. Commit on a branch off `origin/docs-site` and open a PR. Don't push `docs-site` directly.
