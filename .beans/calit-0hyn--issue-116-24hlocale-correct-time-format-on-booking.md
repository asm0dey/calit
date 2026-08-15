---
# calit-0hyn
title: 'Issue #116: 24h/locale-correct time format on booking page'
status: completed
type: feature
priority: normal
created_at: 2026-08-15T20:31:56Z
updated_at: 2026-08-15T21:20:07Z
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
