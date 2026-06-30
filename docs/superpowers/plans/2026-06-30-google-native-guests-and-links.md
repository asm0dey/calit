# Google-Native Guests + Calit Link Emails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an owner has Google connected, make Google the single source of calendar truth for *everyone* on a booking (invitee + owner + guests), while calit emails carry only human notices and the reschedule/decline links — no duplicate `.ics`.

**Architecture:** Add guests to the Google event's attendee list so Google natively invites/updates/cancels them. Introduce one invariant in the email layer — **when `calendarPort.isConnected(ownerId)` is true, calit attaches no `.ics` to any booking email** (Google owns the calendar entry) but still *sends* the email so its reschedule/decline links reach the recipient. Sync guest add/remove (decline, reschedule reconcile) back onto the Google event via an extended `updateEvent` that also patches the attendee list with `sendUpdates=all`.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache entities, Qute email templates, Mockito `@InjectMock CalendarPort`, `MockMailbox` for mail assertions, Google Calendar API v3.

## Global Constraints

- Owner-scoping invariant: every query filters by `owner_id` / `ownerId`. Do not read or write across owners.
- No new user-facing strings → **no i18n changes** in this plan (templates reused, only the `.ics` attachment varies).
- Tests require Docker (Dev Services Postgres). Admin/owner is always id `1L`.
- Spotless/palantir formatting is a `verify`-phase CI gate; run `mvn spotless:apply` before committing. `mvn test` (test phase) is unaffected.
- Behavior change is part of "done" → docs-site changelog + behavior note required (Task 6).

## Known limitation (document, do not fix)

Guests become Google attendees, so Google shows them native Accept/Decline RSVP buttons that calit cannot observe (a Google RSVP routes to the owner's Google account, not back to calit). The calit decline link (`/guest/{token}/decline`) remains the authoritative path; a guest who RSVPs in Google instead won't update `BookingGuest.status`. Google gives no API to suppress native RSVP. Mark with a `ponytail:` comment at the attendee-build site.

## File Structure

- `src/main/java/site/asm0dey/calit/google/CalendarPort.java` — extend `updateEvent` signature (add attendees).
- `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java` — patch attendees in `updateEvent`.
- `src/main/java/site/asm0dey/calit/booking/BookingService.java` — `attendeeEmails(...)` helper; guests on create; attendee sync on reschedule + decline.
- `src/main/java/site/asm0dey/calit/email/EmailService.java` — `connected ⇒ no .ics` invariant; always send invitee; drop dead `InviteeRule`.
- Tests under `src/test/java/site/asm0dey/calit/{booking,email}/`.
- Docs on `docs-site` branch (separate worktree/checkout).

---

### Task 1: Extend `updateEvent` to patch attendees

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/CalendarPort.java:42`
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java:226-243`
- Modify (compile fix only, no behavior yet): `src/main/java/site/asm0dey/calit/booking/BookingService.java:487-489`
- Test: `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java:61`

**Interfaces:**
- Produces: `void updateEvent(Long ownerId, String eventId, Instant start, Instant end, List<String> attendeeEmails)` — patches start/end **and** replaces the attendee list, `sendUpdates=all`. A null/empty `attendeeEmails` leaves attendees untouched.

- [ ] **Step 1: Update the existing reschedule verify to the new 5-arg signature (failing compile/test first)**

In `RescheduleCancelTest.java:61`, change:
```java
verify(calendarPort, times(1)).updateEvent(anyLong(), eq("evt-r"), eq(SLOT_10), eq(SLOT_10.plusSeconds(3600)));
```
to:
```java
verify(calendarPort, times(1))
        .updateEvent(anyLong(), eq("evt-r"), eq(SLOT_10), eq(SLOT_10.plusSeconds(3600)), any());
```

- [ ] **Step 2: Run it — expect compile failure (interface still 4-arg)**

Run: `mvn -q test -Dtest=RescheduleCancelTest`
Expected: FAIL — compilation error, `updateEvent(...)` arity mismatch.

- [ ] **Step 3: Extend the interface**

`CalendarPort.java:41-42`, replace the method with:
```java
    /** Move an existing event to a new time window and replace its attendee list (reschedule / guest sync); {@code sendUpdates=all}. A null attendee list leaves attendees unchanged. */
    void updateEvent(Long ownerId, String eventId, Instant start, Instant end, java.util.List<String> attendeeEmails);
```

- [ ] **Step 4: Patch attendees in the impl**

`GoogleCalendarPort.java:226-243`, replace the method body:
```java
    @Override
    @Transactional
    public void updateEvent(Long ownerId, String eventId, Instant start, Instant end, List<String> attendeeEmails) {
        var ctx = writeContext(ownerId);
        GoogleCalendar target = ctx.target();
        GoogleCredential cred = ctx.cred();
        Event patch = new Event().setStart(eventTime(ownerId, start)).setEnd(eventTime(ownerId, end));
        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            patch.setAttendees(attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }
        try {
            // sendUpdates=all so Google emails attendees the new time and notifies anyone added/removed.
            client(cred)
                    .events()
                    .patch(target.googleCalendarId, eventId, patch)
                    .setSendUpdates("all")
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEvent failed", e);
        }
    }
```

- [ ] **Step 5: Fix the one production caller (no behavior change yet — pass null)**

`BookingService.java:487-489`, temporarily pass `null` for attendees (Task 3 replaces it):
```java
            if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
                calendarPort.updateEvent(type.ownerId, booking.googleEventId, newStartUtc, newEnd, null);
            }
```

- [ ] **Step 6: Run the reschedule test — expect PASS**

Run: `mvn -q test -Dtest=RescheduleCancelTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
mvn -q spotless:apply
git add src/main/java/site/asm0dey/calit/google/CalendarPort.java \
        src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java \
        src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java
git commit -m "refactor(calendar): updateEvent also patches the attendee list"
```

---

### Task 2: Guests become Google attendees on event creation

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:279-292` (and add a private helper)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java`

**Interfaces:**
- Produces: `private static List<String> attendeeEmails(Booking booking, OwnerSettings owner)` — `[inviteeEmail, ownerEmail, ...active(INVITED) guest emails]`. Used by create + Task 3 sync.

- [ ] **Step 1: Write the failing test**

Add to `BookingServiceGuestTest.java` (it already has `@InjectMock CalendarPort` and the `book(...)` helper; mirror `BookServiceTest.happyPath` stubs):
```java
    @Test
    @TestTransaction
    void guestsAreAddedAsGoogleAttendees() {
        seedSettings();
        meetingTypeWithMondayWindow("guest-attendees", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-g", null, "https://calendar.google.com/evt-g"));

        bookingService.book(
                1L, "guest-attendees", SLOT_09, "Sam", "sam@example.com",
                Map.of(), "tok-g", "", "en", List.of("g1@example.com", "g2@example.com"));

        verify(calendarPort, times(1))
                .createEvent(
                        anyLong(), anyString(), anyString(), eq(SLOT_09), any(),
                        eq(List.of("sam@example.com", "owner@example.com", "g1@example.com", "g2@example.com")),
                        anyBoolean(), any());
    }
```
> If `BookingServiceGuestTest` lacks `seedSettings`/`meetingTypeWithMondayWindow`/`SLOT_09`, copy them verbatim from `BookServiceTest.java` (`:371`, `:386-405`, and the `SLOT_09` constant) — they are duplicated per-class by convention.

- [ ] **Step 2: Run it — expect FAIL**

Run: `mvn -q test -Dtest=BookingServiceGuestTest#guestsAreAddedAsGoogleAttendees`
Expected: FAIL — actual attendees were `[sam@example.com, owner@example.com]` (guests missing).

- [ ] **Step 3: Add the helper + use it in `createGoogleEvent`**

In `BookingService.java`, add the helper (near `createGoogleEvent`):
```java
    /**
     * The full Google attendee set for this booking: invitee + owner + currently-active (INVITED) guests.
     * ponytail: guests here also gain Google's native Accept/Decline RSVP, which calit can't observe — the
     * calit decline link stays authoritative; a Google-side RSVP won't update BookingGuest.status. No Google
     * API suppresses native RSVP, so this is accepted.
     */
    private static List<String> attendeeEmails(Booking booking, OwnerSettings owner) {
        List<String> emails = new ArrayList<>();
        emails.add(booking.inviteeEmail);
        emails.add(owner.ownerEmail);
        for (BookingGuest g : BookingGuest.<BookingGuest>activeForBooking(booking.id)) {
            emails.add(g.email);
        }
        return emails;
    }
```
Then in `createGoogleEvent` (`:281-289`) replace the attendee argument:
```java
        CreatedEvent created = calendarPort.createEvent(
                type.ownerId,
                type.name + " with " + booking.inviteeName,
                "Booked via calit.",
                booking.startUtc,
                booking.endUtc,
                attendeeEmails(booking, owner),
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
```
(`owner` is already in scope as `OwnerSettings.forOwner(type.ownerId)` at `:280`. Ensure `java.util.ArrayList` is imported.)

- [ ] **Step 4: Run it — expect PASS. Also run the no-guest happy path to confirm it's unaffected**

Run: `mvn -q test -Dtest=BookingServiceGuestTest#guestsAreAddedAsGoogleAttendees,BookServiceTest`
Expected: PASS (the no-guest `BookServiceTest` still asserts `[sam@example.com, owner@example.com]` because `activeForBooking` returns empty).

- [ ] **Step 5: Commit**

```bash
mvn -q spotless:apply
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java
git commit -m "feat(google): add booking guests as Google event attendees"
```

---

### Task 3: Sync attendee changes (decline + reschedule reconcile) to Google

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:486-489` (reschedule) and `:554-565` (declineGuest)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java`, `src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java`

**Interfaces:**
- Consumes: `attendeeEmails(Booking, OwnerSettings)` (Task 2); `updateEvent(..., attendees)` (Task 1).

- [ ] **Step 1: Write the failing decline test**

Add to `BookingServiceGuestTest.java`. Use the existing guest-seeding path (book with a guest, then decline it via its token). If the class has a helper that returns the booking/guest, reuse it; otherwise seed inline:
```java
    @Test
    @TestTransaction
    void decliningAGuestPatchesGoogleAttendeesWithoutThatGuest() {
        seedSettings();
        meetingTypeWithMondayWindow("guest-decline-sync", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-d", null, "https://calendar.google.com/evt-d"));

        Booking b = bookingService.book(
                1L, "guest-decline-sync", SLOT_09, "Sam", "sam@example.com",
                Map.of(), "tok-d", "", "en", List.of("g1@example.com", "g2@example.com"));

        BookingGuest g1 = BookingGuest.<BookingGuest>allForBooking(b.id).stream()
                .filter(g -> g.email.equals("g1@example.com")).findFirst().orElseThrow();
        bookingService.declineGuest(g1.declineToken);

        verify(calendarPort, times(1))
                .updateEvent(anyLong(), eq("evt-d"), eq(SLOT_09), eq(SLOT_09.plusSeconds(3600)),
                        eq(List.of("sam@example.com", "owner@example.com", "g2@example.com")));
    }
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `mvn -q test -Dtest=BookingServiceGuestTest#decliningAGuestPatchesGoogleAttendeesWithoutThatGuest`
Expected: FAIL — `updateEvent` never called from `declineGuest`.

- [ ] **Step 3: Patch attendees in `declineGuest`**

`BookingService.java:554-565`, replace body:
```java
    @Transactional
    public void declineGuest(String declineToken) {
        BookingGuest guest = BookingGuest.findByDeclineToken(declineToken);
        if (guest == null) {
            throw new NotFoundException("No guest for token " + declineToken);
        }
        if (guest.status == GuestStatus.DECLINED) {
            return; // idempotent: a second decline click is a no-op
        }
        guest.status = GuestStatus.DECLINED;
        // Re-sync the Google attendee list (now excludes this guest, since activeForBooking returns only INVITED).
        // Google emails the removed guest a cancellation via sendUpdates=all.
        Booking booking = Booking.findById(guest.bookingId);
        if (booking != null && calendarPort.isConnected(guest.ownerId) && booking.googleEventId != null) {
            OwnerSettings owner = OwnerSettings.forOwner(guest.ownerId);
            calendarPort.updateEvent(
                    guest.ownerId, booking.googleEventId, booking.startUtc, booking.endUtc,
                    attendeeEmails(booking, owner));
        }
        guestDeclinedEvent.fire(new GuestDeclined(guest.bookingId, guest.id));
    }
```

- [ ] **Step 4: Run it — expect PASS**

Run: `mvn -q test -Dtest=BookingServiceGuestTest#decliningAGuestPatchesGoogleAttendeesWithoutThatGuest`
Expected: PASS.

- [ ] **Step 5: Wire reschedule to pass the live attendee list**

`BookingService.java:486-489` (the non-approval branch), replace the `null` from Task 1:
```java
        } else {
            if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
                OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
                calendarPort.updateEvent(
                        type.ownerId, booking.googleEventId, newStartUtc, newEnd,
                        attendeeEmails(booking, owner));
            }
            bookingRescheduledEvent.fire(new BookingRescheduled(booking.id, oldStart));
        }
```

- [ ] **Step 6: Update + extend reschedule tests**

In `RescheduleCancelTest.java`, the verify from Task 1 step 1 (`...any()` for attendees) still passes. Add a guest-aware assertion (seed a booking with a guest, reschedule with the guest dropped, assert attendees minus that guest). Place after the existing reschedule test:
```java
    @Test
    @TestTransaction
    void rescheduleSyncsAttendeesWhenGuestRemoved() {
        seedSettings();
        meetingTypeWithMondayWindow("resched-guest", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-rg", null, "https://calendar.google.com/evt-rg"));

        Booking b = bookingService.book(
                1L, "resched-guest", SLOT_09, "Sam", "sam@example.com",
                Map.of(), "tok-rg", "", "en", List.of("g1@example.com"));

        // reschedule to SLOT_10 with NO guests → g1 removed
        bookingService.reschedule(b.manageToken, SLOT_10, List.of());

        verify(calendarPort, times(1))
                .updateEvent(anyLong(), eq("evt-rg"), eq(SLOT_10), eq(SLOT_10.plusSeconds(3600)),
                        eq(List.of("sam@example.com", "owner@example.com")));
    }
```
> Copy `SLOT_10`, `seedSettings`, `meetingTypeWithMondayWindow` from `BookServiceTest`/`RescheduleCancelTest` if not present.

- [ ] **Step 7: Run reschedule + decline tests — expect PASS**

Run: `mvn -q test -Dtest=RescheduleCancelTest,BookingServiceGuestTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
mvn -q spotless:apply
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java \
        src/test/java/site/asm0dey/calit/booking/RescheduleCancelTest.java
git commit -m "feat(google): sync attendee list on guest decline and reschedule"
```

---

### Task 4: Email invariant — Google connected ⇒ no calit `.ics`, but always deliver the link email

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` — `sendForKindLocaleAware` (`:681-710`), `sendGuestInvites` (`:528-553`), `sendGuestCancels` (`:556-569`), `handleGuestRemoved` (`:571-583`), `handleGuestDeclined` guest `.ics` (`:593-598`); remove `InviteeRule` enum (`:166-172`) + its params at all `sendForKindLocaleAware` call sites.
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java`, `src/test/java/site/asm0dey/calit/email/EmailServiceGuestTest.java`

**Interfaces:**
- The `connected ⇒ ics == null` rule is local to EmailService. No new public surface.

- [ ] **Step 1: Write failing tests for the new email behavior**

Add to `EmailServiceGuestTest.java` (mirror the existing connected-path test style; `@InjectMock CalendarPort` is present):
```java
    @Test
    void guestGetsLinkButNoIcsWhenGoogleConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seedWithGuest(GuestStatus.INVITED);
        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail m = mailbox.getMailsSentTo(GUEST_EMAIL).getFirst();
        assertTrue(m.getHtml().contains("/guest/"), "guest decline link present");
        assertTrue(m.getAttachments().isEmpty(), "no .ics when Google notifies");
    }
```
Add to `EmailServiceTest.java`:
```java
    @Test
    void inviteeGetsLinkEmailWithoutIcsWhenGoogleConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = /* seed a CONFIRMED booking — reuse this class's seed helper */;
        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee still gets the calit notice (carries the manage link)");
        Mail m = toInvitee.getFirst();
        assertTrue(m.getHtml().contains("/manage"), "manage/reschedule link present");
        assertTrue(m.getAttachments().isEmpty(), "no .ics when Google notifies");
    }
```
> Use this class's existing CONFIRMED-seed helper (see `EmailServiceTest.seed(...)` ~`:378`). If a current test asserts the invitee gets **no** mail when connected, that is the old behavior — locate it (`grep -n "getMailsSentTo(INVITEE" src/test/java/site/asm0dey/calit/email/EmailServiceTest.java`) and update it to expect 1 mail with no attachment.

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -q test -Dtest=EmailServiceGuestTest#guestGetsLinkButNoIcsWhenGoogleConnected,EmailServiceTest#inviteeGetsLinkEmailWithoutIcsWhenGoogleConnected`
Expected: FAIL — guest currently has an `.ics`; invitee currently gets no mail when connected.

- [ ] **Step 3: Apply the invariant in `sendForKindLocaleAware`**

`EmailService.java:681-710`, replace the two-arg-rule body. Remove the `rule` parameter from **both** overloads (`:665-672` and `:681-688`) and from every call site (`handleRequested:237-238`, `handleConfirmed:276-277`, `handleApproved`, `handleRescheduled`, `handleCancelled`, `handleDeclined` — drop the leading `InviteeRule.XXX,` argument). Then:
```java
    private void sendForKindLocaleAware(
            Loaded l,
            String icsLocation,
            String inviteeSubject,
            String ownerSubject,
            UnaryOperator<String> bodyForRole,
            MailSink sink) {
        // Invariant: when Google is connected it natively notifies invitee + owner (they are event
        // attendees), so calit attaches NO .ics. We still send the email — it carries the manage/cancel
        // links Google's invite does not. When NOT connected, calit's .ics is the only calendar source.
        boolean googleNotifies = calendarPort.isConnected(l.owner.ownerId);
        byte[] ics = googleNotifies
                ? null
                : IcsBuilder.build(IcsEvent.builder()
                                .uid(l.booking.manageToken)
                                .summary(l.meetingType.name)
                                .location(icsLocation)
                                .organizer(new IcsBuilder.Party(l.owner.ownerName, mailFrom))
                                .attendee(new IcsBuilder.Party(l.booking.inviteeName, l.booking.inviteeEmail))
                                .start(l.booking.startUtc)
                                .end(l.booking.endUtc)
                                .build())
                        .getBytes(StandardCharsets.UTF_8);
        var from = fromName(l);

        sink.deliver(from, l.booking.inviteeEmail, inviteeSubject, bodyForRole.apply(INVITEE_ROLE), ics);
        if (l.owner.ownerNotificationsEnabled) {
            sink.deliver(from, l.owner.ownerEmail, ownerSubject, bodyForRole.apply(OWNER_ROLE), ics);
        }
    }
```
Delete the now-unused `InviteeRule` enum (`:166-172`). Also update the single-arg overload (`:665-672`) to drop `rule`.

- [ ] **Step 4: Apply the invariant to every guest `.ics` site**

`sendGuestInvites` (`:535`): replace `byte[] ics = guestIcs(l, g, location, IcsMethod.REQUEST);` with
```java
            byte[] ics = calendarPort.isConnected(l.owner.ownerId) ? null : guestIcs(l, g, location, IcsMethod.REQUEST);
```
`sendGuestCancels` (`:561-567`), `handleGuestRemoved` (`:577-582`), `handleGuestDeclined` guest send (`:593-598`): wrap each `guestIcs(...)` argument the same way:
```java
            calendarPort.isConnected(l.owner.ownerId) ? null : guestIcs(l, g, null, IcsMethod.CANCEL)
```
(Keep the invitee-notify in `handleGuestDeclined:599-616` exactly as-is — it has no `.ics` and Google does not tell other attendees who declined.)

- [ ] **Step 5: Run the new tests — expect PASS**

Run: `mvn -q test -Dtest=EmailServiceGuestTest,EmailServiceTest`
Expected: PASS (after the old invitee/guest connected-path assertions are updated in Task 5).

- [ ] **Step 6: Commit**

```bash
mvn -q spotless:apply
git add src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/test/java/site/asm0dey/calit/email/EmailServiceTest.java \
        src/test/java/site/asm0dey/calit/email/EmailServiceGuestTest.java
git commit -m "feat(email): Google connected suppresses calit .ics, still sends link emails"
```

---

### Task 5: Fix tests broken by the behavior change + full green run

**Files:**
- Modify: `src/test/java/site/asm0dey/calit/email/EmailServiceGuestTest.java:105-118` and any EmailServiceTest case asserting the old connected-path behavior.

- [ ] **Step 1: Update the now-wrong existing test**

`EmailServiceGuestTest.java:105-118` — `confirmedSendsGuestInviteWithDeclineLinkAndIcsEvenWhenGoogleConnected` asserts a guest `.ics` even when connected, which Task 4 reverses. The test as written likely does **not** stub `isConnected` → it's the default mock `false` (disconnected), so it may still pass as a *disconnected* case. Verify: if it does not stub `isConnected(...)=true`, rename it to `...WhenGoogleDisconnected` and keep the `.ics`-present assertion. If it does stub true, flip the assertion to `assertTrue(m.getAttachments().isEmpty())` and rename to `...NoIcsWhenGoogleConnected`. Decide by reading the test's stubs.

- [ ] **Step 2: Grep for other assertions coupled to the old behavior**

Run:
```bash
grep -rn "getMailsSentTo(INVITEE" src/test/java/site/asm0dey/calit/email/
grep -rn "isConnected" src/test/java/site/asm0dey/calit/email/
```
Update any case asserting "invitee gets no mail when connected" → now "1 mail, no `.ics`". Update any "owner `.ics` present when connected" → no `.ics`.

- [ ] **Step 3: Full suite green**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 4: Spotless gate**

Run: `mvn -q spotless:check`
Expected: no violations (run `spotless:apply` if it fails, then re-commit).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/site/asm0dey/calit/email/
git commit -m "test(email): align connected-path expectations with Google-native invites"
```

---

### Task 6: Docs (part of done)

**Files:**
- `docs-site` branch: `docs-site/src/content/docs/releases/changelog.md` (new top entry) + the Google/usage page that describes booking notifications.

- [ ] **Step 1: Add a changelog entry** on the `docs-site` branch describing: guests now appear in the Google Calendar event and receive Google's native invite; when Google is connected, calit no longer attaches a duplicate `.ics`; invitee/guest reschedule and decline links are still delivered by calit and now sync to the Google event (removed guests get a Google cancellation). Note the known limitation (guest Google-side RSVP isn't tracked by calit).

- [ ] **Step 2: Update the booking-notifications/Google doc page** to reflect the "Google connected = Google is the single calendar source; calit emails carry links only" model.

- [ ] **Step 3: Commit on `docs-site`** with `docs: google-native guest invites + no duplicate ics`.

---

## Self-Review

- **Spec coverage:** guests as Google attendees → Task 2; reschedule notifies all → Tasks 1+3 (updateEvent patch, sendUpdates=all) + link delivery Task 4; guest decline removes from Google + notifies invitee → Task 3 (attendee patch) + existing invitee-notify retained in Task 4; no duplicate emails → Task 4 invariant; docs → Task 6. ✅
- **Placeholder scan:** one intentional `/* seed a CONFIRMED booking */` in Task 4 Step 1 — resolved by the adjacent note pointing at the class's existing seed helper. No other placeholders.
- **Type consistency:** `attendeeEmails(Booking, OwnerSettings)` and the 5-arg `updateEvent(Long, String, Instant, Instant, List<String>)` are used identically in Tasks 1–4. `BookingGuest.activeForBooking` (returns INVITED only) and `allForBooking` (all rows) used per existing semantics.
- **Risk:** removing `InviteeRule` touches ~6 call sites — mechanical; the compiler enforces completeness. The `verify(... any())` widening in Task 1 keeps existing reschedule coverage while admitting the new arg.
