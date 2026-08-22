---
# calit-8nlx
title: Date-override create path 500s on an unparseable overrideDate
status: todo
type: bug
priority: normal
created_at: 2026-08-22T10:21:53Z
updated_at: 2026-08-22T10:21:53Z
---

`AdminResource.createInitialDateOverride` calls `LocalDate.parse(date)` with no guard, so a crafted `overrideDate=x` on `POST /me/meeting-types` throws `DateTimeParseException` out of the transaction and returns 500 instead of dropping the value.

Pre-existing — not introduced by calit-9d76 — but that change made the asymmetry conspicuous: the working-hours half of the same form now routes through `persistFrames`, which catches `DateTimeParseException | IllegalArgumentException`, skips blanks and drops inverted frames, while the date-override half directly below it still 500s on garbage.

## Notes

- Same guard shape as `persistFrames` (`AdminResource.java:1143`): try/catch around the parse, skip the value rather than fail the request.
- Check `persistWindows` and the window start/end parsing on the same path for the same hole.
- The bulk-save date-override endpoints may already guard — verify before assuming this is only the create path.

## Todo

- [ ] Guard the overrideDate parse (and the window times, if unguarded) on the create path
- [ ] Test: a garbage overrideDate on create returns 200 and persists no override, not 500
- [ ] `mvn test` green
