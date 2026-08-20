---
# calit-o69e
title: '404 on update/details: tolerate, and recreate only when the owner asked'
status: todo
type: bug
priority: normal
created_at: 2026-08-17T10:52:27Z
updated_at: 2026-08-17T11:00:52Z
---

`GoogleCalendarPort.updateEvent` (:249) and `updateEventDetails` (:276) catch only
`IOException`. `GoogleJsonResponseException` IS an `IOException` subclass, so a 404 lands
in that catch and becomes `UncheckedIOException` -> hard 500. No status check at all,
unlike `deleteEvent` (:292-307) which tolerates 404/410 as "already in the end state we
wanted".

Observable today: owner deletes an event by hand in Google Calendar.
- Owner CANCELS that booking in calit -> works (delete tolerates the 404).
- Owner RESCHEDULES it instead -> 500 error page, local row not updated, invitee never
  emailed.

This is the shared prerequisite of calit-8dqz and calit-64hy, and it is the only part of
either that needed no field data to settle. Split out at the 2026-08-17 triage.

## Decision (2026-08-17)

**Superseded 2026-08-17 by ADR-0001** — see the Amendment below. `byOwner` does NOT
discriminate the 404 path; only `addr.stored()` does. Two cases:

1. **Stored ref was used, 404/410 -> RECREATE.** `createEvent` + re-stamp
   `googleEventId`/address, whoever triggered the write. The Google event is a mirror of the
   booking (ADR-0001), so a confirmed-missing mirror is stale and gets re-projected.
2. **`!addr.stored()` (fell back to the write target), 404 -> TOLERATE.**
   Recreating here could duplicate an event that is still alive on the calendar we
   failed to address. Same treatment `deleteEvent` gives it today.

Case 3 is also what unblocks **calit-8dqz Q1**: the stored vs default-write-target cases
now get different treatment, so nobody has to watch INFO lines for months to find out
whether the fallback case occurs in the wild.

Why no error banner / no "recreate the event" button (considered and dropped): it costs a
POST route, a CSRF token, three locales of i18n, a template block and a test — all to
make the owner click something calit can do itself in the one case where recreating is
provably safe. In the other two cases there is nothing for a button to do.

## Notes

- Recreate cannot live inside the port: `updateEvent` has no summary/description and
  `updateEventDetails` has no start/end. The port must REPORT the 404 (typed
  `EventGoneException`) and `BookingService` — which holds the booking row, the
  `MeetingType`, the guests, the meet flag and `locationText` — does the recreate.
- Throw `EventGoneException` ONLY when `addr.stored()`; the non-stored 404 is logged and
  swallowed in the port, so `BookingService` never sees case 3.
- The recreate path is a second caller for the `stampAddress` helper proposed in
  calit-vi8n ("event id and address are set together"). Extract that first, then reuse.
- Group paths call the same port methods (`BookingService:1038`, `:1246`) — decide
  per-row or fail the whole group, and cover it.

## Todo

- [ ] Extract `stampAddress(Booking, CreatedEvent)` (from calit-vi8n) first
- [ ] Add `EventGoneException`; throw from `updateEvent`/`updateEventDetails` on 404/410 only when `addr.stored()`
- [ ] Log + swallow the non-stored 404 in the port, mirroring `deleteEvent:292-307`
- [ ] `BookingService`: catch it at the reschedule and updateDetails call sites; recreate unconditionally (ADR-0001)
- [ ] Decide and cover the group-path behaviour (`:1038`, `:1246`)
- [ ] Test: owner-initiated reschedule of a hand-deleted event recreates and re-stamps
- [ ] Test: invitee-initiated reschedule of a hand-deleted event ALSO recreates (no 500, mirror restored)
- [ ] Test: reschedule through the fallback address on a 404 tolerates — no recreate, no duplicate

## Amendment (2026-08-17, ADR-0001)

The original decision above made the 404 mean different things depending on who triggered the
write: recreate for the owner, tolerate for the invitee. The grilling that produced
`docs/adr/0001-google-event-is-a-mirror-of-the-booking.md` rejected that — a model where the
answer to "is this meeting on the owner's calendar?" depends on who last clicked is a model with
no answer. The Google event is a **mirror** of the Booking; a hand-delete is projection drift,
not intent.

So:

- **Recreate on a stored-ref 404 regardless of `byOwner`.** The invitee-initiated case restores
  the mirror too — the meeting genuinely is still on, and the owner's calendar should say so.
- **`byOwner` drops out of this path entirely.** It still exists for its other purposes
  (`BookingService.reschedule(:768)`, `updateDetails(:949)`), but the 404 branch must not read it.
  One less axis to test.
- **The fallback-address case is unchanged** — tolerate, because a 404 there does not prove the
  mirror is missing.
- **calit-r8et is scrapped** by the same ADR: there is no event deletion to detect, so no
  webhook and no reconcile scheduler. This bean's recreate-on-write IS the whole mechanism.
