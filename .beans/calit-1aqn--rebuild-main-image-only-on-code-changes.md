---
# calit-1aqn
title: Rebuild main image only on code changes
status: completed
type: task
priority: normal
created_at: 2026-08-16T07:31:16Z
updated_at: 2026-08-16T08:54:58Z
---

Every push to `main` currently runs the full image pipeline (build native + jvm images per arch, Trivy scan, push, merge manifest, retag `edge`) — even when the push touched only docs, bean files, or other non-code paths. That is minutes of CI and GHCR churn for a byte-identical image.

Goal: `edge`/main image rebuild happens only when something that can change the image changed.

## Notes
- Branch is `main` (no `master`); the affected tag is `type=edge,branch=main` in the `merge` job of `.github/workflows/ci.yml`.
- The `build` job already gates on `github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v')` — add a path condition on top of that, and never skip on `v*` tags (a release must always publish).
- Options: workflow-level `paths-ignore` (careful: also skips `test`, and required-check semantics on PRs), or `dorny/paths-filter` / `git diff` step feeding the `build` job `if:`. Prefer the narrower one.
- Non-code paths to ignore: `.beans/**`, `**/*.md`, `docs*/**`, `.agents/**`, `LICENSE`. Everything else (pom.xml, src/**, Dockerfile, package.json, bun.lock, css) must still trigger a rebuild.

## Todo
- [x] Decide gating mechanism (paths-filter vs paths-ignore) and confirm it cannot skip a tag release
- [x] Implement in `.github/workflows/ci.yml`
- [x] Verify LIVE (partial — see "Live verification" below): code push to main → image jobs run (confirmed); docs-only push and `v*` tag not yet exercised

## Plan

`docs/superpowers/plans/2026-08-16-rebuild-main-image-only-on-code-changes.md` — decision: new `changes` job doing `git diff --name-only <before> <sha>` under exclude-pathspecs, gating `build` via `needs.changes.outputs.code == 'true'`. No new action dependency; fails open so `v*` tags always publish.

## Summary of Changes

Implemented the `changes` job in `.github/workflows/ci.yml` (git diff under exclude pathspecs, `fetch-depth: 0`), gated `build` on its output, and updated CLAUDE.md's Docker/CI section to describe the gate.

Final-review pass (4 findings, all approved, applied in commit `0a3534d`):
1. `build`'s `if:` now uses `!cancelled() && needs.test.result == 'success' && ... && needs.changes.outputs.code != 'false'` — fails OPEN if the `changes` job itself fails/is skipped (previously the implicit `success()` gate over `needs:` would fail-closed and a tagged release could ship with no images).
2. Pathspec narrowed: `':(exclude)*.md'` → `':(exclude,glob)*.md'` (only root-level markdown) plus `':(exclude)docs/**'` — previously hid markdown at every depth including under `src/**`.
3. CLAUDE.md sentence reworded to match the real exclusion list (root markdown + `docs/**`, not `**/*.md`).
4. Added a comment above the `changes` job noting the `if: github.event_name == 'push'` coupling, so a future new trigger doesn't silently skip the gate.

Verified: YAML/job-graph assertion, actionlint (one pre-existing unrelated shellcheck warning in the native smoke-test step, out of scope), pathspec classification against real commits (beans-only skips, code commit builds, root markdown excluded, no currently-existing deep markdown newly exposed), and `bash -n` on the extracted `changes` script. Full detail in `.superpowers/sdd/task-1-report.md` under "Final-review fixes".

## Live verification

Merged as PR #123 (main 3cc200c). CI run 31935735625 on that push: `Detect code changes` succeeded, and all four image jobs plus both manifest merges ran — the code-push half of the gate behaves as designed on real GitHub Actions.

The other two paths have not been exercised live yet, only by local simulation of every script branch (see .superpowers/sdd/progress-ci-image-merged-pr123.md):
- docs/beans-only push to main -> image jobs skipped: happens on its own the next time such a push lands; visible as skipped image jobs in that run.
- `v*` tag -> always builds: exercised by the next release. The if: was hardened to fail OPEN (`!cancelled() && needs.test.result == 'success' && ... && needs.changes.outputs.code != 'false'`) precisely so a broken/skipped changes job cannot produce a tagged release with no images.

Closing rather than holding the bean open: nothing further to implement, and both remaining paths verify themselves passively on the next docs-only push and the next release.
