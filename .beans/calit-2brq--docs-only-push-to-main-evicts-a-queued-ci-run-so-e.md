---
# calit-2brq
title: Docs-only push to main evicts a queued CI run, so edge never gets rebuilt
status: completed
type: bug
priority: high
created_at: 2026-08-16T10:11:29Z
updated_at: 2026-08-16T11:08:35Z
---

Observed while cutting 1.20.1: the \`release: 1.20.1\` main run (run 31940816985) ended up \`cancelled\` without ever starting a job, so the \`edge\` image was never rebuilt for that commit.

Cause is NOT \`cancel-in-progress\`. \`.github/workflows/ci.yml:10-12\` already has:

    concurrency:
      group: \${{ github.workflow }}-\${{ github.ref }}
      cancel-in-progress: \${{ github.event_name == 'pull_request' }}

so a push never cancels a RUNNING run. But GitHub keeps only ONE pending run per concurrency group: "Any previously pending job or workflow in the concurrency group will be canceled." Sequence that bit us:

1. \`fix(google)… (#125)\` push -> run A starts (in_progress).
2. \`release: 1.20.1\` push -> run B queues behind A (pending).
3. \`chore(beans): …\` push (bean files only) -> run C queues -> B is cancelled as the superseded pending run.

Run C then correctly skips every image job (it is a docs/beans-only push — the calit-1aqn gate works). So a push that is REQUIRED to publish nothing silently killed the run that was required to publish \`edge\`. Any docs/beans push landing while main CI is busy has the same effect, and nothing surfaces it — the cancelled run just sits in the list.

Fix: give push runs their own concurrency group per SHA so they neither queue nor evict each other; keep PR runs cancelling superseded pushes as today.

    group: \${{ github.workflow }}-\${{ github.ref }}\${{ github.event_name == 'push' && format('-{0}', github.sha) || '' }}

Trade-off accepted: two rapid main pushes now build in parallel instead of serialising, so in principle an older commit's image could finish last and leave \`edge\` stale. Much narrower than today's failure — docs-only pushes skip the image jobs entirely and so can never win that race, and it takes two code pushes minutes apart plus out-of-order finishes to hit it.

## Todo

- [x] Change the concurrency group in \`.github/workflows/ci.yml\` so push runs are per-SHA
- [x] Confirm PR behaviour is unchanged (a new PR push still cancels the in-progress run for that PR)
- [x] Confirm \`v*\` tag runs are unaffected
- [x] Re-run the cancelled \`release: 1.20.1\` run so \`edge\` actually carries 1.20.1

## Summary of Changes

Shipped in PR #126 (`fix(ci): per-SHA concurrency group for push runs`, merged 2026-08-16, main 085cd21).

- `.github/workflows/ci.yml` concurrency group now appends `-${{ github.sha }}` for `push` events; `cancel-in-progress` unchanged (PR-only).
- PR behaviour verified unchanged: runs for #126 (5945db6) and the renovate pin PR both completed normally.
- Tag pushes are `push` events, so they get their own per-SHA group too — no queue, no eviction.
- No separate re-run of the cancelled `release: 1.20.1` run (31940816985) was needed: the #126 merge push (085cd21, descendant of `release: 1.20.1`) ran the full image matrix — jvm+native amd64/arm64 plus both multi-arch manifest merges — so `edge` carries 1.20.1 code.
