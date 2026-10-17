---
# calit-4air
title: Drop V37/V38-era compatibility shims
status: todo
type: task
priority: low
created_at: 2026-10-17T11:38:45Z
updated_at: 2026-10-17T15:12:32Z
---

BLOCKED until the release containing the CalDAV work (V37/V38, calit-yotg) and calit-5fw0 is tagged. The previous release must keep running against the new schema during rolling deploys (ADR-0014), so do not drop anything before that tag exists.

In the release AFTER it, one new V*.sql drops:
- the `google_credential` and `google_calendar` compatibility views
- old `booking` / `meeting_type` google_* columns and their paired-column compat triggers
- `meeting_type_host.google_credential_id` / `google_calendar_id` (calit-5fw0 stopped mapping them; this bean absorbs the former scope of this task)

Then remove the compat-view tests.
