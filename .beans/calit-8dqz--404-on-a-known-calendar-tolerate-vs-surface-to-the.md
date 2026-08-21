---
# calit-8dqz
title: '404 on a known calendar: tolerate vs surface to the owner'
status: todo
type: task
priority: normal
created_at: 2026-08-17T08:28:10Z
updated_at: 2026-08-17T11:01:15Z
blocked_by:
    - calit-o69e
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

## Decision (2026-08-17)

**Q1 (does the default-write-target 404 actually occur?) is no longer a blocker.** calit-o69e
gives the two 404s different treatment on the strength of `addr.stored()` alone, so nothing
here waits on months of INFO lines. A stored-ref 404 is treated as 'really gone'; a
fallback-address 404 stays tolerated precisely because it does NOT prove the event is gone.

**Q3 (reschedule/updateDetails throw where delete tolerates) moved to calit-o69e** — it was
the one code-ready piece, and it settles the 500.

**Q2 (surface the fallback case to the owner?) — NO, not from here.** A banner on the
fallback 404 would fire on a case that may never occur, and would tell the owner something
they cannot act on. The genuinely unhandled problem is upstream and now lives in
calit-r8et: calit never learns an event was deleted in Google at all, so the booking stays
active and its slot stays blocked. Surfacing belongs there, driven by a reconcile that
knows the event is gone, not by whichever request happens to trip over a 404.

What remains in this bean: keep watching the tolerated-delete INFO lines to learn whether
the default-write-target case occurs at all. If it never fires, the fallback branch in
`writeAddress` is dead weight and can go. Pure observation — no code pending.

## Amendment (2026-08-17, ADR-0001)

Q2's forwarding pointer is dead: **calit-r8et was scrapped** by
`docs/adr/0001-google-event-is-a-mirror-of-the-booking.md`. Under that ADR there is no event
deletion to surface — a missing mirror is stale, not a cancellation — so "surface the fallback
case to the owner" is now a NO with no successor bean, not a NO that was deferred elsewhere.

What still stands here, unchanged: watch the tolerated-delete INFO lines
(`GoogleCalendarPort.java:300-307`) to learn whether the `default-write-target` case fires at all.
If it never does, the fallback branch in `writeAddress` is dead weight and can go — and so can
case 2 of calit-o69e, which exists only to protect it. Pure observation, no code pending.
