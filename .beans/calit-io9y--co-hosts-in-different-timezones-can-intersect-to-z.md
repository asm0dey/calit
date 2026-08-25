---
# calit-io9y
title: Co-hosts in different timezones can intersect to zero slots
status: todo
type: bug
priority: high
created_at: 2026-08-25T20:00:13Z
updated_at: 2026-08-25T22:50:00Z
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

## Fix (decided 2026-08-25, ADR-0008)

One lattice for the whole type, anchored to the CREATOR's clock at a request-independent instant:

```java
Instant anchor = LocalDate.EPOCH.atStartOfDay(creatorZone).toInstant();
```

Creator's zone for the phase (round times on the clock of whoever defined the type; an all-one-timezone shared type keeps exactly today's start times), a constant date so every request computes the same comb.

The constant matters: anchoring to the request's `from` date would make the booking page (`from = today`) and `assertSlotAvailable` (`from = the chosen day`) disagree whenever the cadence does not divide 1440 — 25 or 50 — so a slot the page just rendered would 409 on submit.

Single-host stays window-anchored, byte-identical.

## Scope

Being fixed inside [[calit-p5xm]], which rewrites the same grid line (step stops defaulting to `durationMinutes` and starts defaulting to the shortest allowed length). Fixing it afterwards would mean editing that line twice. Gets its own changelog entry as a bugfix, separate from the feature.

## Todo

- [x] ADR-0008: the slot lattice is anchored to the Creator's clock
- [x] Replace the `boolean dayAnchoredGrid` parameter with a nullable `Instant gridAnchor` (null = window-anchored, single-host)
- [x] Align in absolute time rather than host-local minute-of-day
- [x] Regression test: London + Berlin, 45-minute cadence, both free 09:00-17:00 local -> non-empty intersection
- [x] Regression test: Berlin + Kathmandu, 30-minute cadence -> non-empty intersection
- [x] Regression test: all-hosts-one-timezone shared type -> start times unchanged from today (covered by the existing `AvailableSlotsIntersectionTest` suite, all-Amsterdam, which the full-suite run confirms is byte-identical after this change)
- [ ] Regression test: a slot rendered by the booking page validates in assertSlotAvailable at a 50-minute cadence (not exercised yet — needs a per-type selectable duration/cadence to set up, deferred to a later task in the selectable-booking-duration plan)
- [ ] Changelog entry under ## Unreleased on docs-site (deferred to branch integration/finish, not part of this intermediate task commit)
