---
# calit-wvtl
title: 'PR #142: Sonar quality gate fails on a possible NPE in WriteTargetResolver'
status: completed
type: bug
priority: high
created_at: 2026-08-21T19:00:42Z
updated_at: 2026-08-21T22:16:54Z
---

SonarCloud's gate on [PR #142](https://github.com/asm0dey/calit/pull/142) fails with **new reliability rating C (3)**, threshold A (1). One bug, `javabugs:S2259` MAJOR:

`WriteTargetResolver.java:48` — `if (ownerId.equals(type.ownerId))` throws NPE if `ownerId` is null.

Every other condition passes: new coverage 92.4% (threshold 80), new maintainability A, new security A, duplication 0.0%, security hotspots 100% reviewed. Build & test, CodeQL, Trivy and GitGuardian all pass.

This is the same finding the Task 2 review raised as a Minor and the final whole-branch review triaged as **Drop**, on the grounds that all three call sites are provably non-null (`BookingService` guards `organizer == null` earlier, the other passes `type.ownerId` which is NOT NULL, and the web callers use `CurrentOwner.require()`). That analysis is correct — but reachability is not the question the gate asks, and a failing gate blocks the merge either way.

Fix: guard `ownerId` explicitly alongside the existing `type` guard, so a null owner resolves to "no override" rather than throwing.

- [x] Confirm the gate failure and its single cause
- [x] Guard the null owner in `writeOverride`
- [x] Re-run the affected tests — WriteTargetResolverTest 9/9
- [x] Confirm the gate goes green on the PR

## Summary of Changes

`WriteTargetResolver.writeOverride` guards `ownerId == null || type == null` and returns null ("no override") instead of throwing, clearing javabugs:S2259 and the new-reliability-rating gate. Shipped in PR #142, merged 2026-08-21T19:30:04Z; the guard is on main at WriteTargetResolver.java:45.
