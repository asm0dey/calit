---
# calit-i3wt
title: 'GH #168: fold past date overrides into a collapsed section'
status: completed
type: feature
priority: normal
created_at: 2026-09-05T13:16:10Z
updated_at: 2026-09-05T13:59:06Z
---

The /me/date-overrides page lists every override the owner has ever created, oldest mixed with newest, so a host with many of them cannot find the upcoming ones. Show upcoming (date >= today in the owner's timezone) first, and move past ones into a native <details> collapse labelled with their count. Nothing is deleted.

Plan: docs/superpowers/plans/2026-09-05-past-date-overrides.md

- [x] Add adm_dateOverrides_past_summary with de + he translations
- [x] Split overrides into upcoming/past in AdminResource
- [x] Extract the override card into a Qute partial and render the past collapse
- [x] Tests for the split (placement, not substring)
- [x] Browser + no-JS check
- [x] docs-site: changelog Unreleased bullet + availability.md paragraph (committed 7001fb7, unpushed pending PR number)

## Summary of Changes

- `AdminResource.dateOverridesInstance()` replaces `overridesWithWindows()`. One query, `ownerId = ?1 order by overrideDate, meetingTypeId nulls first`, split in memory on `LocalDate.now(ownerZoneId())`: upcoming (`!isBefore(today)`, so today counts as upcoming) ascending, past descending. GET, create-POST and delete-POST all render through it, so the owner predicate exists in one place instead of three.
- `ownerZoneId()` falls back to `ZoneOffset.UTC` on a null, blank or unparseable stored timezone, with a javadoc note on why `OwnerSettings.coerceZone`'s allowlist was deliberately not reused (it would flatten offset ids like `+03:00` to UTC).
- `templates/AdminResource/_dateOverrideCard.html` extracted so the card markup is written once; `dateOverrides.html` includes it from both loops and wraps the past loop in a native `<details id="past-overrides">` daisyUI collapse, rendered only when past overrides exist. No JavaScript.
- New `adm_dateOverrides_past_summary(int count)` key with German and Hebrew values.
- Four tests in `AdminDateOverridesTest` assert placement relative to the collapse marker plus the rendered `Past overrides (N)` label — `<details>` keeps its content in the DOM, so substring checks would pass on a broken feature. A mutation check confirmed they fail when the split is broken.
- Verified in a browser against a running dev server: ordering, the count, expansion, delete-from-inside-the-collapse, and the collapse disappearing when no history remains.
- docs-site 7001fb7: `## Unreleased` changelog bullet + a paragraph in `usage/availability.md`. NOT pushed — the changelog link still carries a `PRNUM` placeholder to be replaced with the real pull-request number.

Commits: 3a65498, b3646bb, f4733bb, a55aee7, 1e6a4e5. Full suite green at 1047 tests, 0 failures, 0 errors.
