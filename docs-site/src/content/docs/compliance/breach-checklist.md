---
title: Breach checklist
description: What to do in the first 72 hours after a personal data breach on a calit deployment.
---

:::caution[Not legal advice]
A checklist to work through, not a substitute for advice on your own notification duties.
:::

## The 72-hour clock

Under Art. 33 GDPR a controller notifies its supervisory authority within **72 hours** of becoming
aware of a breach, unless the breach is unlikely to put people at risk. The clock starts when you
become aware, not when you have finished investigating. Notify with what you know and follow up.

If you host calit for someone else, you are the processor: tell the controller without undue delay,
and they notify the authority.

## 1. Contain

- [ ] Take the affected replica or database offline, or restrict access, if the breach is ongoing.
- [ ] Rotate what may have leaked: `DB_PASSWORD`, `SESSION_ENCRYPTION_KEY` (signs everyone out),
      `MAIL_PASSWORD`, `GOOGLE_OAUTH_CLIENT_SECRET`, `OIDC_CLIENT_SECRET`.
- [ ] If `TOKEN_ENCRYPTION_KEY` leaked together with the database, treat the Google tokens and
      channel URLs as exposed: hosts must disconnect Google, revoke calit's access in their Google
      account and reconnect, and replace their channel URLs. Rotating the key alone strands the
      stored tokens; see [Configuration](/calit/installation/configuration/#secrets).
- [ ] Write down the time you became aware.

## 2. Work out what was exposed

Which tables hold what, from calit's personal-data inventory (see
[records of processing](/calit/compliance/records-of-processing/)):

| Table | Whose data | What |
|---|---|---|
| `booking` | Invitees | Name, email, booking-field answers (free text, possibly sensitive), meet link, title, description |
| `booking_guest` | Guests | Email |
| `email_outbox` | Invitees, guests, hosts | Recipient, subject, full HTML body, `.ics` invite of email sent or queued in the last 30 days |
| `app_user` | Hosts | Username, argon2id password hash, Google and SSO subject ids |
| `owner_settings` | Hosts | Display name, email, timezone |
| `google_credential` | Hosts | Google account email, subject id, encrypted OAuth tokens |
| `google_calendar` | Hosts | Calendar ids and names |
| `notification_channel` | Hosts | Encrypted channel URLs (these often embed a bot token or webhook secret), labels |
| `password_reset_token`, `login_ticket` | Hosts | Token hashes, expiring within a day |
| `meeting_type`, `booking_field` | Hosts | Text the host wrote |
| `deleted_username` | Former hosts | SHA-256 hashes of deleted usernames |

- [ ] Identify which tables, and which rows, were reachable.
- [ ] Count the affected people in each category.

## 3. Pull the logs

- [ ] The `audit` logger category: sign-ins, sign-in failures and privileged admin actions, as
      `AUDIT actor=… action=… target=… ip=…` lines.
- [ ] Lines starting with `PRIVACY`: erasures, account deletions, retention runs and purges, with ids
      and counts only. These show what was already erased before the breach.
- [ ] Your reverse proxy's access logs, for the requests themselves.
- [ ] Database logs, if your provider keeps them.

Keep copies somewhere the incident cannot reach.

## 4. Notify

- [ ] **Supervisory authority**, within 72 hours: nature of the breach, categories and approximate
      numbers of people and records, likely consequences, measures taken, and your contact.
      Authority: _[operator]_.
- [ ] **The people affected**, without undue delay, when the breach is likely to put them at high
      risk (Art. 34), for example leaked booking answers holding health data, or password hashes.
- [ ] **Hosts** on the deployment, who may have their own duties toward their invitees.
- [ ] **The controller**, if you host calit on someone else's behalf.
- [ ] **Sub-processors** whose credentials were involved (Google, your SMTP provider, channel
      providers).

## 5. Afterwards

- [ ] Record the breach in your internal breach register (Art. 33(5)), even if you decided not to
      notify.
- [ ] Update your [operator guide](/calit/compliance/operator-guide/) notes and security measures.
