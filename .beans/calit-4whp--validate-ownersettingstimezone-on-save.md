---
# calit-4whp
title: Validate OwnerSettings.timezone on save
status: in-progress
type: bug
priority: normal
created_at: 2026-08-16T06:38:59Z
updated_at: 2026-08-22T12:24:31Z
---

`AdminResource.updateSettings` (~line 1131) stores `row.timezone = timezone` straight from the form with no validation, unlike the `locale` field one line above which guards with `AppLocales.isSupported`.

There are 11 unguarded `ZoneId.of(settings.timezone)` call sites; a bad stored value throws `DateTimeException` -> 500 at each:

- `SlotService.java:59` — slot computation
- `PublicResource.java:381,424,553` — the owner's PUBLIC booking page
- `BookingService.java:169,609,658` — the booking transaction
- `EmailService.java:818,925`
- `AdminResource.java:229,1325`

Only `DisplayExtensions.java:85` is guarded (added by the issue #116 branch, falls back to UTC).

Blast radius is worse than "my admin page breaks": the owner's public booking page and the booking transaction both 500, so invitees cannot book at all.

REACHABILITY IS LOW. `tzField.html` renders a `<select>` populated from `ZoneId.getAvailableZoneIds()`, so a browser cannot submit anything else. Requires a hand-crafted POST, and damages only the attacker's own account (owner-scoped), repairable by saving a valid zone. Pre-existing, not introduced by #116.

## Todo
- [ ] Guard on save: `zoneIds().contains(timezone) ? timezone : "UTC"` in `updateSettings`, mirroring the locale guard
- [ ] Test: POST a garbage timezone, assert it is coerced and the public booking page still renders
- [x] Consider whether existing rows need a backfill/repair pass — **NO**. Only reachable by a hand-crafted POST against your own owner-scoped account, and the save-time guard repairs the row on the next save. Not worth a migration.
