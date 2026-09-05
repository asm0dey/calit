---
title: Changelog
description: Notable changes per release.
---

This changelog is maintained manually. The canonical release notes, including
asset downloads, are on
[GitHub Releases](https://github.com/asm0dey/calit/releases).

## 1.23.0

Booking links now unfurl with a generated preview card, meeting types can carry
a note for the person booking, and the overrides page folds past dates out of
the way.

- **Past date overrides no longer clutter the overrides page.** A host with a
  long history of date overrides saw every one of them, past and future, in a
  single flat list, which made the ones that still mattered hard to find. The
  overrides page now leads with upcoming overrides, soonest first, and folds
  everything before today into a collapsed **Past overrides (N)** section,
  newest first. Nothing is deleted — the old overrides are one click away, and
  the section only appears once you actually have some. "Today" is your own
  configured timezone, and an override dated today still counts as upcoming.
  ([#176](https://github.com/asm0dey/calit/pull/176))
- **Meeting types can carry a note for the person booking.** The booking page
  and landing card have always had a slot for a per-type description, but no
  form ever wrote it, so it was invisible in practice. Meeting types now have
  a **Note** box in their Basics section; whatever you write there shows under
  the meeting name and duration on the booking page and on the type's landing
  card. Worth knowing: the note also becomes the calendar event description,
  so it lands in the Google Calendar entry and the `.ics` invite for bookings
  of that type.
  ([#172](https://github.com/asm0dey/calit/pull/172))
- **Booking links now unfurl with a preview card.** A calit link pasted into
  Slack, WhatsApp, iMessage or a tweet used to render as a bare URL, with no
  title, description or image. Public pages now carry `og:`/`twitter:`
  metadata, and each meeting type gets a generated card image showing the
  owner, the meeting name and its length. Pages reached through a
  booking-management or guest-decline token deliberately carry **no**
  preview and are marked `noindex` — an unfurl there would paint the
  invitee's name and meeting time into whatever chat the link was pasted
  into. Secret meeting types show the generic calit card rather than naming
  the meeting.
  ([#155](https://github.com/asm0dey/calit/pull/155))
- **The production JVM image is now hardened and distroless.** It moved to a
  distroless BellSoft base with no shell and no package manager, so
  `docker exec -it <container> sh` (or any other shell-based debugging) no
  longer works against it. The base swap also brings the font stack this
  release needs for card rendering, and despite that it makes the image
  **smaller**: 214 MB → 211 MB. The native image is unaffected by the base
  swap and is about 161 MB.

Two things to check before you rely on this: set `APP_BASE_URL` to the
public URL of your instance — preview image and page URLs are absolute and
are built from it, so a wrong value has a visible failure mode. And keep
`/tmp` writable — `Font.createFont` spills the embedded font to a temporary
file, so a container run with a read-only root filesystem needs a tmpfs
mount at `/tmp` or card generation fails at request time. No database
changes.

## 1.22.0

A meeting type can offer several booking lengths, each with its own buffers,
and shared meeting types whose hosts sit in different timezones offer slots
again.

- **A meeting type can offer several lengths.** Running 30-, 60- and
  120-minute sessions used to mean three near-duplicate meeting types that
  differed only in duration. A meeting type now carries a set of allowed
  lengths, each with its own optional before/after buffers, and the invitee
  picks one above the slot grid with a plain link — no JavaScript required.
  Start times sit on the same lattice whichever length is picked, so
  switching length only drops the starts that no longer fit; it never moves
  the times already on offer.
  ([#153](https://github.com/asm0dey/calit/pull/153))
- **Shared meeting types across timezones offer slots again.** Each host's
  slot grid was anchored to midnight in that host's own timezone and then
  intersected by exact start time, so two hosts whose UTC offsets differed
  by a non-multiple of the slot cadence — London and Berlin on a 45-minute
  cadence, for instance — never matched on a single instant, and the page
  showed no available times at all. Every host's grid is now anchored to
  one clock, the meeting type creator's, so hosts sharing a type always
  share a lattice.
  ([#153](https://github.com/asm0dey/calit/pull/153))

A shared meeting type whose hosts span timezones may now show start times at
unround local minutes for some of those hosts — that is the timezone fix
working, since before it those hosts' pages showed no times at all.

## 1.21.0

Per-meeting-type Google calendars, working hours for brand-new accounts, a
disabled account that is genuinely disabled, and every booking now remembering
the calendar its event was created on.

- **Cancelling or rescheduling now reaches the event on the calendar it was
  actually created on.** calit remembered a booking's Google event id but not
  which calendar it lived on, so every later change was aimed at whichever
  calendar is your write target *now*. If you switched that target — or
  connected a different Google account — between taking a booking and
  cancelling it, calit aimed at the wrong calendar. Google replies "no such
  event", which since 1.20.1 is treated as "already deleted", so the
  cancellation looked like it worked while the meeting stayed on your old
  calendar forever. Rescheduling failed outright instead. Each booking now
  records the calendar and account its event was created on, and every later
  cancel, reschedule or detail edit is addressed there.
  ([#133](https://github.com/asm0dey/calit/pull/133))
- **Bookings taken before this upgrade are not retrofitted.** They have no
  stored calendar, so they keep resolving the old way — the fix protects
  bookings made from this version onward, not meetings already stranded on a
  calendar you have since stopped writing to. Existing bookings were left
  alone on purpose: guessing that they belong on your current write target
  would be wrong for precisely the bookings this bug affected.
- **Each meeting type can pick the Google calendar its events land on.**
  Before, every booking of every meeting type was created on the one
  calendar marked as your write target, so a personal "Coaching" type and a
  work "Client intro" type could not live on different calendars. Now a
  meeting type can name any of your selected calendars, your write target is
  what a type uses when it names none, and on a shared type each host picks
  their own — whoever organizes a booking writes it on their choice. If the
  chosen calendar is later unselected or its account is disconnected,
  bookings fall back to your write target instead of failing, the
  meeting-type page warns you, and your pick is kept rather than quietly
  erased. Changing a type's calendar tells you how many upcoming bookings
  stay on the calendar they were created on.
  ([#142](https://github.com/asm0dey/calit/pull/142))
- **New accounts now start with working hours already set.** Finishing the
  first-login wizard used to leave an account with no availability at all: its
  meeting types offered no bookable slots, and the working-hours grid rendered
  empty even though its help text promised your global defaults. Completing the
  wizard now sets Monday–Friday 09:00–18:00 as your global hours, which you can
  edit, extend, or clear like any other. Clearing them sticks — the defaults are
  set once, when you first complete the wizard, not restored afterwards.
  ([#145](https://github.com/asm0dey/calit/pull/145))
- **The meeting-type create form now uses the same working-hours grid as
  the edit page.** Creating a type only allowed one time frame per weekday;
  it now supports several frames per day plus the copy-to-all-days and
  copy-to-weekdays buttons.
  ([#147](https://github.com/asm0dey/calit/pull/147))
- **A malformed date on the meeting-type create form no longer rejects the
  whole submission.** Submitting a date override with an unparseable date, or
  a time window with an unparseable time, rejected the entire request — since
  the date override was saved together with the rest of the form, the new
  meeting type and its working hours were not created either. The bad value
  is now skipped and everything else on the form is saved. The same
  window-time guard now covers time windows added from the date-override
  pages. Relatedly, adding a date override with a malformed meeting-type id
  now returns a plain bad-request error instead of a server error; only a
  hand-crafted request could reach that, never the pages themselves.
  ([#147](https://github.com/asm0dey/calit/pull/147))
- **A disabled account no longer takes bookings.** Switching an account off
  stopped it logging in, but left its public page live and bookable: strangers
  could still book it, and every booking mailed someone who had left. The
  landing page, the booking page and the JSON booking API now all refuse for a
  disabled account. Invitees holding an existing manage link can no longer
  reschedule or change details either — that would put a new time on a departed
  host's calendar — but they can always still cancel.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- **Every /me page now shows times in your configured timezone.** The manage
  page used your browser's timezone while the dashboard and approval queue used
  the one in your settings, so the same booking showed two different clock times
  one click apart if you were travelling. All three now use your configured
  zone, the zone is named on screen, and the picker on the manage page is an
  explicit override rather than a second default.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- **Saving a Google Meet type whose calendar cannot create Meet links now
  explains itself.** It used to return a blank error page and lose whatever you
  had typed into the form. You now land back on the meeting type with a
  translated message telling you to pick another location or another calendar.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- **Reminder emails are no longer silently dropped.** A booking whose owner had
  no settings row made the reminder fail internally; it was marked as sent and
  the invitee never received it, with nothing surfacing but a log line. That
  path now degrades cleanly, and a timezone the system cannot read falls back to
  UTC instead of breaking every email for that account.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- **The "bookings stay behind" notice only appears when the calendar actually
  changed.** Saving a meeting type without touching its calendar still claimed
  bookings were staying behind, which read as though something had moved.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- **Counted messages read correctly at one.** Several notices said "1 upcoming
  bookings"; they now read correctly at any count, in English, German and
  Hebrew.
  ([#148](https://github.com/asm0dey/calit/pull/148))

Nothing to configure. The migrations run themselves. Disconnecting a Google
account still clears the stored calendar for its bookings, which fall back to
the previous behaviour. Every existing meeting type's write override starts
unset, which means "use my write target" — exactly today's behaviour — and
existing bookings are not moved to a type's new calendar.

Accounts that were created before this release and have **no global hours at
all** are given Monday–Friday 09:00–18:00 on upgrade. Anything you already set
is left untouched, and disabled accounts are skipped. One case to know about: if
you deliberately kept an account with no global hours, its meeting types that
have no hours of their own become bookable on those defaults. Such a type offers
no slots today, so it was already effectively parked — but if you want it to
stay that way, give it hours of its own or clear the global grid again after
upgrading.

Bookings that were taken on a disabled account's page **before** this upgrade
are not cancelled retroactively. They stay on the books, and the invitee can still cancel them
from their existing link.

## 1.20.2

A meeting type's working hours are now its whole week, so you can close a
weekday for one meeting type.

- **A weekday you leave blank in a meeting type's working hours is no longer
  bookable.** Until now that day silently fell back to your global weekly
  hours, so a meeting type configured for Monday and Tuesday only stayed
  bookable every other day your global schedule covered — and there was no way
  to say "never on Thursdays" for a single meeting type short of one date
  override per calendar date. A meeting type that defines any hours of its own
  is now driven by that grid alone. A meeting type with no hours of its own
  still follows your global schedule, and still picks up later edits to it.
  ([#127](https://github.com/asm0dey/calit/issues/127))
- **The hours editor fills itself in and can clear a day.** A meeting type with
  no hours of its own opens with your global hours already filled in — nothing
  is stored until you save — and every day row has a **Remove availability**
  button that empties that day. Same on a co-host's own hours for a shared
  meeting type.

On upgrade, check any meeting type that has its own working hours: it is now
bookable **only** on the days its own grid lists. Add the missing days there if
you were relying on the old fallback (the copy buttons make it quick). Meeting
types with no hours of their own are unaffected. No configuration or database
changes.

## 1.20.1

Cancelling a booking works again when the Google event was already deleted.

- **Cancelling no longer fails when the Google event is already gone.** If you
  deleted a booking's event straight from Google Calendar, cancelling that
  booking in calit returned a 500 and left it uncancelled: Google answers
  `410 Gone` for an event that no longer exists, and calit treated that as a
  hard error. An event that is already gone is now the outcome we wanted, so
  the cancellation goes through — the booking is cancelled and the invitee
  still gets the cancellation mail and `.ics`. Any other Google failure still
  aborts the cancel loudly, and rescheduling onto a deleted event still errors
  rather than silently reporting a move that never happened.
  ([#118](https://github.com/asm0dey/calit/issues/118))

## 1.20.0

Booking-page times now follow each visitor's own device, a per-account time
format setting, and diagnostics for Google Calendar sync failures.

- **Booking-page times now follow the visitor's own device.** Times were
  formatted using the page's display language, so bare `en` meant US defaults
  and every visitor to an English page saw `2:30 PM` rather than `14:30`. Only
  the clock format changed — weekday and month names still follow the page's
  language, so Hebrew and German pages keep their own wording.
  ([#116](https://github.com/asm0dey/calit/issues/116))
- **New per-account time format setting.** **Settings → Time format** offers
  Automatic, 24-hour and 12-hour, applied to your own `/me` pages and the
  emails you receive. It defaults to Automatic, which reproduces the previous
  output exactly, so upgrading changes nothing. The setting is yours alone — it
  never changes what a visitor sees on your booking page, nor the mail your
  invitees and their guests receive.
  ([#122](https://github.com/asm0dey/calit/pull/122))
- **Fixed raw timestamps on the dashboard.** `/me` and the pending-approvals
  list printed `2026-08-20T13:00:00Z UTC` instead of a readable time. They now
  render in your configured timezone, and remain readable with JavaScript
  disabled. The same bug also affected the public cancel and guest-decline
  pages, which invitees do see. ([#122](https://github.com/asm0dey/calit/pull/122))
- **Google Calendar sync now says why it failed.** Every failure on the Google
  path was swallowed silently, so anyone hitting "Couldn't reach Google for one
  or more accounts" on `/me/google` found nothing in their container logs and
  had nothing to report. calit now logs at each of those points — a failed
  calendar list, a refused token refresh, a revoked account — and puts Google's
  own status and message on the first line, so a `403 Calendar API has not been
  used in project N` is no longer buried in a cause chain. A single startup line
  reports the effective client id, redirect URIs and scope; the client secret is
  only ever reported as set or missing. No secrets are logged. For more detail,
  set `QUARKUS_LOG_CATEGORY__SITE_ASM0DEY_CALIT_GOOGLE__LEVEL=DEBUG` — see
  [Google OAuth → Troubleshooting](/calit/installation/google-oauth/#troubleshooting).
  ([#98](https://github.com/asm0dey/calit/issues/98))
- **Dependencies.** Quarkus 3.38.2, BouncyCastle 1.85.2, and refreshed Liberica
  and PostgreSQL base images.

## 1.19.0

Full timezone list for invitees, a Compose default fix, and a booking crash fix.

- **Invitee timezone picker now offers the full IANA list** (e.g.
  `Asia/Jerusalem`), sourced from the browser's
  `Intl.supportedValuesOf('timeZone')`, with the curated short list kept as a
  pre-2022 fallback. No server change. ([#102](https://github.com/asm0dey/calit/pull/102))
- **`docker compose up` now pulls the prebuilt image by default.** The app
  service used `build: .`, so Compose built from a local checkout even though
  the docs advertise pulling the published image; it now defaults to
  `image: ghcr.io/asm0dey/calit:latest`, with the build-from-source path
  documented in the Compose header comment. ([#104](https://github.com/asm0dey/calit/pull/104))
- **Fixed a 500 when creating a booking on a fresh install.** The first user
  created via `/setup` had no internal settings row until the first-login
  wizard ran, so a booking made before completing the wizard failed with an
  internal error. New installs seed the row up front, and existing installs are
  backfilled automatically on upgrade — no manual step required. ([#99](https://github.com/asm0dey/calit/issues/99))
- **Security.** Bumped to Quarkus 3.38.0 (pulls fixed netty and the PostgreSQL
  JDBC driver) and pinned `jackson-core` 2.22.1, clearing several CVEs. ([#102](https://github.com/asm0dey/calit/pull/102))

## 1.18.0

Optional OpenID Connect single sign-on.

- **Sign in with SSO (OIDC).** calit can now sit behind any OpenID Connect
  provider (Authelia, Keycloak, Auth0, Zitadel, Authentik, …) as a relying
  party. It is **optional and off by default** (`OIDC_ENABLED=false`) — form
  login is unchanged whether or not OIDC is configured. SSO is **login-only**:
  once the provider verifies the identity, calit bridges into its normal
  session, so there is no OIDC session to manage. Accounts link by the
  **verified** `email` claim; an unmatched login provisions a new account only
  when `SIGNUP_ENABLED=true`, and a verified email matching more than one
  account is rejected rather than guessed. Optional `OIDC_ADMIN_GROUP` grants
  calit admin from a `groups` claim (grant-only — never demotes a
  locally-granted admin). Configure with `OIDC_ENABLED`, `OIDC_ISSUER_URL`,
  `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, and optional `OIDC_ADMIN_GROUP`; the
  redirect URI is `${APP_BASE_URL}/api/oidc/login`. See
  [OIDC / SSO setup](/calit/installation/oidc-sso/).

- **Localised booking-page dates.** The timezone bar and slot-day date on the
  public booking pages now localise to the viewer's language (en/de/he) instead
  of always rendering in English.

## 1.17.0

Self-hosted ALTCHA CAPTCHA and email invitations for admin-created users.

- **Invite users by email.** When a site admin adds a user, calit now sends
  the person an **invitation email** with a link to set their own password,
  instead of the admin setting a temporary password to share out-of-band. The
  new account stays dormant (cannot log in) and shows **Awaiting activation**
  until the link is used; the activation link is valid for **48 hours**, and
  admins can **Resend invite** for a fresh link. Uses the existing `MAIL_*` and
  `APP_BASE_URL` config — no new settings. See
  [Users & admin](/calit/usage/users-admin/).

- **Self-hosted ALTCHA CAPTCHA.** The booking form's bot protection is now
  pluggable via `CAPTCHA_PROVIDER` (`none` | `turnstile` | `altcha`). The new
  **ALTCHA** option is a privacy-first, self-hosted proof-of-work challenge —
  no external service, no third-party account, and it works air-gapped (the
  widget script is served by calit, not a CDN). Configure it with
  `CAPTCHA_PROVIDER=altcha` + `ALTCHA_HMAC_KEY` (and optional
  `ALTCHA_MAX_NUMBER`); the widget localises to en/de/he automatically. See
  [ALTCHA setup](/calit/installation/altcha/). Existing Turnstile deployments
  are unaffected — `TURNSTILE_ENABLED=true` still selects Turnstile.

## 1.16.0

Multi-host meeting types — a meeting type can now require more than one host.

- **Multi-host meeting types.** A meeting type can have up to **10 hosts**
  total (the creator plus up to 9 co-hosts). Add co-hosts by username from
  the meeting-type form — an autocomplete suggests matching usernames as you
  type. The type only becomes bookable once **every** co-host has accepted;
  each invited co-host gets a one-click accept/decline email link and a
  pending request on their own `/me` dashboard, under a new **Shared**
  section. Each co-host sets their **own** working hours and buffers for the
  shared type independently — duration, minimum notice, and booking horizon
  still come from the creator's settings. Bookable slots are the
  **intersection** of every host's availability, and one booking creates a
  single calendar event shared by all hosts. The public page is reachable at
  `/<anyHost>/<slug>` — every accepted host's username is a valid alias for
  the same booking page, though the creator's URL is the canonical one used
  in emails. A shared type shows as temporarily unavailable — no bookable
  slots — while any host hasn't accepted, is disabled, or has a disconnected
  Google Calendar; calit never offers a slot it can't verify for every host.
  A requested slug is blocked if it collides with any host's existing slugs,
  in either direction. See [Multi-host meeting
  types](/calit/usage/multi-host-meetings/).
- **Cancel and reschedule act on the whole group.** Cancelling or
  rescheduling a multi-host booking (from any host's Manage link, or the
  invitee's) applies to every host at once — one shared calendar event, one
  set of notifications. For an approval-required shared type, any host's
  decline kills the booking, and rescheduling returns the whole group to
  pending approval.
- **Behavior change: single-host approval reschedule.** As part of the same
  work, an **owner-initiated** reschedule of a single-host, approval-required
  booking now **stays confirmed** instead of reverting to pending approval —
  only an **invitee-initiated** reschedule still sends it back to pending.
  Previously any reschedule of an approval-required booking reverted it,
  regardless of who initiated it.
- **Fix: booking-hold constraint is now owner-scoped.** The database
  constraint that rejects overlapping held bookings was instance-wide,
  ignoring which owner a booking belonged to — a latent bug that could also
  let per-host rows of a multi-host booking collide with each other. It is
  now scoped per owner, so different owners may legitimately hold
  overlapping bookings, but no single owner can ever double-book itself
  (V22 migration).
- **Progressive enhancement, not "no JavaScript."** calit's long-standing "no
  JavaScript ships at runtime" rule is now "progressive enhancement" —
  every feature must work fully without JavaScript, and JavaScript may only
  *enhance* it. The multi-host co-host autocomplete is the first feature
  built this way: typing a username works and suggests matches with
  JavaScript on, and still works as a plain text field with JavaScript off.
- **Booking page layout fixes.** The time-slots column now matches the
  calendar's height and scrolls on its own instead of looking stunted (or
  running longer than the calendar) beside it; available times render as a
  compact grid rather than one lonely full-width button per row, and the day
  and time you pick are echoed in the sidebar. The month calendar is
  right-sized instead of oversized.
- **Cleaner, more consistent screens.** Public pages are centered with the
  footer pinned to the bottom — now a single row with a clearer language
  switcher. Admin forms share one comfortable width, the co-host box is a
  roomier card, and time fields no longer overlap their picker icon.

## 1.15.1

A follow-up fix to the 1.15.0 booking editor.

- **A too-long meeting name or description now shows a clear error instead of
  silently failing.** Longer text — especially in non-Latin scripts like Hebrew,
  or with emoji — could be rejected by the server with an opaque low-level error
  before the form's own length check ran. The size limit was raised so the normal
  "too long" validation always applies.

## 1.15.0

Both the host and the invitee can now edit a booking after it's made — its name,
description, and guest list — not just reschedule or cancel it.

- **Edit a booking's name, description, and guests after booking.** From the
  **Manage** page — both the host (**/me** → a booking's Manage link) and the
  invitee (their manage link) — you can now rename a meeting, set or clear its
  description, and add or remove guests, all without rescheduling. The change is
  emailed to the other party and pushed to the Google Calendar event and the
  `.ics` invite (guests get an updated invite too). An untouched save changes
  nothing and notifies no one.
- **Reschedule is now time-only.** Guest editing moved into the new **Edit name &
  description** section, so the reschedule step only moves the meeting; picking the
  same time again is a no-op. The owner can now manage a booking's guest list too
  (previously guests were owner-read-only).
- **The Google Calendar event description now reflects the meeting's description**
  (previously a fixed "Booked via calit." placeholder). The meeting's displayed
  name across emails, the `.ics`, and the calendar event follows any per-booking
  rename.

## 1.14.1

Reschedule and cancellation emails now name the right person.

- **Host-initiated reschedules and cancellations are attributed correctly.** When
  the owner rescheduled or cancelled a booking (from **/me** or an owner email link),
  the notifications still read "*{guest} rescheduled their booking*" — blaming the
  guest for something the host did. The wording now follows who actually acted: a
  host-initiated change tells the guest "*{owner} rescheduled/cancelled your booking*"
  and gives the host a neutral notice, while guest-initiated changes are unchanged.

## 1.14.0

Google-native guest invites — when Google is connected, Google is the single
calendar source for everyone on a booking.

- **Guests now appear on the Google Calendar event.** When the owner has Google
  connected, invitee-added guests are added as attendees on the Google event
  (previously only the invitee and owner were), so they show up in the participant
  list and receive Google's own invitation. Guest changes stay in sync: declining a
  guest or rescheduling re-syncs the event's attendees, and a removed guest gets
  Google's cancellation.
- **No duplicate calendar entries when Google is connected.** calit no longer
  attaches its own `.ics` to booking emails when Google is connected — Google sends
  the authoritative invite/update/cancellation. calit still emails everyone so its
  **Reschedule** (invitee) and **Decline** (guest) links, which Google's native
  invite doesn't carry, still reach them. When Google is **not** connected, calit's
  `.ics` remains the only calendar source, unchanged.
- **Tidier emails.** Removed the redundant "This message was sent to the …" footer
  line from all booking emails.

Known limitation: because guests are now Google attendees, Google shows them its own
Accept/Decline buttons; a guest who responds in Google instead of via calit's decline
link won't update calit's guest list. No configuration or migration steps.

## 1.13.0

Owner-side booking management, plus a friendlier email sender name.

- **Owners can reschedule and cancel bookings.** Every upcoming booking on the
  owner dashboard (`/me`) now has a **Manage** link to a page that reschedules
  (from your own availability slots) or cancels the booking, notifying the invitee
  and guests. The same **Reschedule or cancel** link appears in your copy of the
  confirmation, reschedule, and reminder emails; it is login-gated and only works
  for the signed-in owner. Rescheduling preserves the booking's guests.
- **Friendlier email sender name.** Booking emails are now sent from
  **`<Owner name> via calit`** instead of a bare address that some clients rendered
  as "Notify". The underlying `MAIL_FROM` address and the `.ics` organizer are
  unchanged, so SPF/DKIM and Gmail invite rendering are unaffected. No configuration
  or migration steps.

## 1.12.1

A fix for booking invites in Gmail.

- **Gmail "Unable to load event" fixed.** Booking `.ics` invitations now set the
  calendar `ORGANIZER` to the address mail is actually sent from (`MAIL_FROM`),
  keeping the owner's name as the organizer display name. Gmail refuses to render an
  invitation whose organizer differs from the sender, so invitees and guests
  previously saw "Unable to load event" instead of the event card. No configuration
  or migration steps — pull `:1.12.1` (or `:1.12.1-native`) as usual.

## 1.12.0

Invitee guests, plus internal code-formatting tooling.

- **Invitee guests.** Invitees can now add guests to a booking — a chips field on
  the booking form (and on the reschedule page) takes up to 10 guest emails. Guests
  receive their own calendar invite and stay in sync: they get an `.ics` invitation
  when the meeting is created, an update when it is rescheduled, and a cancellation
  when it is cancelled. Guests cannot reschedule or cancel the meeting; a guest who
  can't attend uses a **decline** link in their invitation, which removes them and
  notifies the invitee. No configuration or migration steps beyond the usual upgrade.
- **Code formatting (contributor-facing).** The codebase is now auto-formatted with
  Spotless + palantir-java-format (Java) and Prettier (JS/CSS), enforced by a lefthook
  pre-commit hook and the CI `verify` gate. No runtime or configuration impact —
  pull `:1.12.0` (or `:1.12.0-native`) as usual.

## 1.11.1

A small fix for the native image.

- **Native image footer shows the real version again.** The page footer on the
  native (`-native`) image displayed `dev dev` instead of the release version and
  commit. The native build was compiling out the build-stamped `git.properties`;
  it is now explicitly bundled. The JVM image was unaffected. No configuration or
  upgrade steps are needed — pull `:1.11.1-native` (or `:latest-native`).

## 1.11.0

An optional GraalVM **native** container image with a much smaller runtime footprint,
published alongside the default JVM image.

- **Native image variant (`-native` tags).** Every published tag now has a GraalVM
  native counterpart — `:latest-native`, `:edge-native`, `:1.11.0-native`, etc. — built
  ahead-of-time and run on a minimal Alpaquita musl base with no JRE. Compared to the JVM
  image it is roughly half the size (~115 MB vs ~205 MB), uses far less memory at idle
  (~60 MB vs ~300 MB), and starts in well under a second. It is functionally identical and
  multi-arch (amd64 + arm64); the JVM image remains the default. Pick whichever fits your
  host — see [Docker Compose install](/calit/installation/docker-compose/#native-image-lower-footprint).

## 1.10.0

Hebrew (right-to-left) localization, plus a round of booking-email improvements:
approve/decline straight from email, role-specific owner and invitee copies, valid
calendar invites, an in-email cancel link, and immediate locale switching in Settings.

- **Hebrew localization with right-to-left (RTL) support.** The entire UI —
  public booking pages, the owner admin UI, and all notification emails — is now
  available in Hebrew (`עברית`) alongside English and German. When Hebrew is
  active, calit automatically mirrors the layout right-to-left (`<html dir="rtl">`)
  for both web pages and emails; no setting controls this, it follows the chosen
  language. Like German, Hebrew needs no configuration — it is always available,
  selectable from the footer language switcher (visitors) or **Settings**
  (owners), with untranslated phrases falling back to English. See
  [Language & localization](/calit/usage/languages/).

- **Approve or decline pending bookings straight from email.** When a booking
  requires approval, the request email now carries one-click **Approve** and
  **Decline** links. They open the owner console — if you are not signed in you
  log in first and are returned to the action — so only the authenticated owner
  can act on their own request. See [Bookings & approvals](/calit/usage/bookings/).

- **Cancel link in invitee emails.** Booking emails now include a direct
  **Cancel this booking** link (alongside the manage link), which opens a
  confirmation page before releasing the slot.

- **Role-specific booking emails.** Owner and invitee copies of every booking
  email now differ appropriately: the owner copy is addressed to the owner and
  names the invitee, the invitee copy is addressed to the invitee. Each side
  only sees the links relevant to it.

- **Calendar invites (`.ics`) fixed for Gmail.** The attached invite is now a
  valid iTIP request (it includes the attendee), so Gmail and other clients
  render the event card instead of showing "Unable to load event".

- **Language changes in Settings apply immediately.** Changing your admin
  language under **Settings** now updates the page in the same response, rather
  than after navigating away and back.

## 1.9.0

Google OAuth verification, German localization, and footer & first-run polish.

- **Google OAuth verification support.** A hosted instance can now pass Google's
  OAuth verification: set `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL` to serve a
  complete privacy policy at `/privacy` and terms at `/terms` (including Google's
  required Limited Use disclosure), and optionally `GOOGLE_SITE_VERIFICATION` to
  render the Search Console `<meta>` tag for domain verification. All three are
  optional; unset leaves the feature off (no tag; pages fall back to
  `APP_BASE_URL`). See [Google OAuth setup](/calit/installation/google-oauth/#oauth-verification).

- **German localization (English default + fallback).** The entire UI — public
  booking pages, the owner admin UI, and all notification emails — is now
  available in English and German. No configuration or environment variables are
  required: both languages are always on, and any untranslated phrase falls back
  to English. Booking visitors get a language switcher in the page footer (choice
  persisted in a `calit_lang` cookie, otherwise detected from `Accept-Language`),
  and the language used when booking is reused for that booking's follow-up
  emails. Account owners choose their own language in **Settings**, applied to
  their admin UI and the notification emails they receive. See
  [Language & localization](/calit/usage/languages/).
- **Build info in the footer.** Every page now shows the running release version
  and short git commit in the footer (e.g. `calit 1.8.0 · a1b2c3d`), so you can
  tell at a glance which build a deployment is running.
- **Footer, language switcher & first-run polish.** The footer is now a single
  shared component on every page (public and admin) with improved contrast, and
  the language switcher is a no-JS dropdown that scales past a handful of
  languages. `/privacy` and `/terms` are reachable before the first user is
  created (so Google's verification crawler can read them on a fresh instance).
  First-run setup auto-detects the visitor's timezone (falling back to UTC
  instead of a hardcoded zone). The privacy/terms pages now carry the full
  canonical policy in the site's visual style, and the marketing landing page is
  pinned to its light theme so its footer stays readable in dark-mode browsers.

## 1.8.0

Scheduler timing control and crash-safe dispatch.

- **Configurable grace window.** New `SCHEDULER_GRACE_SECONDS` setting (default
  `30`, `0` = exact). The reminder and pending-expiry ticks now treat a row as
  due up to N seconds early (`send_at <= now() + grace`), so replicas ticking on
  independent timers fire on time instead of waiting up to a whole extra tick.
  Postgres `now()` remains the single clock authority, so app-replica clock skew
  never affects which rows are due — this only smooths per-node tick latency.
- **Crash-safe reminder & auto-decline dispatch.** Both ticks now render the
  outgoing email and write it to the email outbox **inside the same transaction
  that claims the row** (marks the reminder sent / flips the booking to
  declined), instead of firing a post-commit in-memory event that a node crash
  between commit and send could drop. Claim and intent-to-send now commit
  atomically; the existing outbox tick delivers with retry/backoff. The manual
  owner-decline path is unchanged.
- New `SCHEDULER_GRACE_SECONDS` config. Dependency updates: Quarkus 3.36.3,
  `google-api-services-calendar`, and `actions/checkout` v7.

## 1.7.0

Google Calendar disconnect detection.

- **Booking page fails closed when Google is unreachable.** Previously a silent
  disconnect (dead refresh token) made every slot appear free, risking
  double-bookings. Now the public page shows "Scheduling temporarily
  unavailable" and new bookings are blocked while the calendar can't be read,
  so nothing lands on an event calit can't see.
- **Hourly connection probe.** Each connected Google account is checked on a
  schedule (a forced refresh-token round-trip), distinguishing a permanently
  dead grant from a transient blip. The probe also keeps the token warm,
  preventing the 6-months-unused expiry. Multi-node-safe with
  `SELECT … FOR UPDATE SKIP LOCKED`, no leader.
- **Reconnect email.** The owner is emailed once per outage with a link to
  reconnect (`/me/google`); the alert re-arms after the account recovers.
- Most recurring disconnects come from leaving the Google OAuth app in
  **"Testing"** publishing status (7-day refresh-token expiry) — publish it to
  "In production" to avoid them.
- New `GOOGLE_PROBE_INTERVAL` setting (duration, default `1h`). New V15
  migration adds `reconnect_notified_at` and `last_probed_at` columns.

## 1.6.0

Resilient email delivery and health probes.

- **Email survives SMTP outages.** Mail is sent synchronously; if a send
  fails, the message is parked in a new database outbox instead of being lost
  and retried by a background tick (every 60 s, on every replica, claimed with
  `SELECT … FOR UPDATE SKIP LOCKED` — multi-node-safe, no leader). Retries use
  exponential backoff (1 min doubling to 1 h, capped at 10 attempts). Booking
  and password-reset flows no longer fail when SMTP is unavailable.
- Time-sensitive mail carries a deadline: a queued password-reset email is
  dropped once its 30-minute token has expired, so a recovered SMTP server
  never delivers a dead reset link.
- **Health probes.** `GET /q/health/live` (liveness, process only) and
  `GET /q/health/ready` (readiness). The SMTP and Google checks are
  informational — always `UP`, exposing reachability under `data.state` — so a
  down mail server never pulls a replica out of rotation now that the outbox
  covers delivery.
- New V14 migration adds the `email_outbox` table. No new configuration — the
  outbox is always on and reuses the existing mailer settings.

## 1.5.0

Self-service password reset.

- Users who forget their password can reset it from the sign-in page via
  **Forgot password?**. Requesting by username emails a single-use,
  30-minute reset link to the account's stored address.
- The request never reveals whether an account exists (anti-enumeration);
  only a hashed token is stored server-side.
- Google-only accounts can set a password through the same flow.
- New V13 migration adds the `password_reset_token` table. No new
  configuration — reuses the existing mailer settings.

## 1.4.0

Token-at-rest encryption and security audit remediation.

- **Google OAuth tokens are now encrypted at rest** using AES-256-GCM
  (`TOKEN_ENCRYPTION_KEY`). Existing plaintext tokens are back-filled
  automatically on first boot — no reconnection required.
- Added `TOKEN_ENCRYPTION_KEY` config; production startup fails closed if the
  key is absent or too weak (mirrors the existing `SESSION_ENCRYPTION_KEY`
  guard from 1.3.1).
- Security audit remediation: CSRF tokens on all state-changing form POSTs,
  structured audit log for admin actions and failed logins, ReDoS-safe email
  regex, outbound HTTP timeouts and redirect policy, self-lockout and
  last-admin removal blocked, owner-scope invariant asserted at the JSON API
  layer, SQL logging restricted to `%dev`.
- Container hardened: non-root runtime user, base-image digest pinning,
  Trivy image-scan gate in CI, CodeQL analysis added.
- Google OAuth redirect URIs now derived from `APP_BASE_URL` (no localhost
  leak in production).
- `TOKEN_ENCRYPTION_KEY` **must not be rotated** after first boot without
  re-linking all Google accounts (see [Upgrading](/calit/releases/upgrading/)).

## 1.3.1

Production startup secret guard.

- App now fails fast at startup in `%prod` if required secrets
  (`SESSION_ENCRYPTION_KEY`, etc.) are missing or set to weak/dev defaults.

## 1.3.0

Sign in with Google.

- Users can authenticate via "Sign in with Google" in addition to
  username/password.
- Existing accounts are auto-linked by verified email; unknown Google
  identities can be provisioned as new passwordless users.
- Single-use login tickets bridge the Google OAuth callback to the existing
  form-auth session.
- New V11 migration: nullable `password`, `google_sub`, and `login_ticket`
  columns on `app_user`.
- Copy-meeting-type-link button added to meeting-type cards.

## 1.2.0

Seven-day schedule grid and brand favicon.

- Weekly availability is now displayed and edited as a seven-day grid (global
  schedule and per-meeting-type overrides).
- Bulk replace-all endpoints for weekly schedule slots.
- Brand favicon added matching the landing-page chip.
- Google Meet hint hidden on booking pages when the host has no connected
  Google account.

## 1.1.0

Multi-account Google Calendar.

- Users can connect more than one Google account; each is tracked with its own
  credentials.
- New `/me/google` UI for selecting which calendars to read for free/busy and
  which account to write new events to.
- FreeBusy checks fan out across all connected accounts; write-target routes to
  the selected account.
- New V4-extension migration for multi-account schema fields.

## 1.0.1

Postgres 18 volume fix, trademark disclaimer, version bump.

- Fixed Docker Compose volume configuration incompatible with Postgres 18.
- Added trademark disclaimer to README.
- Dependency and version bumps.

## 1.0.0

Initial release.

- Self-hosted, multi-user scheduling application on Quarkus / Java.
- Per-user booking pages at `/<username>/<slug>`.
- Google Calendar integration (read free/busy, write events).
- Email confirmations with `.ics` invites.
- Admin UI at `/me` for managing meeting types, availability, and settings.
- Site-admin user management at `/me/users`.
- Docker Compose deployment; native multi-arch images published to
  `ghcr.io/asm0dey/calit`.
- CI pipeline (GitHub Actions) with build, test, and release stages.
