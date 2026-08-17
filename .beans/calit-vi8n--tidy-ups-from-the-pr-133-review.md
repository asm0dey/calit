---
# calit-vi8n
title: 'Tidy-ups from the PR #133 review'
status: todo
type: task
priority: low
created_at: 2026-08-17T08:28:32Z
updated_at: 2026-08-17T09:41:00Z
---

Cosmetic follow-ups the final review of PR #133 explicitly rated ship-as-is. No behaviour change, no urgency.

## Todo

- [ ] BookingService.java:437-440 and :525-528 hold the same 4-line address-stamping block modulo the receiver. A `private static void stampAddress(Booking row, CreatedEvent created)` would give the invariant "event id and address are set together" one home.
- [ ] Rename `cancelOfAPreMigrationRowPassesNoAddress` — it pins Task 1/3 behaviour (a null-address CreatedEvent does not get an address invented for it), not the cancel path its name implies. Something like `bookingWithNoReportedAddressStoresNone`.

Residual test coverage named by the final review of PR #133 — production risk today is zero (both sites are correct as written), this is regression insurance on two one-line expressions identical in shape to two now proven:

- [x] **declineGuest (BookingService.java:1248)** — DONE in commit 2211245 (closing the Sonar coverage gate). Review verdict: the ref half discriminates; the owner-id half provably cannot, since persistGuests sets g.ownerId = booking.ownerId (BookingService.java:473, :1121), so there is no state in which they diverge. — prioritise this one: it is the only write site where the owner id and the ref come from *different objects* (`guest.ownerId` + `booking.calendarRef()`), which makes it the most plausible thing for a future reader to "fix" wrongly while reasoning about owner scoping. Test: book -> seed a guest -> `declineGuest(token)` -> `verify(calendarPort).updateEvent(eq(ownerId), eq(ref), eq(eventId), any(), any(), any())`.
- [x] **group details (BookingService.java:1040)** — DONE in commit 2211245. The any() -> eq(ref) tightening also proves the group-creation persist round-trips, since updateGroupDetails reads the ref back off the DB row and BookingService:437-440 is its only writer. — in `GroupEditDetailsTest`, stub the group's `createEvent` with a real `CalendarRef` instead of `null` and tighten the existing `updateEventDetails(eq(1L), any(), eq("grp-evt"), ...)` verification to `eq(ref)`. The multi-host fixture already exists there; a two-line edit, not a new test.

Both are still matched with `any()` in the CalendarRef position, so reverting either expression to `null` would leave the suite green.
