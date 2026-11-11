---
# calit-23wn
title: Owner must RSVP yes to own approved Google event
status: in-progress
type: bug
priority: normal
created_at: 2026-11-11T14:31:07Z
updated_at: 2026-11-11T14:35:44Z
---

calit adds the owner's email to the Mirror event attendees with no responseStatus, so Google shows the owner as needsAction and they must click Yes after approving. Fix: GoogleCalendarPort marks the Creator's own attendee accepted on insert/patch.

- [x] Failing test
- [x] Fix in GoogleCalendarPort
- [x] Affected tests green
- [ ] Changelog Unreleased bullet on docs-site
