---
# calit-23wn
title: Owner must RSVP yes to own approved Google event
status: completed
type: bug
priority: normal
created_at: 2026-11-11T14:31:07Z
updated_at: 2026-11-11T15:22:07Z
---

calit adds the owner's email to the Mirror event attendees with no responseStatus, so Google shows the owner as needsAction and they must click Yes after approving. Fix: GoogleCalendarPort marks the Creator's own attendee accepted on insert/patch.

- [x] Failing test
- [x] Fix in GoogleCalendarPort
- [x] Affected tests green
- [x] Changelog Unreleased bullet on docs-site

## Summary of Changes

GoogleCalendarPort pre-accepts the owner's own attendee on insert and on both patches (PR #268). Also: mise postinstall runs bun install (git hooks), with Windows variants for the hook and Maven tasks. Changelog bullet on docs-site.
