---
# calit-wsdr
title: Index booking.google_credential_id for the disconnect path
status: todo
type: task
priority: low
created_at: 2026-08-17T08:28:32Z
updated_at: 2026-08-17T08:28:32Z
---

From the final review of PR #133 (Minor).

V26 added `booking.google_credential_id bigint REFERENCES google_credential(id) ON DELETE SET NULL`. Postgres does not auto-index the *referencing* side of a foreign key, so every `google_credential` delete — the account-disconnect path, GooglePageResource.java:219 — now triggers a sequential scan of `booking` to apply the SET NULL.

Harmless at this project's scale, but free to avoid.

## Todo

- [ ] Add `CREATE INDEX ON booking (google_credential_id) WHERE google_credential_id IS NOT NULL;` in a new V*.sql (V26 will be released by then — never edit an applied migration)
