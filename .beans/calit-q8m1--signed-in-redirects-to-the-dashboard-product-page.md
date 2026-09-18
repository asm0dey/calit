---
# calit-q8m1
title: Signed-in / redirects to the dashboard; product page moves to /calit
status: in-progress
type: feature
priority: normal
created_at: 2026-09-18T15:02:39Z
updated_at: 2026-09-18T16:31:50Z
---

GitHub #193. Signed-in GET / 303s to /me (per-user opt-out, default on); the marketing page gets a permanent home at /calit, linked from the admin shell brand.

## Plan

`docs/superpowers/plans/2026-09-18-home-redirect.md` (spec: `docs/superpowers/specs/2026-09-18-home-redirect-design.md`)

- [x] Task 1: V36 migration + `OwnerSettings.homeRedirectEnabled`
- [x] Task 2: `/calit` route, cache headers, canonical, first-run exemption, admin brand anchor
- [x] Task 3: signed-in `/` 303s to `/me`
- [ ] Task 4: opt-out checkbox on /me/settings + de/he
- [ ] Task 5: CONTEXT.md, ADR 0011, precedent, docs-site changelog
