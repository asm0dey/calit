---
# calit-aybq
title: Refresh stale docs screenshots (slot-grid drift + note)
status: completed
type: task
priority: normal
created_at: 2026-09-05T10:37:43Z
updated_at: 2026-09-05T10:46:21Z
---

Several docs-site images predate the booking-page slot-grid redesign (stacked full-width buttons instead of today's 4-column grid); manage-booking.png still shows footer version 1.14.1. Separately, the marketing shots do not show the new per-type note (PR #172).

## Todos

- [x] Seeded demo: Alex Rivera (Europe/Berlin), three types, two bookings, plus co-host Pasha (10:00-16:00) on Intro Call
- [x] product-landing.png
- [x] product-booking.png
- [x] manage-booking.png
- [x] multi-host-public-booking-page.png (via the co-host's username, intersected slots visible)
- [x] Pushed to docs-site (8352ba6, 974da11); Pages deploys green, live bytes verified
- [x] Audited by last-changed date; also refreshed availability.png + product-dashboard.png

## Summary of Changes

Six images refreshed across two pushes to `docs-site`:

| Image | Why |
|---|---|
| `product-booking.png` | old single-column slot list; now shows the note |
| `product-landing.png` | current layout, but no note on any card |
| `manage-booking.png` | stacked full-width slots, footer read 1.14.1 |
| `multi-host-public-booking-page.png` | stacked full-width slots |
| `availability.png` | every day empty (no frames), predates "Remove availability" |
| `product-dashboard.png` | listed bookings as raw `2026-06-12T08:00:00Z UTC`, no Manage link |

Deliberately NOT replaced:

- `product-confirmation.png` — a fresh capture renders "Google Meet:" with an empty value because
  the demo has no connected Google account, which is worse than the existing shot.
- `booking-fields.png` — recaptured and compared; identical apart from card width and the footer.

Audit method: `git log -1` per file on `docs-site`. The June-13/14 batch is the oldest and the most
suspect. Still unchecked from it: `date-overrides`, `users-admin`, `setup-wizard` (needs a fresh DB),
`google-connect` (needs a real Google account), `forgot-password`, `reset-password`.

Seeding notes for next time: `app_user` needs `roles` ('user') and `created_at` set when inserted by
SQL; a co-host is just a `meeting_type_host` row with `status='ACCEPTED'`, `role='COHOST'`, which
skips the consent-email flow entirely.
