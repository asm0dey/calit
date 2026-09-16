---
title: Privacy Policy
description: How calit handles your data, including Google user data.
---

<!-- REFERENCE copy, not a mirror. The app page at ${APP_BASE_URL}/privacy
     (src/main/resources/templates/LegalResource/privacy.html on main) is authoritative for a
     running instance: it renders from what that deployment actually does. This page describes the
     software with every optional feature enabled, for someone evaluating calit. Do not re-establish
     a line-by-line mirror; update the shared prose by hand when the software's behaviour changes. -->

:::caution[This is the reference copy]
Your deployment's actual privacy policy is served by your own instance at
`${APP_BASE_URL}/privacy`. It describes only what that deployment really does — a deployment
without Google connected does not claim Google processing, and the retention section reflects the
window you configured. This page describes the software with every optional feature enabled, for
evaluation. Link the instance URL, not this one, from your OAuth consent screen.
:::

:::note[For operators]
calit is **self-hosted**: each deployment is run and controlled by whoever
installs it, and that operator is the data controller for their instance. This
page documents how the calit software handles data. Your instance serves its own
policy, naming you through `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL`; see the
[GDPR operator guide](/calit/compliance/operator-guide/) and
[Customising the legal pages](/calit/compliance/custom-legal-pages/). Review it
against your own legal requirements before relying on it.
:::

_Last updated: 2026-09-16_

## What calit is

calit is open-source scheduling software you host yourself. It stores all data
in a database you control. The maintainers of the project do not operate a
central service, do not receive your data, and have no access to any
deployment's database.

## Data calit stores

- **Account data** — your username, email address, display name, timezone, and
  an argon2id hash of your password (never the password itself).
- **Scheduling data** — your meeting types, availability rules, and the
  bookings made by invitees (invitee name, email, and any answers to custom
  booking questions you configure).
- **Google account data** — when the deployment has Google configured and you connect Google Calendar: the
  Google account's email and stable subject identifier (from the OpenID
  id_token), and the OAuth access and refresh tokens. **Tokens are encrypted at
  rest** in the deployment's database.

## How Google user data is used (when Google is configured)

When you connect a Google account, calit requests the Google Calendar scope and
uses it **only** to provide scheduling:

- **Read free/busy** information from the calendars you select, to compute which
  time slots are available.
- **Create, update, and delete events** on the single write-target calendar you
  choose, when bookings are made, rescheduled, or cancelled.

calit does not read the content of your calendar events beyond busy intervals,
and does not use Google data for advertising, profiling, or any purpose other
than the scheduling features you initiated.

### Limited Use disclosure

calit's use of information received from Google APIs adheres to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the **Limited Use** requirements. Google user data is not sold,
not transferred to third parties except as needed to provide the scheduling
features, not used for advertising, and not read by humans except where required
for security, to comply with law, or with your explicit consent.

## Data sharing

calit does not sell or share your data. A deployment sends data only to the
services its operator has configured, and its own `/privacy` page lists exactly
which ones:

- **Google** (when configured), for the calendar operations above.
- **A single sign-on identity provider** (when configured), when you sign in with it.
- **The SMTP server** the operator configures, which delivers booking and
  notification emails.
- **Chat or push services** (when a host has configured notification channels,
  for example Telegram, Slack, Discord or ntfy). Messages already delivered
  there cannot be recalled.
- **Cloudflare Turnstile** (when configured), which checks booking requests for
  abuse.

## Retention and deletion

- Disconnecting a Google account in **Settings → Google** (when Google is
  configured) deletes that account's stored tokens and calendar selections. It
  does not withdraw the grant at Google — revoke calit's access in your Google
  account to do that.
- Deleting your account at **Settings → Delete my account** (`/me/settings/delete`)
  removes your account, settings, meeting types, availability, bookings,
  connected Google accounts and notification channels. It does **not** revoke
  the grant at Google either.
- A hash of a deleted account's username is kept permanently, so that name can
  never be re-registered and used to take over a stale login.
- If you booked a meeting, the manage link in your confirmation email lets you
  download your data or erase it from that booking. Bookings are not linked to
  each other by email address, so erasure reaches only the booking whose manage
  link you used. An operator may turn self-service erasure off; the instance
  then tells you whom to contact instead.
- Booking details are anonymised a set number of days after the meeting ends
  (when the operator or host has configured a retention window). Without one,
  bookings are kept until removed by the host or the operator.
- Emails queued for sending are deleted within 30 days of being queued or sent.
  Password-reset and sign-in tokens are deleted within a day of expiring.

## Security

Passwords are hashed with argon2id; Google OAuth tokens are encrypted at rest.
Production deployments run behind TLS with secure cookies. The operator is
responsible for securing the host and database.

## Contact

For questions about a specific deployment, contact that deployment's operator.
For questions about the calit software itself, open an issue at
[github.com/asm0dey/calit](https://github.com/asm0dey/calit).
