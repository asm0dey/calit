---
# calit-64hy
title: Disconnecting an account leaves a half-address that 500s on reschedule
status: todo
type: bug
priority: normal
created_at: 2026-08-17T08:28:32Z
updated_at: 2026-08-17T10:53:09Z
blocked_by:
    - calit-o69e
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

## Decision (2026-08-17)

**Q1 — agreed: `writeAddress` should NOT gain a `findByGoogleId` fallback.** For a
genuinely disconnected account, discarding the stored calendar id is the only honest
outcome; the OAuth grant is gone.

**Q2 (the ambiguity) — this is what kills the fallback.** Two independent failures:

1. **The lookup is not unique.** `V9__google_multi_account.sql:16-18` deliberately dropped
   `UNIQUE (owner_id, google_calendar_id)` in favour of `UNIQUE (google_credential_id,
   google_calendar_id)`, because two connected accounts can each see and select the same
   SHARED calendar. So `(ownerId, googleCalendarId)` can match 2+ rows with different
   `google_credential_id`, and `GoogleCalendar.findByGoogleId` ends in `.firstResult()`
   (`GoogleCalendar.java:68`) with no ORDER BY — Postgres picks whichever, not necessarily
   the same one twice. Guessing wrong means writing through an account that may hold only
   read access on that shared calendar: 403, i.e. we trade a 500 for a different 500.
2. **The row is usually gone anyway.** `cred.delete()` cascades away that account's
   `google_calendar` rows (`GooglePageResource.java:216`, `ON DELETE CASCADE` from V9), so
   straight after a disconnect the lookup returns null and we fall back to the default write
   target regardless. The fallback would only ever pay off in the narrow RECONNECT case —
   owner reconnects and re-selects the same calendar, minting a new credential row.

Narrow payoff, real 403 risk, and `findByGoogleId` is the wrong helper for it (it hides
multiplicity behind `firstResult()`). **Verdict: don't re-resolve.** Keep falling through
to the default write target, as today.

**Q3 — the user-visible symptom is fixed in calit-o69e**, which turns the hard 500 on
reschedule / edit-details into a tolerate-or-recreate. That was the whole reason this bean
was worth opening, so once o69e lands there is nothing left here but the 'don't do it'
record above.
