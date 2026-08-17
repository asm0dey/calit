# A meeting type's `duration_minutes` doubles as the default of its allowed set

Once a meeting type may offer several lengths, something has to say which one an Invitee sees
before they choose. Rather than add a pointer column, the existing `meeting_type.duration_minutes`
IS the default, and the allowed-set table must contain it. An empty allowed set means the set is
exactly `{duration_minutes}` — so every existing meeting type is already valid and needs no
backfill.

## Consequences

- **The Owner chooses the default**, deliberately: it is the duration field they already fill in.
  Removing it from the allowed set is rejected at save — they must move the default, never lose it.
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
