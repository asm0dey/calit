# No write calendar means the Booking is made without a Mirror event

When a connected account has no write target, a Booking still succeeds. No Mirror event is written;
calit's own `.ics` goes to everyone, the Creator included, so they can add it to a calendar of their
choice. This replaces the old hard 500 on a Booking with no writable calendar. The calendars page
tells the Owner that no writable calendar is selected and each Booking arrives as an `.ics`.

## Considered options

**Refuse the Booking** — rejected: a configuration gap on the Owner's side must not cost an Invitee
their slot (the 500 this replaces).

**Pick any selected calendar as a silent fallback** — rejected: the account may only have read
access, and the Owner never chose it as the place for their meetings.

## Consequences

- A Booking made without a Mirror event keeps receiving calit's invites (`.ics` on confirm,
  reschedule and cancel) even if the Owner later picks a write calendar. Its Mirror event is never
  created retroactively.
- `booking.removed_mirror_provider` records the provider of a Mirror event that was removed, so a
  later decline or cancel still knows which provider sent (or did not send) the invitation.
