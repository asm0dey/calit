# The Google event is a mirror of the Booking, not a participant in it

A Booking in calit's database is the sole source of truth about whether a meeting exists; the
Google Calendar event is a projection of it, kept for the Owner's convenience. Editing or
deleting that event in Google therefore carries no domain meaning — it is projection drift, and
calit re-projects on its next write rather than reading intent into it.

## Considered options

**The event as a shared artifact carrying intent** — treat a hand-deleted event as the Owner
cancelling. Rejected: it makes an Owner's calendar client a second, unauthenticated write path
into calit's booking state, and calit only ever notices such a "cancellation" if someone happens
to touch the booking afterwards. A cancellation nobody is guaranteed to observe is not a
cancellation.

**Actor-dependent meaning** — recreate when the Owner triggered the write, tolerate when the
Invitee did. Rejected: the model then has no answer to "is this meeting on the Owner's
calendar?", and every future Google code path has to re-derive the rule from a `byOwner` flag.

## Consequences

- A 404/410 on a write **through the stored ref** means the mirror is stale: recreate the event
  and re-stamp the Booking, whoever triggered the write.
- A 404 through the **fallback address** is still tolerated, not recreated. It does not prove the
  mirror is missing — we may be addressing the wrong calendar, and recreating could duplicate a
  live event.
- calit deliberately has **no deletion detection** — no `events.watch` webhook and no reconcile
  scheduler. There is no such thing as an event deletion to learn about. The cost is accepted and
  known: until something writes to the mirror, a Booking whose Google event was hand-deleted stays
  active, keeps its slot blocked and keeps sending reminders. That is correct under this model —
  the meeting really is still on.
- Owners who want a meeting cancelled must cancel it in calit. Deleting from Google only makes
  their calendar temporarily disagree with reality.
