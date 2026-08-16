---
# calit-0hyn
title: 'Issue #116: 24h/locale-correct time format on booking page'
status: completed
type: feature
priority: normal
created_at: 2026-08-15T20:31:56Z
updated_at: 2026-08-16T06:39:26Z
---

Brainstorm + design for GitHub issue #116 — invitee sees 12h AM/PM times because TZ_SCRIPT formats with document.documentElement.lang (UI translation locale, region-less) instead of the viewer's browser locale.

## Todo
- [x] Explore project context
- [x] Clarifying questions
- [x] Propose approaches
- [x] Present design, get approval
- [x] Write design doc to docs/superpowers/specs/
- [x] Spec self-review
- [x] User reviews spec (approved 2026-08-15)
- [x] Hand off to writing-plans — plan at docs/superpowers/plans/2026-08-15-time-format.md (7 tasks)

## Findings

- Server-side already renders 24h (`HH:mm`) — PublicResource.java:149, AdminResource.java:224. No-JS fallback is fine.
- Cause: Layout.java:56,65 — TZ_SCRIPT formats with `document.documentElement.lang` (UI translation locale: en/de/he, region-less). 'en' = US = 12h.
- Live page serves `<html lang="en">` — confirmed via curl.
- Measured in reporter's + owner's Firefox: `new Intl.DateTimeFormat(undefined,{timeStyle:'short'})` → 24h. So engine default locale is correct; passing `lang` is what breaks it.
- DECIDED: no 12/24 toggle, no owner setting, no DB. Drop the locale arg for time formatting.
- Adjacent bug: dashboard.html:35 + pending.html:23 include TZ_SCRIPT but render no `#tz-picker`; Layout.java:48 `if (!picker) return` bails → owner sees raw `...Z UTC` ISO.

## Implementation (SDD, branch feat/time-format)

- [x] Task 1 — hourCycle from viewer's device (cfab5ea) — closes #116
- [x] Task 2 — OwnerInfo bean + data-tz + optional picker (b044df6, 8fac21f)
- [x] Task 3 — V25 + OwnerSettings.timeFormat + validation (1977946)
- [x] Task 4 — settings select + de/he translations (536dd81)
- [x] Task 5 — /me honours the preference (data-hc) (7436446, cf11f00)
- [x] Task 6 — host emails honour the preference (9fcd489, 9eef727)
- [x] Task 7 — full verification + docs-site (0231a51, docs-site 38c29f4)

## Summary of Changes

Branch `feat/time-format`, 18 commits off main de1fa46. Full suite 802/802, spotless clean.

**Phase 1 — closes #116.** `Layout.TZ_SCRIPT` formatted times with `document.documentElement.lang`, the region-less UI translation locale, so bare 'en' carried US 12-hour defaults for every viewer of an English page. Now words still follow the page language and only `hourCycle` comes from the viewer's device, so Hebrew and German pages do not regress to English dates. The picker bail-out (`if (!picker) return`) is gone, so /me and /me/pending format at all; they render in the owner's STORED zone via a new `@Named("owner") @RequestScoped` `OwnerInfo` bean and `body[data-tz]`. Side effect found by the whole-branch review: cancelConfirm and guestDeclineConfirm are PUBLIC pages with the same missing picker, so invitees were seeing raw ISO there too — also fixed. No-JS fallback on /me now renders a human time via a new `display:when(Instant, String)` extension reusing the existing manageBooking pattern (zero new translated strings).

**Phase 2 — host preference.** V25 adds `owner_settings.time_format` (auto|h12|h23, default auto). Settings select with de+he. Applied to /me via `body[data-hc]` and to the host's own email copies by widening `RecipientBodyRenderer` with a `hourCycle` parameter (compiler-enforced across 8 lambdas + 9 format calls) and a new `email_datetime_pattern_h12` key per locale. Server-side auto deliberately does NOT probe the locale — every locale's pattern is already 24h by translator choice while a probe classifies en as 12h, so probing would have silently flipped every English host's mail to AM/PM on upgrade. Invitee and guest copies always pass auto.

**Six review rounds each caught a test that passed under a mutation reintroducing the bug** — including the host preference being ignored entirely, the lead host's clock leaking into every co-host's email, and `forOwner(1L)` leaking owner 1's timezone to every other owner. All now fail under their mutation, verified individually.

**Two defects in the plan itself** surfaced during execution and were corrected in the plan file: `Set.of(...).contains(null)` throws rather than returning false (would have 500'd every settings POST omitting the field), and Surefire 3.5.6 needs comma-separated `-Dtest` selectors.

Docs: docs-site commit 38c29f4 documents both halves in usage/languages.md.

Follow-ups filed: [[calit-4whp]] (unvalidated OwnerSettings.timezone, 11 unguarded ZoneId.of sites), [[calit-mhgs]] (/me pages disagree on displayed zone).
