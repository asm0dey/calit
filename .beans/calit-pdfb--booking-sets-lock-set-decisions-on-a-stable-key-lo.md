---
# calit-pdfb
title: 'Booking sets: lock set decisions on a stable key; lock single cancel/reschedule'
status: todo
type: task
priority: normal
created_at: 2026-11-15T15:32:45Z
updated_at: 2026-11-15T15:32:45Z
parent: calit-ku6n
---

Follow-up from the multi-slot final review (feat/multi-slot-booking).

- [ ] Take pg_advisory_xact_lock on 'set:<set_id>' in approve, decline, cancelSet and declineSetGuest instead of locking leadOfSet (the earliest row can change under an unlocked reschedule). The expiry tick already uses that key.
- [ ] Call the set lock at the top of single cancel() and reschedule() for set rows: today a single cancel can race cancelSet (outcome converges, one duplicate mail).
- [ ] Decide: after a reschedule, the Owner's ORIGINAL set-request Approve/Decline links act on the rescheduled PENDING session they never described. Either scope the link to the Booking it was sent for, or accept and document.
- [ ] Compensation's isConnected call sits outside the tolerant try; a port exception there masks the original error.
