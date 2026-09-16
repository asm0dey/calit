---
# calit-l3fk
title: GDPR compliance
status: in-progress
type: epic
priority: normal
created_at: 2026-09-12T19:03:30Z
updated_at: 2026-09-16T15:42:43Z
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
- [ ] Invitee erasure + per-booking JSON export on the manage link (`INVITEE_ERASURE`, default true)
- [ ] Erasure boundary reporting (Google / channels / delivered mail) on confirm + done pages
- [x] Account deletion: self-serve in `/me/settings`, admin in `/me/users`, last-admin guard (calit-20wc)
- [ ] `GET /me/export` owner JSON export, secrets redacted
- [ ] `RetentionScheduler` + `BOOKING_RETENTION_DAYS` + per-owner override
- [ ] Hygiene purges: `email_outbox` 30d, reset/login tokens 24h past expiry
- [ ] `PrivacyFacts`-driven policy sections + `PRIVACY_POLICY_PATH` / `TERMS_PATH` overrides; fix the false deletion claim
- [ ] docs-site: operator guide, Art. 30 starter, sub-processors, DPA template, breach checklist, config reference
- [ ] German translations for all new copy; Hebrew tracked as a translation issue
