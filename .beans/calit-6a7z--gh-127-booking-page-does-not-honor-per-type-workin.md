---
# calit-6a7z
title: 'GH #127: booking page does not honor per-type working hours'
status: completed
type: bug
priority: normal
created_at: 2026-08-17T07:18:30Z
updated_at: 2026-08-17T08:03:10Z
---

Upstream report: https://github.com/asm0dey/calit/issues/127 (reporter h200101, on `latest`).

Symptom (screenshots): meeting type has per-type working hours Mon 06:00-22:00, Tue 06:00-22:00, Wed-Sun blank. Public booking page shows every day of the month as bookable, and Monday 17 Aug offers 16:00-21:00 (Europe/Ljubljana).

## Investigation
- [ ] Reproduce the reported config locally (per-type Mon/Tue only + global defaults)
- [ ] Determine whether Mon slot window is correct (tail 21:00 = 22:00 end minus 60-min duration -> looks honored)
- [ ] Determine why Wed-Sun are bookable (global fallback per day-of-week is by design per SlotService.windowsFor)
- [ ] Ask reporter for version + global availability grid
- [ ] Root cause named before any fix

## Phase 1 findings (code reading, 2026-08-17)

Facts established from code + screenshots:

1. **Per-type working hours are a per-WEEKDAY override, not a schedule.** `SlotService.Availability.windowsFor` (SlotService.java:105-121): for a date, per-type rules for that day-of-week win; if the type has NO rule for that weekday, it falls back to the owner's GLOBAL rules for that weekday. So Wed-Sun blank in the type editor => the owner's global grid drives those days. That is the documented UI text and is original behaviour (`git log -S` -> 25c8073, the first availability commit) — NOT a regression.
2. Reporter's calendar shows Sat/Sun bookable => their GLOBAL grid must cover weekends (the seeder only writes Mon-Fri 09:00-18:00).
3. Monday's last offered slot 21:00 is consistent with the per-type window END 22:00 minus a 60-min duration => the tail of Monday's window IS honoured. The missing 06:00-15:45 is consistent with now+minNotice, Google free/busy, or the global rule being used instead.
4. Viewer-local reformatting of slot labels predates 1.20.0 (TZ_SCRIPT already rendered in the detected zone before #122), so #122 is not the cause of a "times moved" symptom. #122 only changed hourCycle and /me pages.
5. `AvailabilityRule.owner_id` is NOT NULL since V8 with no backfill, so mis-owned rules (which would be shown by the editor — `AdminResource:644` queries by meetingTypeId with no owner filter — while being invisible to SlotService's owner-filtered query) are implausible on any DB that migrated successfully.

**Not yet reproducible.** Cannot distinguish "documented global fallback surprises the user" from "per-type rule genuinely ignored on Monday" without the reporter's data.

### Evidence needed from reporter
- version (footer)
- `SELECT id, owner_id, meeting_type_id, day_of_week, start_time, end_time FROM availability_rule ORDER BY owner_id, meeting_type_id NULLS FIRST, day_of_week;`
- `SELECT owner_id, timezone FROM owner_settings;`
- `SELECT id, owner_id, slug, duration_minutes, slot_interval_minutes, min_notice_minutes, horizon_days, buffer_before_minutes, buffer_after_minutes FROM meeting_type;`
- `SELECT * FROM date_override;` (Fri 28 is disabled in their calendar — override or fully busy?)
- Is Google Calendar connected? (free/busy subtraction would remove the morning)
- Local time when the screenshot was taken


## Decided design (2026-08-17, user)

**Semantics** — a meeting type's weekly grid is all-or-nothing:
- type has NO rules of its own -> inherits the owner's global week (unchanged, keeps every existing install and every freshly-created type bookable)
- type has ANY rule -> that grid IS its whole week; a weekday with no frames is CLOSED, global no longer leaks in per weekday

**Editor UX** — when a type has no rules of its own, its grid renders PREFILLED with the owner's global hours, so the first save materialises "same as global" instead of silently closing six days. Each day row also gets a "Remove availability" button that clears that day's frames (= day closed once saved).

### Tasks
- [x] SlotService.windowsFor: per-type rules replace global wholesale (test-first)
- [x] AdminResource.detailInstance: prefill the grid from global rules when the type has none
- [x] SharedMeetingsResource.availabilityInstance: same prefill for a co-host's own grid
- [x] workplan.js: data-clear-day handler; button in all three grids
- [x] Reword the working-hours hints + de/he translations for every new/changed string
- [x] Full suite (823/823) + spotless
- [x] docs-site: usage docs + 1.20.2 changelog (ae25408, 45708f1, pushed)

## Retrofit decision (2026-08-17)

A V26 migration that copied the owner's global hours onto the weekdays a partly-configured type left blank was written, verified against scratch data, then DROPPED on the user's call: an old type with frames on Mon+Tue and none on Thursday should simply become UNAVAILABLE on Thursday. So no data migration ships — old types just take the new semantics. Types with no rules at all keep inheriting the global week live (editing the global grid still propagates to them).

## Summary of Changes

Root cause: `SlotService.Availability.windowsFor` treated a meeting type's weekly rules as a per-WEEKDAY override — a weekday the type left blank fell back to the owner's global rules for that weekday. A type configured Mon+Tue only therefore stayed bookable every day the global grid covered, and no per-type way to close a weekday existed. Original behaviour (first availability commit 25c8073), not a regression; #122 and mis-owned rows were ruled out on the way.

Fix (PR #134, squashed as dd79264): weekly rules are all-or-nothing per type. A type with ANY rule owns its whole week and a blank weekday is closed; a type with NO rules still inherits the global week live. No migration — old partly-configured types simply close the days they never filled in, which is what the reporter wanted. Editor: a type with no hours of its own renders its grid prefilled from the global week (same for a co-host's grid on a shared type), and every day row gained a "Remove availability" button (`workplan.js` `data-clear-day`). Hints reworded, de + he translated. Tests: 823/823, including a red-first `typeWithAnyRuleIsClosedOnTheDaysItLeavesBlank` and `AdminTypeHoursPrefillTest`.

Docs: availability page rewritten with global-vs-per-type semantics and an upgrade caution; 1.20.2 changelog entry. Released as 1.20.2.
