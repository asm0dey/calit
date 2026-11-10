---
# calit-vhsy
title: Single-use booking links
status: completed
type: feature
priority: normal
tags:
    - competitor-parity
created_at: 2026-10-16T22:05:41Z
updated_at: 2026-11-10T08:59:15Z
parent: calit-0arg
---

Owner generates a link to a meeting type that dies after one booking (secret types today are reusable). Fits ADR 0009 capability URLs. Competitors: Calendly single-use links, Cal.com private links. Size: S.

Design: docs/superpowers/specs/2026-11-09-single-use-booking-invites-design.md
Plan: docs/superpowers/plans/2026-11-09-single-use-booking-invites.md

## Summary of Changes

Owners generate a one-shot `/{user}?invite={token}` URL from a meeting type's detail page in `/me`. It books once and expires after 30 days.

- `V39__booking_invite.sql`: stores only SHA-256 token hashes; owner and type foreign keys cascade.
- `BookingService.book()` deletes the invite in the booking transaction, so a failed booking leaves it usable and a committed one uses it up for good.
- `GET /{user}?invite=` renders the booking page with no slug and no preview card; new `POST /{user}?invite=` submits it. Any bad token shows one invalid-invite page.
- `booking_invite` is classified in `PersonalData`.

Docs: "Single-use invites" section with screenshots on the meeting types page and an `## Unreleased` changelog entry, on `docs-site` (373746ad, 61e7ad7b).
