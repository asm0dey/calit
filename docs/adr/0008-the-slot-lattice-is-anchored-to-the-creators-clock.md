# The slot lattice is anchored to the Creator's clock

Every Host of a meeting type shares one lattice of candidate start times, defined by the local
time-of-day in the **Creator's** timezone:

```
onLattice(t)  ⟺  minuteOfDay(t, creatorZone) mod step == 0
```

A Host never has a lattice of their own. What a Host owns is their timezone, working hours, date
overrides and buffers — the cadence belongs to the meeting type, because a shared lattice is the
only thing that lets several Hosts' slots intersect at all.

Multi-host availability is the intersection of the Hosts' free sets, taken by exact start instant.
Anchoring each Host's grid to their *own* local midnight rotates each comb by that Host's UTC
offset, so two combs coincide only when the offsets differ by a whole number of the cadence. They
often do not: London and Berlin on a 45-minute cadence differ by 60 minutes, which is not a
multiple of 45, and the intersection is empty on every day forever while the page renders the
ordinary "no times available" state.

The rule above removes that by construction. There is one definition, in one zone, and every Host
tests the same predicate against the same instants, so Hosts cannot disagree about which instants
are candidates.

## Why a predicate rather than an origin plus a step

The lattice is not "some origin instant, plus multiples of the step". It has no origin. Membership
depends only on `t` and on the Creator's zone rules *as they apply at `t`*.

That matters twice:

- **Request-independence.** The booking page asks for slots from today across the horizon; the
  submit-time re-check asks for one chosen day. An origin derived from a request's own start date
  puts those two computations a whole number of days apart, and a cadence that does not divide 1440
  — 25 or 50 minutes — then makes them compute different lattices, so a slot the page has just
  rendered is rejected as unavailable when the Invitee submits it. A predicate on `t` cannot
  develop that disagreement.
- **The zone's rules are read at `t`, not frozen.** A fixed origin bakes in whatever offset the
  Creator's zone had on the origin's date. `Asia/Kathmandu` was `+05:30` until 1986 and `+05:45`
  after; `Europe/Lisbon` was `+01:00` and is now `+00:00`. An origin at the epoch would put an
  all-Kathmandu team — who have no cross-timezone problem at all — on `09:15`/`09:45` where they
  see `09:00`/`09:30` today. Consulting the zone at `t` keeps them where they are.

## Considered options

**Anchor to each Host's own midnight** — the status quo. Rejected: it is the defect. No lattice
exists that both runs continuously and restarts at every Host's local midnight, because the Hosts'
midnights are different instants.

**A fixed origin instant, rotated by the Creator's offset** — `LocalDate.EPOCH.atStartOfDay(
creatorZone)`, plus multiples of the step. Implemented first, then rejected. It is correct about
Host agreement and about request-independence, but it freezes the zone's 1970 rules, so it shifts
the start times of teams that never had the bug: every team in a zone whose standard offset has
changed since 1970, and every team on a cadence that does not divide 60, whose times then also move
across each DST boundary. Charging an unaffected team a fifteen-minute shift to fix someone else's
problem is the wrong trade.

**Anchor to the UTC epoch, unrotated** — a smaller change again. Rejected for the same reason plus
one more: it drops the Creator's phase entirely, so an all-Adelaide or all-India shared type moves
from `:00` to `:30` local immediately, with no benefit to anyone.

**Anchor to the earliest participating Host's window start** — appealing because it generalises the
single-host rule, where the window start *is* the anchor. Rejected on three counts: it requires
every Host's availability to be loaded before any Host's slots can be generated; it changes
multi-window days for single-host types, whose afternoon window currently anchors itself; and it
makes the lattice availability-dependent, so one Host adding an early window or a single date
override shifts every start time that day for everyone, including on a type they do not own.

## Consequences

- `generateRawSlots` takes a nullable `ZoneId latticeZone` in place of the `boolean
  dayAnchoredGrid` flag. Null means window-anchored: single-host keeps its historical behaviour
  byte-identical, including the rule that each window of a multi-window day anchors itself.
- Candidate starts are enumerated per Creator-local **day**: every `step`-minute mark of the day
  (`00:00`, `00:step`, … ) is re-derived from that day's own midnight, never carried forward across
  a Creator-local midnight by adding `step` to a running local time. A step that does not divide
  1440 (25, 29, 50, …) would otherwise phase-shift a window whose Creator-local start falls on a
  different day than its own midnight computation assumes — the same two-combs defect this decision
  exists to remove, just moved from "per Host" to "per window".
- Each candidate local time is resolved with `ZoneRules.getValidOffsets`, never a single-valued
  `ZonedDateTime.atZone`/`plusMinutes` walk: a local time inside a spring-forward gap has **zero**
  valid offsets (it is skipped, not silently pushed forward across the gap), and a local time inside
  a fall-back's repeated hour has **two** — both real, distinct instants — so both are emitted (see
  the next bullet). Because a local minute can resolve to zero, one, or two instants, the candidate
  sequence is not monotone in the instant time-line, so a caller may not `break` out of the per-day
  loop on the first out-of-window candidate; it must skip (`continue`) instead, and the method sorts
  its output by start instant before returning so callers still see chronological order.
- A Host whose zone is offset from the Creator's by a non-multiple of the cadence sees unround
  local start times — a Kathmandu Host on a Berlin Creator's 30-minute type reads `:15`/`:45`.
  Those are the only instants everyone can share; the alternative is the none they are offered now.
- The lattice has a deliberate discontinuity at a DST transition in the Creator's zone, and when
  the cadence does not divide 1440 the final interval of each Creator-local day is short. Both are
  identical for every Host, which is what correctness requires.
- Across a fall-back in the Creator's zone, the repeated local hour yields **two** candidate
  instants, one per offset, because both satisfy the predicate — the Creator-local time-of-day is
  identical and both are real, distinct moments. This keeps the multi-host (lattice) path's slot
  count consistent with the single-host (window-anchored) path's count across the same transition,
  rather than one silently offering an hour the other does not.
- Changing a Creator's timezone moves the lattice for every type they own. Changing a Co-host's
  does not.
