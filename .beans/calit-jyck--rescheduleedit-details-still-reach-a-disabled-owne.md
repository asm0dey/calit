---
# calit-jyck
title: Reschedule/edit-details still reach a disabled owner's calendar
status: todo
type: bug
priority: normal
created_at: 2026-08-22T14:33:36Z
updated_at: 2026-08-22T14:33:36Z
---

Found by the whole-branch review of the `fix/bug-sweep` branch, and explicitly scoped OUT of it.

`calit-h8mb` closed the two ways a STRANGER can reach a disabled owner: `PublicResource.resolveOwner`
(the landing page and the booking GET/POST) and `BookingResource.create` (the public, unauthenticated
`POST /api/bookings`). Both now 404.

The token-keyed routes are a third class and are still open. `/booking/{manageToken}/reschedule`
(`PublicResource.java:474`) resolves the booking by its manage token, not by username, so neither
guard applies. An invitee holding a link from before the owner was disabled can still put a NEW time
on that departed owner's calendar and trigger a fresh notification to them.

Cancel must obviously stay open — an invitee must always be able to cancel. The question is only
about routes that CREATE new calendar state.

## Todo

- [ ] Decide which token-keyed routes should refuse for a disabled owner: reschedule certainly,
      edit-details probably, cancel never
- [ ] Decide the response shape — 404 is wrong here (the invitee holds a legitimate token and knows
      the booking exists); a rendered "this host is no longer taking bookings" page is the honest answer
- [ ] Guard the chosen routes and cover each with a test
