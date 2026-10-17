---
# calit-bgxr
title: Invitee/owner .ics is always METHOD:REQUEST, SEQUENCE:0 — even on cancel/decline
status: todo
type: bug
priority: normal
created_at: 2026-09-29T18:52:11Z
updated_at: 2026-09-29T18:52:11Z
---

IcsBuilder supports CANCEL, but EmailService.inviteeIcsBytes (:1092-1106) never sets method or sequence. sendForKindLocaleAware (:959) attaches it to invitee + owner/host copies, so handleCancelled (:606-633) and deliverDeclined (:493-517) send a REQUEST/CONFIRMED invite. Guest cancels (guestIcs CANCEL :858) are correct. SEQUENCE:0 also means reschedule/detail updates may not replace the old event despite icsSequence bumps.

- [ ] Add method + sequence params to inviteeIcsBytes (sequence = booking.icsSequence)
- [ ] Pass CANCEL from handleCancelled and deliverDeclined
- [ ] Tests on attached ics content
