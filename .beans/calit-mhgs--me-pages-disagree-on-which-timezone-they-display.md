---
# calit-mhgs
title: /me pages disagree on which timezone they display
status: todo
type: bug
priority: low
created_at: 2026-08-16T06:39:08Z
updated_at: 2026-08-17T10:52:02Z
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
- [x] Decide which zone /me standardises on — **STORED** (decided 2026-08-17)
- [ ] Implement, keeping the picker as an explicit override
- [ ] Ensure whichever zone is shown is always named on screen

## Decision (2026-08-17)

**/me standardises on the owner's STORED zone.** The `#tz-picker` becomes an explicit
per-page override, not the default: `Layout.TZ_SCRIPT` must stop pre-selecting the
browser-detected zone, so `manageBooking` falls in line with the dashboard and
/me/pending instead of diverging from them.

Rationale: availability rules, per-type working hours, date overrides and reminder
times are all authored in the stored zone. One zone for both authoring and display is
the honest model; showing bookings in a zone the owner never configured invites exactly
the 15:00-vs-22:00 confusion this bean reports.

Whichever zone is on screen stays NAMED on screen (second todo) — the stored zone is no
more self-evident than the detected one, and `manageBooking`'s existing zone label is
the pattern to copy onto the dashboard and /me/pending.
