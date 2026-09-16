---
title: Sub-processors
description: A template list of the third parties your calit deployment sends personal data to.
---

calit itself sends data to no one on the project's behalf. Your deployment talks to the services you
configure, and each of those is a recipient you have to list. Your instance's own `/privacy` page
names the categories it actually uses; this table is where you record the specifics.

Fill in one row per service you actually run. Delete the rows that do not apply.

| Sub-processor | Used when | Data sent | Purpose | Location | DPA link |
|---|---|---|---|---|---|
| Google (Calendar API, and Google sign-in) | `GOOGLE_OAUTH_CLIENT_ID` is set and a host connects Google | Booking times, meeting title and description, invitee and guest email addresses; the host's Google identity | _[operator]_ | _[operator]_ | _[operator]_ |
| Your SMTP provider (`MAIL_HOST`) | Always | Every email calit sends: recipient, subject, body, `.ics` invite | _[operator]_ | _[operator]_ | _[operator]_ |
| Notification-channel provider (Telegram, Slack, Discord, ntfy, Gotify, webhook, …) — one row each | A host has configured that channel | Booking event messages, which name the invitee | _[operator]_ | _[operator]_ | _[operator]_ |
| SSO identity provider (`OIDC_ISSUER_URL`) | `OIDC_ENABLED=true` | The sign-in exchange for hosts who use SSO | _[operator]_ | _[operator]_ | _[operator]_ |
| Cloudflare Turnstile | `CAPTCHA_PROVIDER=turnstile` (or `TURNSTILE_ENABLED=true` with no provider set) | The booking visitor's browser challenge | _[operator]_ | _[operator]_ | _[operator]_ |
| Your hosting and database provider | Always | Everything calit stores | _[operator]_ | _[operator]_ | _[operator]_ |

ALTCHA (`CAPTCHA_PROVIDER=altcha`) runs inside calit and sends nothing to a third party.

Notification channels are added by hosts, not by you, and their URLs are encrypted at rest, so the
database does not show which services are in use. Ask your hosts, or limit the choice up front with
`NOTIFY_ALLOWED_SCHEMES` (see
[Configuration](/calit/installation/configuration/#outbound-notification-channels-optional)).
