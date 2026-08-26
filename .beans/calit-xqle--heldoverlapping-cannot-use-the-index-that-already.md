---
# calit-xqle
title: heldOverlapping cannot use the index that already fits it
status: todo
type: task
priority: normal
created_at: 2026-08-26T08:54:09Z
updated_at: 2026-08-26T08:54:09Z
---

The busy-set lookup on the booking path reads **every held booking an owner has ever had** and filters by time in the heap, once per host per slot computation. The ideal index already exists and the query cannot use it.

## The query

`Booking.heldOverlapping` (`booking/Booking.java:128`):

```
ownerId = ?1 and status in ?2 and startUtc < ?3 and ?4 < endUtc
```

Called from `BookingService.busyIntervals` → `hostFreeSlots`, so once per host on every public booking page render, every reschedule page, and every `assertSlotAvailable`.

## Why the existing index does not serve it

`V22__owner_scope_no_overlap.sql` re-scoped the exclusion constraint to:

```sql
EXCLUDE USING gist (owner_id WITH =, tstzrange(start_utc, end_utc) WITH &&)
WHERE (status IN ('PENDING','CONFIRMED'))
```

That GiST index is exactly the right shape — owner equality, time overlap, already filtered to held rows. But the query expresses overlap as two independent btree comparisons on the base columns, which the planner cannot match to an index over `tstzrange(start_utc, end_utc)`. So it falls back to `idx_booking_owner_status (owner_id, status)`, which narrows to owner+held and then filters time by reading rows.

## Why that matters more over time

"Held" is `PENDING` or `CONFIRMED`, and a completed booking is never moved out of `CONFIRMED` — only cancelling or declining changes the status. So the set this scan walks grows with the owner's **lifetime** booking count, while the useful answer is bounded by the 60-day horizon. A long-lived instance degrades on its busiest owner first, which is the one that matters.

## Two ways out

1. **Add `(owner_id, status, start_utc)`.** One migration, no code change; the planner can then bound the scan by `start_utc < to` instead of reading everything. Does not exploit the range as directly as option 2 but is trivially safe.
2. **Rewrite the query as a range overlap** so the existing GiST index serves it: `tstzrange(start_utc, end_utc) && tstzrange(?, ?)`. Zero new indexes, and it expresses the intent directly rather than as two inequalities. Needs a native query — HQL cannot spell `&&` — so it costs some Panache idiom.

Option 2 is the more satisfying answer and adds no index to maintain; option 1 is the smaller diff. Worth measuring before choosing.

## Measure first

None of this is worth doing on a hunch. Seed an owner with a few thousand held bookings spread over a couple of years, then `EXPLAIN (ANALYZE, BUFFERS)` the query as it stands, with option 1's index, and with option 2's rewrite. If the difference is small at realistic volumes, record that and close this — a self-hosted instance for one person may never reach the point where it matters.

## Also worth a look while in here

- `idx_booking_status_start (status, start_utc)` and `idx_booking_owner_status (owner_id, status)` overlap in purpose. If option 1 lands, check whether one of them becomes redundant rather than leaving three indexes covering the same column pairs.
- The scheduler queries (`ReminderScheduler`, `PendingExpiryScheduler`) also filter on time and status; they have their own partial indexes, so confirm this change does not make one of those worse.

## Todo

- [ ] Seed a realistic dataset and EXPLAIN ANALYZE the three variants
- [ ] Choose, and record why in the bean rather than only in the migration
- [ ] Implement, with the EXPLAIN output before/after in the summary
- [ ] Re-check the redundant-index question
