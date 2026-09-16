---
# calit-xoyd
title: GDPR final fix wave
status: completed
type: task
priority: normal
created_at: 2026-09-16T19:42:53Z
updated_at: 2026-09-16T20:01:57Z
parent: calit-l3fk
---

Fix the whole-branch review findings: meeting-type/account deletion, retention throughput, owner mail tags, Google-failure erasure, partial group retention, admin delete confirm, small items

## Checklist

- [x] 1. Meeting-type deletion refuses with upcoming bookings; account deletion cancels owned upcoming bookings first
- [x] 2. Retention sweep loops batches within a time budget
- [x] 3. Owner mails (reset, invite, Google disconnected) tagged with owner
- [x] 4. Invitee erasure survives Google failures; Google ids cleared
- [x] 5. Group erasure/export after partial retention
- [x] 6. Admin account deletion confirm page
- [x] 7. Small items (token in 404 messages, secrets test, lock order, no-store, privacy facts count, MailHealth comment, docs audit line)
- [x] Docs updated; full suite green

## Summary of Changes

- Meeting-type delete refuses while any upcoming PENDING/CONFIRMED booking (any owner) uses the type; account deletion cancels upcoming bookings on the account's own types (groups included) through a new Google-tolerant cancel, in committed transactions before the atomic tombstone+delete, and keeps parked cancellation notices for other people.
- Retention sweep loops 200-row batches (own transaction each) until a short batch or a 5-minute budget.
- Password-reset, invite and Google-disconnected mails are tagged with the owner.
- Invitee erasure survives a failing Google delete (UNREACHABLE) and clears event refs on past bookings.
- Erasure/export of a partly retained group reach the remaining rows; 404 only when all are erased.
- Admin deletion of another account goes through a typed-username confirm page (EN/DE/HE).
- No bearer tokens in 404 messages; export routes send Cache-Control: no-store; stronger export secrets test; ordered admin lock; /privacy counts channels once; MailHealth comment fixed.
- Docs (docs/gdpr-compliance): operator guide, users-admin, meeting-types, changelog.
