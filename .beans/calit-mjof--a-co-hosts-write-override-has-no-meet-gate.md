---
# calit-mjof
title: A co-host's write override has no Meet gate
status: todo
type: bug
priority: normal
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-21T18:50:17Z
---

`SharedMeetingsResource` never calls `WriteTargetResolver.blocksMeet`. The Meet gate is answered once, against the Creator's resolved calendar, on the meeting-type page.

So a co-host can pick a write override whose calendar cannot mint Meet links on a type whose location is Google Meet. When that co-host is the organizer, `GoogleCalendarPort.retryWithoutConference` fires: the booking succeeds, `supportsMeet` flips false, and the invitee receives a booking labelled "Google Meet" with no join link.

The mechanism is pre-existing; the write override gives it a new and much more reachable trigger. Deliberately out of scope for the feature (the gate is the Creator's question by design) — this bean is about the invitee-facing outcome, not about moving the gate.

Found in the final whole-branch review of [[calit-bh5t]].

- [ ] Decide the product answer: warn the co-host at pick time, or tell the invitee, or both
- [ ] Cover whichever path with a test
