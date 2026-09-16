---
title: Records of processing (Art. 30)
description: A starter for your Art. 30 records, filled from calit's personal-data inventory.
---

:::caution[A starter, not a finished record]
Art. 30 records describe *your* processing, not the software's. The categories, tables and
erasure routes below come from calit's personal-data inventory (`PersonalData.TABLES` in the
source), which a schema test checks against the live database on every build. Everything marked
_[operator]_ is yours to fill in. Nothing here is an example to copy.
:::

## Controller

| Field | Value |
|---|---|
| Controller name and contact | _[operator]_ (the same as `OPERATOR_NAME` / `PRIVACY_CONTACT_EMAIL`) |
| Representative in the EU, if required | _[operator]_ |
| Data protection officer, if appointed | _[operator]_ |
| Security measures (Art. 32) | Passwords hashed with argon2id; Google OAuth tokens and notification-channel URLs encrypted at rest; TLS and secure cookies in production. Host, database and backup security: _[operator]_ |

## Invitees

People who book a meeting through a public booking page.

| Field | Value |
|---|---|
| Categories of data | Name, email address, answers to the host's custom booking fields (free text, may include anything the host asks for), the booking's title and description when the invitee edits them, the video-meeting link, the booking's time and language, and emails addressed to the invitee |
| Where it is held | `booking` (`invitee_name`, `invitee_email`, `answers`, `meet_link`, `title`, `description`); `email_outbox` (`recipient`, `subject`, `html_body`, `ics_bytes`) |
| Purpose | Scheduling the meeting and sending confirmations, reminders and changes |
| Lawful basis | _[operator]_ |
| Recipients | The host; Google Calendar when the host connected Google; the SMTP provider; any notification channel the host configured; Cloudflare Turnstile when enabled. See [sub-processors](/calit/compliance/sub-processors/) |
| Retention | `BOOKING_RETENTION_DAYS` or the host's own window, measured from the meeting's end: _[operator: your window, or "kept until removed"]_. Queued email: about 30 days after it is sent, or after it was queued if never sent |
| Erasure route | Self-service from the manage link: the `booking` row is anonymised in place, its queued email deleted. Email queued before migration `V34` is cleared only by the 30-day purge. See the [operator guide](/calit/compliance/operator-guide/#where-erasure-stops) |

## Invitees' guests

Addresses an invitee adds to a booking.

| Field | Value |
|---|---|
| Categories of data | Email address, response status, emails addressed to the guest |
| Where it is held | `booking_guest` (`email`); `email_outbox` |
| Purpose | Inviting the guest and keeping their calendar in step with the meeting |
| Lawful basis | _[operator]_ |
| Recipients | As for invitees |
| Retention | As for the booking the guest belongs to |
| Erasure route | Guest rows are deleted when the booking is erased or anonymised by retention, and removed with the booking when the host's account is deleted |

## Hosts (account owners)

Users with an account on the deployment.

| Field | Value |
|---|---|
| Categories of data | Username, password hash, Google and SSO subject identifiers, display name, email address, timezone, the text of their meeting types (name, description, location) and booking-field labels, connected Google account email, calendar ids and names, OAuth tokens, notification-channel URLs and labels, password-reset and sign-in tokens |
| Where it is held | `app_user`, `owner_settings`, `meeting_type`, `booking_field`, `google_credential`, `google_calendar`, `notification_channel`, `password_reset_token`, `login_ticket`, `deleted_username` |
| Purpose | Running the host's scheduling page and signing them in |
| Lawful basis | _[operator]_ |
| Recipients | Google when connected; the SSO identity provider when `OIDC_ENABLED` is on; the SMTP provider; the host's own notification channels |
| Retention | For the life of the account. Password-reset, invitation and sign-in tokens: about a day after they expire. `deleted_username`: permanently |
| Erasure route | Account deletion (`/me/settings/delete`, or `/me/users` for a site admin) deletes `app_user`, and every other table cascades away with it. `deleted_username` keeps a SHA-256 hash of the username permanently, so the name can never be re-registered. `email_outbox` rows queued before migration `V34` have no owner link, so they survive deletion until the age purge |

Tables with no personal data of their own (`reminder`, `meeting_type_host`, `availability_rule`,
`date_override`, `date_override_window`, `notification_channel_meeting_type`,
`meeting_type_duration`) are removed with their parent rows.

## Transfers outside the EEA

| Recipient | Transfer mechanism |
|---|---|
| _[operator: one row per sub-processor outside the EEA]_ | _[operator]_ |
