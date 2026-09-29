---
# calit-wsab
title: In-app approve/decline don't require PENDING
status: completed
type: bug
priority: high
created_at: 2026-09-29T18:52:11Z
updated_at: 2026-09-29T19:24:52Z
---

Spec: UC-014 BR-006. AdminResource.approveBooking/declineBooking (:2238-2260) check ownership only. BookingService.approve (:837) guards only group rows; a CANCELLED/DECLINED single booking becomes CONFIRMED, creates a Google event, fires BookingApproved. decline (:874) guards only already-DECLINED group rows, so CONFIRMED/CANCELLED bookings (single, and cancelled groups) flip to DECLINED. Email link (actFromEmail :2288) already checks.

- [x] approve/decline: no-op or refuse when status != PENDING, single and group
- [x] Tests

## Summary of Changes

BookingService.approve/decline now return early unless the clicked row is PENDING, replacing the group-only guards. Single-host double-approve no longer creates a second Google event. A host who already approved a still-pending group can no longer decline their own row (the other host can). Tests in ApproveDeclineTest and GroupApprovalTest.
