---
# calit-5fw0
title: 'The Creator is always the Organizer: remove the Co-host write override'
status: completed
type: task
priority: normal
created_at: 2026-08-23T16:52:30Z
updated_at: 2026-10-17T17:18:17Z
---

Implements `docs/adr/0007-the-creator-is-always-the-organizer.md` and the
location fallback from `docs/adr/0005-the-location-belongs-to-the-meeting-type.md`.
Supersedes the scrapped [[calit-mjof]].

Every Google event is written on the Creator's connected account or not at all.
The Co-host organizer fallback goes, and with it the Co-host write override —
`writeTargets.resolve` has only two call sites (`BookingService:432`, `:521`), so
once the organizer is always the Creator, `writeOverride(coHostId, type)` is read
by nothing.

## Scope

- `MeetingHosts.chooseOrganizer:111-122` — the Creator if connected, else null.
  The lowest-id-connected-Co-host loop is deleted.
- `BookingService.createGroupGoogleEvent:415-449` — `organizerRow` is always the
  group's lead row, so the `Booking.group(...).filter(...).orElseThrow()` lookup
  and the "propagate the meet link to the lead row" block both go.
  `writeTargets.resolve(organizer, type)` becomes `resolve(type.ownerId, type)`.
- `SharedMeetingsResource` — remove the Co-host branch of `applyWriteCalendar`.
  KEEP THE CREATOR BRANCH: an accepted Creator host row reaches that page too and
  its override is still live.
- `sharedAvailability.html:40-49` — remove the write-calendar picker for a
  Co-host. It stays for the Creator.
- `MeetingTypeHost` stops mapping/reading `google_credential_id` and `google_calendar_id`. **No
  drop in this release** — the previous release still reads them (backward-compatible
  migrations rule); dropping them is a follow-up bean for the next release.
- Location fallback (ADR-0005): `EmailService.resolveLocation:883` and
  `PublicResource:391` return the minted link, else the meeting type's
  `locationDetail`. The field is already collected on both meeting-type forms
  (`meetingTypeDetail.html:63`, `meetingTypes.html:94`) and already saved
  (`AdminResource:483`), so no form or i18n change.

## Tests to update

- `MeetingHostsTest:74` (Co-host chosen when the Creator is disconnected) and
  `:77`.
- `BookingWriteTargetOverrideTest:165` — its comment already pins the old
  fallback behaviour.

## User-visible change

When the Creator's account is disconnected, a shared type's bookings get no
Google event at all, so Co-hosts stop receiving the Google invitation. calit's
own mail and `.ics` still reach every Host (`EmailService:948-950`) and every
scheduling feature still works — degraded mode as the glossary defines it. Needs
a `## Unreleased` changelog bullet on the `docs-site` branch.

## Todo

- [x] chooseOrganizer: Creator-if-connected, else null
- [x] createGroupGoogleEvent: organizer row is always the lead row
- [x] Remove the Co-host branch of applyWriteCalendar (keep the Creator branch)
- [x] Remove the Co-host write-calendar picker from sharedAvailability.html
- [x] Stop mapping the two meeting_type_host columns (drop deferred to a next-release bean)
- [x] Location fallback in EmailService.resolveLocation + PublicResource
- [x] Update MeetingHostsTest and BookingWriteTargetOverrideTest
- [x] Full `mvn test` green
- [x] Changelog bullet under `## Unreleased` on docs-site

## Progress

Code done on feat/caldav (e20a87d3, ca2cd6e3, 5d68de43, d1d068f7); mvn verify green (1308). Legacy pre-ADR-0007 co-host event rows guarded by GroupEditDetailsTest. Open: changelog + docs-site google-oauth.md co-host text — written in CalDAV plan Task 10.

## Summary of Changes

The Creator is the only organizer: chooseOrganizer returns the Creator or null; group Mirror events live on the lead row; the Co-host write override, its picker and the MeetingTypeHost google_* mapping are gone (columns kept for the previous release; drop tracked in calit-4air). Pre-ADR-0007 Co-host event rows stay editable/cancellable (GroupEditDetailsTest). Meet-type bookings without a minted link fall back to the type's locationDetail (EmailService.resolveLocation, PublicResource). Docs: google-oauth.md co-host text, changelog Unreleased. Follow-ups: GOOGLE_MEET styling, declineGuest co-host drop, dashboard location beans.
