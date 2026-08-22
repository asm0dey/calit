---
# calit-ek26
title: cancelSingle leaves a stale googleEventId after cancelling
status: completed
type: bug
priority: low
created_at: 2026-08-16T10:07:39Z
updated_at: 2026-08-22T12:36:55Z
---

\`BookingService.cancelSingle\` (BookingService.java:1172-1178) sets the row to CANCELLED and deletes the Google event, but never clears \`booking.googleEventId\` (nor \`meetLink\`). The group path does the opposite — \`deleteGroupGoogleEvent\` nulls both fields right after the remote delete (BookingService.java:1194-1195).

So after a single-booking cancel the row keeps pointing at an event that no longer exists on Google. Nothing reads it today in a way that breaks, and since 1.20.1 a second delete of that id is tolerated (410/404 count as success), so the dangling reference has one less way to surface — which is exactly why it is worth cleaning up now rather than waiting for it to bite.

Found by the final review of the 410/404 fix (see calit-qjqb); explicitly out of scope there.

## Todo

- [x] Clear \`googleEventId\` and \`meetLink\` in \`cancelSingle\` after the remote delete, matching \`deleteGroupGoogleEvent\`
- [x] Decide whether the fields should also be cleared when \`isConnected\` is false (the group path clears them regardless — be consistent) — YES: cleared unconditionally in \`cancelSingle\`, mirroring \`deleteGroupGoogleEvent\`s rule that calit-side state must not be left dangling just because the remote call cannot be made
- [x] Test: cancelling a single booking leaves no event refs on the row

## Summary of Changes

`cancelSingle` now nulls `googleEventId`, `meetLink`, `googleCalendarId` and `googleCredentialId` right after cancelling, whether or not the remote delete could be made — matching `deleteGroupGoogleEvent`'s existing behaviour and javadoc. A cancelled single booking no longer keeps pointing at a Google event that no longer exists, closing the asymmetry between the single and group cancel paths.
