# Buffers are constraints, so the strictest one governs

A buffer expresses a minimum a host requires, not a value someone configures for a meeting. A
meeting cannot be created until every participating host's constraints are satisfied, so where
several buffers apply to the same booking — a co-host's own override and (once meeting types
offer several lengths) the chosen duration's override — the effective buffer is the **maximum**
of them, never the most specific one.

The maximum is taken over the overrides that are actually **set**. A `NULL` override is the
absence of a requirement, not a requirement equal to the meeting type's flat buffer, so it never
enters the maximum:

```
set = { host override if not null } ∪ { duration override if not null }
effective = set.isEmpty() ? type buffer : max(set)
```

Letting a `NULL` fall back to the type's buffer *inside* the maximum would defeat a host who
deliberately set a buffer BELOW the type's — a 5-minute host override against a 10-minute type
default would be raised back to 10. That host is stating a smaller requirement, and no other
requirement contradicts it.

This is across the sources that apply to ONE host. Across several hosts nothing is maximised:
each host's slots are filtered with that host's own effective buffer and the free sets are
intersected, so the strictest host wins per slot, as they already do.

## Consequences

- `MeetingHosts.effectiveBufferBefore/After` are the only two places this rule lives. Both take
  the chosen duration and return the maximum of whichever overrides are set, falling back to the
  meeting type's buffer only when neither is:

  | host override | duration override | effective |
  |---|---|---|
  | null | null | type buffer |
  | 5 | null | 5 |
  | null | 45 | 45 |
  | 5 | 45 | 45 |
  | 90 | 45 | 90 |

  Every pre-existing row has a null duration override, so its answer is unchanged: the new column
  can only ever raise a buffer, never lower or alter an existing one.
- A small override never weakens a larger one. An Owner who sets a 5-minute buffer on a duration
  will still see a host's 90-minute requirement enforced for that host. This is intended: the
  Owner of a shared type does not get to shorten a co-host's turnaround.
- Slot computation must not be cached across durations — a different chosen length can mean a
  different buffer and therefore a different set of slots.
