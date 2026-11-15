# Multi-slot booking — Booking sets — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Owner lets an Invitee book 2–5 slots of one meeting type in one submit, as a Booking set
that is booked, approved, declined and expired as one.

**Architecture:** One nullable `booking.set_id` ties the rows; `BookingService.bookSet` runs the
existing single-Booking path per pick inside one transaction, with the per-form checks lifted out.
Events stay the existing ones, fired once on the set's lead row; listeners expand by `set_id`. The
booking page grows a GET-form picker (checkbox per slot) and a details step, both on the existing
routes, both reachable through an Invite without naming the slug.

**Tech Stack:** Quarkus 3.40 / Java 25, Panache, Qute `@CheckedTemplate`, Flyway, Tailwind v4 +
daisyUI 5, RestAssured + AssertJ, Mockito `@InjectMock CalendarPort`.

**Spec:** `docs/superpowers/specs/2026-11-15-multi-slot-booking-design.md`. This plan covers the
**Set** mode and the shared picker. **Proposal mode** gets its own plan after this merges (it reuses
the picker from Tasks 7–8).

## Global Constraints

- Vocabulary per `CONTEXT.md`: Invitee, Booking, Booking set, Host, Co-host, Mirror event, Invite.
  Invitee-facing copy says "sessions".
- Cap: 1 ≤ min ≤ max ≤ 5; "exactly N" stores min = max = N, 2 ≤ N ≤ 5.
- A single pick is a plain Booking: no `set_id`, today's emails and pages. Sets always have ≥ 2 rows'
  worth of picks.
- Meeting types with multi-slot off: page, flow and emails byte-identical (existing tests must pass
  untouched).
- No JavaScript required; any JS only adds live feedback.
- An Invite's pages and emails never contain `/{user}/{slug}`.
- Every new string: `@Message` English default + `de` + `he` in `messages/{msg,adm}_{de,he}.properties`.
- Migration is expand-only; the previous release must run on the new schema.
- Every new POST form carries `{inject:csrf.token}`.
- AssertJ for assertions; Hamcrest only inside RestAssured `.body(...)`.

## Review Focus

1. **Two picks inside one buffer of each other** — the second pick conflicts because the first row is
   already flushed; expected: refused with the conflict bounce, nothing booked. Test in Task 4.
2. **The same pick submitted twice** (`pick=X/30&pick=X/30`, replayed form) — expected: de-duplicated,
   not an overlap error and not two rows. Test in Task 3.
3. **A tampered `pick` value** (non-ISO instant, negative/disallowed minutes, > 5 picks) — expected:
   the validation bounce, never a 500. Test in Task 3 and Task 8.
4. **A set where only one host's approval link is clicked** on a meeting type with Co-hosts —
   expected: that host's rows flip, the set stays PENDING, no Mirror event, no Invitee mail. Test in
   Task 5.
5. **Owner turns multi-slot off while a set is pending** — expected: the set still approves, declines
   and expires as a set (behaviour keys on `set_id`, never on the type's current setting). Test in
   Task 5.

---

### Task 1: Schema and entity fields

**Files:**
- Create: `src/main/resources/db/migration/V40__multi_slot_booking.sql`
- Modify: `src/main/java/site/asm0dey/calit/domain/MeetingType.java`, `src/main/java/site/asm0dey/calit/booking/Booking.java`
- Test: `src/test/java/site/asm0dey/calit/migration/V40MigrationTest.java`, `src/test/java/site/asm0dey/calit/booking/BookingSetQueryTest.java`

**Interfaces:**
- Produces: `MeetingType.multiSlotMin`, `multiSlotMax` (`Integer`, both null = off), `multiSlotSameDay`
  (`boolean`); `MeetingType.isMultiSlot()` (`multiSlotMax != null && multiSlotMax > 1`);
  `Booking.setId` (`UUID`); `static List<Booking> Booking.set(UUID setId)` (ordered by `startUtc, id`);
  `static Booking Booking.leadOfSet(UUID setId, Long creatorOwnerId)` (Creator's row with the earliest
  `startUtc`); `countDistinctBookingsByEmailBetween` counts a set once.

- [ ] **Step 1: Write the failing tests**
  - `V40MigrationTest.columnsExistWithDefaults` — `information_schema.columns` has
    `meeting_type.multi_slot_min int null`, `multi_slot_max int null`,
    `multi_slot_same_day boolean not null default false`, `booking.set_id uuid null`; an index on
    `booking(set_id)` exists. Follow `V37MigrationTest`'s shape.
  - `BookingSetQueryTest.setCountsOnceTowardDailyCap` — persist 3 rows same email, same `setId`, one
    plain row → `countDistinctBookingsByEmailBetween(...)` `isEqualTo(2)`.
  - `BookingSetQueryTest.setOfCoHostedBookingsCountsOnce` — 2 groups × 2 hosts sharing one `setId` →
    `isEqualTo(1)`.
  - `BookingSetQueryTest.leadOfSetIsCreatorsEarliestRow`.
- [ ] **Step 2: Run** `mvn test -Dtest='V40MigrationTest,BookingSetQueryTest'` — expected FAIL (no columns).
- [ ] **Step 3: Write V40** — the four columns and `CREATE INDEX booking_set_id_idx ON booking (set_id)`.
  Add the entity fields (`@Column(name = "multi_slot_min")` etc.).
- [ ] **Step 4: Change `countDistinctBookingsByEmailBetween`** to count: rows with `groupId is null and
  setId is null` + distinct `groupId` where `setId is null` + distinct `setId`. One JPQL per term is fine.
- [ ] **Step 5: Run** the two tests plus `BookingGroupQueryTest` — PASS.
- [ ] **Step 6: Commit** `feat(booking): set_id and multi-slot columns (V40)`.

### Task 2: Owner setting on the meeting-type form

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (`applyEditableFields` and both
  callers at ~569 and ~1179), `src/main/resources/templates/AdminResource/meetingTypeDetail.html` (and
  the create form if it renders the same fieldset), `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`,
  `src/main/resources/messages/adm_de.properties`, `adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/AdminMeetingTypeFormTest.java`

**Interfaces:**
- Produces: form params `multiSlotMode` (`off` | `range` | `exact`), `multiSlotMin`, `multiSlotMax`,
  `multiSlotExact` (ints as strings), `multiSlotSameDay` (`on` | absent). Stored per Task 1.

- [ ] **Step 1: Write the failing tests** (RestAssured POST to the edit route, then reload the entity):
  - `multiSlotRangeIsStored` — `range`, 2, 4, sameDay on → min 2, max 4, sameDay true.
  - `multiSlotExactStoresMinEqualsMax` — `exact`, 3 → min 3, max 3.
  - `multiSlotOffClearsBoth` — `off` → both null, sameDay false.
  - `multiSlotOutOfRangeIsRefused` — `range` 0..6, `range` 4..2, `exact` 1 → the form's existing
    `HostRuleException` error render, entity unchanged.
  - `editPageRendersToggleNotCheckbox` — body contains `name="multiSlotSameDay"` with `class="toggle`.
- [ ] **Step 2: Run** `mvn test -Dtest=AdminMeetingTypeFormTest` — FAIL.
- [ ] **Step 3: Implement** — extend `applyEditableFields` with the five values; reuse `inRange` /
  `HostRuleException` for the bounds (keys `adm_detail_error_multi_slot_range`). Fieldset per spec
  ("Several sessions per booking", radios, divider, daisyUI `toggle toggle-sm`, helper "Up to 5.
  Approval, co-hosts and invites apply to the whole set."). Fields for min/max/N sit inline in their
  radio's label; the toggle is shown only when not Off (server-rendered `hidden` when off; no JS needed
  since the setting is ignored when Off).
- [ ] **Step 4: Add i18n** — every new `adm_*` key in `AdminMessages` with `de` + `he`.
- [ ] **Step 5: Run** the test class + `AdminMeetingTypeDetailTest` — PASS.
- [ ] **Step 6: Commit** `feat(admin): several sessions per booking setting`.

### Task 3: Picks and set rules (pure)

**Files:**
- Create: `src/main/java/site/asm0dey/calit/booking/SlotPick.java`, `src/main/java/site/asm0dey/calit/booking/SetRules.java`
- Test: `src/test/java/site/asm0dey/calit/booking/SetRulesTest.java` (plain JUnit)

**Interfaces:**
- Produces:
  - `record SlotPick(Instant startUtc, int minutes)` with `Instant endUtc()`, `String value()` →
    `"<ISO instant>/<minutes>"`, and `static List<SlotPick> parseAll(List<String> raw)` — trims,
    drops duplicates (order kept), sorts by `startUtc`; throws `BookingValidationException` on a
    malformed value or more than 5 distinct picks.
  - `enum SetRules.Blocked { NONE, SAME_DAY, OVERLAP, MAX }`
  - `static Blocked SetRules.blocked(MeetingType type, List<SlotPick> picked, SlotPick candidate, ZoneId ownerZone)`
    — a candidate already in `picked` is `NONE`; else `MAX` if `picked.size() >= max`; `OVERLAP` if its
    interval intersects any pick; `SAME_DAY` if same-day is off and a pick shares its owner-zone date.
  - `static void SetRules.validate(MeetingType type, List<SlotPick> picks, ZoneId ownerZone)` — throws
    `BookingValidationException` unless `min ≤ size ≤ max`, every minutes value is in
    `MeetingTypeDuration.allowedDurations(type)`, and no pick is blocked by the others.

- [ ] **Step 1: Write the failing tests:** `parsesAndSortsPicks`, `dropsDuplicatePick`,
  `rejectsMalformedPick` (`"x/30"`, `"2026-01-01T10:00:00Z/-5"`, `"2026-01-01T10:00:00Z"`),
  `rejectsSixPicks`, `blockedOverlap`, `blockedSameDayInOwnerZone` (23:30 and 00:30 UTC on the same
  Amsterdam date → `SAME_DAY`), `sameDayAllowedWhenToggleOn`, `blockedMax`, `validateCountBounds`,
  `validateRejectsDisallowedDuration`. MeetingType instances built in memory (no DB) — if
  `allowedDurations` needs the DB, pass the allowed list into `validate` instead and say so in its
  signature: `validate(MeetingType, List<SlotPick>, List<Integer> allowed, ZoneId)`.
- [ ] **Step 2: Run** `mvn test -Dtest=SetRulesTest` — FAIL.
- [ ] **Step 3: Implement** both classes.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(booking): slot picks and set rules`.

### Task 4: `BookingService.bookSet` — atomic booking

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`
- Test: `src/test/java/site/asm0dey/calit/booking/BookingSetWriteTest.java`

**Interfaces:**
- Consumes: Task 1 fields, Task 3 `SlotPick`, `SetRules.validate`.
- Produces:
  `@Transactional public List<Booking> bookSet(Long ownerId, String meetingTypeSlug, List<SlotPick> picks, String inviteeName, String inviteeEmail, Map<String,String> answers, String turnstileToken, String altchaSolution, String honeypot, String locale, List<String> guestEmails, String inviteToken)`
  — returns the Invitee-facing row per pick (the lead row for a Co-host group), ordered by start. A
  single pick delegates to the existing 13-arg `book(...)` and returns a one-element list.

- [ ] **Step 1: Write the failing tests** (`@QuarkusTest`, `@InjectMock CalendarPort`, fixtures from
  `test/MultiHostFixtures`):
  - `booksThreePicksAsOneSet` — 3 rows, one shared non-null `setId`, distinct `manageToken`s, each its
    own duration (30 and 60 mixed).
  - `onePickIsPlainBooking` — `setId` null.
  - `conflictOnLastPickBooksNothing` — third pick pre-occupied → `BookingConflictException`; afterwards
    zero rows for that email and `createEvent` never called (`verify(calendarPort, never())`).
  - `picksWithinBufferConflict` — type buffer 15, picks 10:00/30 and 10:40/30 → conflict, nothing
    booked (Review Focus 1).
  - `inviteConsumedOnceForTheSet` — invite gone after success; still present after a conflict.
  - `captchaVerifiedOnce` — mock `CaptchaVerifier` verified exactly once for 3 picks.
  - `dailyCapCountsSetOnce` — cap 2: one set of 3 succeeds, a second set succeeds, a third →
    `RateLimitException`.
  - `coHostedSetWritesRowsPerHost` — 2 picks × 2 hosts = 4 rows, 2 `groupId`s, 1 `setId`.
  - `autoSetCreatesMirrorEventPerBookingAfterFlush` — `createEvent` called 2× for 2 picks.
  - `oneRequestedEventForApprovalSet` / `oneConfirmedEventForAutoSet` — observe with a test observer
    or `EmailServiceEventWiringTest`'s technique: exactly one `BookingRequested`/`BookingConfirmed`,
    carrying `leadOfSet` id.
  - `singleSlotBookRefusedWhenMinAboveOne` — 13-arg `book(...)` on a type with min 2 →
    `BookingValidationException` (covers the JSON API).
- [ ] **Step 2: Run** `mvn test -Dtest=BookingSetWriteTest` — FAIL.
- [ ] **Step 3: Refactor `book` (13-arg)** into: `precheck(type, …)` (email, bounds, name, guests,
  captcha, honeypot, cap, required fields) → `writeOne(type, start, minutes, …, UUID setId, boolean fireEvents)`
  returning the Invitee-facing row and never calling `calendarPort` or firing events when
  `fireEvents` is false; `book` = precheck + `assertDurationAllowed` + `assertSlotAvailable` +
  `consumeInvite` + `writeOne(..., null, true)` — behaviour unchanged. `bookGroup` gains the same
  `setId`/`fireEvents` parameters. Add the `min > 1` refusal to `book`.
- [ ] **Step 4: Implement `bookSet`** — `SetRules.validate`, precheck once, per pick
  `assertDurationAllowed` + `assertSlotAvailable` + `writeOne(..., setId, false)` (the flush inside each
  surfaces the exclusion constraint), then `consumeInvite` once, then Mirror events for every row (auto
  types only; per group via `createGroupGoogleEvent`), then one event on `leadOfSet`.
- [ ] **Step 5: Run** the class + `GroupBookingWriteTest`, `BookingServiceGuestTest`,
  `BookingInviteServiceTest` — PASS.
- [ ] **Step 6: Commit** `feat(booking): book a set atomically`.

### Task 5: Approve, decline and expire per set

**Files:**
- Modify: `BookingService.java` (`approve`, `decline`), `src/main/java/site/asm0dey/calit/scheduler/PendingExpiryScheduler.java`
- Test: `src/test/java/site/asm0dey/calit/booking/SetApprovalTest.java`, `src/test/java/site/asm0dey/calit/scheduler/SetPendingExpiryTest.java`

**Interfaces:**
- Consumes: `Booking.set`, `Booking.leadOfSet`.
- Produces: no new API; `approve(id)`/`decline(id)` become set-aware.

- [ ] **Step 1: Write the failing tests:**
  - `approvingOneRowConfirmsWholeSet` (single host, 3 Bookings) — all CONFIRMED, 3 `createEvent`s, one
    `BookingApproved` on the lead.
  - `coHostApprovalWaitsForEveryHost` — host A approves → A's rows CONFIRMED, B's PENDING, no
    `createEvent`, no Invitee event (Review Focus 4); B approves → all confirmed, one
    `BookingConfirmed`.
  - `decliningOneRowDeclinesWholeSet` — every row DECLINED, one `BookingDeclined` on the lead.
  - `setBehaviourSurvivesTurningMultiSlotOff` — set pending, type's min/max set null, approve → whole
    set confirmed (Review Focus 5).
  - `SetPendingExpiryTest.setExpiresAtFirstStartAsUnit` — first Booking's start inside the grace
    window, later ones days away → every row DECLINED, exactly one declined enqueue.
  - `SetPendingExpiryTest.lateApproveAfterExpiryIsNoop`.
- [ ] **Step 2: Run** both classes — FAIL.
- [ ] **Step 3: Implement** — `approve`: when `setId != null`, flip every PENDING row of the set with
  `ownerId == booking.ownerId`; when none remains PENDING, create Mirror events per Booking (single via
  `createGoogleEvent`, group via `createGroupGoogleEvent`) and fire `BookingApproved(lead)` if the lead
  has no `groupId`, else `BookingConfirmed(lead)`. `decline`: every row DECLINED, one
  `BookingDeclined(lead)`. Lock the lead row (`PESSIMISTIC_WRITE`) first so concurrent clicks
  serialise. Scheduler: select `set_id` too; lock key `SET_LOCK_PREFIX + setId` when present; a
  `declineSet` mirroring `declineGroup` over `Booking.set(setId)`.
- [ ] **Step 4: Run** both classes + `GroupApprovalTest`, `PendingExpiryTest`, `GroupSchedulerTest` — PASS.
- [ ] **Step 5: Commit** `feat(booking): approve, decline and expire a set as one`.

### Task 6: Set emails, one `.ics` with N events, guests once per set

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/IcsBuilder.java`, `EmailService.java`,
  `src/main/resources/templates/email/{requested,confirmation,declined,cancellation,guestInvite}.html`,
  create `src/main/resources/templates/email/_sessions.html`; `BookingService.declineGuest`;
  `AppMessages` + `msg_de/he.properties`
- Test: `src/test/java/site/asm0dey/calit/email/EmailSetTest.java`, `src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java` (extend if present, else create)

**Interfaces:**
- Produces: `static String IcsBuilder.build(List<IcsEvent> events)` — one VCALENDAR, one METHOD, N
  VEVENTs (the single-event `build(IcsEvent)` delegates to it); `record SessionLine(int index, int of, String when, int minutes)`
  in `EmailService`; every booking-mail template takes `List<SessionLine> sessions` (empty for non-set
  bookings ⇒ output unchanged).

- [ ] **Step 1: Write the failing tests:**
  - `IcsBuilderTest.buildsOneCalendarWithThreeEvents` — one `BEGIN:VCALENDAR`, one `METHOD:`, three
    `BEGIN:VEVENT`, three distinct `UID:`s.
  - `EmailSetTest.requestMailListsEverySession` — one mail to the Invitee and one to the Owner for a
    3-Booking approval set; each body contains all three times and "1 of 3"; Invitee `.ics` (degraded
    mode) has 3 VEVENTs.
  - `EmailSetTest.confirmationMailOncePerSet` — auto set → exactly one Invitee confirmation.
  - `EmailSetTest.guestGetsOneInviteForSet` — one guest mail, `.ics` with 3 VEVENTs, one decline link.
  - `EmailSetTest.guestDeclineRemovesFromEverySession` — `declineGuest(token)` → that email DECLINED on
    every Booking of the set; one `GuestDeclined`.
  - `EmailSetTest.inviteSetMailHasNoSlug` — booked via Invite → no body contains `/{user}/{slug}`.
  - Existing `EmailServiceTest`, `EmailServiceGuestTest`, `MultiHostEmailFanoutTest` stay green
    unmodified (non-set output unchanged).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** — `BookingSnapshot` gains `List<Booking> setBookings` (empty unless
  `setId`); `inviteeIcsBytes`/`guestIcs` build one event per set Booking; handlers render
  `sessions` from it; `_sessions.html` renders the list ("{k} of {n} · {when} · {minutes} min").
  Guests: `bookSet` persists the guest rows on every set Booking's lead row (Task 4 already calls
  `persistGuests` per row — check) and `sendGuestInvites` sends once per address for a set;
  `declineGuest` declines that address on every lead row of the set.
- [ ] **Step 4: Run** the new tests + every `src/test/java/site/asm0dey/calit/email` test — PASS.
- [ ] **Step 5: Commit** `feat(email): one mail and one .ics per Booking set`.

### Task 7: Step 1 — the slot picker

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (`book`, `invitePage`,
  `renderBook`, `bookTemplate`, `Templates.book`), `src/main/resources/templates/PublicResource/book.html`,
  `src/main/css/input.css`, `AppMessages` + `msg_de/he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/BookPageSetTest.java`, `BookingInviteRoutesTest.java`

**Interfaces:**
- Consumes: `SlotPick.parseAll`, `SetRules.blocked`.
- Produces: `public record SetPicker(int min, int max, List<SlotPick> picks, Map<String, SetRules.Blocked> blockedBySlot)`
  passed to `Templates.book` as a new last param `SetPicker picker` (null when multi-slot is off ⇒
  today's markup). Query params on both GET routes: repeated `pick`, `duration`, `remove` (a pick value),
  `step` (`details` to go to step 2).

- [ ] **Step 1: Write the failing tests** (RestAssured GET, assert HTML):
  - `offTypeRendersRadiosUnchanged` — no `name="pick"`, has `name="startUtc"`.
  - `multiSlotRendersCheckboxesInGetForm` — `<form method="get"`, `type="checkbox" name="pick"`, steps
    header "Pick sessions".
  - `picksSurviveDurationSwitch` — GET `?pick=A/30&duration=60` → A rendered as a hidden `pick` input and
    listed in the rail with "30", the 60-min slots as checkboxes.
  - `removeDropsPick` — `?pick=A/30&pick=B/30&remove=A/30` → only B.
  - `sameDayPickDisablesThatDay` — `disabled` on the other slots of A's date, reason "You already have
    a session this day".
  - `maxReachedDisablesRest` — reason "You have the maximum of 3".
  - `continueDisabledBelowMin` — "Pick at least 2 sessions" with `disabled`.
  - `otherLengthPickShowsBadgeAndDayDot` — `?pick=A/30&duration=60` → A's time carries the "30m" badge,
    A's calendar day carries the pick-dot marker class.
  - `tamperedPickRendersPageNot500` (Review Focus 3) — `?pick=garbage` → 200, error alert.
  - `BookingInviteRoutesTest.invitePickerHidesSlug` — invite GET with picks, duration switch and remove
    → `not(containsString(SLUG))`, form action is `/{user}`, `invite` carried as a hidden input.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** — parse picks on GET, build `SetPicker` (blocked map keyed by
  `SlotView.startUtc` for the current duration), template branch on `picker != null`: one GET form
  wrapping calendar + slots + rail; duration links become `<button name="duration" value="…">`; ✕ is
  `<button name="remove" value="…">`; Continue is `<button name="step" value="details">`. The invite
  route's GET form posts to `/{user}` with hidden `invite`. CSS: checkbox pills share today's
  `:checked + time` rules; `.slot-disabled` dashed/faded; accent day dot. Live region
  `role="status" aria-atomic="true"`.
- [ ] **Step 4: Add i18n** for every new `pub_*` key (`de` + `he`).
- [ ] **Step 5: Run** the two classes + `BookPageTest` — PASS. `bun run css:build` succeeds.
- [ ] **Step 6: Commit** `feat(public): pick several sessions on the booking page`.

### Task 8: Step 2 — details, submit, conflict bounce, confirmation

**Files:**
- Modify: `PublicResource.java` (`book`/`invitePage` render step 2 when `step=details`; `submitBooking`,
  `submitInvite`, `submit`; new `setConfirmationPage`), `book.html`, create
  `src/main/resources/templates/PublicResource/setConfirmation.html`, i18n files
- Test: `src/test/java/site/asm0dey/calit/web/BookSetFlowTest.java`, `BookingInviteRoutesTest.java`

**Interfaces:**
- Consumes: `BookingService.bookSet`, `SetPicker`.
- Produces: `Templates.setConfirmation(String title, List<Booking> bookings, MeetingType type, boolean pending, String tzBar, String tzScript)`.
  POST bodies carry repeated `pick`; when present (≥ 1) the POST goes to `bookSet`, else today's path.

- [ ] **Step 1: Write the failing tests:**
  - `detailsStepCarriesPicksAsHiddenInputs` — `?pick=A/30&pick=B/60&step=details` → POST form with two
    hidden `pick`, alert "You're booking 2 sessions", button "Confirm 2 sessions" ("Request 2 sessions"
    when approval).
  - `belowMinStaysOnStep1` — `step=details` with 1 pick on min 2 → step 1 with "Pick at least 2."
  - `postBooksSetAndShowsRows` — 200, "You're booked: 2 sessions", two rows each with its own
    `/booking/{token}/manage` link.
  - `conflictBouncesToStep1KeepingFreePicks` — B taken before POST → step 1, A still picked, B absent,
    error naming B's time.
  - `validationErrorKeepsPicksOnStep2` — missing email → step 2, both hidden picks present.
  - `setIcsDownloadHoldsEverySession` — the confirmation's "Add all to calendar (.ics)" links to the
    existing `/booking/{leadToken}/invite.ics`, which (via Task 6's set-aware `inviteeIcsBytes`) returns
    one VEVENT per Booking.
  - `BookingInviteRoutesTest.inviteSetFlowHidesSlug` — details step, conflict bounce and confirmation
    via the Invite → `not(containsString(SLUG))`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** — `submit` reads `form.get("pick")`; ≥ 1 pick → `SlotPick.parseAll` →
  `bookSet`; one returned row → existing `confirmationPage`, several → `setConfirmationPage`. On
  `BookingConflictException`, recompute which picks are still available (`availableSlots` per day) and
  render step 1 with the survivors and the error; other exceptions render step 2 with the same picks.
  Invite route keeps the generic conflict message (`pub_invite_slot_unavailable`) plus the times.
- [ ] **Step 4: Run** the classes + `BookPageTest`, `InviteeFieldModesTest` — PASS.
- [ ] **Step 5: Commit** `feat(public): book a set from the details step`.

### Task 9: Manage page siblings, Cancel all, dashboard tag

**Files:**
- Modify: `BookingService.java` (new `cancelSet`), `src/main/java/site/asm0dey/calit/booking/events/` (new
  `BookingSetCancelled`), `EmailService.java` + `NotificationDispatcher.java` (observe it),
  `PublicResource.java` (`manage`, new POST route), `PublicResource/manage.html`, the dashboard
  template that lists bookings (`AdminResource` dashboard view), i18n files
- Test: `src/test/java/site/asm0dey/calit/booking/SetCancelTest.java`, `src/test/java/site/asm0dey/calit/web/ManageSetTest.java`

**Interfaces:**
- Produces: `@Transactional public List<Booking> cancelSet(String manageToken, boolean byOwner)` —
  cancels every still-held (PENDING|CONFIRMED, future) Booking of the token's set through the existing
  per-row cancel internals without firing per-row `BookingCancelled`, then fires one
  `record BookingSetCancelled(Long leadBookingId, List<Long> cancelledIds, boolean byOwner)`.
  Route: `POST /booking/{manageToken}/cancel-all` (CSRF token) → the existing `cancelled` page.

- [ ] **Step 1: Write the failing tests:**
  - `cancelSetCancelsRemainingOnly` — one of 3 already cancelled → the other 2 CANCELLED, their Mirror
    events deleted, one `BookingSetCancelled` with 2 ids, zero `BookingCancelled`.
  - `cancelSetSendsOneMailListingCancelled` and one guest cancellation per guest.
  - `cancellingOneBookingKeepsSiblings` — existing `cancel(token)` on a set row → siblings untouched,
    today's single mail.
  - `ManageSetTest.manageListsSiblingsWithLinks` and shows "Cancel all 2 sessions" (POST form with
    `{inject:csrf.token}`).
  - `ManageSetTest.dashboardTagsKOfN` — "2 of 3" on the Owner's dashboard row.
  - `ManageSetTest.manageViaInviteBookingHasNoSlug`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; email handler reuses the cancellation template with `sessions` (Task 6).
- [ ] **Step 4: Run** the classes + `GroupCancelRescheduleTest` — PASS.
- [ ] **Step 5: Commit** `feat(booking): cancel all sessions of a set`.

### Task 10: Full suite, docs and changelog

**Files:**
- Modify (branch `docs-site`): a usage page under `docs-site/src/content/docs/` for meeting types
  (add "Several sessions per booking"); `docs-site/src/content/docs/releases/changelog.md` `## Unreleased`
  bullet at merge time.

- [ ] **Step 1: Run** `mvn test` — 0 failures, 0 errors, `BUILD SUCCESS`; `mvn spotless:check` clean.
- [ ] **Step 2: Run** the i18n parity check: every new `String key()` in `AppMessages`/`AdminMessages`
  has a line in each of the four locale files.
- [ ] **Step 3: Write the docs section** — what the setting does, the cap of 5, approval/co-hosts/invites
  apply to the whole set, migration `V40` adds nullable columns (nothing to do on upgrade).
- [ ] **Step 4: Changelog bullet** (≤ 35 words, ends with the PR link), upgrade note "nothing to do".
- [ ] **Step 5: Commit** on `docs-site`: `docs: several sessions per booking`; update bean `calit-ku6n`
  checkboxes.
