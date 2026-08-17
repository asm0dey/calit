---
# calit-64hy
title: Disconnecting an account leaves a half-address that 500s on reschedule
status: todo
type: bug
priority: normal
created_at: 2026-08-17T08:28:32Z
updated_at: 2026-08-17T08:28:32Z
---

From the final review of PR #133 (Minor, but with a user-visible symptom).

When a Google credential row is deleted (account disconnect), `ON DELETE SET NULL` clears `booking.google_credential_id` but leaves `booking.google_calendar_id` populated. `GoogleCalendarPort.writeAddress` requires BOTH to be non-null (GoogleCalendarPort.java:343), so the surviving calendar id is discarded and the write falls back to the remaining account's write target.

Not a regression — it is exactly the pre-#133 behaviour, and for a genuinely disconnected account it is the only honest outcome (the OAuth grant is gone). The improvable sub-case is a *reconnect*: if the same calendar is still selected under a new credential row, `GoogleCalendar.findByGoogleId(ownerId, googleCalendarId)` (GoogleCalendar.java:68) could re-resolve the credential.

Why it is worth fixing: the observable symptom for such a booking is a hard 500 on reschedule / edit-details (UncheckedIOException on the 404), not a soft skip like cancel gets.

Caveat noted in calit-rma2: this re-resolution is unreliable in general — the GoogleCalendar row may be gone, and a shared calendar can match two rows.

## Todo

- [ ] Decide whether writeAddress should fall back to findByGoogleId when only the credential id is missing
- [ ] Handle the ambiguous case (calendar id matching more than one credential) explicitly rather than picking one
- [ ] Either way, make reschedule/updateDetails degrade instead of 500ing — see calit-8dqz
