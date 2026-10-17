---
# calit-bgxr
title: Invitee/owner .ics is always METHOD:REQUEST, SEQUENCE:0 — even on cancel/decline
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:52:11Z
updated_at: 2026-10-17T10:59:53Z
---

IcsBuilder supports CANCEL, but EmailService.inviteeIcsBytes (:1092-1106) never sets method or sequence. sendForKindLocaleAware (:959) attaches it to invitee + owner/host copies, so handleCancelled (:606-633) and deliverDeclined (:493-517) send a REQUEST/CONFIRMED invite. Guest cancels (guestIcs CANCEL :858) are correct. SEQUENCE:0 also means reschedule/detail updates may not replace the old event despite icsSequence bumps.

- [x] Add method + sequence params to inviteeIcsBytes (sequence = booking.icsSequence)
- [x] Pass CANCEL from handleCancelled and deliverDeclined
- [x] Tests on attached ics content

## Summary of Changes

EmailService.inviteeIcsBytes takes an IcsMethod and always stamps SEQUENCE = booking.icsSequence. Both sendForKindLocaleAware overloads thread the method through; handleCancelled and deliverDeclined (incl. the enqueueDeclined outbox path) send METHOD:CANCEL / STATUS:CANCELLED, all other kinds and the .ics download send REQUEST. Tests: InviteeIcsMethodTest (6). Follow-ups: calit-1cub (bump SEQUENCE on CANCEL), calit-cslr (download serves REQUEST for cancelled bookings).
