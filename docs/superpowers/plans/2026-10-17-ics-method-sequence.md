# Invitee .ics METHOD/SEQUENCE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The invitee's (and owner/host copies') `.ics` carries `METHOD:CANCEL` on cancellation and decline, and `SEQUENCE = booking.icsSequence` on every message, so recipients' calendars update or remove the event instead of keeping a stale one.

**Architecture:** `EmailService.inviteeIcsBytes` gains an `IcsMethod` parameter and always stamps `booking.icsSequence`. The two private `sendForKindLocaleAware` overloads gain an `IcsMethod` parameter, which they pass to it. `handleCancelled` and `deliverDeclined` pass `CANCEL`; every other caller passes `REQUEST`.

**Tech Stack:** Quarkus 3.39.5 / Java 25, Qute, quarkus-mailer `MockMailbox`, AssertJ.

**Spec:** bean `calit-bgxr` (`.beans/calit-bgxr--inviteeowner-ics-is-always-methodrequest-sequence0.md`). Prerequisite for `docs/superpowers/specs/2026-10-17-caldav-calendar-design.md` (call 12).

## Global Constraints

- No user-facing string changes, so no i18n work.
- The guest `.ics` path (`guestIcs`, `sendGuestCancels`) is already correct. Don't touch it.
- When Google is connected, no `.ics` is attached; that branch is unchanged.
- AssertJ only; `assertThat(boolean).isTrue()` is forbidden where a fluent check exists.
- Full `mvn test` green before the PR.

## Review Focus

1. **Cancel after a reschedule:** `icsSequence` is already > 0, and the CANCEL must carry that same value, not 0, or clients ignore it. Test: `cancelAfterRescheduleCarriesCurrentSequence`.
2. **Owner copy of a cancellation:** the single-host owner copy gets the same CANCEL attachment as the invitee, since both come from `sendForKindLocaleAware`. Test: `ownerCopyOfCancellationIsCancel`.
3. **The `.ics` download endpoint (`inviteeIcs`)** keeps serving a REQUEST with the current sequence. Test: `downloadIsRequestWithCurrentSequence`.

---

### Task 1: METHOD and SEQUENCE on the invitee .ics

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`:
  - `inviteeIcsBytes` (~:1092)
  - both `sendForKindLocaleAware` overloads (~:928, ~:950) and their seven callers (~:386, 445, 495, 535, 574, 613, 656)
  - `inviteeIcs` (~:1080)
- Test: `src/test/java/site/asm0dey/calit/email/InviteeIcsMethodTest.java` (new; model setup on `EmailServiceGuestTest`: `@QuarkusTest`, `@InjectMock CalendarPort` returning `isConnected = false`, `MockMailbox`, owner id 1)

**Interfaces:**
- Produces: `private byte[] inviteeIcsBytes(BookingSnapshot l, String location, IcsMethod method)`, which sets `.method(method).sequence(l.booking().icsSequence)`.
- `sendForKindLocaleAware(..., IcsMethod icsMethod, boolean actionable)` and `(..., MailSink sink, IcsMethod icsMethod, boolean actionable)`: the new parameter goes immediately before `actionable` in both.

- [ ] **Step 1: Write the failing tests** in `InviteeIcsMethodTest`. Each test reads the attachment of the mail sent to the invitee (or owner) as a UTF-8 string:
  - `cancellationIsCancel`: cancel a confirmed booking with `icsSequence = 0`. The invitee's attachment contains `METHOD:CANCEL` and `STATUS:CANCELLED`, and doesn't contain `METHOD:REQUEST`.
  - `cancelAfterRescheduleCarriesCurrentSequence`: booking with `icsSequence = 2`, then cancel. The attachment contains `SEQUENCE:2` and `METHOD:CANCEL`.
  - `declineIsCancel`: decline a pending booking. The invitee's attachment contains `METHOD:CANCEL`.
  - `rescheduleRequestCarriesSequence`: rescheduled notice with `icsSequence = 1`. The attachment contains `METHOD:REQUEST` and `SEQUENCE:1`.
  - `ownerCopyOfCancellationIsCancel`: single-host booking with `ownerNotificationsEnabled = true`, then cancel. The owner's attachment contains `METHOD:CANCEL`.
  - `downloadIsRequestWithCurrentSequence`: `emailService.inviteeIcs(id)` with `icsSequence = 3` contains `METHOD:REQUEST` and `SEQUENCE:3`.

  Drive cancel/decline/reschedule the way the existing email tests do (the `BookingCancelled` / declined / rescheduled events or the `BookingService` calls those tests use). Read `EmailServiceGuestTest` and `EmailServiceTest` for the fixture helpers.

- [ ] **Step 2: Run them.** `JAVA_HOME=/home/finkel/.sdkman/candidates/java/27.0.0+36-librca ./mvnw -o test -Dtest=InviteeIcsMethodTest`. Expected: the CANCEL and SEQUENCE assertions FAIL (today it's always REQUEST / 0); `downloadIsRequestWithCurrentSequence` fails on SEQUENCE.

- [ ] **Step 3: Implement** per Interfaces. `handleCancelled` and `deliverDeclined` pass `IcsMethod.CANCEL`; all other callers pass `IcsMethod.REQUEST`; `inviteeIcs` passes `REQUEST`. Update the javadoc on `inviteeIcsBytes`, which currently says "REQUEST .ics".

- [ ] **Step 4: Run** `InviteeIcsMethodTest`, then the whole suite with `./mvnw -o test` (same `JAVA_HOME`). Expected: all green, `BUILD SUCCESS`. Existing tests that asserted the old REQUEST-on-cancel behaviour are wrong now: update them, and name each one in the report.

- [ ] **Step 5: Commit** with explicit paths (no `git add -A`):
  ```bash
  git add src/main/java/site/asm0dey/calit/email/EmailService.java src/test/java/site/asm0dey/calit/email/InviteeIcsMethodTest.java <any updated test files>
  git commit -m "fix(email): invitee .ics carries CANCEL and the booking's SEQUENCE"
  ```
