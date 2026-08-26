---
# calit-jdrr
title: FirstRunRedirectFilter allow-list matches the raw path, not the normalized one
status: todo
type: bug
priority: normal
created_at: 2026-08-26T16:37:20Z
updated_at: 2026-08-26T16:37:20Z
---

Found reviewing calit-o89d's social-preview fix (commit 2cb5af1). **Pre-existing**, not introduced by that branch — it only extended the existing pattern.

## The defect

`FirstRunRedirectFilter.isAllowedWhileUnbootstrapped(path)` is called with `rc.request().path()` — the **raw, undecoded** path. But everything downstream dispatches on `context.normalizedPath()` (Vert.x `RouteState.matches`, `useNormalizedPath=true` by default, confirmed in vertx-web 4.5.32), which percent-decodes unreserved characters and collapses `.`/`..` segments via `HttpUtils.normalizePath`/`removeDots`.

So the filter's allow-list and the router can disagree about what path a request is. In principle a request such as:

```
/og/%2e%2e/me
```

satisfies `startsWith("/og/")` on the raw string, skipping the first-run redirect, while actually dispatching to `/me` downstream.

This is the same raw-vs-normalized matching class Quarkus has had to patch in its own HTTP RBAC path matching.

## Why it was not fixed in calit-o89d

- It is pre-existing and affects the whole allow-list — `/`, `/img/`, `/privacy`, `/terms`, `/calit.css`, `/favicon.ico`, `/q/`, `/j_security_check` — not just the `/og` entries that branch added.
- The fix changes matching semantics for **every** exempt path, on a filter that runs at `@RouteFilter(10000)`, i.e. **before security**. Trailing-slash and encoding differences could silently un-exempt a path that must stay reachable pre-bootstrap (the legal pages exist on that list specifically so Google's verification crawler can reach a fresh instance). That deserves its own test pass, not a branch-tail one-liner.

## Impact is bounded, which is why it is normal priority and not critical

1. The window exists **only pre-bootstrap** — once any `AppUser` row exists the filter is a no-op for every path.
2. The filter runs *before* security, not *instead of* it. Whatever route a traversal actually lands on still enforces its own RBAC.
3. The `/og/*` endpoints are safe however they are reached: `owner()`/`meetingType()` check `owner == null` before any further lookup, and `meeting_type.owner_id REFERENCES app_user(id) ON DELETE CASCADE` (V8__owner_scoping.sql:10) makes an orphaned MeetingType structurally impossible — so with zero users every card degrades to the generic product card.

## Fix

- [ ] Switch `firstRunCheck` to pass `rc.normalizedPath()` (or normalize before comparing) so the allow-list and the router agree on the path
- [ ] Re-verify every existing exempt path still resolves: `/`, `/img/**`, `/setup`, `/privacy`, `/terms`, `/j_security_check`, `/q/**`, `/calit.css`, `/favicon.ico`, `/og.png`, `/og/**`
- [ ] Add tests for encoded/dot-segment shapes (e.g. `/og/%2e%2e/me` must still redirect to /setup pre-bootstrap)
- [ ] Check whether any other filter in the codebase compares against `request().path()` and has the same mismatch

## Related

- `calit-o89d` — the branch where this surfaced
- `FirstRunOgImageTest` — existing regression coverage for the exemption itself
