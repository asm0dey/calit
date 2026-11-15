---
# calit-wp29
title: 'Booking sets: test gaps from the per-task and final reviews'
status: todo
type: task
priority: low
created_at: 2026-11-15T15:32:57Z
updated_at: 2026-11-15T15:32:57Z
parent: calit-ku6n
---

Follow-up from the multi-slot reviews (feat/multi-slot-booking). Behaviour reads correct; these pin it.

- [ ] Co-host versions of the five set lifecycle cases (partial approval across Bookings, co-host declining Bookings it already approved)
- [ ] Co-host set: auto Mirror events once per group; compensation on a mid-set calendar failure; cancel-all cancels every host row and deletes the shared event once
- [ ] Exclusion-constraint (booking_no_overlap_held) path for sets, not only the app-level check
- [ ] Scheduler: set approved between candidate SELECT and lock is not expired; approve after a co-host declined is a no-op; channels.notifyDeclined fires exactly once
- [ ] Email: set Mirror re-sync on guest decline; repeat-click idempotence; distinct UIDs in the mail .ics; one mail per co-host; calendar-file phrase hidden when Google connected
- [ ] Owner form: same-day toggle absent when Off; non-numeric POST values; AdminMeetingTypeFormTest shared slugOfNewType field
- [ ] SetRules: dedupe-before-count, a/b/c, overflow, blank entry, same start different minutes, validate with null max
- [ ] Hygiene: unchecked cast in V40MigrationTest; countWhere concat comment; S107 suppression comment on bookGroup
