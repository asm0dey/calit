---
title: Privacy Policy
description: How calit handles your data, including Google user data.
---

<!-- Canonical privacy policy. The app serves the same text at ${APP_BASE_URL}/privacy
     (rendered by src/main/resources/templates/LegalResource/privacy.html on the main branch).
     When editing this policy, mirror the changes into that template so the two stay in sync. -->

:::note[For operators]
calit is **self-hosted**: each deployment is run and controlled by whoever
installs it, and that operator is the data controller for their instance. This
page documents how the calit software handles data so it can serve as the
privacy policy for a deployment (e.g. the URL you submit for Google OAuth
verification). Adapt the contact details below to your deployment, and review
with your own legal requirements before relying on it.
:::

_Last updated: 2026-06-15_

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
- **Google account data** — when you connect Google Calendar (optional): the
  Google account's email and stable subject identifier (from the OpenID
  id_token), and the OAuth access and refresh tokens. **Tokens are encrypted at
  rest** in the deployment's database.

## How Google user data is used

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

calit does not sell or share your data. The only external party a deployment
communicates with for core functionality is Google (and only for the calendar
operations above), plus the SMTP server the operator configures for sending
booking and notification emails.

## Retention and deletion

- Disconnecting a Google account in **Settings → Google** deletes that account's
  stored tokens and calendar selections from the database.
- Deleting a user account removes that user's scheduling data.
- Booking records are retained until removed by the account owner or operator.

## Security

Passwords are hashed with argon2id; Google OAuth tokens are encrypted at rest.
Production deployments run behind TLS with secure cookies. The operator is
responsible for securing the host and database.

## Contact

For questions about a specific deployment, contact that deployment's operator.
For questions about the calit software itself, open an issue at
[github.com/asm0dey/calit](https://github.com/asm0dey/calit).
