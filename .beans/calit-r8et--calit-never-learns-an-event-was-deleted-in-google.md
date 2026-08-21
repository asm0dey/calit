---
# calit-r8et
title: calit never learns an event was deleted in Google
status: scrapped
type: feature
priority: normal
created_at: 2026-08-17T10:52:43Z
updated_at: 2026-08-17T11:00:18Z
---

Surfaced while triaging calit-8dqz / calit-64hy on 2026-08-17.

Deleting the event in Google Calendar is a natural way for an owner to cancel a meeting.
calit never finds out. There is no push channel and no reconcile pass, so:

- the booking stays ACTIVE in calit,
- its slot stays blocked in `SlotService`, so nobody else can book that time,
- the invitee still believes the meeting is on,
- reminder emails keep going out for a meeting that exists nowhere.

Every fix in calit-o69e is downstream of this: they only make the missing event stop
500ing when someone happens to touch the booking. Nothing notices on its own.

## Open questions

- Push (Google Calendar `events.watch` + a webhook endpoint) or a periodic reconcile
  scheduler? Push needs a publicly reachable HTTPS callback, which many self-hosted
  installs will not have; reconcile fits the existing `scheduler/` package and the
  `SELECT ... FOR UPDATE SKIP LOCKED` multi-node pattern with no new network surface.
- What does calit DO on detecting the deletion? Cancel the booking (emails the invitee a
  cancellation, frees the slot) or flag it for the owner to confirm? Cancelling on a
  reconcile false-negative would email an invitee a cancellation that the owner never
  meant.
- Distinguish "deleted" from "not visible to us" — the same 404 ambiguity as
  calit-8dqz: a 404 through a fallback address does not prove the event is gone.
- Degraded/no-Google mode must be unaffected.

## Notes

- Reconcile is the lazier half: one scheduler that walks ACTIVE bookings with a
  non-null `googleEventId`, `events.get` each, and acts on a confirmed 404 through the
  STORED ref only. No webhook, no public callback, no new config.
- Cheap first cut: detect and log only, decide the user-facing action once the logs show
  how often it fires. Same "measure before building the reaction" posture 8dqz took.

## Reasons for Scrapping

Settled by ADR-0001 (`docs/adr/0001-google-event-is-a-mirror-of-the-booking.md`) during the
2026-08-17 grilling: the Google event is a **mirror** of the Booking, not a participant in it.
calit's Booking row is the sole source of truth about whether a meeting exists, so hand-deleting
the event in Google is projection drift, not a domain event. There is no deletion to "learn".

That answers this bean's open questions by dissolving them:

- Push vs reconcile: **neither**. Detection is lazy — we find the mirror missing when we next
  write to it, and re-project. No webhook, no scheduler, no new config, no public callback.
- What to DO on detection: recreate the event (see calit-o69e), never cancel the booking.
- The 404 ambiguity survives and is still honoured: a fallback-address 404 proves nothing and
  stays tolerated.
- Degraded mode: unaffected, since nothing new runs.

Accepted cost, stated in the ADR: until something writes to the mirror, a booking whose Google
event was hand-deleted stays ACTIVE, keeps its slot blocked and keeps sending reminders. Under
this model that is correct — the meeting really is still on. An Owner who wants it cancelled
cancels it in calit.
