---
# calit-4whp
title: Validate OwnerSettings.timezone on save
status: completed
type: bug
priority: normal
created_at: 2026-08-16T06:38:59Z
updated_at: 2026-08-22T14:16:00Z
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
- [x] Guard on save: `zoneIds().contains(timezone) ? timezone : "UTC"` in `updateSettings`, mirroring the locale guard
- [x] Test: POST a garbage timezone, assert it is coerced and the public booking page still renders (coercion asserted in the DB plus GET /me; the public page is not separately exercised — after coercion the stored value is a valid zone, so that path has nothing left to fail on)
- [x] Consider whether existing rows need a backfill/repair pass — **NO**. Only reachable by a hand-crafted POST against your own owner-scoped account, and the save-time guard repairs the row on the next save. Not worth a migration.

## Summary of Changes

Added timezone validation to guard against hand-crafted POSTs storing invalid IANA zone IDs. The fix coerces invalid timezones to UTC on save, preventing DateTimeException-500s at eleven unguarded call sites including the public booking page. No backfill migration needed—the guard repairs any existing bad rows on the next save.


## Second path found by the whole-branch review — now closed

The fix above landed at the bug's **reported** call site (`AdminResource.updateSettings`), not at
the invariant, and it left the guard as a `private static zoneIds()` helper local to that one
resource. The whole-branch review, run as the last gate before this branch became a PR, found the
second writer:

`MeSetupResource.submit` (`:103`) did a bare `s.timezone = timezone`. That is the FIRST-LOGIN
WIZARD, which **every** user passes through — same column, same eleven unguarded
`ZoneId.of(settings.timezone)` readers, same blast radius (the owner's public booking page and the
booking transaction both 500). `grep -rn '\.timezone = ' src/main/java/` returns exactly three
sites: the `"UTC"` literal in `OwnerSettings.seed`, the guarded settings page, and this one.

Demonstrated red: `MeSetupResourceTest.wizardCoercesAnUnknownTimezoneToUtc` failed with
`expected: <UTC> but was: <Not/AZone>` before the fix.

Closed by moving the invariant onto the entity that owns the column, mirroring the
`OwnerSettings.seed` extraction this branch already made:

- `OwnerSettings.zoneIds()` — every zone id the JDK knows, sorted; the source for both pickers.
- `OwnerSettings.coerceZone(String)` — anything not a known zone id (null and blank included)
  becomes `"UTC"`, with a javadoc stating that every writer of `timezone` must call it.

Both writers now call `coerceZone`, and the two duplicate `private static List<String> zoneIds()`
helpers (`AdminResource`, `MeSetupResource`) are deleted with all four picker call sites repointed
at `OwnerSettings.zoneIds()`. `coerceZone` carries its own unit tests (known zone, unknown string,
null, blank, and every id the picker can offer). The bean stays `completed` — the bug genuinely is
now, across both writers.

The no-backfill decision recorded above is unchanged and now covers the wizard path too: the value
is still only reachable by a hand-crafted POST against your own owner-scoped account, and either
writer repairs the row on the next save.
