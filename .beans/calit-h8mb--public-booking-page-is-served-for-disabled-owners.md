---
# calit-h8mb
title: Public booking page is served for disabled owners
status: todo
type: bug
priority: normal
created_at: 2026-08-21T22:28:14Z
updated_at: 2026-08-21T22:28:14Z
---

`PublicResource.resolveOwner` (PublicResource.java:542-550) resolves `/{username}` with a plain `AppUser.findByUsername` and no `enabled` check, and `MeetingHosts.bookable` (MeetingHosts.java:52-56) returns true unconditionally for single-host types. So an account an admin has disabled still has a live, bookable public page: `EnabledUserAugmentor` stops them logging in, but nothing stops a stranger booking them.

Harmless-looking today only because a disabled account usually has no availability, so the page offers zero slots. That is luck, not a guard — a disabled account that HAD hours before being switched off is bookable right now, and every booking sends real email to someone who has left.

Found during the final review of the calit-sjwh branch: `V28__seed_default_availability.sql` would have handed Mon-Fri 09:00-18:00 to every owner without hours, disabled ones included, converting the latent gap into a live one. The migration now filters on `enabled`, which closes the new exposure but not the underlying one.

- [ ] Decide the response for a disabled owner's public page: 404, or a rendered 'not accepting bookings' page
- [ ] Guard `PublicResource.resolveOwner` (and the slug route) on `enabled`
- [ ] Check `MeetingHosts.bookable` and the co-host path for the same hole
- [ ] Test that a disabled owner's page and POST both refuse, including an owner disabled AFTER their hours were set
