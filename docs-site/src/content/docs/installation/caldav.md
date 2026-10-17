---
title: CalDAV calendar setup
description: Connect a Nextcloud, Fastmail, iCloud, mailbox.org or Radicale calendar to calit.
---

calit can read busy time from, and write booking events to, any CalDAV server. It works alongside Google Calendar or instead of it, and needs no operator setup: each user connects their own account.

## Connecting an account

1. Open **Settings → Calendars** (`/me/calendars`).
2. Under **Connect a CalDAV calendar**, enter the **Server URL**, your **Username** and a **Password**. Use an app password where your provider offers one.
3. Select **Connect a CalDAV account**.

The server URL can be just the server's base address (for example `https://cloud.example.com`); calit finds the calendars itself using RFC 6764 discovery. If discovery doesn't work for your server, enter the full calendar-home URL from the table below.

calit lists the account's calendars. Tick **Read busy** on each calendar that should block your availability, and choose one calendar as the **write target**, as for Google.

## Provider notes

| Provider | Server URL | App password |
|---|---|---|
| Nextcloud | `https://cloud.example.com`, or `https://cloud.example.com/remote.php/dav/calendars/USERNAME/` | **Settings → Security → Devices & sessions**, create a new app password |
| Fastmail | `https://caldav.fastmail.com/dav/calendars/user/you@example.com/` | **Settings → Privacy & Security → Integrations → New app password**, access *Calendars (CalDAV)* |
| iCloud | `https://caldav.icloud.com` | At appleid.apple.com, **Sign-In and Security → App-Specific Passwords**. Use your Apple ID as the username |
| mailbox.org | `https://dav.mailbox.org` | An app password from the mailbox.org security settings |
| Radicale | `https://radicale.example.com/USERNAME/` | Your Radicale password (whatever its auth backend uses) |

Menu names change between provider releases; if one differs, look for "app password" in the provider's security settings.

## What calit does with it

- **Busy time.** Events on the ticked calendars block slots. calit uses only their times and doesn't store event content.
- **Mirror event.** The write-target calendar receives a Mirror event for each booking, updated on reschedule and deleted on cancel.
- **Invitations.** A CalDAV Mirror event carries no attendees (guests' email addresses go in its description), so the server sends no invitations of its own. calit emails the `.ics` invitations. The Creator gets none when the Mirror event is on their CalDAV calendar, since it is already there.
- **No Google Meet.** Meet links are Google-only. A Meet-type booking made on a CalDAV calendar has no generated link and shows the meeting type's location detail instead.

### No write calendar

Choose **No calendar — send an .ics instead** to book without any Mirror event. Bookings still succeed; calit emails the `.ics` to everyone, the Creator included, and keeps doing so for those bookings if you later pick a write calendar. This also applies to an account that has no writable calendar selected.

## Private server addresses

By default calit refuses CalDAV servers that resolve to private, loopback or link-local addresses (checked on every request and redirect), so a user can't point it at your internal network. On a single-user or trusted instance with Nextcloud on the LAN or in the same Docker network, set:

```
CALDAV_ALLOW_PRIVATE_HOSTS=true
```

## Rejected passwords

calit probes each CalDAV account on the same schedule as Google (`GOOGLE_PROBE_INTERVAL`). When the server rejects the password, the account is marked for reconnect, and the owner is emailed with a link to re-enter the password. Enter a new password via **Update password** on the Calendars page.

## Troubleshooting

| Message | Cause |
|---|---|
| *That server is on a private network address…* | See [Private server addresses](#private-server-addresses) |
| *The server rejected that username or password.* | Wrong credentials; use an app password |
| *No CalDAV calendars found at that URL.* | Wrong address; try the full calendar-home URL |
| *Couldn't reach that server.* | DNS, TLS or network problem |
