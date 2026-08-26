---
title: Meeting types
---

Meeting types define what your invitees can schedule. Each type gets its own public URL and can be configured independently.

![Meeting types list](/calit/img/meeting-types.png)

## Owner console

All meeting-type management lives in your owner console at `/me`. From there you can create, edit, activate, deactivate, or delete meeting types.

![Meeting type editor](/calit/img/meeting-type-detail.png)

## Public URLs

Each meeting type has a **slug** — a short identifier that forms the public booking URL:

```
/<username>/<slug>
```

Your landing page at `/<username>` lists all of your **active, non-secret** meeting types. Secret meeting types are still bookable via their direct URL but do not appear on the listing.

## Per-type settings

| Setting | Description |
|---|---|
| **Slug** | URL-safe identifier; must be unique within your account. |
| **Duration** | Length of the meeting in minutes. |
| **Buffer before / after** | Padding added before and after each booking so it does not count as free time. |
| **Minimum notice** | How far in advance a booking must be made (e.g. 60 minutes means no same-hour bookings). |
| **Booking horizon** | How many days into the future invitees can book (default 60 days). |
| **Requires approval** | When enabled, new bookings are held in a pending state until you approve them. See [Bookings & approvals](/calit/usage/bookings/). |
| **Custom booking fields** | Extra questions shown to the invitee on the booking form (name, company, notes, etc.). |
| **Secret** | Hides the type from `/<username>` while keeping the direct link active. |

:::tip[Minimum notice smart default]
When creating a new meeting type, **Min scheduling notice** defaults to 4× the duration and updates automatically as you adjust the duration (e.g. a 45-minute meeting suggests 180 minutes' notice). Once you edit the notice field yourself it stops updating. You can set it to any value — including 0 for instant bookings — before saving.
:::

## Allowed durations

A meeting type does not have to offer just one length. In the type's **Allowed durations** section you can add extra lengths — say a 30-minute type that also offers 60 and 120 minutes — each with its own buffer before and after. Invitees pick a length above the slot grid on the public booking page before choosing a time; if you only ever offer one length, no picker appears at all.

To add a length, fill in a duration (in minutes) on a blank row and save. To remove one, clear its duration field and save — the row disappears from the allowed set. Leave a row's buffer fields blank to have that length use the meeting type's own **Buffer before / after** instead of a value of its own.

The **Duration** field above is always the type's default — the length that renders before the invitee picks one, and the length a plain link to the type's URL books. It is always part of the allowed set; there is no separate control to remove it here, and changing it is done in the **Duration** field itself, not in this table.

When a length has its own buffer and the meeting has more than one host, or the host's own buffer differs from the length's, the buffer actually applied is whichever is larger — buffers are a floor on how much padding surrounds a booking, never a setting that can be relaxed by mixing a wide host buffer with a narrow duration override, or the reverse.

Switching between lengths does not move the start times already on offer — a longer pick simply drops the starts that no longer leave enough room, since every length shares the same underlying lattice.

## Google account

Connect a Google account in your account settings to enable Google Meet links and Google Calendar sync for your bookings.

![Connect a Google account](/calit/img/google-connect.png)

## Availability

Bookable slots for a meeting type come from your availability rules and any date overrides. See [Availability & overrides](/calit/usage/availability/).
