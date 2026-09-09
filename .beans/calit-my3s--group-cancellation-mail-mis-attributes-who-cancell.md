---
# calit-my3s
title: Group cancellation mail mis-attributes who cancelled
status: todo
type: bug
priority: low
created_at: 2026-09-09T22:49:07Z
updated_at: 2026-09-09T22:49:07Z
---

For a group booking, BookingCancelled carries only a byOwner boolean, so EmailService cannot tell which co-host clicked cancel. Every co-host's copy therefore reads "You cancelled your meeting with {name}." and the invitee's copy names l.owner.ownerName as the canceller, who may be a different host. Fix needs an actor id on the BookingCancelled event (and the same treatment for BookingRescheduled, which has the identical shape).

Deferred out of GH #198 / bean calit-ot22 deliberately - that branch was a copy fix and the mis-attribution predates it.
