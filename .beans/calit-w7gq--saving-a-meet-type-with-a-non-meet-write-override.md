---
# calit-w7gq
title: Saving a Meet type with a non-Meet write override returns a bare 400
status: completed
type: bug
priority: normal
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-22T13:17:11Z
---

`AdminResource.parseLocationType` throws `BadRequestException` when a meeting type is saved as Google Meet while its resolved write calendar cannot mint Meet links. `editMeetingType` catches only `IllegalStateException`, and no `ExceptionMapper` covers it — so the Host gets an empty, unlocalized 400 and loses the form they were filling in.

Pre-existing in kind, but the write-override feature adds a plausible new trigger: the location select and the calendar select now sit in the same form, so one save can set both into conflict.

Found in the final whole-branch review of [[calit-bh5t]].

- [x] Route it through the localized `IllegalStateException` path `requireOwnedCalendar` already uses
- [x] Add the `@Message` key with de and he values
- [x] Assert the Host lands on a usable, localized page with nothing persisted. NARROWED: the form re-renders from the stored row, not the submission, matching every other error path in this resource (requireOwnedCalendar, assertNoOwnerSlugCollision, resolveEligibleCohost). Echoing submitted fields back would make this one path unlike its neighbours.

## Summary of Changes

Routed `parseLocationType`'s Meet-vs-write-calendar conflict through a new `IllegalStateException` + `adm_detail_error_location_meet_unsupported` message key (en/de/he) instead of the uncaught `BadRequestException`, so both `createMeetingType` and `editMeetingType` render a localized 200 page instead of a blank 400. The German string uses "Termintyp" (not "Terminart" as originally drafted) to match the codebase's established term (22 existing occurrences) and correct grammatical gender. Updated `AdminMeetGatingTest` and `AdminMeetGatingOverrideTest` to assert 200 + message instead of 400, confirmed nothing persists on rejection, and added a German localization test.
