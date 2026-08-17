---
# calit-8dqz
title: '404 on a known calendar: tolerate vs surface to the owner'
status: todo
type: task
priority: normal
created_at: 2026-08-17T08:28:10Z
updated_at: 2026-08-17T08:28:10Z
---

Split out of calit-rma2 at its close-out — it was the one item that branch deliberately did NOT settle.

Since PR #133 a booking stores the calendar its event was created on, so calit can finally tell the two 404s apart:
- the stored ref WAS used and Google still says 404 -> the event is really gone (someone deleted it by hand). Tolerating it is right.
- the stored ref was unusable and we fell back to the default write target -> we may be asking the wrong calendar. Tolerating it hides a real failure.

Today GoogleCalendarPort.deleteEvent logs which of the two happened (GoogleCalendarPort.java:300-307 logs `stored` vs `default-write-target`) and keeps 1.20.1's blanket tolerance for both. That was a conscious scope decision in the rma2 plan, not an oversight.

## Todo

- [ ] Watch the tolerated-delete INFO lines in the wild for a while — find out whether the `default-write-target` case actually occurs
- [ ] Decide whether the fallback case should surface to the owner (banner? email?) instead of being tolerated silently
- [ ] Same question for reschedule/updateDetails, which currently throw UncheckedIOException on 404 rather than tolerating
