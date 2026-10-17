---
# calit-4air
title: Drop meeting_type_host.google_credential_id/google_calendar_id
status: todo
type: task
priority: low
created_at: 2026-10-17T11:38:45Z
updated_at: 2026-10-17T11:38:45Z
---

calit-5fw0 stopped mapping these columns but kept them for the previous release (backward-compatible migrations). Drop them with a new V*.sql in the release AFTER the one that ships calit-5fw0. Bundle with the V37 CalDAV compatibility-shim drop if timing matches.
