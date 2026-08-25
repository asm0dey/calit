# The Creator is always the Organizer

A Booking's Google event is created on the Creator's connected account or not at all. When the
Creator has no connected account, a shared meeting type books in degraded mode: calit's own
confirmations and `.ics` files still reach every Host and the Invitee, and no Google event exists.
calit never promotes a Co-host to write the event instead.

A Co-host who wants meetings on their own calendar creates their own meeting type. That is what
owning a meeting type means.

## Considered options

**Fall back to the lowest-numbered connected Co-host** — what the code did. Rejected on three
counts. It makes "whose calendar is this meeting on?" depend on who happened to be connected at
booking time. It hands a Co-host authority over a meeting type they do not own — the calendar the
event is written on, and with it (before ADR-0005) whether a Google Meet link exists at all. And it
is the sole reason a Co-host write override ever existed: no other code path reads one.

**Create one Google event per connected Host** — rejected: N events for one meeting, each a separate
Google entity to keep in sync through every reschedule and cancellation, and an Invitee who receives
N invitations to the same meeting.

## Consequences

- The Co-host write override is deleted — the picker, the resolution path, and the
  `meeting_type_host` columns. Only the Creator's override survives, and it is the one every write
  already used.
- When the Creator is disconnected, Co-hosts stop receiving the Google invitation they used to get.
  They keep calit's own mail and its `.ics` attachment, and every scheduling feature still works.
  This is degraded mode as the glossary already defines it, and it is user-visible.
- Organizer selection collapses to "the Creator if connected, else nobody", and the group Booking row
  carrying the Google event is always the group's lead row.
- "Organizer" stops being a concept worth naming: it can only ever mean the Creator, so calit's
  glossary records it under `_Avoid_` rather than defining it.
