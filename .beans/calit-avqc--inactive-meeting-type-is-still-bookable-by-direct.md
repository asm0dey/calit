---
# calit-avqc
title: Inactive meeting type is still bookable by direct link
status: completed
type: bug
priority: high
created_at: 2026-09-29T18:52:11Z
updated_at: 2026-09-29T19:23:14Z
---

Spec: UC-010 BR-007, UC-001 A1. active is checked only in listPublic/listPublicIncludingCohosted (MeetingType.java:115,178) and OgImageResource:87. findBySlug/resolveForAlias don't check it; PublicResource.resolveBookingTarget (:442-459) serves GET book and POST submitBooking; BookingService.book (:353-356) and JSON POST /bookings (BookingResource:61-64) likewise.

- [x] 404 when !type.active in resolveBookingTarget
- [x] Same guard in BookingService.book (covers JSON API)
- [x] Tests: GET page, form POST, JSON POST

## Summary of Changes

404 for inactive types in PublicResource.resolveBookingTarget (page + form POST) and BookingService.book (all writes, JSON API included). PublicInactiveTypeTest pins all three entry points plus the manage link staying live after deactivation.
