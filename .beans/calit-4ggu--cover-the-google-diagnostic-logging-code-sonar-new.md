---
# calit-4ggu
title: Cover the Google diagnostic-logging code (Sonar new coverage 59.3%)
status: in-progress
type: task
priority: normal
created_at: 2026-08-15T19:37:55Z
updated_at: 2026-08-15T19:48:38Z
---

Commit de1fa46 added 40 new lines; 13 uncovered -> Sonar new-code coverage 59.26% (project 81.1%).

Uncovered: GoogleCalendarListPort 41,44-48 (6); GoogleTokenService 326,333,337 (3); GoogleConfigStartupLog 34-35 (2); GooglePageResource 109,153 (2).

Plan: docs/superpowers/plans/2026-08-15-google-logging-test-coverage.md

## Tasks
- [x] Task 1: GooglePageResource fail-soft path tests (banner + preserved rows)
- [x] Task 2: GoogleCalendarListPortTest (403 mapping, plain Mockito)
- [x] Task 3: GoogleConfigStartupLogTest (degraded vs configured branch)
- [ ] Task 4: transport() seam + GoogleTokenServiceRequestTokenTest via MockHttpTransport
- [ ] Task 5: full suite + spotless + push + re-read Sonar measures
