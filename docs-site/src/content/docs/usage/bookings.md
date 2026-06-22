---
title: Bookings & approvals
---

Invitees book meetings through the public booking page at `/<username>/<slug>`. No account is required.

![Custom booking fields](/calit/img/booking-fields.png)

## Booking flow

1. Invitee opens the meeting type's public URL.
2. They pick a date, then a time slot in their local timezone.
3. They fill in the booking form (name, email, and any custom fields defined for that meeting type).
4. On submission, calit validates the slot, checks for conflicts, and either confirms or holds the booking.

## Confirmed vs pending bookings

If the meeting type does **not** require approval, the booking is confirmed immediately. A confirmation email with an `.ics` calendar invite is sent to the invitee. If the owner has connected a Google account, a Google Meet link is generated and included in the invite.

If the meeting type **requires approval**, the booking is created with a **pending** status. The booking is held until you approve or reject it. Pending bookings that are neither approved nor cancelled expire automatically after the number of hours configured with `APPROVAL_HOLD_HOURS` (default: 24 hours).

You can act on a pending request two ways:

- **From the owner console** — the **Pending** page at `/me/pending` lists every request with Approve / Decline buttons.
- **Straight from the request email** — the notification you receive carries one-click **Approve** and **Decline** links. Clicking one opens the owner console; if you are not signed in, you log in first and are returned to the action automatically. These links only work for you, the signed-in owner of the booking — they are not usable by anyone the email is forwarded to.

Owner and invitee receive different copies of every booking email: yours is addressed to you and names the invitee, theirs is addressed to them. Each side only sees the links relevant to it.

## Invitee self-service links

The confirmation email includes unique links the invitee can use to:

- **View** their booking details.
- **Reschedule** — pick a new available slot; the old slot is released.
- **Cancel** — open a confirmation page, then release the slot and notify both parties. A direct **Cancel this booking** link is included right in the email.

These actions work via a secure token; no login is required. The attached `.ics` calendar invite is a standard iTIP request, so it loads as an event card in Gmail and other mail clients.

## Reminders

calit sends a reminder email to the invitee before each confirmed meeting. The lead time is controlled by `REMINDER_LEAD_MINUTES` (default: `1440`, i.e. 24 hours before the meeting).

## Abuse protection

The public booking form has several layers of protection:

- **Cloudflare Turnstile** — bot-detection challenge on the form (requires setup; see [Turnstile setup](/calit/installation/turnstile/)).
- **Honeypot field** — a hidden field that only bots fill in; submissions that include it are silently rejected.
- **Per-email daily cap** — a single email address cannot make more than `PER_EMAIL_DAILY_CAP` bookings per day (default: 10) across the owner's meeting types.
