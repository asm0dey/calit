# calit

Self-hosted, multi-user scheduling: each Owner publishes meeting types on their own page, and
Invitees book slots on them. This file is the glossary — the words we use for the concepts, and
the words we have decided not to use. No implementation detail belongs here.

## Language

### Scheduling

**Owner**:
The user whose time is being booked and who configures the meeting types, availability and
settings. Every tenant row belongs to exactly one.
_Avoid_: user (a login), admin (site-wide privilege), account (a connected Google account)

**Invitee**:
The person who books an Owner's time. Has no login and no calit row of their own beyond the
booking.
_Avoid_: guest (an extra attendee added to a booking), booker, attendee, customer

**Host**:
An Owner who participates in a meeting type and whose calendar a booking must fit — the Creator
or an accepted Co-host. The unit availability and buffers are resolved per.
_Avoid_: participant, organizer (there is no such role — every Google event is written on the
Creator's connected account, see `docs/adr/0007-the-creator-is-always-the-organizer.md`)

**Creator**:
The Host who owns the meeting type: it lives in their namespace and they invite the Co-hosts.
_Avoid_: primary host, main host

**Co-host**:
A Host invited onto someone else's meeting type, making it bookable under their page too and
requiring them to be free.
_Avoid_: guest host, secondary host

**Meeting type**:
A bookable offering of an Owner: length, availability, location and questions. What an Invitee
picks before choosing a slot.
_Avoid_: event type, service, offering

**Buffer**:
Protected time a host requires immediately before or after a meeting. A constraint, not a
preference: a slot is offered only if it satisfies every participating host's buffer, so the
strictest one governs — see `docs/adr/0002-buffers-are-constraints-not-settings.md`.
_Avoid_: padding, gap, break, turnaround

**Slot**:
A candidate start time for a meeting type, derived from availability minus buffers, minimum
notice, horizon and existing bookings. Never stored — always computed.
_Avoid_: opening, availability (that is the rules a slot is derived from), timeslot

**Cadence**:
The spacing between consecutive slot starts, independent of how long the meeting is. Anchored to
the shortest length a meeting type allows, so the start times on offer do not move when an Invitee
changes the length.
_Avoid_: interval, step, granularity, lattice

**Allowed durations**:
The set of lengths a meeting type may be booked at. Its default — what an Invitee sees before
choosing — is the meeting type's own duration, which the set always contains
(`docs/adr/0003-a-meeting-types-duration-doubles-as-its-default.md`).
_Avoid_: duration options, duration range, lengths

**Booking**:
An agreement between an Owner and an Invitee to meet at a specific time. calit's sole source of
truth about whether a meeting exists.
_Avoid_: appointment, meeting (the real-world encounter), reservation, event (a Google event)

**Location**:
Where a meeting happens: a phone number, an address, custom text, or a Google Meet link. A property
of the meeting type its Creator published, never of a Booking or of a Host
(`docs/adr/0005-the-location-belongs-to-the-meeting-type.md`).
_Avoid_: place, venue, meeting place, room

### Google Calendar

**Google event**:
The calendar entry calit creates in Google to reflect a Booking. A mirror of the Booking, never
an authority over it — see `docs/adr/0001-google-event-is-a-mirror-of-the-booking.md`.
_Avoid_: the meeting, the booking, the appointment

**Connected account**:
One Google account an Owner has authorised, with its credential. An Owner may connect several.
_Avoid_: credential (the token row inside it), integration, calendar

**Write target**:
The one calendar, among a connected account's calendars, where an Owner's new Google events are
created by default.
_Avoid_: primary calendar, default calendar, target

**Write override**:
The Creator's choice of a different calendar for one meeting type's Google events, instead of their
write target. Unset — the normal case — means the write target. Only the Creator has one: every
Google event is written on the Creator's connected account
(`docs/adr/0007-the-creator-is-always-the-organizer.md`). Where an event is written never changes
what a meeting type offers, because the location belongs to the type
(`docs/adr/0005-the-location-belongs-to-the-meeting-type.md`).
_Avoid_: per-type calendar, type calendar, default write target (the write target already is the
default)

**Dangling override**:
A write override naming a calendar the Creator no longer has selected — unticked, or its connected
account disconnected. Never fails a Booking: the write falls back to the write target, and the
Creator is told their choice is no longer in effect.
_Avoid_: broken override, stale calendar, orphaned override (that is a Google event left behind on
the calendar it was created on)

**Stored ref**:
The calendar a Booking's Google event was actually created on, recorded on the Booking so later
writes address the same calendar even after the write target moves.
_Avoid_: original calendar, event calendar

**Fallback address**:
The write target, used when a Booking's stored ref cannot be used (the account was disconnected,
or the ref belongs to another Owner). A 404 through it proves nothing about the event, because we
may simply be asking the wrong calendar.
_Avoid_: default write target (that is the write target itself, addressed for a different reason)

**Degraded mode**:
Running with no Google configured or connected at all. Every scheduling feature works; only
mirroring is absent.
_Avoid_: no-Google mode, offline mode
