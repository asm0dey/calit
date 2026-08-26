# A meeting type's `duration_minutes` doubles as the default of its allowed set

Once a meeting type may offer several lengths, something has to say which one an Invitee sees
before they choose. Rather than add a pointer column, the existing `meeting_type.duration_minutes`
IS the default, and it is an **implicit member** of the allowed set — the set is read as the union
of the table's rows with `{duration_minutes}`, never the table alone:

```
allowedDurations(type) = sorted(rows(type.id).duration_minutes ∪ {type.durationMinutes})
```

An empty allowed set therefore means the set is exactly `{duration_minutes}`, so every existing
meeting type is already valid and needs no backfill.

A row whose `duration_minutes` equals the default is not what puts the default in the set; it
exists only to carry that length's buffer overrides. Deleting it drops the overrides, never the
duration.

## Consequences

- **The Owner chooses the default**, deliberately: it is the duration field they already fill in.
  Moving it is how they change it; losing it is not expressible.
- **The invariant is unbreakable, not enforced.** Because membership is a union at read time, no
  sequence of edits can produce a set that omits the default. There is no reject-at-save path, no
  error message and no i18n for one, and the meeting type's main edit form (which owns
  `duration_minutes`) can never disagree with the durations form about what the set contains.
  Clearing the default's row in the durations editor is a no-op on the set.
- **Two columns can never disagree**, because there is only one. Read paths that want "the length"
  for a single-duration type keep working untouched.
- **No `position` column and no ordering UI.** Ordering the set by `duration_minutes` is enough;
  nothing about the default depends on row order.
- Do not "fix" this by introducing `default_duration_minutes`. That reintroduces the disagreement
  this avoids, and forces a backfill over every existing row.

## Not the same as the slot cadence

The default is what renders before a choice is made. The slot lattice is anchored separately, to
the *shortest* allowed duration, so the candidate start times do not move when the Invitee
switches length. Anchoring the lattice to the default instead would offer a 30-minute meeting only
at 60-minute intervals when the default is 60, wasting real availability.
