---
# calit-75vf
title: adm_detail_write_calendar_moved has no plural form
status: todo
type: bug
priority: low
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-21T18:50:17Z
---

The notice reads "1 upcoming bookings stay on the calendar they were created on" when the count is 1. Ships in en, de and he.

The codebase does no i18n pluralization anywhere, so this is a codebase-wide gap rather than a defect of [[calit-bh5t]] — but that feature ships a new, user-visible instance of it in three locales.

- [ ] Decide whether to add a pluralization mechanism or reword the message to read correctly at any count (e.g. "upcoming bookings that stay: 1")
- [ ] Apply the same treatment to any other counted message
