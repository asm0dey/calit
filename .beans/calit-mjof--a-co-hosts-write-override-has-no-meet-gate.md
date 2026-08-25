---
# calit-mjof
title: A co-host's write override has no Meet gate
status: scrapped
type: bug
priority: normal
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-23T16:52:12Z
---

`SharedMeetingsResource` never calls `WriteTargetResolver.blocksMeet`. The Meet gate is answered once, against the Creator's resolved calendar, on the meeting-type page.

So a co-host can pick a write override whose calendar cannot mint Meet links on a type whose location is Google Meet. When that co-host is the organizer, `GoogleCalendarPort.retryWithoutConference` fires: the booking succeeds, `supportsMeet` flips false, and the invitee receives a booking labelled "Google Meet" with no join link.

The mechanism is pre-existing; the write override gives it a new and much more reachable trigger. Deliberately out of scope for the feature (the gate is the Creator's question by design) — this bean is about the invitee-facing outcome, not about moving the gate.

Found in the final whole-branch review of [[calit-bh5t]].

- [ ] Decide the product answer: warn the co-host at pick time, or tell the invitee, or both
- [ ] Cover whichever path with a test

## Decision (2026-08-23, ADR-0005)

Recorded in `docs/adr/0005-meet-capability-is-the-creators-guarantee.md`. The
Creator defines the meeting type; a co-host neither chooses nor is asked about
Meet capability, and uses the link the Creator's calendar mints.

Finding that reframed the bean: a co-host's calendar mints a Meet link in exactly
ONE situation. Any type with an accepted co-host is multi-host
(`MeetingTypeHost.isMultiHost`), so every booking goes through `bookGroup` — one
event, one organizer — and `MeetingHosts.chooseOrganizer:112` returns
`type.ownerId` whenever the Creator is connected. A co-host organizes only when
the Creator's Google account is DISCONNECTED. The write-override picker is
consulted nowhere else on a shared type.

Product answer:

- **Hide the write-calendar picker on a `GOOGLE_MEET` type** (co-host view,
  `sharedAvailability.html:40-49`). Nothing for the co-host to decide.
- **Still refuse a Meet-less override server-side** in
  `SharedMeetingsResource.saveBuffers:377` — hiding a control is not a gate.
  Reuses `adm_detail_error_location_meet_unsupported`, so no new i18n.
- **Do NOT** gate the Creator on co-hosts' calendars (ADR-0005 rejects it: it
  inverts the ownership) and do NOT move the invitee-facing label to a runtime
  outcome.

## Remaining gap

The Creator-disconnected fallback is NOT closed by the above. A co-host
organizing a `GOOGLE_MEET` type can still write on a Meet-less calendar and
produce a booking labelled "Google Meet" with no join link — the original
symptom. Under ADR-0005 the fix belongs in organizer selection
(`MeetingHosts.chooseOrganizer:111-122`): among connected hosts, prefer one whose
resolved calendar can mint links when `type.locationType == GOOGLE_MEET`. Not yet
approved — decide before implementing.

## Todo

- [x] Decide the product answer: warn the co-host at pick time, or tell the invitee, or both
- [ ] Hide the picker on GOOGLE_MEET types in the co-host view
- [ ] Refuse a Meet-less override in saveBuffers (crafted POST)
- [ ] Decide the chooseOrganizer preference for the Creator-disconnected fallback
- [ ] Cover whichever paths ship with a test

## Reasons for Scrapping (2026-08-23)

Superseded by `docs/adr/0007-the-creator-is-always-the-organizer.md`. The bug
describes a gate missing from a feature that is being removed, so there is no
gate to add.

The grilling that produced ADR-0005 and ADR-0007 established two things:

1. **The location belongs to the meeting type** (ADR-0005). Whether a type
   offers a Google Meet link is the Creator's question, asked once against the
   Creator's resolved calendar. A Co-host was never entitled to an answer.
2. **The Creator is always the Organizer** (ADR-0007). `writeTargets.resolve` has
   exactly two call sites — `BookingService:432` (organizer) and `:521` (Creator)
   — so once the organizer is always the Creator, `writeOverride(coHostId, type)`
   is read by nothing. The Co-host write override is dead, picker included.

So the invitee-facing symptom (a booking labelled Google Meet with no join link)
cannot arise from a Co-host's calendar any more: no Co-host calendar ever writes
the event. What remains of the symptom is Creator-side and pre-existing — a
failed mint via `retryWithoutConference`, or an Owner publishing a Meet type with
no connected account — and ADR-0005 answers both with the `location_detail`
fallback.

Scrapped rather than completed: nothing from this bean's own todo list ships. The
work is carried by the removal bean that supersedes it.
