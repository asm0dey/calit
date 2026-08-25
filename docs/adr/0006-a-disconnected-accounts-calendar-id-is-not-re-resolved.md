# A disconnected account's calendar id is not re-resolved to another credential

Disconnecting a connected account nulls `booking.google_credential_id` (`ON DELETE SET NULL`) and
leaves `booking.google_calendar_id` populated. `GoogleCalendarPort.writeAddress` requires both, so
that surviving calendar id is discarded and the write falls back to the Host's write target. calit
does **not** try to recover the address by looking the calendar id up under a different credential.

## Considered options

**Re-resolve via `GoogleCalendar.findByGoogleId(ownerId, googleCalendarId)`** — recover the stored
calendar when the Host has reconnected and re-selected it under a new credential row. Rejected on
three counts:

- *The lookup is not unique.* `V9__google_multi_account.sql` deliberately dropped
  `UNIQUE (owner_id, google_calendar_id)` in favour of `UNIQUE (google_credential_id,
  google_calendar_id)`, because two connected accounts can each see and select the same **shared**
  calendar. `findByGoogleId` ends in `.firstResult()` with no `ORDER BY`, so it hides that
  multiplicity and Postgres may not even pick the same row twice.
- *Guessing wrong is not a soft failure.* The account it picks may hold only read access on a shared
  calendar, so the write 403s — trading one 500 for another.
- *The row is usually gone anyway.* Deleting a credential cascades away that account's
  `google_calendar` rows, so straight after a disconnect the lookup returns null and the write falls
  back regardless. The re-resolution would only ever pay off in the narrow reconnect-and-re-select
  case.

**Store a stable per-owner calendar identity** — a local id that survives re-selection under a new
credential. Rejected here as out of proportion: it re-opens ADR-0004's identity decision to serve
one recovery case, and it still cannot say which of two accounts sharing a calendar should write.

## Consequences

- For a genuinely disconnected account, discarding the calendar id is the honest outcome: the OAuth
  grant is gone, so the stored address names a calendar calit can no longer write through.
- A Host who reconnects and re-selects the same calendar does not get their old overrides back
  automatically — consistent with ADR-0004, where a dangling override is re-picked by the Host
  rather than healed by calit.
- The user-visible symptom of the half-address was a hard 500 on reschedule and edit-details. That
  is not fixed by re-resolution but by tolerating the 404 (ADR-0001), which is what makes the
  fallback survivable rather than fatal.
