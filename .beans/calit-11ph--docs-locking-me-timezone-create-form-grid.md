---
# calit-11ph
title: 'Docs: locking, /me timezone, create-form grid'
status: completed
type: task
created_at: 2026-08-22T16:26:19Z
updated_at: 2026-08-22T16:26:19Z
---

The 1.21.0 work reached the changelog but not the pages describing the behaviour. Closed that gap on `docs-site` (d3c87a6, deployed).

## Summary of Changes

- `usage/users-admin.md`: the bullet claimed a locked account merely "cannot log in". Added a **Locking an account** section — 404 on the landing page, the booking page and the JSON booking API; reschedule and detail edits blocked on existing manage links while cancel still works; a caution that existing bookings are not cancelled.
- `usage/availability.md`: timezone section only covered invitees and rule interpretation. Noted that the /me dashboard, approval queue and manage page all render in the owner's configured zone, and that the manage-page picker is a one-off override.
- `usage/availability.md`: noted the meeting-type create form uses the same working-hours grid as the edit page (#147).

Checked and left alone: `releases/upgrading.md` is version-agnostic; the per-meeting-type write calendar was already documented in `installation/google-oauth.md`; the availability seeding was already in `usage/availability.md` and `usage/first-run.md`; no docs page pins an image tag.
