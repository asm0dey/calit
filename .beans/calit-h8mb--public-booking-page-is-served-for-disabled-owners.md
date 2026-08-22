---
# calit-h8mb
title: Public booking page is served for disabled owners
status: completed
type: bug
priority: normal
created_at: 2026-08-21T22:28:14Z
updated_at: 2026-08-22T14:13:34Z
---

`PublicResource.resolveOwner` (PublicResource.java:542-550) resolves `/{username}` with a plain `AppUser.findByUsername` and no `enabled` check, and `MeetingHosts.bookable` (MeetingHosts.java:52-56) returns true unconditionally for single-host types. So an account an admin has disabled still has a live, bookable public page: `EnabledUserAugmentor` stops them logging in, but nothing stops a stranger booking them.

Harmless-looking today only because a disabled account usually has no availability, so the page offers zero slots. That is luck, not a guard — a disabled account that HAD hours before being switched off is bookable right now, and every booking sends real email to someone who has left.

Found during the final review of the calit-sjwh branch: `V28__seed_default_availability.sql` would have handed Mon-Fri 09:00-18:00 to every owner without hours, disabled ones included, converting the latent gap into a live one. The migration now filters on `enabled`, which closes the new exposure but not the underlying one.

- [x] Decide the response for a disabled owner's public page: 404, or a rendered 'not accepting bookings' page — chose 404: the route already 404s an unknown username, so this needs no template and no three-locale message, and it does not tell a stranger probing usernames which accounts exist but are switched off.
- [x] Guard `PublicResource.resolveOwner` (and the slug route) on `enabled` — resolveOwner is the one method both `userLanding` and `resolveBookingTarget` (shared by the booking GET and POST) call, so one guard covers all three entry points. The check is 404 before `currentOwner.set`, so a disabled owner is never bound to the request.
- [x] Check `MeetingHosts.bookable` and the co-host path for the same hole — already covered: `addCohost` ensures a CREATOR row (MeetingHosts.java:196) and `bookable` checks `u.enabled` for every host row (:63-66), so a disabled creator yields hostPending. Single-host types are only reachable via their own owner's username, which `resolveOwner` now guards. Pinned by test, not changed.
- [x] Test that a disabled owner's page and POST both refuse, including an owner disabled AFTER their hours were set — `PublicDisabledOwnerTest` seeds an owner with hours + settings + a type, disables the account, and asserts 404 on the landing GET, booking-page GET, and booking POST. A pin in `CohostManageTest` confirms the co-host path (a disabled CREATOR reached via an enabled co-host's alias) already yielded hostPending before this change.

## Summary of Changes

Guarded `PublicResource.resolveOwner` (the single method both `userLanding` and `resolveBookingTarget`, shared by the booking GET/POST, call) to 404 when `!owner.enabled`, checked before `currentOwner.set`. Added `PublicDisabledOwnerTest` covering all three public entry points (landing GET, booking GET, booking POST) for an owner disabled after their hours were set, and a pin test in `CohostManageTest` confirming (and proven to pass before this change too) that the multi-host co-host path was already covered by `MeetingHosts.bookable`'s per-host `enabled` check.


## Second path found by the whole-branch review — now closed

The fix above landed at the bug's **reported** call site (`PublicResource.resolveOwner`), not at
the invariant. The whole-branch review, run as the last gate before this branch became a PR,
found a second reachable path that the guard never covered:

`POST /api/bookings` (`BookingResource.create`) resolves the username **itself** —
`AppUser.findByUsername(Usernames.normalize(req.user()))` — and never routes through
`resolveOwner`, so the `!owner.enabled` check did not apply. `application.properties` lists only
`/me`, `/me/*`, `/api/google` and `/api/google/*` as authenticated paths, so this is a public,
unauthenticated endpoint.

Nothing downstream saved it: the "already covered" note above is correct only for MULTI-host
types. `MeetingHosts.bookable` returns `true` unconditionally for a single-host type, and a
single-host type is exactly what a departed owner has. A plain JSON POST therefore still booked
the disabled owner, still wrote to their calendar, and still emailed them.

Demonstrated red: `PublicDisabledOwnerTest.apiBookingPostIs404` with a slot inside the type's
60-day horizon returned **201 Created** before the fix.

Closed by adding the same guard to `BookingResource.create` (`owner == null || !owner.enabled`),
with a comment pointing back at `resolveOwner` and naming the `MeetingHosts.bookable` gap so the
next reader does not repeat the inference. The bean stays `completed` — the bug genuinely is now,
across both entry points.
