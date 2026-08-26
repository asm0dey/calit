---
# calit-ntek
title: A 1-minute allowed duration silently drops the cadence to 1
status: todo
type: task
priority: low
created_at: 2026-08-26T08:43:59Z
updated_at: 2026-08-26T08:43:59Z
---

The slot cadence falls back to the SHORTEST allowed duration, so adding a 1-minute option to a type also sets its grid step to 1 — about 1440 candidate instants per host per day, ~86k across the default 60-day horizon, on every public page render and every `availableSlots` call.

Nothing in the UI hints at that coupling. An owner adding "1" to try something sees a slow page, not a cadence change, and the fix (set an explicit **Slot interval**) is in a different section.

Related to [[calit-xjrg]], which is the same fallback with a zero rather than a small number — that one hangs outright, this one just gets expensive. Fixing xjrg's guard does not fix this.

## Options

- leave it: the cadence rule is correct, and a 1-minute meeting type is a legitimate if odd thing to offer
- warn in the durations editor when the shortest allowed length is below some threshold and no explicit slot interval is set
- floor the derived cadence (e.g. at 5) when it comes from the fallback rather than from an explicit interval — changes behaviour for anyone deliberately running very short meetings, so not obviously right

Worth measuring before choosing: the per-render cost at step 1 over a 60-day horizon, single-host and multi-host, since the lattice branch enumerates per Creator-local day.

## Todo

- [ ] Measure it rather than assume it is a problem
- [ ] Decide between the three options above, and record why
