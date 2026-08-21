---
# calit-ztty
title: createEvent's Google-failure retry path is untested
status: todo
type: task
priority: low
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-21T18:50:17Z
---

`GoogleCalendarPort:129` — the `return handleCreateFailure(e, cred, targetCalendar, event, createMeetLink);` line in the `GoogleJsonResponseException` catch — is the one meaningful line of new code from [[calit-bh5t]] left uncovered (new-code coverage was 111/113, 98.2%).

Reaching it needs a simulated Google API error, and the surrounding failure handling was already thin on coverage before that branch. Deliberately not fixed pre-merge rather than building error-injection scaffolding in a fix wave.

Note the WireMock MCP server is configured in `.mcp.json` and may make this cheaper than it looks.

- [ ] Decide the approach: WireMock, or a mocked client that throws GoogleJsonResponseException
- [ ] Cover the 404-on-write-calendar retry and the Meet-unsupported retry
