# GDPR compliance

Date: 2026-09-12
Bean: `calit-l3fk`

## Scope

calit is self-hosted software. The operator of a deployment is the data controller;
the maintainers are not, and never see a deployment's data. "GDPR compliance" for
this project therefore means two things:

1. **Operator-enabling features** — the mechanics an operator needs to answer a data
   subject request and to hold a defensible retention policy.
2. **A paperwork kit** — templates on the docs site that an operator fills in:
   records of processing, sub-processor list, DPA, breach checklist.

Out of scope: running a hosted calit service, audit trails of every access to personal
data, DPIA.

GDPR's territorial scope follows the data subject, not the server. A deployment hosted
outside the EU that takes a booking from someone inside it is in scope. There is
therefore no global "GDPR mode" switch: such a switch would remove the tools without
removing the obligation. Features default on; the two that change what an operator
keeps are individually configurable.

## What exists today

- `/privacy` and `/terms` render from Qute templates, operator-customisable through
  `{inject:site.*}`. Written for Google OAuth verification.
- No account deletion anywhere — only lock/unlock. The shipped privacy policy already
  states that "deleting a user account removes that user's scheduling data", which is
  currently false.
- No data export.
- No retention or purge of anything. Bookings are kept forever. `email_outbox` keeps
  the full rendered HTML of every booking mail, with recipient address, for the life of
  the deployment.
- No IP addresses recorded in application code. All cookies are strictly necessary
  (`quarkus-credential`, remember-me, CSRF, `calit_lang`), so no consent banner is
  required.

## Approach

One `PrivacyService` holding explicit Panache queries per table, plus a schema guard
test — rather than a `PersonalDataSource` interface implemented once per module, and
rather than reflection over JPA metadata.

A registry buys extensibility that has no second consumer, and reflection hides exactly
the judgement that matters: which columns are personal, and what erasure does with each.
The safety property is the guard test, which works the same under any of the three.

## 1. Data inventory and the guard test

`privacy/PersonalData.java` classifies every table: which columns carry personal data,
which subject they belong to, and how erasure treats them.

| Table | Personal columns | Subject | Erase route |
|---|---|---|---|
| `booking` | `invitee_name`, `invitee_email`, `answers`, `meet_link` | invitee | anonymise in place |
| `booking_guest` | `email` | invitee's guests | delete rows |
| `reminder` | none (links only) | via `booking_id` | cascades |
| `email_outbox` | `recipient`, `subject`, `html_body`, `ics_bytes` | none today; V33 adds nullable `booking_id` and `owner_id` | age purge, plus FK-keyed purge on erasure and deletion |
| `app_user` | `username`, `password_hash`, `oidc_sub` | owner | delete row |
| `owner_settings` | `owner_name`, `owner_email`, `timezone` | owner | cascades |
| `google_credential` | Google email, subject id, OAuth tokens | owner | cascades |
| `google_calendar` | calendar names and ids | owner | cascades |
| `notification_channel` | `url` (secret-bearing), `label` | owner | cascades |
| `password_reset_token`, `login_ticket` | `user_id`, token hashes | owner | cascades; age purge |
| `meeting_type`, `meeting_type_host`, `booking_field`, `availability_rule`, `date_override`, `date_override_window` | free-text fields | owner | cascades |

`PersonalDataInventoryTest` (`@QuarkusTest`) reads `information_schema` from the live
Dev Services Postgres and fails when a table or column is not classified. A future
migration that adds a personal column breaks the build until someone decides what
erasure does with it. This test is the compliance property; the rest is plumbing.

### Defects this surfaced

- **`email_outbox` is orphan personal data.** No owner link, no booking link, no purge;
  it survives account deletion because nothing cascades to it. Matching on `recipient`
  alone is not a fix: two owners can send to the same invitee, and erasing one booking
  would take the other's mail with it. V33 adds the missing links instead.
- **`booking.meeting_type_id` has no `ON DELETE` clause** (V4). Owner deletion resolves
  today only because `meeting_type` and `booking` each cascade separately from
  `app_user`. Made explicit in V33.
- **`answers` is a free-form JSONB bucket.** An owner can ask any question, including
  Art. 9 special-category data, and calit stores the reply as plain text. Not fixable in
  code: it goes in the operator guide, plus one line of warning copy under the
  custom-field editor.

## 2. Subject-rights surfaces

### Invitee, through the manage link

The `manage_token` already proves control of a booking, so no new identity verification
is introduced. Three routes on `PublicResource`, beside the existing
`/booking/{manageToken}/cancel` pair:

- `GET /booking/{manageToken}/data` — JSON of that booking (times, meeting type, the
  invitee's own name, email and answers, guest list), `Content-Disposition: attachment`.
- `GET /booking/{manageToken}/erase` — confirm page, mirroring `cancelConfirm.html`.
- `POST /booking/{manageToken}/erase` — cancels first when the booking is still upcoming,
  reusing the existing cancel path so the Google event is deleted and the owner receives
  the normal cancellation mail; then anonymises. The form carries `{inject:csrf.token}`.

Anonymise: `invitee_name` and `invitee_email` to empty, `answers` to `{}`, `meet_link`
to null, delete `booking_guest` rows, delete unsent reminders, delete `email_outbox`
rows for that booking, stamp `booking.erased_at`.

The row survives, so the owner keeps a "someone booked 14:00–14:30" record. `erased_at`
is what makes the manage page and the `.ics` route 404 afterwards, and what the owner's
booking list renders as an erased placeholder rather than a blank name.

`invitee_email` is `NOT NULL`, so it becomes an empty string rather than null. This also
means the per-email abuse cap (`idx_booking_email_created`) can never match an erased
row against a real address.

`INVITEE_ERASURE` (`calit.privacy.invitee-erasure`, default `true`) gates this. Set
false, the manage page shows the operator's contact address in place of the button; the
operator still owes the response, by hand, and the docs say so.

### Owner, in `/me`

- `GET /me/export` — one JSON file: account, settings, meeting types, availability,
  overrides, bookings including invitee data (the owner is the controller for it),
  guests, notification channels with `url` redacted, Google account email but no tokens.
- `GET|POST /me/settings/delete` — confirm page requiring password re-entry; OIDC-only
  accounts type their username instead. Refuses when the account is the last remaining
  admin. Revokes any Google OAuth grant through the existing disconnect path, deletes the
  `app_user` row so the `owner_id` cascades take the subtree — including `email_outbox`
  once V33 links it.
- `POST /me/users/{id}/delete` — the same for site admins, same last-admin guard,
  beside the existing lock and unlock buttons.

No "your account was deleted" email: the mailbox may be the thing being erased.

No owner-side search-by-email erasure. Self-serve through the manage link is the chosen
route, and an email search in `/me` is a data-fishing surface for a compromised owner
account. Add it if requests actually start arriving by mail.

### Erasure boundary

calit sends invitee data to destinations it cannot reach back into.

| Destination | When | Reachable by erasure |
|---|---|---|
| Owner's Google Calendar event, invitee as attendee | owner has Google connected | Yes, via API — but Google keeps it in trash roughly 30 days, and the call cannot run at all if the grant was disconnected |
| Notification-channel message (Telegram, Discord, Slack) | owner configured a channel | No. Fire-and-forget webhook, no message id stored, no recall |
| Email already delivered over SMTP | every booking | No |
| The `.ics` in the invitee's and guests' own calendars | every booking | No |

`PersonalData` carries this as a second list beside the table classification. The guard
test cannot check it, but a new outbound integration lands next to the line that records
whether erasure reaches it.

The erase confirm page states these limits before the click, so the choice is informed.
The done page reports per-destination outcome rather than a blanket success, and names
the operator's contact address when the Google copy could not be removed — that remaining
copy is the operator's to finish under Art. 17(2).

Proof of handling is a log line carrying the booking id and per-destination outcome. No
`erasure_log` table until an operator needs a real audit trail.

## 3. Retention and hygiene purges

Configurable:

- `BOOKING_RETENTION_DAYS` (`calit.retention.booking-days`), unset by default. Unset
  means keep forever, so an upgrade changes nothing until an operator opts in.
- `owner_settings.booking_retention_days INT NULL` — per-owner override, null falls back
  to the instance default. Editable in `/me/settings`.
- `RetentionScheduler` in `scheduler/`, daily, using the same
  `SELECT … FOR UPDATE SKIP LOCKED` no-leader pattern as `ReminderScheduler`. It
  anonymises bookings where `end_utc < now() - window` and `erased_at IS NULL`, through
  the same anonymise method the erase button calls, minus the cancel step — these are all
  in the past.

Not configurable, because none of it should have been kept:

- `email_outbox`: delete 30 days after `sent_at`, and dead rows
  (`next_attempt_at IS NULL`) 30 days after `created_at`. Never touches a row still in
  its retry window. This is the largest single reduction in stored personal data in the
  epic.
- `password_reset_token` and `login_ticket`: delete 24 hours past `expires_at`.

30 days is a constant, documented in the config reference. No env var until asked for.

Sent `reminder` rows are left alone: a booking id and a timestamp, no personal data, and
they cascade with the booking.

## 4. Policy copy

`PrivacyFacts` reads what the deployment actually does — Google OAuth configured, OIDC
configured, SMTP host, signup open, retention windows, invitee erasure on or off, whether
any `notification_channel` rows exist. `privacy.html` renders its processor, retention and
"where your data goes" sections from those facts, so a deployment without Google stops
claiming it talks to Google.

`PRIVACY_POLICY_PATH` and `TERMS_PATH` point at HTML fragment files, served inside the
normal layout in place of the shipped body. HTML rather than markdown keeps this near
fifteen lines and adds no runtime dependency; the fragment is rendered unescaped, at the
same trust level as any other operator-supplied configuration. The shipped text is published as the
starting point.

The retention and deletion section is corrected in the same change: it currently promises
account deletion that does not exist.

## 5. Docs kit

On the `docs-site` branch, under `docs/compliance/`, each page opening with a
"not legal advice" note:

- **Operator guide** — the operator is the controller and the maintainers are not; what
  calit does for them; what only they can do (contact address, lawful basis, responding
  to requests); the `answers` special-category warning.
- **Records of processing (Art. 30) starter** — pre-filled from the same inventory as
  the guard test: purposes, categories, recipients, retention.
- **Sub-processor list** — Google, the operator's SMTP provider, notification-channel
  providers, with blanks.
- **DPA template** — for operators hosting on someone else's behalf.
- **Breach checklist** — the 72-hour clock, which tables and logs to pull.

The config reference gains `BOOKING_RETENTION_DAYS`, `INVITEE_ERASURE`,
`PRIVACY_POLICY_PATH` and `TERMS_PATH`. Changelog bullets land under `## Unreleased` as
each PR merges.

## 6. Migration

V33:

- `booking.erased_at TIMESTAMPTZ` (null = not erased).
- `owner_settings.booking_retention_days INT` (null = instance default).
- `email_outbox.booking_id BIGINT REFERENCES booking(id) ON DELETE CASCADE` and
  `email_outbox.owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE`, both
  nullable, set at enqueue where known. Erasure and account deletion key on these
  instead of on the recipient address.
- Explicit `ON DELETE CASCADE` on `booking.meeting_type_id`.

No backfill. Existing rows are not erased and retention stays off until configured. Rows
enqueued before V33 have null links and are cleared by the 30-day age purge alone.

## 7. Testing

All `@QuarkusTest`, since plain unit tests contribute nothing to this repo's coverage.

- `PersonalDataInventoryTest` — the schema guard.
- `BookingErasureTest` — anonymise semantics, upcoming versus past, Google delete
  attempted, outbox purged, manage link 404 afterwards, button hidden when the toggle is
  off.
- `AccountDeletionTest` — the cascade reaches every classified table, last-admin guard,
  outbox rows for that owner gone.
- `RetentionSchedulerTest` — unset is a no-op, instance default, per-owner override.
- `ExportTest` — channel `url` redacted, no OAuth tokens present.
- `PrivacyPolicyRenderTest` — Google section absent when unconfigured, override file wins.

## 8. Internationalisation

Every new user-facing string ships with its German translation in the same change.
Hebrew is deferred: the copy is legal-adjacent, nobody in the loop can review it, and a
wrong nuance costs the operator. Those keys ship with their English `@Message` defaults
and a GitHub issue labelled for translation, referenced from the PR — the gap is tracked,
not silent.

## Deliberately not built

- A global "GDPR mode" switch — it would remove the tools without removing the obligation.
- A public email-verified request page covering every booking under one address across
  the instance — a new abuse surface for a case the manage link already covers.
- An `erasure_log` table.
- A cookie consent banner — every cookie calit sets is strictly necessary.
- ZIP export bundles, printable HTML export views.
- Purging sent `reminder` rows.
