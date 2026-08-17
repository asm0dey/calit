# The write override names a calendar by its Google identity, not by a local row

A write override stores the pair `(google_credential_id, google_calendar_id)` — the connected
account plus Google's own calendar id — rather than a foreign key to the `google_calendar` row that
represents that calendar in calit.

## Considered options

**A foreign key to `google_calendar.id`** — the obvious relational modelling. Rejected: an Owner's
calendar selection is saved by deleting every one of their `google_calendar` rows and re-inserting
the submitted set, so those ids churn every time the Owner ticks any checkbox on the Google page. A
meeting type's override would be nulled or left dangling by an unrelated settings save, which is
exactly the failure this feature must not have. A calendar's Google id survives an untick and a
later re-tick.

**Google's calendar id alone** — rejected on two counts. The write path needs a credential to mint
an access token, and a calendar id carries none. And a calendar shared into two connected accounts
appears as two rows with the same Google id and different credentials, so the id alone does not say
which account to write through.

## Consequences

- An override survives the Owner unticking and re-ticking the same calendar: what is remembered is
  the calendar's Google identity, not calit's row for it.
- Disconnecting the account nulls the credential (`ON DELETE SET NULL`) and leaves the calendar id
  behind. That half-row is a **dangling override**, not the absence of one — writes fall back to the
  write target and the Host is told, rather than the choice vanishing silently.
- There is no referential integrity between an override and a calendar selection. "Does this
  override still point at something?" is answered by a lookup, and every read path must tolerate
  "no" — which is why resolution degrades to the write target instead of failing a Booking.
- Re-connecting a disconnected account mints a new credential id, so an override that dangled
  through a disconnect does not heal itself. The Host re-picks; the meeting-type page prompts them.
