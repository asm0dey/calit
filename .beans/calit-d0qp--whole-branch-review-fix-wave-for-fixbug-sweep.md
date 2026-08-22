---
# calit-d0qp
title: Whole-branch review fix wave for fix/bug-sweep
status: in-progress
type: task
created_at: 2026-08-22T14:08:50Z
updated_at: 2026-08-22T14:08:50Z
---

Apply the 10-item fix wave from the whole-branch code review of fix/bug-sweep, before the branch becomes a PR.

Two merge blockers, both 'fix landed at the call site, not the invariant':
- [ ] Blocker 1 (calit-h8mb second path): POST /api/bookings resolves the username itself and skips the !owner.enabled guard added to PublicResource.resolveOwner. Public unauthenticated endpoint; MeetingHosts.bookable returns true for single-host types so nothing downstream saves it.
- [ ] Blocker 2 (calit-4whp second path): MeSetupResource:103 writes s.timezone unguarded. Move the invariant onto OwnerSettings (coerceZone + zoneIds) and route both writers through it; delete the two duplicate private zoneIds() helpers.

Minors:
- [ ] 3. Task4 x Task5 interaction test in AdminMeetGatingOverrideTest (locationType+writeCalendar in one POST)
- [ ] 4. Coverage for the two reworded i18n keys (revokeConfirm_count, removeConfirm_count)
- [ ] 5. Collapse duplicated fallback expression in Layout.java:74 (keep inverted-ternary protection)
- [ ] 6. Reword stale Layout.tzBar javadoc
- [ ] 7. Point thePickerDefaultsToTheZoneThePageWasAuthoredIn at a page with a picker
- [ ] 8. German adm_times_shown_in -> 'Alle Zeiten in {zone}'
- [ ] 9. Fix PublicDisabledOwnerTest POST field names (inviteeName/inviteeEmail)
- [ ] 10. Reopen/correct calit-h8mb and calit-4whp bean bodies

Out of scope by explicit decision: token-keyed reschedule path (PublicResource:474), Hebrew drift at adm_he:143/146, extracting the before/after comparison blocks.
