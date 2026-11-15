---
# calit-ku6n
title: 'Multi-slot booking: booking sets and proposals'
status: in-progress
type: feature
priority: normal
created_at: 2026-11-15T11:40:32Z
updated_at: 2026-11-15T20:15:56Z
---

Spec: docs/superpowers/specs/2026-11-15-multi-slot-booking-design.md

An Owner lets an Invitee pick several slots of one meeting type in one flow. Two modes:
- Booking set: min..max or exactly N Bookings (cap 5), booked/approved/expired as one.
- Proposal: Invitee offers up to N times, any Host confirms one; nothing held while waiting.

- [x] Design approved
- [x] Implementation plan (sets: docs/superpowers/plans/2026-11-15-multi-slot-booking-sets.md; proposals: own plan after sets merge)
- [x] Booking sets
- [ ] Proposal mode
- [x] Booking sets: docs-site page + Unreleased changelog (#278)
- [ ] Proposal mode: docs + changelog
