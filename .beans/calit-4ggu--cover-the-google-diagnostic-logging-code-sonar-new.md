---
# calit-4ggu
title: Cover the Google diagnostic-logging code (Sonar new coverage 59.3%)
status: completed
type: task
priority: normal
created_at: 2026-08-15T19:37:55Z
updated_at: 2026-08-15T20:04:39Z
---

Commit de1fa46 added 40 new lines; 13 uncovered -> Sonar new-code coverage 59.26% (project 81.1%).

Uncovered: GoogleCalendarListPort 41,44-48 (6); GoogleTokenService 326,333,337 (3); GoogleConfigStartupLog 34-35 (2); GooglePageResource 109,153 (2).

Plan: docs/superpowers/plans/2026-08-15-google-logging-test-coverage.md

## Tasks
- [x] Task 1: GooglePageResource fail-soft path tests (banner + preserved rows)
- [x] Task 2: GoogleCalendarListPortTest (403 mapping, plain Mockito)
- [x] Task 3: GoogleConfigStartupLogTest (degraded vs configured branch)
- [x] Task 4: transport() seam + GoogleTokenServiceRequestTokenTest via MockHttpTransport
- [x] Task 5: full suite + spotless + push + re-read Sonar measures

## Summary of Changes

11 tests added across 4 files; one production change (protected HttpTransport transport() seam on GoogleTokenService, behaviour-preserving).

- GooglePageResourceTest: Google-unreachable path on render + save (banner shown, saved rows and write target preserved).
- GoogleCalendarListPortTest (new, plain Mockito): 403 SERVICE_DISABLED and plain IOException mapping.
- GoogleConfigStartupLogTest (new): degraded vs configured branch, asserted via verify(never()).
- GoogleTokenServiceRequestTokenTest (new): real requestToken driven through MockHttpTransport — invalid_grant, 400+invalid_client, 503+invalid_grant, I/O failure, happy path. Both operands of the `400 && invalid_grant` guard now pinned.

Full suite 787/787 green (was 776). spotless:check clean. Final whole-branch review (opus): READY TO MERGE, no Critical/Important.
