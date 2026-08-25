---
# calit-5fw0
title: 'The Creator is always the Organizer: remove the Co-host write override'
status: todo
type: task
created_at: 2026-08-23T16:52:30Z
updated_at: 2026-08-23T16:52:30Z
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
- Migration `V*.sql` dropping `meeting_type_host.google_credential_id` and
  `meeting_type_host.google_calendar_id`.
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

- [ ] chooseOrganizer: Creator-if-connected, else null
- [ ] createGroupGoogleEvent: organizer row is always the lead row
- [ ] Remove the Co-host branch of applyWriteCalendar (keep the Creator branch)
- [ ] Remove the Co-host write-calendar picker from sharedAvailability.html
- [ ] Migration dropping the two meeting_type_host columns
- [ ] Location fallback in EmailService.resolveLocation + PublicResource
- [ ] Update MeetingHostsTest and BookingWriteTargetOverrideTest
- [ ] Full `mvn test` green
- [ ] Changelog bullet under `## Unreleased` on docs-site
