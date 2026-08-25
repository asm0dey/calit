# The slot lattice is anchored to the Creator's clock

Every Host of a meeting type shares one lattice of candidate start times, anchored to local
midnight in the **Creator's** timezone on a fixed reference date:

```java
Instant anchor = LocalDate.EPOCH.atStartOfDay(creatorZone).toInstant();
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

Two properties of the anchor do the work, and they are separate:

- **The Creator's zone** supplies the phase. Start times come out round on the clock of whoever
  defined the meeting type, and a shared type whose Hosts are all in one timezone keeps exactly the
  start times it had before. A Host in a zone offset by a non-multiple of the cadence sees unround
  local times — genuinely the only instants everyone can share.
- **A fixed date** supplies request-independence. The booking page asks for slots from today; the
  submit-time re-check asks for a single chosen day. Anchoring to the request's own start date
  would put those anchors a whole number of days apart, and a cadence that does not divide 1440 —
  25 or 50 minutes — makes the two lattices disagree, so a slot the page has just rendered is
  rejected as unavailable when the Invitee submits it. Any constant date removes this; the epoch is
  the obvious one.

## Considered options

**Anchor to each Host's own midnight** — the status quo. Rejected: it is the defect. No lattice
exists that both runs continuously and restarts at every Host's local midnight, because the Hosts'
midnights are different instants.

**Anchor to the UTC epoch, unrotated** — correct, and a slightly smaller change. Rejected because
the phase is free: rotating by the Creator's offset costs one `ZoneId` lookup and keeps round local
times wherever the arithmetic allows, while leaving UTC unrotated moves an all-Adelaide or
all-India shared type from `:00` to `:30` local for no benefit.

**Anchor to the earliest participating Host's window start** — appealing because it generalises the
single-host rule, where the window start *is* the anchor. Rejected on three counts: it requires
every Host's availability to be loaded before any Host's slots can be generated; it changes
multi-window days for single-host types, whose afternoon window currently anchors itself; and it
makes the lattice availability-dependent, so one Host adding an early window or a single date
override shifts every start time that day for everyone, including on a type they do not own.

## Consequences

- `generateRawSlots` takes a nullable `Instant gridAnchor` in place of the `boolean
  dayAnchoredGrid` flag. Null means window-anchored: single-host keeps its historical behaviour
  byte-identical, including the rule that each window of a multi-window day anchors itself.
- Alignment happens in absolute time rather than in host-local minute-of-day.
- A cadence that does not divide 1440 (25, 50) produces a comb that drifts off local midnight on
  later days. It stays consistent, which is what correctness requires; it is merely not round. This
  is arithmetic, not a choice — no continuous lattice can hit midnight on consecutive days when the
  step does not divide the day.
- Changing a Creator's timezone moves the lattice for every type they own. Changing a Co-host's
  does not.
