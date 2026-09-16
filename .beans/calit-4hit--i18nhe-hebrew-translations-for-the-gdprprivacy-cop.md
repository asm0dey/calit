---
# calit-4hit
title: 'i18n(he): Hebrew translations for the GDPR/privacy copy'
status: completed
type: task
priority: normal
created_at: 2026-09-16T14:51:21Z
updated_at: 2026-09-16T19:01:52Z
parent: calit-l3fk
---

GDPR epic ships English + German only for its new copy (legal-adjacent, no Hebrew reviewer). Keys are listed in MultiHostMessageParityTest HE_DEFERRED_APP_KEYS / HE_DEFERRED_ADMIN_KEYS; add msg_he/adm_he values and delete the exemption sets. File the GitHub issue (label translation) when the epic's PRs open.


## Summary of Changes

- Added Hebrew values for the 27 AppMessages keys (msg_he.properties) and 19 AdminMessages keys (adm_he.properties) this epic introduced, placed where their German counterparts sit; he key order now mirrors the de files.
- Removed HE_DEFERRED_APP_KEYS / HE_DEFERRED_ADMIN_KEYS and the exemption plumbing from MultiHostMessageParityTest (restored to its pre-epic shape: every key needs de AND he).
- Added InviteeErasureRouteTest#confirmPageRendersInHebrew (GET /booking/{token}/erase with Accept-Language: he).
- Legal texts (/privacy and /terms bodies) intentionally stay English-only.
- The Hebrew gap is closed, so no GitHub translation issue is needed.
