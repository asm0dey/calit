# Buffers are constraints, so the strictest one governs

A buffer expresses a minimum a host requires, not a value someone configures for a meeting. A
meeting cannot be created until every participating host's constraints are satisfied, so where
several buffers apply to the same booking — a co-host's own override and (once meeting types
offer several lengths) the chosen duration's override — the effective buffer is the **maximum**
of them, never the most specific one.

## Consequences

- `MeetingHosts.effectiveBufferBefore/After` are the only two places this rule lives. Both take
  the chosen duration and return `max(host-or-type default, duration-or-type default)`.
- A small override never weakens a larger one. An Owner who sets a 5-minute buffer on a duration
  will still see a host's 90-minute requirement enforced for that host. This is intended: the
  Owner of a shared type does not get to shorten a co-host's turnaround.
- Slot computation must not be cached across durations — a different chosen length can mean a
  different buffer and therefore a different set of slots.
