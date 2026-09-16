---
title: GDPR operator guide
description: What calit does for you as a data controller, what only you can do, and where erasure stops.
---

:::caution[Not legal advice]
This page describes what the calit software does. It is not legal advice, and it does not make a
deployment compliant on its own. Check your obligations with someone qualified in your jurisdiction.
:::

## You are the controller, not the maintainers

calit is software you host. Whoever runs a deployment is the data controller for it. The project's
maintainers operate no central service, never see your deployment's data, and cannot answer a data
subject's request on your behalf. If someone writes to the project asking for their data, the only
possible answer is "ask the operator of the site you booked on".

There is no global "GDPR mode" to switch on. The regulation follows the data subject, not the
server, so the tools below are on by default, and only two of them are configurable.

## What calit does for you

- **Invitee download.** The manage link in every confirmation email carries **Download my data**
  (`/booking/{token}/data`): a JSON file with that booking's times, meeting, the invitee's name,
  email and answers, and its guests. It is always available.
- **Invitee erasure.** The same page carries an erase button (`/booking/{token}/erase`). A confirm
  page states what erasure cannot reach, then:
  - an upcoming booking is cancelled first through the normal cancel path, so the Google event is
    deleted and the slot is freed;
  - the booking is anonymised: name and email blanked, answers emptied, the meet link and the
    booking's own title and description cleared, guests deleted, unsent reminders and the booking's
    queued email deleted;
  - the row survives with `erased_at` set, and the host sees "(erased at the invitee's request)" in
    place of the invitee;
  - for a past booking with a Google event, calit makes a best-effort delete of that event and
    clears the event reference from the row;
  - for a multi-host booking, every host's copy is anonymised.

  If Google fails the delete, the erasure still completes: the booking is cancelled and anonymised,
  and the done page reports the Google copy as unreachable.

  Hosts' copies of a multi-host booking can be anonymised at different times, because retention
  follows each host's own window. The link keeps working until every host's copy is erased: the
  download reads from a copy that still holds the data, and erasure clears the rest.

  After erasure every route for that token returns 404, including the JSON API
  (`/api/bookings/{token}` and `/api/bookings/{token}/reschedule`). Set `INVITEE_ERASURE=false` to
  replace the button with your `PRIVACY_CONTACT_EMAIL`; `/erase` then returns 404.
- **Owner export.** `GET /me/export` (**Download all my data** in settings) returns the whole
  account as one JSON file, including bookings with invitee data and guests. The password hash and
  Google OAuth tokens are left out, and notification-channel URLs appear as `[redacted]`.
- **Account deletion.** A user deletes their own account at `/me/settings/delete`, confirming with
  their password (or their username, for accounts that sign in only through Google or SSO). A site
  admin deletes someone else's from `/me/users`, on a confirm page that asks for that account's
  username. See [Users & admin](/calit/usage/users-admin/#deleting-an-account).
- **Retention sweep.** With `BOOKING_RETENTION_DAYS` set, or a host's own **Delete booking details
  after (days)** setting, bookings are anonymised that many days after they end. See
  [Configuration](/calit/installation/configuration/#privacy).
- **Automatic purges.** Queued email is deleted about 30 days after it is sent (or after it was
  queued, if never sent), and password-reset, invitation and sign-in tokens about a day after they
  expire. Neither is configurable.

## What only you can do

- Set `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL`, so that `/privacy` names a real controller and a
  real contact address. Without them the page names your `APP_BASE_URL` and shows no contact line.
- Decide your lawful basis for each kind of processing, and write it into your
  [records of processing](/calit/compliance/records-of-processing/).
- Answer requests that arrive by email instead of through the manage link. calit has no admin
  screen for erasing someone else's booking. Look the booking up in the `booking` table and open
  `/booking/{manage_token}/erase` yourself: it runs the same erasure the invitee would.
  That route exists only while `INVITEE_ERASURE` is on.
- Maintain the Art. 30 records and your [sub-processor list](/calit/compliance/sub-processors/).
- Choose a retention window. calit keeps bookings forever until you or a host sets one.

## Where erasure stops

calit erases what it holds. It cannot reach copies that have already left the server:

| Copy | Reachable? | What happens |
|---|---|---|
| Google Calendar event on the host's calendar | While Google is connected | Deleted, then kept in Google's trash for about 30 days. If the host has since disconnected Google, calit cannot reach it. |
| Notification-channel message (Telegram, Slack, Discord, ntfy, …) | No | A delivered message cannot be recalled. |
| Email already delivered over SMTP | No | It stays in every mailbox it reached. |
| The `.ics` invite in the invitee's and guests' calendars | No | It stays in their own calendars. |

The erase confirm page lists these limits before the invitee clicks, and the done page reports what
happened to each copy: the Google event removed, unreachable, or never there. When a copy is out of
reach, the done page shows your `PRIVACY_CONTACT_EMAIL`, when set.

Two more consequences of the erasure order:

- An upcoming booking is cancelled before it is anonymised, and the cancellation emails are sent
  directly. If the direct send failed and a notice is parked in `email_outbox` for retry, the
  erasure deletes it with the rest of that booking's queued email, so neither the host's nor the
  invitee's copy is delivered. The host still sees the booking marked as erased.
- Retention sweeps do not touch Google events. A booking anonymised by retention keeps its event on
  the host's calendar.

## Bookings are not linked by email

Erasure covers only the booking whose manage link was used. calit does not match bookings by email
address, because anyone can type anyone's address into a booking form. An invitee with several
bookings erases each one from its own confirmation email. If they have lost those emails, they
write to you; you find their bookings in the `booking` table, check that the request really comes
from them, and erase each one through its `manage_token` as above.

The confirm and done pages say this, and state how long the other links keep working. When a
retention window applies (the host's own, otherwise the instance default), the links stop working
that many days after the meeting ends. Otherwise they keep working until the host or you remove the
booking.

## Deleting an account

Deletion removes the account, its settings, meeting types, availability, bookings, connected Google
accounts, notification channels, queued email, and reset and sign-in tokens. No "your account was
deleted" email is sent. The last enabled admin cannot be deleted, and an admin cannot delete their
own account from `/me/users`.

- **Upcoming bookings are cancelled first.** Every upcoming pending or confirmed booking on the
  account's own meeting types is cancelled through the normal cancel path before anything is
  deleted, including every host's copy of a multi-host booking. Invitees, guests and co-hosts get
  the usual cancellation email, and the Google event is deleted where Google is reachable; a Google
  failure does not stop the deletion.
- **Queued cancellation emails survive.** If a cancellation email could not be sent and is waiting
  in `email_outbox`, the copies for invitees, guests and co-hosts are kept for retry; their booking
  link is dropped, so the 30-day age purge clears them. The deleted user's own copy is deleted with
  the account.
- **Bookings where the user is only a co-host are not cancelled.** Their own copy of such a booking
  is deleted; the meeting stays on the other hosts' calendars.
- **A narrow race remains.** A booking made in the moment between the cancellations and the final
  delete is removed without a notice. Two admins deleting the last two enabled admin accounts at
  once can both cancel their bookings before one of the deletions is refused.

- **Google grants are not revoked.** Deletion removes calit's copy of the OAuth tokens. It does not
  withdraw the grant at Google; the user does that in their own Google account settings.
- **Usernames are blocked for good.** calit keeps a SHA-256 hash of every deleted account's
  username, permanently. Signup, invitations, first-run setup and Google or SSO auto-provisioning
  all refuse that name. This stops a stale login cookie, which carries only the username, from
  attaching to a new account with the same name. A deleted person can never get their old username
  back.

## Custom booking fields can collect sensitive data

Answers to custom booking fields are stored as plain text. An owner can ask for health, religious,
political or other special-category data (Art. 9), and calit will store whatever the invitee types.
There is no technical fix. The field editor warns owners about this, and so does this guide: if your
hosts ask for such data, you need a lawful basis for holding it.

## Email queued before V34

Migration `V34` links queued email to its booking and owner. Rows queued before that migration carry
no link, so an erasure request does not clear them. The 30-day age purge does. If you upgraded from
an earlier version and need those rows gone sooner, wait 30 days or clear `email_outbox` by hand.

## Background jobs

Both jobs run on every replica, coordinated through Postgres with no leader.

- The retention sweep runs daily at 03:17. It anonymises bookings in batches of 200, each batch in
  its own transaction, until nothing is left or five minutes have passed; a larger backlog continues
  the next day.
- The purge runs daily at 03:37. Email still waiting for a retry is never purged.

Each erasure, account deletion, retention run and purge writes a log line starting with `PRIVACY`.
These lines carry ids and counts, never personal data, and are your record that a request was
handled.

Account deletions also write a line to the `audit` logger category. For a self-deletion that line
names the deleted account by username (`AUDIT actor=<username> action=delete-account
target=user:<id>`), so the audit log keeps a username after the account is gone. An admin deletion
names the acting admin and only the target's id (`action=delete-user target=user:<id>`). Set your
audit-log retention with that in mind. See the [breach checklist](/calit/compliance/breach-checklist/) for which logs to keep.

## Related pages

- [Configuration](/calit/installation/configuration/#privacy) for `INVITEE_ERASURE`,
  `BOOKING_RETENTION_DAYS`, `PRIVACY_POLICY_PATH` and `TERMS_PATH`.
- [Bookings & approvals](/calit/usage/bookings/#invitee-self-service-links) for what invitees see.
- [Customising the legal pages](/calit/compliance/custom-legal-pages/) if your policy must differ
  from the shipped one.
