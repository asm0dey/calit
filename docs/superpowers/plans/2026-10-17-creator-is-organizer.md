# The Creator Is Always the Organizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Mirror event is written on the Creator's connected account or not at all. The Co-host organizer fallback and the Co-host write override are removed, and the location falls back to the meeting type's `locationDetail`.

**Architecture:** This deletes code and adds none. `MeetingHosts.chooseOrganizer` returns the Creator or null; the group event always lives on the lead row; the Co-host override UI and its resolution path go away; `MeetingTypeHost` stops mapping the two override columns. The columns stay in the schema, because the previous release still reads them.

**Tech Stack:** Quarkus 3.39.5 / Java 25, Panache, Qute, RestAssured, AssertJ.

**Spec:** bean `calit-5fw0` (`.beans/calit-5fw0--the-creator-is-always-the-organizer-remove-the-co.md`), ADR `docs/adr/0007-the-creator-is-always-the-organizer.md`, ADR `docs/adr/0005-the-location-belongs-to-the-meeting-type.md`. The bean's line numbers are from an older `main`; locate code by the method names given.

## Global Constraints

- **No migration in this plan.** `meeting_type_host.google_credential_id` / `google_calendar_id` stay in the schema; the previous release still reads and writes them. Only the Java mapping goes.
- Keep the **Creator** branch of `SharedMeetingsResource.applyWriteCalendar` and the Creator's picker on `sharedAvailability.html`. An accepted Creator host row reaches that page and its override is live.
- No form or i18n change for the location fallback: `locationDetail` is already collected and saved.
- AssertJ only. Full `mvn test` green.

## Review Focus

1. **The Creator is disconnected but a Co-host is connected:** no Mirror event is created, the booking still succeeds, and every host gets calit's mail with an `.ics`. Test: `MeetingHostsTest.creatorDisconnectedMeansNoOrganizer` plus a booking-level test in Task 1.
2. **The Creator opens `sharedAvailability` for their own type:** the write-calendar picker is still shown and saving it still sets the type's override. Test: Task 1 `creatorStillSeesWriteCalendarPicker`.
3. **A `GOOGLE_MEET` type booked with the Creator disconnected:** the location shown in mail and on the confirmation page is the type's `locationDetail`, not blank. Test: Task 2.

---

### Task 1: Organizer is the Creator; remove the Co-host override

**Files:**
- Modify:
  - `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java` (`chooseOrganizer`)
  - `src/main/java/site/asm0dey/calit/booking/BookingService.java` (`createGroupGoogleEvent`, and the `writeTargets.resolve(...)` call sites)
  - `src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java` (`writeOverride`: drop the `MeetingTypeHost` branch)
  - `src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java` (`applyWriteCalendar`: drop the Co-host branch)
  - `src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html` (hide the picker for Co-hosts)
  - `src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java` (remove the `googleCredentialId` / `googleCalendarId` fields and every reader or writer)
- Test: update `MeetingHostsTest` and `BookingWriteTargetOverrideTest`; add the cases below to them, or to the existing `SharedMeetingsResource` test class.

**Interfaces:**
- Produces:
  - `public Long chooseOrganizer(MeetingType type, List<Long> hostOwnerIds)` returns `type.ownerId` if `calendarPort.isConnected(type.ownerId)`, else `null`. Keep the signature so callers are untouched; drop the `hostOwnerIds` parameter only if no caller needs it, then update callers.
  - `WriteTargetResolver.writeOverride(Long ownerId, MeetingType type)` returns the type's override only when `ownerId.equals(type.ownerId)`, else `null`.
  - In `createGroupGoogleEvent`, the event row is the group's lead row; `writeTargets.resolve(type.ownerId, type)`.

- [ ] **Step 1: Update and add failing tests:**
  - `MeetingHostsTest`: replace the "Co-host chosen when the Creator is disconnected" cases with `creatorDisconnectedMeansNoOrganizer` (Creator disconnected, Co-host connected, result `null`) and `creatorConnectedIsOrganizer`.
  - `BookingWriteTargetOverrideTest`: the case pinning the old fallback now asserts that no `createEvent` happens (`verify(calendarPort, never()).createEvent(...)`) when the Creator is disconnected and a Co-host is connected, and the booking is `CONFIRMED`.
  - Shared availability page, with RestAssured as the Co-host: the body has no write-calendar `<select>`. Posting a write-calendar value as Co-host changes nothing on any row.
  - `creatorStillSeesWriteCalendarPicker`: as the Creator, the picker is present and saving it sets `MeetingType.googleCalendarId`.
- [ ] **Step 2: Run them.** `JAVA_HOME=/home/finkel/.sdkman/candidates/java/27.0.0+36-librca ./mvnw -o test -Dtest='MeetingHostsTest,BookingWriteTargetOverrideTest,<shared page test class>'`. Expected: the new assertions FAIL.
- [ ] **Step 3: Implement** per Interfaces. Delete the dead code; don't leave it commented out.
- [ ] **Step 4: Run the full suite.** `./mvnw -o test` (same `JAVA_HOME`). Expected: `BUILD SUCCESS`. Any other test relying on Co-host organizer or Co-host override behaviour is now wrong: update it and name it in the report.
- [ ] **Step 5: Commit** with explicit paths: `git commit -m "refactor(booking): the Creator is always the organizer (ADR 0007)"`.

### Task 2: Location falls back to the meeting type's locationDetail

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` (`resolveLocation`), `src/main/java/site/asm0dey/calit/web/PublicResource.java` (the confirmation-page location computation, near the `resolveLocation`-equivalent code)
- Test: an existing email or confirmation test class, or a new `LocationFallbackTest`.

**Interfaces:**
- Produces: for any booking, the location is the minted Meet link when the booking has one, else `meetingType.locationDetail` (which may be null). That's the same rule in both places.

- [ ] **Step 1: Failing tests:**
  - `meetTypeWithoutMintedLinkShowsLocationDetail`: a `GOOGLE_MEET` type with `locationDetail = "https://meet.example/abc"`, Creator disconnected, so no `meetLink`. The confirmation mail body and the confirmation page both contain `https://meet.example/abc`.
  - `mintedLinkWins`: the booking has a `meetLink`; it's shown, and `locationDetail` isn't.
- [ ] **Step 2: Run** (FAIL). **Step 3: Implement.** **Step 4: Run the full suite** (green).
- [ ] **Step 5: Commit:** `git commit -m "fix(location): fall back to the meeting type's location detail (ADR 0005)"`.
