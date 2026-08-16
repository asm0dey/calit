---
# calit-rma2
title: Rotating the Google write target orphans existing booking events
status: draft
type: bug
priority: normal
created_at: 2026-08-16T10:07:53Z
updated_at: 2026-08-16T10:07:53Z
---

A booking row stores only \`googleEventId\` — never the calendar the event was created on. Every later write (\`updateEvent\`, \`updateEventDetails\`, \`deleteEvent\`) resolves the calendar from \`GoogleCalendar.writeTarget(ownerId)\`, i.e. whatever the owner's write target is *now*.

If the owner switches their write target (or reconnects a different Google account) between booking and cancel/reschedule, calit addresses the event on the wrong calendar. Google answers 404, and since 1.20.1 \`deleteEvent\` treats 404 as "already gone" — so the cancel succeeds locally while the event stays on the old calendar forever, invisible to calit. Reschedule 404s loudly instead, which is at least noisy but still cannot reach the event.

Raised by the final review of calit-qjqb. The 410/404 tolerance is still right (the common case genuinely is a hand-deleted event); the missing piece is that calit cannot tell the two 404s apart. Since 1.20.1 the tolerated-delete INFO line logs event id + calendar id + ownerId, which is the evidence needed to find out whether this happens in practice.

## Todo

- [ ] Check the logs / ask whether write-target rotation actually happens in the wild before paying for a schema change
- [ ] If it does: add a \`google_calendar_id\` column next to \`google_event_id\` on booking (new Flyway V*.sql — never edit an applied migration), backfill with the current write target
- [ ] Address event writes by the stored calendar id, falling back to the write target for pre-migration rows
- [ ] Decide what a 404 means once the calendar id is known — likely "really gone" (tolerate) vs "wrong calendar" (surface to the owner)
