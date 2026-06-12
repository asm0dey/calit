# Multi-account Google Calendar — Design

**Date:** 2026-06-12
**Status:** Approved (brainstorming), pending implementation plan

## Problem

A public booking page (e.g. `/asm0dey/30min`) shows every slot as free. Root cause: no
`google_calendar` row is flagged `read_for_busy = true`, so `GoogleCalendarPort.freeBusy` short-circuits
(`GoogleCalendar.readForBusy` returns empty → `freeBusy` returns `List.of()`), and no busy time is ever
subtracted. The backend save endpoint (`POST /api/google/calendars`) exists, but **no UI ever calls it** —
`templates/AdminResource/google.html` only has a GET link that dumps the calendar list as JSON. The
calendar-selection UI was never built.

While fixing this, a second requirement surfaced: the owner needs **multiple Google accounts** (e.g. a
personal and a work account) read for busy at once, with one chosen write target. The current model is
one account per owner, enforced by `uq_google_credential_owner UNIQUE (owner_id)`.

This spec covers the full multi-account feature (chosen over an incremental single-account fix).

## Decisions (resolved during brainstorming)

1. **Save integrity:** on save, re-fetch the authoritative calendar list from Google (per account) and
   apply the submitted flags against it; never trust client-submitted summaries/ids. One extra
   `calendarList` call per save (rare action).
2. **Write target ⇒ read-for-busy (hard):** the write-target calendar is always `read_for_busy = true`,
   enforced server-side; its read checkbox renders checked + disabled. No "write here but ignore its
   busy" state (would let calit double-book its own confirmed events).
3. **Google unreachable on GET:** catch, show an error banner, render no form (banner only).
4. **No "None" write target:** when connected, exactly one write target is required. The
   broken-rollback state (`createEvent` → `requireWriteTarget` throws → `@Transactional` rolls the
   booking back) is prevented by validation. calit-only (no-write) bookings are out of scope.
5. **First-load defaults:** all calendars read-checked; write target defaults to the first calendar in
   the list. No primary-flag detection (deliberately dropped).
6. **Account identity / dedupe:** add `openid email` to the OAuth scope; capture `sub` + `email` from the
   id_token. Dedupe on `(owner_id, google_sub)` — `sub` is Google's stable account id. `email` is the
   human label in the UI. One-time re-consent for existing users.
7. **Existing-data migration:** wipe `google_credential` + `google_calendar` in V9. Re-consent is
   mandatory for the new scope anyway; wiping avoids fragile backfill/matching of rows that have no
   `sub`. Release note: "reconnect Google after upgrade."
8. **Partial failure across accounts (`freeBusy`):** fail-soft — skip an account whose token is
   dead/unrefreshable, compute availability from healthy accounts, and flag that account
   `needs_reconnect` so the owner fixes it. One stale account must not take down the public booking page.
9. **Same calendar id across accounts:** a shared/subscribed calendar can appear under two accounts with
   the same `google_calendar_id`. Unique becomes `(google_credential_id, google_calendar_id)`; all form
   values are encoded `credentialId:calId` to disambiguate which account's copy.
10. **Disconnect holding the write target:** block until the owner reassigns the write target to a
    remaining account (never silently move where confirmed events get written). Disconnecting the *last*
    account is allowed → degraded mode (no Google, no write target needed).
11. **Phantom calendars / cannot verify existence without a live call:** the write target may only be
    *selected* from a live, verified `calendarList` (never from cached DB rows). If the only remaining
    account is flagged `needs_reconnect`, the owner must reconnect it first (no DB fallback — a cached
    calendar may have been deleted since). Tokens can still die after selection, so `createEvent` is
    hardened: catch Google **404** → clear the write target, flag the account, surface "write-target
    calendar no longer exists — re-select," fail that one booking cleanly (no raw 500).

## Schema (V9 migration)

- Wipe existing rows: `DELETE FROM google_calendar; DELETE FROM google_credential;` (selections are
  empty in practice; re-consent re-creates everything).
- `google_credential`:
  - drop `uq_google_credential_owner`;
  - add `google_sub VARCHAR NOT NULL`, `account_email VARCHAR`,
    `needs_reconnect BOOLEAN NOT NULL DEFAULT FALSE`;
  - add `UNIQUE (owner_id, google_sub)`.
- `google_calendar`:
  - add `google_credential_id BIGINT NOT NULL REFERENCES google_credential(id) ON DELETE CASCADE`;
  - drop `uq_google_calendar_owner_cal`; add `UNIQUE (google_credential_id, google_calendar_id)`;
  - keep `idx_google_calendar_single_write_target ON (owner_id) WHERE write_target = TRUE` — one write
    target per owner across all accounts.

## OAuth / tokens

- `GoogleOAuthConfig.scope`: add `openid email`.
- `GoogleTokenService.TokenResponse` + `requestToken`: capture the id_token, extract `sub` + `email`.
- `exchangeCode(ownerId, code, now)`: upsert credential by `(owner_id, sub)`. Re-consent updates the
  existing row; a different account inserts a new one. Clear `needs_reconnect` on success.
- `validAccessToken` becomes **per-credential** (e.g. `validAccessToken(GoogleCredential c, Instant now)`).
  Refresh failure → set `needs_reconnect = true` and surface upward (fail-soft).
- Connect flow unchanged (HMAC state carries `ownerId`); "connect another account" reuses
  `GET /api/google/connect`. `prompt=consent` + `access_type=offline` already force a refresh token.

## Port (`GoogleCalendarPort`)

- `freeBusy(ownerId, from, to)`: load read-for-busy calendars, group by `google_credential_id`, build a
  client per **healthy** credential, run a freebusy query per account, merge via `BusyIntervals.merge`.
  Skip flagged accounts.
- `createEvent`: resolve write-target calendar → its credential → that account's client. Catch Google
  **404** → clear write target, flag account, surface a clear error; otherwise unchanged.
- `CalendarListPort.listCalendars` → per-credential.

## Page `/me/google` (server-rendered — the missing UI)

- Per connected account: a section showing `account_email`, a "needs reconnect" badge when flagged, a
  **Disconnect** button (confirm; disabled/refused when it holds the write target and other accounts
  remain), and the account's live calendars, each with a read-for-busy checkbox.
- One global **write-target radio** spanning all healthy accounts' calendars. The selected write
  target's read checkbox is checked + disabled (decision 2).
- All form values encoded `credentialId:calId` (decision 9).
- "Connect another account" button always present.
- GET re-fetches live `calendarList` per account; any Google failure → banner, no form (decision 3).
- First load (no saved selection): all read-checked, write target = first calendar (decision 5).

## Save (`POST /me/google/calendars`, `MultivaluedMap<String,String>`)

- Re-fetch live lists per healthy account (decision 1); validate submitted `credentialId:calId` against
  them; drop phantoms; require exactly one write target present in a live list (decisions 4, 11); force
  the write target `read_for_busy = true` (decision 2). Replace the owner's `google_calendar` rows.
- Extract a shared `CalendarSelectionService.save(ownerId, selections)` used by both this form handler
  and the existing JSON `GoogleCalendarResource` (no duplicated persistence logic).

## Disconnect (`POST /me/google/accounts/{credentialId}/delete`)

- Cascade-removes the credential + its calendars. Blocked when it holds the write target and ≥1 other
  account remains (decision 10). If the only other account is flagged, reconnect-first (decision 11).
  Last account → degraded mode, allowed freely.

## Out of scope

- calit-only (no write target) bookings.
- More than the multi-account support described here (e.g. per-meeting-type calendar routing).

## Testing (TDD)

- `CalendarSelectionService.save`: replace semantics, ≤1 write target, write ⇒ read coupling, phantom
  drop, require-write-target-when-connected.
- Token service: per-credential refresh; `needs_reconnect` set on failure, cleared on reconnect; id_token
  `sub`/`email` capture and `(owner_id, sub)` dedupe.
- `freeBusy`: multi-account merge; skip-flagged account (fail-soft); empty when no read calendars.
- `createEvent`: routes to the write target's credential; Google 404 clears the write target + flags.
- Form handler: round-trip (POST → correct rows; unchecked → removed); `credentialId:calId` parsing.
- Disconnect: blocked while holding write target with other accounts present; allowed for last account.
- GET: live-list merge with saved flags prefilled; Google failure → banner, no 500.
