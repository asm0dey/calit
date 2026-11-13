---
# calit-ffr7
title: Bump Prince of Space 2.2.0 -> 2.3.1
status: completed
type: task
priority: normal
created_at: 2026-11-13T15:06:45Z
updated_at: 2026-11-13T15:32:51Z
---

Upstream fixed our string-literal double-escape (#100) and multi-line chain comment (#101) bugs in 2.3.0. Bump, reformat, verify string constants unchanged via javap diff, full suite green.

- [x] Baseline: compile and dump String constants
- [x] Bump to 2.3.1 and spotless:apply
- [x] Diff compiled String constants (must be identical)
- [x] Renovate rule so future POS bumps get proposed
- [x] Full test suite green
- [x] PR

## Summary of Changes

- POS 2.2.0 -> 2.3.1 in the Spotless `<princeOfSpace>` step; picks up upstream fixes for #100 (string-literal double-escaping, via PR #89) and #101 (multi-line chain comments).
- Reformat touched 4 files, annotation wrapping only. Compiled String/Utf8 constants of every main+test class identical before/after (javap diff, 82,682 entries).
- renovate.json: regex customManager so Renovate proposes future POS bumps (maven manager ignores versions nested in plugin config).
- Full suite: 1503 tests, 0 failures.

Follow-up: POS 2.3.1 rewrote `'\u2026'` to a literal `'…'` in ChannelAdmin.looksRedacted, making that line new code; Sonar gate failed at 57.1% (3/6 branches). Parameterized ChannelSettingsPageTest.aPastedMaskIsRejectedInANewRow over the ellipsis and both notify4j sentinels (`<redacted>`, `<none>`): 5/6 branches, PIT 5/5 mutants killed.
