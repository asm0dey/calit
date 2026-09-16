---
# calit-l3fk
title: GDPR compliance
status: completed
type: epic
priority: normal
created_at: 2026-09-12T19:03:30Z
updated_at: 2026-09-16T21:01:42Z
---

Design + implement what calit needs so a deployment can be GDPR-compliant. Brainstorming in progress; scope TBD.

## Design

Spec: `docs/superpowers/specs/2026-09-12-gdpr-compliance-design.md`

Scope: operator-enabling features + a docs compliance kit. calit is self-hosted; the
operator is the controller. No global "GDPR mode" switch — territorial scope follows the
data subject, so a switch would remove the tools without removing the obligation.

## Planned children

- [x] V34 migration (calit-gfl7): `booking.erased_at`, `owner_settings.booking_retention_days`, `email_outbox.booking_id`/`owner_id`, explicit cascade on `booking.meeting_type_id`
- [x] `privacy/PersonalData` inventory + `PersonalDataInventoryTest` schema guard (calit-1okc)
- [x] Invitee erasure + per-booking JSON export on the manage link (`INVITEE_ERASURE`, default true)
- [x] Erasure boundary reporting (Google / channels / delivered mail) on confirm + done pages
- [x] Account deletion: self-serve in `/me/settings`, admin in `/me/users`, last-admin guard (calit-20wc)
- [x] `GET /me/export` owner JSON export, secrets redacted
- [x] `RetentionScheduler` + `BOOKING_RETENTION_DAYS` + per-owner override
- [x] Hygiene purges: `email_outbox` 30d, reset/login tokens 24h past expiry
- [x] `PrivacyFacts`-driven policy sections + `PRIVACY_POLICY_PATH` / `TERMS_PATH` overrides; fix the false deletion claim
- [x] docs-site: operator guide, Art. 30 starter, sub-processors, DPA template, breach checklist, config reference
- [x] German translations for all new copy; Hebrew tracked as a translation issue

## Summary of Changes

Shipped in #223 (code, squash `ebdbd303`) and #224 (docs on `docs-site`, squash `33811b9f`).

- V34 (erasure marker, per-host retention, outbox booking/owner links, explicit booking→meeting type cascade) and V35 (deleted-username tombstones against stale-cookie takeover).
- `PersonalData` inventory with an `information_schema` guard test.
- Invitee `/booking/{token}/data` and `/erase`: cancel-first, group-wide, two-statement anonymise, per-destination report, 404 on every token route afterwards; pages say bookings aren't linked by email.
- Owner `/me/export` (one aggregated query), `/me/settings/delete` with re-auth, admin `/me/users/{id}/delete` with a confirm page; account deletion cancels upcoming bookings of owned types first; meeting-type delete refused while upcoming bookings exist.
- `RetentionScheduler` (per-host window, capped at 36500 days, batched until drained) and `PurgeScheduler` (queued mail ~30 days, auth tokens ~1 day), both multi-replica safe.
- `/privacy` rendered from deployment facts; `PRIVACY_POLICY_PATH` / `TERMS_PATH` overrides.
- English, German and Hebrew for every new UI string; operator compliance kit on the docs site.
- 1251 tests green; Sonar 0 new issues.
