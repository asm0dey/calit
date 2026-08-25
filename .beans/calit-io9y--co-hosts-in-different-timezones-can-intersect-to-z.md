---
# calit-io9y
title: Co-hosts in different timezones can intersect to zero slots
status: in-progress
type: bug
priority: high
created_at: 2026-08-25T20:00:13Z
updated_at: 2026-08-25T21:52:42Z
parent: calit-p5xm
---

A shared meeting type offers no slots at all when its hosts' UTC offsets do not differ by a whole number of the slot cadence.

`SlotService.generateRawSlots` anchors the multi-host grid to 00:00 in each HOST's own timezone (`dayAnchoredGrid`, SlotService.java:71). Host-local midnight is a different instant per host, so each host's comb is rotated in absolute time by their own UTC offset. `BookingService:145` intersects the per-host free sets by exact start Instant (`candidate.keySet().retainAll(...)`), so the combs must coincide or the intersection is empty.

They coincide only when `(offsetB - offsetA) mod step == 0`.

## Reproductions

- London (+0) and Berlin (+1), cadence **45**: delta 60, 60 mod 45 = 15. London's comb is 0 mod 45 from UTC midnight, Berlin's is 30 mod 45. Disjoint. Zero slots forever.
- Berlin (+1) and Kathmandu (+5:45), cadence 30: delta 285, not a multiple of 30. Zero slots forever.

`step` falls back to the meeting length, so the first case is just a 45-minute type shared between London and Berlin. No exotic timezone, no unusual config.

## Symptom

Silent. The public page renders the ordinary 'no times available' state, as though both hosts were booked solid. An Owner would reasonably conclude their availability rules are wrong.

## Fix (decided 2026-08-25, ADR-0008; corrected 2026-08-25)

A first attempt anchored the lattice to a fixed origin instant (`LocalDate.EPOCH.atStartOfDay(creatorZone)` plus multiples of the step) and landed as commit `2a59d0f`. It fixed host agreement and request-independence, but froze the Creator zone's 1970 UTC offset — `Asia/Kathmandu` was `+05:30` until 1986 and `+05:45` since, so an all-Kathmandu team (no cross-timezone problem at all) shifted from `09:00`/`09:30` to `09:15`/`09:45`. Superseded.

The lattice is now a PREDICATE with no origin, evaluated at each candidate instant:

```
onLattice(t)  <=>  minuteOfDay(t, creatorZone) mod step == 0
```

Candidate starts are enumerated per Creator-local DAY: every `step`-minute mark of the day is re-derived from that day's own midnight via `ZoneRules.getValidOffsets(localTime)` — never carried forward across a Creator-local midnight by `plusMinutes` on a running local value (a first cut of this fix did that and was caught by review: it re-derives the phase once per window and then walks it forward, so a window whose Creator-local start falls late the previous day, or a step that does not divide 1440, drifts onto a different comb -- two combs again, verified against the reviewer's LA/Berlin cadence-50 repro, which the buggy version intersected to 0 and the corrected per-day version intersects to 7, matching the ADR predicate). `getValidOffsets` also resolves DST correctly: zero offsets for a spring-forward gap (skip), two for a fall-back's repeated hour (emit BOTH -- both are real, distinct instants satisfying the predicate, which keeps single-host and multi-host slot counts consistent across the same transition).

Creator's zone still supplies the phase (round times on the clock of whoever defined the type; an all-one-timezone shared type keeps exactly today's start times), but the zone's rules are read at `t` rather than frozen at an origin date, so a zone whose offset has changed historically (Kathmandu, Lisbon, ...) is unaffected. Request-independence falls out of the predicate having no origin at all: whether the request's range is `D` or `D-3..D+3`, the candidate starts on day `D` are identical.

Single-host stays window-anchored, byte-identical.

## Scope

Being fixed inside [[calit-p5xm]], which rewrites the same grid line (step stops defaulting to `durationMinutes` and starts defaulting to the shortest allowed length). Fixing it afterwards would mean editing that line twice. Gets its own changelog entry as a bugfix, separate from the feature.

## Todo

- [x] ADR-0008: the slot lattice is anchored to the Creator's clock (revised to a no-origin predicate, superseding the epoch-anchored `2a59d0f` attempt)
- [x] Replace the `boolean dayAnchoredGrid` parameter, then the epoch-anchored `Instant gridAnchor`, with a nullable `ZoneId latticeZone` (null = window-anchored, single-host); `gridAnchorFor` replaced by `SlotService.latticeZoneFor`
- [x] Enumerate candidate starts per Creator-local DAY via `ZoneRules.getValidOffsets` (never carry the phase forward across a Creator-local midnight by `plusMinutes` on a running local value -- that shape shipped once, was caught by review, and is fixed)
- [x] Handle DST correctly: skip a spring-forward gap (zero valid offsets); emit BOTH instants of a fall-back's repeated hour; `continue` (not `break`) on the window-end test since the resolved-instant sequence is not monotone once candidates can be skipped/doubled; sort the returned list by start instant so callers never see out-of-order slots
- [x] Regression test: multi-host fall-back emits 5 starts (both instants of the repeated hour), matching the single-host count (`SlotServiceLatticeTest#aFallBackHourYieldsBothInstantsOnTheLatticePath`)
- [x] Regression test: London + Berlin, 45-minute cadence, both free 09:00-17:00 local -> non-empty intersection (`SlotServiceLatticeTest#hostsAnHourApartShareALatticeOnAFortyFiveMinuteCadence`)
- [x] Regression test: Berlin + Kathmandu, 30-minute cadence -> non-empty intersection (`#aQuarterHourOffsetZoneStillShares`)
- [x] Regression test: all-hosts-one-timezone shared type -> start times unchanged from today (`AvailableSlotsIntersectionTest`, all-Amsterdam, byte-identical; plus the new `#anAllKathmanduTeamKeepsRoundLocalTimes`, which is what the epoch-anchored `2a59d0f` attempt actually failed -- it produced `09:15` instead of `09:00`)
- [x] Regression test: the lattice does not move with the requested date range, which is what a 50-minute-cadence booking-page-vs-submit-time disagreement would need (`#theLatticeDoesNotMoveWithTheRequestedRange`) -- a 50-minute cadence is settable today via `slotIntervalMinutes`, so this was never blocked on a later task; the earlier note claiming otherwise was wrong
- [x] Regression test: the lattice's phase is the CREATOR's zone, not the Host's and not UTC (`#theLatticeIsRoundInTheCreatorsZoneNotTheHosts`)
- [x] Regression test: the hostile 29-minute / 4h45-offset case, dense comb over the overlap (`#aTwentyNineMinuteCadenceStillIntersectsAcrossAFourHourFortyFiveOffset`)
- [x] Regression test: null-zone single-host path stays window-anchored (`#aNullLatticeZoneKeepsWindowAnchoringForSingleHost`)
- [x] Regression test: pin the single-host DST fall-back behaviour on the null-anchor Instant walk (`SlotServiceTest#aWindowStraddlingAFallBackTransitionCoversTheFullElapsedTime`)
- [ ] Changelog entry under ## Unreleased on docs-site (deferred to branch integration/finish, not part of this intermediate task commit)
