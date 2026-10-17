# CalDAV calendar support — design

Bean: `calit-yotg` (parent epic `calit-0arg`, competitor parity).

## Intent

Google Calendar is the only calendar calit can read busy time from or write bookings to. Self-hosters
mostly run something else — Nextcloud, Fastmail, iCloud, mailbox.org, Radicale — and all of those
speak CalDAV. Cal.com and Zcal both offer CalDAV. The goal is parity: an Owner can connect a CalDAV
account and get the same two things Google gives today — busy time blocks slots, and each Booking is
mirrored as an event on a calendar they choose.

There is no specific user asking for this; the bar is "what Cal.com's CalDAV integration does".

## Goals

- An Owner connects a CalDAV account with **server URL + username + password** (app password).
- An Owner can have Google accounts **and** CalDAV accounts at the same time. Busy time from every
  read-for-busy calendar on every account, of either kind, blocks slots.
- The Owner picks which CalDAV calendars count for busy time, and can pick a CalDAV calendar as the
  write target or as a meeting type's write override — exactly as with Google calendars today.
- Create / reschedule / edit details / cancel of a Booking is mirrored to the CalDAV event, with the
  same rules as Google (ADR 0001: the event is a mirror; 404 through the stored ref recreates).
- Works against Nextcloud, Fastmail, iCloud, mailbox.org, Radicale.

## Non-goals

- Signing in to calit with a CalDAV account (Google/OIDC sign-in stay as they are).
- Two-way sync, deletion detection, webhooks (ADR 0001 already rules these out).
- A video link for CalDAV events (see "Decisions taken with the user" and call 11).
- CardDAV, tasks (VTODO), anything that isn't busy-read + event-write.
- Microsoft 365 (separate bean `calit-qj4b`); this design should leave room for it, not build it.

## Anti-goals

- A double booking because a CalDAV server was slow or broken. Fail closed, like Google.
- Guests getting two invitations (calit's `.ics` and one from the CalDAV server).
- calit becoming a tool for one tenant to probe the server's private network (SSRF).
- Two parallel models (Google tables + CalDAV tables) that every calendar feature has to handle twice.

## Decisions taken with the user

- **Alongside, not instead of Google.** Mixed accounts per Owner.
- **Meeting links:** a meeting type whose location is "Google Meet" but whose event is written to a
  CalDAV calendar gets no link; the Owner adds one themselves, as with "other" locations.
- **Private network:** server URLs resolving to private, loopback, link-local or otherwise
  non-public addresses are refused by default. `CALDAV_ALLOW_PRIVATE_HOSTS=true` lifts this for
  single-user / trusted instances. The check applies to the resolved IP on every request (including
  redirects), not just the URL string.

## Calls made by the designer (not discussed — review these)

1. **One generalised model, not parallel tables — migrated backward-compatibly** (decided with the
   user). Every migration must leave the *previous* release working against the new schema (rolling
   deploys of N replicas). So:
   - **This release:** `ALTER TABLE google_credential RENAME TO calendar_account`, same for
     `google_calendar` → `calendar`; add `provider text NOT NULL DEFAULT 'GOOGLE'` and nullable
     CalDAV columns (server URL, username, encrypted password, discovered calendar-home URL). Then
     create **compatibility views** under the old names — single-table, so PostgreSQL makes them
     auto-updatable — filtered `WHERE provider = 'GOOGLE'` and exposing every column under its old
     name. Old replicas read/write through them unchanged, never see CalDAV rows, and inserts get
     `provider = 'GOOGLE'` from the default. FKs follow the renamed tables.
   - **Next release:** drop the views, the old booking/meeting_type columns and their triggers.
   - **Columns on tables that stay** (decided with the user): add neutral columns
     `booking.event_id`, `calendar_remote_id`, `calendar_account_id` (FK `ON DELETE SET NULL`) and
     `meeting_type.override_remote_calendar_id`, `override_account_id`; backfill from the old ones;
     a `BEFORE INSERT OR UPDATE` trigger per table keeps each old/new pair equal (insert: both =
     `coalesce(new, old)`; update: whichever side `IS DISTINCT FROM OLD` is copied to the other).
     New code maps only the new columns. Next release drops the old columns and triggers together
     with the views. (`meeting_type_host` columns are excluded — `calit-5fw0` removes them.)
   - **Compatibility is proven by automated tests, no manual step:** the previous release's Flyway
     validates (V37 is a future migration), the views keep the old column names and types (Hibernate
     7.4.9 `validate` accepts views — verified in its source), and the previous release's native SQL
     still runs against the new schema.
   New Flyway migration(s) only; never edit applied ones.
2. **Ports stay, implementations split by provider.** `CalendarPort` / `CalendarListPort` become
   provider-neutral (`CalendarRef(accountId, remoteCalendarId)`, `CreatedEvent(eventId, meetLink,
   htmlLink, ref)`, no `GoogleCredential` in signatures). A dispatching implementation routes each
   call to the Google or CalDAV adapter by the account's provider; `freeBusy` fans out over all
   read-for-busy calendars of all accounts and merges.
3. **Busy time via `calendar-query` REPORT with a time range**, server-side `expand` for recurring
   events, not the CalDAV free-busy query (iCloud's support for it is unreliable). Count events
   unless `TRANSP:TRANSPARENT` or `STATUS:CANCELLED`. If the server won't expand, the builder expands
   recurrences client-side (ical4j or equivalent already-maintained library).
4. **Timeouts and failure.** Busy time is fetched live on every slot request today (no cache). CalDAV
   calls get a short connect/read timeout (order of a few seconds; exact value builder's call, made
   configurable). A failed or timed-out read throws `CalendarUnavailableException`, same as Google —
   slots fail closed. No cache is added in this work. A failed **write** at booking time fails the
   booking, same as Google today (decided with the user) — no retry outbox.
5. **Invitations come from calit for CalDAV.** Today `EmailService` skips the invitee `.ics` when
   Google is connected because Google sends invites. That check becomes "the account the event is
   written to sends invites" — true for Google, false for CalDAV. For CalDAV events calit sends its
   own `.ics` exactly as in degraded mode.
6. **No server-side scheduling.** The CalDAV event is written **without ATTENDEE properties**
   (guests' names/emails go in the description), so servers that implement RFC 6638 implicit
   scheduling (Nextcloud does) do not email guests a second invitation.
7. **Connect flow.** The Owner enters a URL; calit does RFC 6764 discovery
   (`/.well-known/caldav` → `current-user-principal` → `calendar-home-set`), so "https://cloud.example.com"
   is enough, and a full calendar-home URL also works. On save, calit authenticates and lists
   calendars; a failure is shown inline and nothing is stored. Only calendars supporting `VEVENT`
   are listed. The password is encrypted with the existing `TokenCipher` / `EncryptedStringConverter`.
8. **Health.** The existing scheduled probe that marks Google accounts `needs_reconnect` and notifies
   the Owner also probes CalDAV accounts; a 401/403 marks it `needs_reconnect`. The fix is "edit the
   account and re-enter the password", not OAuth.
9. **UI.** The calendar settings page stops being Google-only: it lists all connected accounts with
   their calendars, plus "Connect Google" and "Connect CalDAV". Route moves from `/me/google` to
   `/me/calendars` with a redirect from the old path. Works without JavaScript (plain form). The
   meeting type page shows a warning when location is "Google Meet" and the resolved write calendar
   is not Google.
10. **Glossary/ADRs.** "Google event" → "Calendar event" (or similar), "Connected account" covers
    both providers, "Degraded mode" = no calendar account connected at all. ADR 0001/0004/0006/0007
    keep their meaning; update wording to provider-neutral and add an ADR for "CalDAV events carry
    no attendees; calit sends the invites". Mirror decisions into the precedent graph per CLAUDE.md.
11. **Meet on a CalDAV calendar is allowed, not refused.** `WriteTargetResolver.blocksMeet` today
    refuses a GOOGLE_MEET type whose write calendar lacks Meet. That refusal stays for **Google**
    calendars without Meet (current behaviour unchanged); for **non-Google** calendars it is relaxed
    to warn-and-allow, and `createEvent` simply creates no link. The meeting type page shows the
    warning; booking never fails for this reason.
12. **`.ics` recipients when the event is on CalDAV.** The event already sits on the Creator's
    calendar, so the **Creator gets no `.ics` attachment** (it would duplicate). Invitee, guests and
    co-hosts **do** get calit's `.ics` — for co-hosts it is their only copy (ADR 0007). The CalDAV
    event's `UID` equals the `.ics` `UID` (the booking's `manageToken`), so a client that holds both merges them — e.g. an invitee/co-host mailbox that auto-imports invitations into the same CalDAV calendar (CalDAV forbids two events with one UID in a collection, so no duplicate). Reschedule and
    cancel `.ics` follow the same matrix. The confirmation page offers the `.ics` download as in
    degraded mode.
13. **Editing a CalDAV account changes only the password.** Server URL and username are fixed for the
    account's life; changing them means disconnecting and connecting a new account (overrides then
    dangle per ADR 0004/0006). Uniqueness: one account per (owner, discovered principal URL).
14. **No instance-level enable flag.** Google needs `GOOGLE_OAUTH_*` env to appear; CalDAV is always
    available (it needs no server-side secret). Only `CALDAV_ALLOW_PRIVATE_HOSTS` is new.

## Prerequisites

- **`calit-5fw0`** (remove the Co-host write override, per ADR 0007) lands first. This design
  assumes only the Creator's write override exists and the organizer is the Creator or nobody.
- **`calit-bgxr`** (calit's `.ics` is always `METHOD:REQUEST`, `SEQUENCE:0`) lands first. For CalDAV
  Owners calit's `.ics` is the only invite guests and co-hosts get, so cancel/reschedule must send
  `CANCEL` and a bumped `SEQUENCE`.

## Constraints

- First commit of the branch: add the backward-compatible-migrations rule to `CLAUDE.md`
  ("Database / migrations": the previous release must run on the new schema; expand/contract;
  renames via filtered compatibility views dropped a release later) and `precedent record` it.

- Existing Google behaviour and data must be unchanged by the migration — every current Google test
  stays green.
- Owner scoping: every account/calendar query filters by owner (CLAUDE.md invariant).
- New personal-data columns (CalDAV username, server URL, password) go into the hand-written privacy
  inventory guarded by its schema test, and into erasure/export.
- Progressive enhancement: the connect form and calendar picker work with JS off.
- All new user-facing strings in `AppMessages`/`AdminMessages` with `de` and `he` translations.
- Docs on the `docs-site` branch: new "CalDAV" setup page (per-provider notes: iCloud app-specific
  password, Fastmail app password, Nextcloud app password, URL formats), `CALDAV_ALLOW_PRIVATE_HOSTS`
  in configuration reference, changelog `## Unreleased` bullet.
- Tests run with no external accounts: CalDAV integration tests use a Radicale (or similar)
  container via Testcontainers; full `mvn test` green before PR.

## Left to the builder

- CalDAV client library choice (prefer an existing maintained Java library, e.g. built on ical4j;
  plain JDK `HttpClient` + XML for the handful of WebDAV verbs is acceptable if no library fits).
- Exact migration mechanics, timeout values, event UID scheme (must be stable per Booking so a
  recreate doesn't duplicate), ETag/If-Match use on update.
- How to split the work into PRs (a pure "generalise the model, Google-only" refactor PR first is
  the obvious seam).
