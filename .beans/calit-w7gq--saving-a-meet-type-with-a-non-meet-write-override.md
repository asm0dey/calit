---
# calit-w7gq
title: Saving a Meet type with a non-Meet write override returns a bare 400
status: todo
type: bug
priority: normal
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-21T18:50:17Z
---

`AdminResource.parseLocationType` throws `BadRequestException` when a meeting type is saved as Google Meet while its resolved write calendar cannot mint Meet links. `editMeetingType` catches only `IllegalStateException`, and no `ExceptionMapper` covers it — so the Host gets an empty, unlocalized 400 and loses the form they were filling in.

Pre-existing in kind, but the write-override feature adds a plausible new trigger: the location select and the calendar select now sit in the same form, so one save can set both into conflict.

Found in the final whole-branch review of [[calit-bh5t]].

- [ ] Route it through the localized `IllegalStateException` path `requireOwnedCalendar` already uses
- [ ] Add the `@Message` key with de and he values
- [ ] Assert the Host keeps their form input and sees the message
