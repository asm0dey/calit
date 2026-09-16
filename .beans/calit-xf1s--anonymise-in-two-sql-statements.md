---
# calit-xf1s
title: Anonymise in two SQL statements
status: completed
type: task
created_at: 2026-09-16T15:26:30Z
updated_at: 2026-09-16T15:26:30Z
parent: calit-l3fk
---

Refactor PrivacyService.anonymise to two native SQL statements (UPDATE...RETURNING + data-modifying CTE delete) instead of Java load-mutate-flush plus three Panache deletes.

## Summary of Changes

- Replaced `PrivacyService.anonymise(Long)`'s Java load-mutate-flush body (plus `anonymiseRow` and
  three per-row Panache deletes) with a new `@Transactional int anonymise(Collection<Long>)` that
  runs exactly two native statements in one transaction:
  1. `UPDATE booking SET ... erased_at = now() WHERE id IN (:ids) AND erased_at IS NULL RETURNING id`
     — blanks every personal column (`PersonalData`'s "booking" entry: invitee_name, invitee_email,
     answers, meet_link, title, description), filtered so an already-erased row is untouched.
  2. A single statement of data-modifying CTEs (`booking_guest`, unsent `reminder`) plus a final
     `DELETE FROM email_outbox` — scoped to exactly the ids statement 1 returned, so dependents of an
     already-erased id are never re-deleted. Skipped entirely when statement 1 erased nothing.
  - Returns the count of newly-erased bookings (0 for empty input or an all-already-erased batch).
- `anonymise(Long bookingId)` kept its signature/return type; it now just resolves the booking's
  group (unchanged group semantics) and delegates to the collection overload.
- `eraseByManageToken` unchanged (still calls `anonymise(b.id)`).
- Constructor now also injects `EntityManager em` (matches the existing pattern in
  `TokenBackfill`/`ReminderScheduler`/`PendingExpiryScheduler`).
- Test fixture fix: `ErasureFixtures.seedPastBookingId()` used a fixed ~30-day-ago timestamp, so two
  calls in the same test raced into the `booking_no_overlap_held` exclusion constraint (same owner,
  overlapping [start,end) windows). Added a static `AtomicLong` offset (hours) so repeated calls
  produce non-overlapping past windows — first test in this codebase to seed two bookings in one
  test.
- New test `BookingErasureTest.anonymiseManyErasesAllAndReportsTheCount`: two separate past bookings,
  `anonymise(List.of(a, b))` returns 2 and erases both; a second call on the same ids returns 0.

### Testing

- `BookingErasureTest` (now 7 tests): PASS.
- `InviteeErasureDisabledTest` (2), `InviteeErasureRouteTest` (5), `BookingExportTest` (3),
  `BookingResourceTest` (8), `PersonalDataInventoryTest` (5): all PASS, unmodified.
- Full suite: `mvn test` → 1176/1176, 0 failures, 0 errors, BUILD SUCCESS.
- `mvn spotless:check`: clean.

### Notes / deviations

- No behavior change to `eraseByManageToken`'s group/Google/logging behavior — it was already
  minimal, so nothing to simplify there.
- The native-query `IN (:ids)` form was used (not `= ANY(:ids)`) per the brief's explicit allowance
  — simpler parameter binding for a `List<Long>` with Hibernate.
