# The location belongs to the meeting type

Where a meeting happens — a phone number, an address, custom text, or a Google Meet link — is part
of what a Creator publishes when they define a meeting type. It does not vary with who books it,
which Host attends, or which calendar the Google event lands on.

Three of the four location kinds satisfy this for free: `PHONE`, `IN_PERSON` and `CUSTOM` store the
location in `location_detail` on the meeting type itself. `GOOGLE_MEET` is the exception, and it is
the only one — calit stores no location at all for it, and instead asks Google to mint a link per
Booking, which only the calendar writing that Booking's event can do. The location therefore becomes
a property of whichever connected account calit wrote through, which is how a Co-host's calendar
could ever change what a meeting type offers.

## Considered options

**Ask the Meet question per Host** — refuse a Co-host's write override when it names a calendar that
cannot mint links. Rejected: it asks a Co-host to guarantee something the Creator published, and it
cannot be complete anyway, because a calendar that passed the check can lose the capability later
when `retryWithoutConference` clears it at booking time.

**Tell the Invitee whatever happened** — let the Booking succeed and stop labelling it Google Meet
when no link was minted. Rejected: it makes the location a per-Booking runtime outcome rather than a
property of the meeting type, which is the model this ADR exists to state.

**Let a Co-host's calendar veto the Creator** — check every accepted Co-host's calendar before
allowing the Creator to choose `GOOGLE_MEET`. Rejected: it inverts the ownership, letting a Co-host's
calendar constrain a meeting type they do not own.

## Consequences

- Whether a type may offer a Google Meet link is asked once, against the Creator's resolved calendar.
  No Co-host's calendar enters that decision.
- A Co-host is never asked what a meeting type offers. Together with ADR-0007, which makes the
  Creator the only Organizer, the Co-host write override disappears entirely.
- When calit cannot mint a link, the Booking falls back to the meeting type's stored
  `location_detail`. An Owner may publish a `GOOGLE_MEET` type with no connected account and paste
  their own standing link there — the field is already collected on both meeting-type forms. An
  Invitee is never shown an empty location.
- Two Creator-side staleness paths remain, unchanged and identical to a single-host type: the Creator
  can move their write target after choosing `GOOGLE_MEET`, and `retryWithoutConference` can clear a
  calendar's Meet capability at booking time. Both now land on the `location_detail` fallback rather
  than on nothing.
