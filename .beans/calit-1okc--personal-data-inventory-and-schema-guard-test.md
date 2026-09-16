---
# calit-1okc
title: Personal-data inventory and schema guard test
status: completed
type: task
priority: normal
created_at: 2026-09-16T13:28:25Z
updated_at: 2026-09-16T13:35:24Z
parent: calit-l3fk
---

PersonalData.java inventory + PersonalDataInventoryTest guard over live schema


## Summary of Changes

Created `src/main/java/site/asm0dey/calit/privacy/PersonalData.java` (hand-written inventory of all 19 live application tables, every column, and the subset classified personal) and `src/test/java/site/asm0dey/calit/privacy/PersonalDataInventoryTest.java` (the schema guard, verbatim from the brief).

Judgement calls beyond the brief's explicit skeleton:
- The brief's illustrative personalColumns used placeholder names that don't match the live schema (`email` -> `account_email` on google_credential, `calendar_id` -> `google_calendar_id` on google_calendar); corrected to the real column names.
- Classified the 7 'remaining owner-configuration tables' the brief named (meeting_type, meeting_type_host, booking_field, availability_rule, date_override, date_override_window, notification_channel_meeting_type): only meeting_type (name/description/location_detail) and booking_field (label) actually carry owner-written free text; the other 5 have no personal columns (Subject.NONE, EraseRoute.CASCADES, consistent with the guard's rule that NOT_PERSONAL/NONE is only forbidden when personalColumns is non-empty).
- Found one live table the brief never mentions: `meeting_type_duration` (ADR-0003 alternate bookable lengths) — numeric-only, no free text, classified Subject.NONE / EraseRoute.CASCADES (cascades via meeting_type_id -> meeting_type ON DELETE CASCADE).

## Test Results
`./mvnw -o test -Dtest=PersonalDataInventoryTest`: RED (compile error, PersonalData missing) -> GREEN (5/5 passing) after implementation.
Full suite: `./mvnw -o test` -> BUILD SUCCESS, 1154 tests, 0 failures, 0 errors.

Commit: (pending — recorded after commit)
