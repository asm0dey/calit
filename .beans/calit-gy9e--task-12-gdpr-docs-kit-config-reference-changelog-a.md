---
# calit-gy9e
title: 'Task 12: GDPR docs kit, config reference, changelog, ADR'
status: completed
type: task
priority: normal
created_at: 2026-09-16T19:06:35Z
updated_at: 2026-09-16T19:13:57Z
parent: calit-l3fk
---

Compliance docs pages on docs-site, config reference + changelog, README env vars, ADR and precedent records.

- [x] Operator guide, Art. 30 starter, sub-processors, DPA template, breach checklist, custom legal pages (docs-site)
- [x] Demote docs-site privacy.md to a reference copy and correct it
- [x] Compliance sidebar group
- [x] Config reference rows (INVITEE_ERASURE, BOOKING_RETENTION_DAYS, PRIVACY_POLICY_PATH, TERMS_PATH) + purge constants
- [x] Usage pages: invitee download/erase, account deletion
- [x] Changelog `## Unreleased` section (PR links are #TBD placeholders)
- [x] README privacy env vars
- [x] ADR 0010 + precedent records
- [x] bun run build green (links validated)

## Summary of Changes

docs-site branch (docs/gdpr-compliance): six new pages under compliance/, a Compliance sidebar
group, a Privacy section in the configuration reference, invitee erase/download and account
deletion in the usage pages, privacy.md demoted to a reference copy with the deletion/Google/purge
facts corrected, and an Unreleased changelog section. `bun run build` passes with link validation.

gdpr-compliance branch: README lists the four privacy env vars and the GDPR tooling;
docs/adr/0010 records the hand-written inventory + schema guard test decision. Precedent graph:
recorded the inventory decision and the no-global-GDPR-switch decision.

Hebrew issue step cancelled (Hebrew shipped in the branch). No push, no PR.
