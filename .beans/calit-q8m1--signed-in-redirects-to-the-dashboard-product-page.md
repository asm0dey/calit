---
# calit-q8m1
title: Signed-in / redirects to the dashboard; product page moves to /calit
status: completed
type: feature
priority: normal
created_at: 2026-09-18T15:02:39Z
updated_at: 2026-09-18T19:25:17Z
---

GitHub #193. Signed-in GET / 303s to /me (per-user opt-out, default on); the marketing page gets a permanent home at /calit, linked from the admin shell brand.

## Plan

`docs/superpowers/plans/2026-09-18-home-redirect.md` (spec: `docs/superpowers/specs/2026-09-18-home-redirect-design.md`)

- [x] Task 1: V36 migration + `OwnerSettings.homeRedirectEnabled`
- [x] Task 2: `/calit` route, cache headers, canonical, first-run exemption, admin brand anchor
- [x] Task 3: signed-in `/` 303s to `/me`
- [x] Task 4: opt-out checkbox on /me/settings + de/he
- [x] Task 5: CONTEXT.md, ADR 0011, precedent (docs-site changelog split out below)
[x] Follow-up: `## Unreleased` changelog bullet on `docs-site` (2b4a7cb), citing #230

## Summary of Changes

Merged as ca6770e4 (PR #230), closing GitHub #193.

- `V36__home_redirect.sql` adds `owner_settings.home_redirect_enabled BOOLEAN NOT NULL DEFAULT TRUE`. Existing rows get TRUE deliberately; the migration comment records the departure from the rule V33 states.
- `GET /` 303s to `/me` for a signed-in owner with the preference on (`Cache-Control: no-store`); anonymous, opted-out, and no-settings-row all render the product page (`Cache-Control: private`). `/{username}` is untouched.
- `GET /calit` is the product page's permanent home and never redirects. `calit` was already in `Usernames.RESERVED`, which is why it is not `/about`. Exempt from `FirstRunRedirectFilter` like `/`.
- The admin shell brand and the product page's own brand anchors link to `/calit`, so the escape hatch does not bounce a signed-in visitor home again.
- Opt-out checkbox on `/me/settings`, translated to de and he.
- `home_redirect_enabled` classified not personal in `PersonalData.java` (ADR 0010's schema guard forced the classification).
- The preference resolves in one query; `HomeRedirectQueryCountTest` pins 0 statements for an anonymous `GET /` and 1 for a signed-in one.
- `CONTEXT.md` glossary (home / landing / product page) + `docs/adr/0011-home-is-the-instance-entrance.md`; mirrored into the precedent graph.
- Changelog under `## Unreleased` on `docs-site` (2b4a7cb).
