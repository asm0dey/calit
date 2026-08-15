---
# calit-1cov
title: Google Calendar failures are silent — add diagnostic logging
status: completed
type: bug
priority: high
created_at: 2026-08-15T18:43:29Z
updated_at: 2026-08-15T18:52:27Z
---

GH issue #98: user connects Google account fine (email stored), but /me/google shows
"Couldn't reach Google for one or more accounts" + "couldn't load — try reload" and
NOTHING appears in the container logs, so neither user nor maintainer can diagnose.

Root cause of the blackout: every failure on the Google path is caught fail-soft with no
log statement.

Silent swallow points:
- web/GooglePageResource.java:103 — listCalendars() RuntimeException -> loadError banner, no log
- web/GooglePageResource.java:147 — listCalendars() failure mid-save -> `continue`, no log
- google/GoogleTokenService.java:200 — refresh failed -> flags needsReconnect, no log
- google/GoogleTokenService.java:243/247 — probe INVALID_GRANT / TRANSIENT, no log

Likely underlying cause for #98 (invisible today): Google Calendar API not enabled in the
GCP project -> calendarList.list returns 403 GoogleJsonResponseException -> wrapped in
UncheckedIOException -> swallowed.

## Tasks
- [x] Log at WARN on GooglePageResource listCalendars failure (credentialId + owner + exception)
- [x] Log on save-selection skip path
- [x] Log in GoogleTokenService: refresh failure, probe invalid_grant, probe transient
- [x] Surface the Google HTTP status/error body in GoogleCalendarListPort's exception message
- [x] Never log tokens/secrets
- [ ] Tests green (mvn test)
- [x] Docs: how to read/raise Google log level (docs-site branch) + missing "enable Calendar API" step

## Notes

- Added GoogleConfigStartupLog (own bean): a StartupEvent observer on GoogleTokenService would be inherited by test stub subclasses, making them beans -> AmbiguousResolutionException across the suite.
- Docs also gained the missing "Enable the Google Calendar API" step — most likely root cause of #98.

## Summary of Changes

Commit 31d8d09 on main (code) + 5ca7ddc on docs-site (docs).

**Logging added (all secret-free):**
- `GooglePageResource` — WARN + stack on calendarList failure (owner + credential id), on page render and on the save-selection skip path.
- `GoogleTokenService` — WARN on refresh failure, WARN on probe invalid_grant, DEBUG on transient probe; `requestToken` errors now carry grant type + Google's error/error_description; INFO on exchangeCode reporting refreshToken=stored|MISSING.
- `GoogleCalendarListPort` — GoogleJsonResponseException caught separately so status + Google's message land on the first line.
- New `GoogleConfigStartupLog` — boot line with clientId, redirectUri, loginRedirectUri, scope; secret only as set|MISSING.

**Docs (docs-site):** missing 'Enable the Google Calendar API' step + Troubleshooting section mapping log lines to causes; .env.example documents QUARKUS_LOG_CATEGORY__SITE_ASM0DEY_CALIT_GOOGLE__LEVEL.

776 tests green, spotless applied. Not pushed yet.
