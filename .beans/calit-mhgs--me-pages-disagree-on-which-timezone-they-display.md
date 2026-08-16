---
# calit-mhgs
title: /me pages disagree on which timezone they display
status: todo
type: bug
priority: low
created_at: 2026-08-16T06:39:08Z
updated_at: 2026-08-16T06:39:08Z
---

Two `/me` pages render bookings in the owner's STORED zone; a third renders them in the BROWSER-DETECTED zone. One click apart, the same booking shows two different clock times.

Owner stored zone `Europe/Amsterdam`, owner travelling in Tokyo, booking at 13:00 UTC:

| Page | Zone used | Displays |
| --- | --- | --- |
| `/me` dashboard | stored (`body[data-tz]`) | 15:00 |
| `/me/pending` | stored (`body[data-tz]`) | 15:00 |
| `/me/bookings/{id}/manage` | detected (browser) | 22:00 |

CAUSE: `manageBooking` passes `Layout.tzBar`, so it HAS a `#tz-picker`, and `Layout.TZ_SCRIPT` marks the detected zone selected in it (`o.selected = z === detected`). Dashboard and pending have no picker and fall through to `body[data-tz]`, which the #116 branch wired to the stored zone.

Knowingly accepted in `docs/superpowers/specs/2026-08-15-time-format-design.md` — manageBooking carries a visible zone label naming its zone, so nothing misleads. Surfaced by the whole-branch review as a seam only visible across pages.

NEEDS A DECISION BEFORE CODE: should `/me` standardise on the stored zone (picker becomes an override, not the default) or on the detected zone (add a zone label to dashboard/pending)? The former matches 'availability is defined in my configured zone'; the latter matches 'show me times where I am'.

## Todo
- [ ] Decide which zone /me standardises on
- [ ] Implement, keeping the picker as an explicit override
- [ ] Ensure whichever zone is shown is always named on screen
