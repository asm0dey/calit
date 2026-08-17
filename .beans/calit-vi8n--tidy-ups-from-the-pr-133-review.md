---
# calit-vi8n
title: 'Tidy-ups from the PR #133 review'
status: todo
type: task
priority: low
created_at: 2026-08-17T08:28:32Z
updated_at: 2026-08-17T08:28:32Z
---

Cosmetic follow-ups the final review of PR #133 explicitly rated ship-as-is. No behaviour change, no urgency.

## Todo

- [ ] BookingService.java:437-440 and :525-528 hold the same 4-line address-stamping block modulo the receiver. A `private static void stampAddress(Booking row, CreatedEvent created)` would give the invariant "event id and address are set together" one home.
- [ ] Rename `cancelOfAPreMigrationRowPassesNoAddress` — it pins Task 1/3 behaviour (a null-address CreatedEvent does not get an address invented for it), not the cancel path its name implies. Something like `bookingWithNoReportedAddressStoresNone`.
