---
# calit-zpj6
title: 'Per-meeting-type toggle to hide the Guests field on the booking page (GH #130)'
status: todo
type: feature
created_at: 2026-08-22T16:37:37Z
updated_at: 2026-08-22T16:37:37Z
---

Upstream: https://github.com/asm0dey/calit/issues/130 (reporter @h200101)

Ask: a per-meeting-type option that hides the "Guests" field from the public booking form,
so a booking is as few keystrokes as possible. Use case: 1:1 sessions (therapy) where extra
attendees never apply.

## Touch points

- `domain/MeetingType.java` — new `hide_guests boolean not null default false`
- new `src/main/resources/db/migration/V29__*.sql` (pick the next free V number at implementation
  time; never edit an applied migration)
- `templates/PublicResource/book.html:89` — `{#include PublicResource/_guestschips ...}`, guard it
- `templates/PublicResource/manage.html` — booker's manage/edit page also edits guests; hide there too
- `booking/BookingService.java` — server side must IGNORE submitted guests when the type hides them,
  not just hide the input. A hidden field is not validation.
- `web/AdminResource.java` + meeting-type edit template — the toggle
- `i18n/AppMessages`/`AdminMessages` — label + de/he values in `messages/{msg,adm}_{de,he}.properties`

## Todos

- [ ] Migration adding `meeting_type.hide_guests` (default false, so existing types unchanged)
- [ ] `MeetingType.hideGuests` field
- [ ] Admin meeting-type form: checkbox + persist
- [ ] `book.html`: skip the guests chips include when hidden
- [ ] `manage.html`: skip guest editing when the type hides guests
- [ ] `BookingService`: drop any guests posted for a hide-guests type (defence in depth)
- [ ] i18n: new label with de + he translations (no English fallback)
- [ ] Tests: booking page has no guests input when hidden; posting guests anyway creates a booking with zero guests
- [ ] Docs: `docs-site` usage page + `## Unreleased` changelog bullet at merge
