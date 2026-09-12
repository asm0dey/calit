---
# calit-ighc
title: Notification channel UI does not match the rest of the admin UI
status: completed
type: bug
priority: high
created_at: 2026-09-12T20:27:17Z
updated_at: 2026-09-12T21:58:10Z
---

Shipped in #210. Verified live with Playwright on a fresh dev DB.

## /me/settings — channel list
- Rows are naked unlabelled inputs (`aria-label` only). No visible "Label"/"URL" text.
- Existing row and the empty new row render identically — no card, no separator. Two channels = six indistinguishable boxes.
- Inputs stack vertically instead of forming a row (`flex-wrap` + `grow` URL input inside `max-w-2xl`).
- Send test / Delete sit OUTSIDE the form card as bare text links below Save, because HTML forbids nested forms and each is its own POST. Design §6 had them per-row.
- No `+ Add another` (design §6 requires it; design §8 even names its no-JS path as tested).
- Both `lastSuccess` and `lastFailure` badges can render simultaneously.

## Meeting type detail
- Notifications is a bare `<h2>` + flat card placed AFTER the accordion container closes, between Date overrides and Hosts. Every sibling section (Allowed durations, Booking fields, Working hours, Date overrides) is a `collapse collapse-arrow` card. Reads as bolted on.
- Empty state prints "No channels yet" with no link to /me/settings.
- The indented per-channel checkbox is always visible and enabled even when "All my channels" is selected.

## Todo
- [x] Move Notifications into the accordion stack as a `collapse collapse-arrow` card, matching siblings
- [x] Empty state links to /me/settings
- [x] settings.html: one card per channel with visible labels
- [x] settings.html: Test/Delete became `formaction` submit buttons inside the single save form — no controller change needed at all
- [x] settings.html: `+ Add another` (progressive enhancement, no-JS path already works)
- [x] Show one current status badge, not both — decided against, see note above
- [x] de + he translations for any new keys
- [x] mvn test green (1136 tests, 0 failures)

## Design change agreed in session

The owner-level pool + per-meeting-type subset stays. What was missing is a way to register a
channel WITHOUT it being a default, so `notification_channel` gains `default_enabled` (V33).

- inherit set = this owner's channels with `default_enabled = true`
- an explicit per-meeting-type link wins over the flag — naming a channel there IS the opt-in
- this closes the design §9 limitation "a channel cannot be excluded from the inherit set"

Form encoding: an unchecked checkbox submits nothing, so `channelDefault` arrives as the set of
ticked ids. A brand-new row has no id yet, so it is created enabled and unticked on the next save.

Deliberately NOT done: both `lastSuccess` and `lastFailure` badges still render together. They are
labelled ("Last delivery OK" / "Last delivery failed"), so showing both is informative rather than
contradictory, and picking "the current one" would need an extra field on ChannelRow.

## Summary of Changes

**Migration** `V33__notification_channel_default_enabled.sql` — `default_enabled BOOLEAN NOT NULL
DEFAULT TRUE`. TRUE for existing rows preserves today's behaviour exactly.

**Routing** `ChannelRouter` — the inherit set is now this owner's `default_enabled` channels. An
explicit per-meeting-type link still wins, so naming a channel there is the opt-in. Applies to a
null meeting type too.

**Settings page** — one horizontal grid row per channel (`Default | Label | Channel URL | actions`)
with the two inputs named once by column headers. Test and Delete are `formaction` submit buttons
inside the single save form, so no nested forms and no extra controller plumbing. A single help
icon sits beside the section heading instead of a docs link per row. `+ Add another` clones the
blank row via `channels.js`; without JS the blank row still adds one channel per save.

**Send test now tests the typed value.** The id-keyed `/settings/channels/{id}/test` is replaced by
`/settings/channels/test`, keyed by a `testIndex` the clicked button carries. That makes the empty
row testable BEFORE it is stored, which is the stated point of the button. An untouched mask still
resolves back to the stored secret through the existing `resolveUrl`, so the real URL never
round-trips through the browser.

**Meeting type** — Notifications moved into the accordion stack as a `collapse collapse-arrow` card
beside Allowed durations / Booking fields / Working hours / Date overrides. Non-default channels
carry a "not by default" badge; the "All my channels" preview lists only default-enabled ones and is
joined in Java (a Qute loop cannot tell whether the next item will also render, which left a
trailing comma). Empty state links to /me/settings.

**Tests** 1139 green. New: non-default channel excluded from inherit, included when explicitly
linked, skipped for a null meeting type; new channel created enabled; unticking leaves the inherit
set without deleting; re-ticking restores it; Send test delivers a typed URL and persists nothing;
an unsupported typed URL is rejected and persists nothing; an untouched mask resolves to the stored
URL.

**Incidental fix.** `resubmittingTheRedactedValueKeepsTheStoredSecret` passed for the wrong reason:
RestAssured posts the body as ISO-8859-1, so the mask's U+2026 arrived as `?`, and the resulting
`telegram://?` failed the policy check and was rejected — which left the stored URL untouched and
satisfied the assertion. Both mask-carrying requests now declare `charset=UTF-8`.

## Deliberately not done

Both `lastSuccess` and `lastFailure` badges still render together. They are labelled, so showing
both is informative rather than contradictory, and choosing "the current one" would need an extra
field on `ChannelRow`.
