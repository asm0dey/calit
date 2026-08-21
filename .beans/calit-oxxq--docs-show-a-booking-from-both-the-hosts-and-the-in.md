---
# calit-oxxq
title: 'Docs: show a booking from both the host''s and the invitee''s timezone'
status: todo
type: task
priority: normal
created_at: 2026-08-21T18:44:13Z
updated_at: 2026-08-21T18:44:13Z
blocked_by:
    - calit-mhgs
---

`usage/availability.md:54` ("Timezone handling") claims invitees see slots in their own local timezone while the owner's rules are interpreted in theirs — the one behaviour where a picture beats prose, and it currently has no screenshot.

Add a pair: the same meeting shown from the invitee's public booking page and from the host's `/me` view, in two different zones, so the conversion is visible rather than asserted.

**Blocked on [[calit-mhgs]].** Today `/me` dashboard and `/me/pending` render in the owner's STORED zone while `/me/bookings/{id}/manage` renders the BROWSER-DETECTED one — the same booking reads 15:00 on one page and 22:00 one click away. Shooting the host side now would enshrine that disagreement in the docs and make it look deliberate. Fix the display first, then capture.

Procedure is Task 12 of `docs/superpowers/plans/2026-06-13-docs-site.md` — Playwright MCP, 1440x960 viewport, crop to the relevant region.

- [ ] Land calit-mhgs so the host-side clock is consistent across /me pages
- [ ] Capture the invitee's booking page in one zone and the host's view of the same booking in another
- [ ] Embed both in the Timezone handling section with alt text naming which perspective is which
