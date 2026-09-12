---
title: Changelog
description: Notable changes per release.
---

This changelog is maintained manually. The canonical release notes, including
asset downloads, are on
[GitHub Releases](https://github.com/asm0dey/calit/releases).

## 1.25.0

Booking events now reach Telegram, Slack, Discord, ntfy, Gotify or any webhook alongside email —
per owner, with per-meeting-type routing and a per-channel default.

- Owners can register notification channel URLs (Telegram, Slack, Discord, ntfy, Gotify, webhook and
  more) under `/me/settings` and receive every booking event on them. Migration `V32` adds
  `notification_channel`; URLs are encrypted at rest. ([#210](https://github.com/asm0dey/calit/pull/210))
- Channel delivery is tuned with `NOTIFY_ALLOWED_SCHEMES`, `NOTIFY_ALLOW_PRIVATE` and
  `NOTIFY_MAX_ATTEMPTS`. A meeting type can override which channels it uses, per host; a failed
  delivery shows when it failed, not why. ([#210](https://github.com/asm0dey/calit/pull/210))
- A notification channel has a **Default** checkbox. Untick it and the channel stays registered but
  only delivers for meeting types that name it explicitly. Migration `V33` adds
  `default_enabled`. ([#211](https://github.com/asm0dey/calit/pull/211))
- **Send test** now tests the URL in the field, so a channel can be checked before it is saved. The
  route moved from `/me/settings/channels/{id}/test` to `/me/settings/channels/test`.
  ([#211](https://github.com/asm0dey/calit/pull/211))
- The channel list is one row per channel with named columns, Test and Delete on the row, and one
  help icon by the heading. A meeting type's Notifications section is now a collapsible card like its
  neighbours. ([#211](https://github.com/asm0dey/calit/pull/211))

Upgrade: nothing to do — `TOKEN_ENCRYPTION_KEY` is already required, no channel exists until an owner
adds one, and `V33` defaults existing channels to on, so routing is unchanged until you untick
something.

## 1.24.0

Visible mail failures, owner notifications that name the invitee and say who
cancelled, and per-meeting-type name and guests fields.

- Cancellation mail now names who acted: the host reads "You cancelled your
  meeting with `<invitee>`", or "`<invitee>` cancelled their booking". Both
  strings are translated in German and Hebrew, and the invitee's own copy is
  unchanged. ([#201](https://github.com/asm0dey/calit/pull/201))
- Group bookings keep the previous neutral wording; the cancelling host is not
  recorded. ([#201](https://github.com/asm0dey/calit/pull/201))
- All owner-facing booking mail now carries an `Invitee:` line with the name
  and the address as a `mailto:` link. The invitee's copy does not.
  ([#196](https://github.com/asm0dey/calit/issues/196))
- Each meeting type sets its own booking-form fields: name `Required`
  (default), `Optional` or `Hidden`; guests `Optional` (default) or `Hidden`. A
  blank or hidden name files the booking under the part of the email before the
  `@`. ([#184](https://github.com/asm0dey/calit/pull/184))
- A hidden guests field is dropped from the booking form and the invitee's
  Manage page, and ignored server-side, JSON API included.
  ([#184](https://github.com/asm0dey/calit/pull/184))
- Hiding guests keeps the guests already on a booking: they still get invites
  and updates, they can no longer be removed, and the host's own guest editor
  goes away too. ([#184](https://github.com/asm0dey/calit/pull/184))
- Availability editor: the per-day buttons now sit beside the time inputs
  rather than next to the day name.
  ([#204](https://github.com/asm0dey/calit/pull/204))
- The owner dashboard now shows a banner when mail is unconfigured or
  unreachable, with a count of messages never delivered.
  ([#207](https://github.com/asm0dey/calit/pull/207))
- The banner stays up after mail recovers if messages were lost.
  ([#207](https://github.com/asm0dey/calit/pull/207))
- Copying a booking link while mail is down shows a red warning instead of
  "Copied". ([#207](https://github.com/asm0dey/calit/pull/207))
- The guest's confirmation page says the email could not be sent and will be
  retried, without showing any server-side detail.
  ([#207](https://github.com/asm0dey/calit/pull/207))
- That page also offers the calendar entry as an `.ics` download, hidden when
  Google Calendar is connected.
  ([#207](https://github.com/asm0dey/calit/pull/207))

Upgrade: nothing to do — the migration adds two columns whose defaults
reproduce the previous booking form. The email changes apply to mail sent from
this version onward, and the banner's count covers only messages already in the
outbox.

## 1.23.0

Booking links unfurl with a generated preview card, meeting types can carry a
note, and the overrides page folds past dates away.

- The overrides page now leads with upcoming overrides, soonest first, and
  folds past ones into a collapsed `Past overrides (N)` section, newest first.
  Nothing is deleted. ([#176](https://github.com/asm0dey/calit/pull/176))
- "Today" is your own configured timezone, and an override dated today counts
  as upcoming. ([#176](https://github.com/asm0dey/calit/pull/176))
- Meeting types now have a `Note` box in Basics, shown under the name and
  duration on the booking page and the type's landing card.
  ([#172](https://github.com/asm0dey/calit/pull/172))
- The note also becomes the calendar event description, so it reaches the
  Google Calendar entry and the `.ics` invite.
  ([#172](https://github.com/asm0dey/calit/pull/172))
- Public pages now carry `og:`/`twitter:` metadata, and each meeting type gets
  a generated card image with the owner, the meeting name and its length.
  ([#155](https://github.com/asm0dey/calit/pull/155))
- Pages reached by a booking-management or guest-decline token carry no preview
  and are `noindex`; secret meeting types show the generic calit card instead
  of naming the meeting.
  ([#155](https://github.com/asm0dey/calit/pull/155))
- The production JVM image is now distroless: no shell and no package manager,
  so `docker exec -it <container> sh` no longer works. It is 211 MB, down from
  214 MB, and the native image is unaffected at about 161 MB.

Upgrade: set `APP_BASE_URL` to your instance's public URL — preview image and
page URLs are absolute and built from it. Keep `/tmp` writable, with a tmpfs
mount on a read-only root filesystem, or card generation fails; no database
changes.

## 1.22.0

A meeting type can offer several booking lengths, and shared meeting types
across timezones offer slots again.

- A meeting type can now carry several allowed lengths, each with its own
  optional before/after buffers, and the invitee picks one above the slot grid
  with a plain link, no JavaScript required.
  ([#153](https://github.com/asm0dey/calit/pull/153))
- Start times sit on the same lattice for every length, so switching length
  only drops the starts that no longer fit.
  ([#153](https://github.com/asm0dey/calit/pull/153))
- Shared meeting types across timezones offer slots again: every host's grid is
  anchored to one clock, the meeting type creator's.
  ([#153](https://github.com/asm0dey/calit/pull/153))

Upgrade: a shared meeting type whose hosts span timezones may now show start
times at unround local minutes for some of those hosts.

## 1.21.0

Per-meeting-type Google calendars, working hours for new accounts, a disabled
account that is genuinely disabled, and bookings that remember their calendar.

- Each booking now records the Google calendar and account its event was
  created on, and every later cancel, reschedule or detail edit goes there.
  ([#133](https://github.com/asm0dey/calit/pull/133))
- Bookings taken before this upgrade are not retrofitted and keep resolving the
  old way. ([#133](https://github.com/asm0dey/calit/pull/133))
- A meeting type can now name any of your selected Google calendars for its
  events. A type that names none uses your write target; on a shared type,
  whoever organizes the booking uses their own choice.
  ([#142](https://github.com/asm0dey/calit/pull/142))
- If a chosen calendar is unselected or its account disconnected, bookings fall
  back to your write target, the meeting-type page warns you, and your pick is
  kept rather than erased.
  ([#142](https://github.com/asm0dey/calit/pull/142))
- Changing a type's calendar reports how many upcoming bookings stay on the
  calendar they were created on.
  ([#142](https://github.com/asm0dey/calit/pull/142))
- Disconnecting a Google account clears the stored calendar for its bookings,
  which fall back to the previous behaviour.
  ([#142](https://github.com/asm0dey/calit/pull/142))
- Completing the first-login wizard now sets Monday–Friday 09:00–18:00 as your
  global working hours. Those defaults are set once, at wizard completion, so
  clearing them sticks. ([#145](https://github.com/asm0dey/calit/pull/145))
- The meeting-type create form now uses the full working-hours grid: several
  frames per weekday, plus copy-to-all-days and copy-to-weekdays.
  ([#147](https://github.com/asm0dey/calit/pull/147))
- An unparseable date or window time on the create form is now skipped instead
  of rejecting the whole submission; the same window-time guard covers the
  date-override pages. ([#147](https://github.com/asm0dey/calit/pull/147))
- A malformed meeting-type id on a date override now returns a bad request
  instead of a server error.
  ([#147](https://github.com/asm0dey/calit/pull/147))
- A disabled account's landing page, booking page and JSON booking API now all
  refuse. Invitees holding a manage link can no longer reschedule or edit, but
  can always still cancel. ([#148](https://github.com/asm0dey/calit/pull/148))
- Every `/me` page now shows times in your configured timezone and names the
  zone on screen; the manage page's picker is an explicit override rather than
  a second default. ([#148](https://github.com/asm0dey/calit/pull/148))
- Saving a Google Meet type whose calendar cannot create Meet links now returns
  a translated message instead of a blank error page.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- Reminder emails are no longer dropped for a booking whose owner has no
  settings row, and an unreadable timezone falls back to UTC instead of
  breaking every email for that account.
  ([#148](https://github.com/asm0dey/calit/pull/148))
- The "bookings stay behind" notice only appears when the calendar actually
  changed. ([#148](https://github.com/asm0dey/calit/pull/148))
- Counted messages read correctly at one, in English, German and Hebrew.
  ([#148](https://github.com/asm0dey/calit/pull/148))

Upgrade: nothing to configure, and the migrations run themselves. Every
existing meeting type starts with no calendar override, meaning your write
target, and existing bookings are not moved.

Accounts with no global hours at all are given Monday–Friday 09:00–18:00 on
upgrade; disabled accounts are skipped and anything you already set is
untouched. Their meeting types that have no hours of their own become bookable
on those defaults — give such a type its own hours, or clear the global grid
again, to keep it parked.

Bookings taken on a disabled account's page before this upgrade are not
cancelled retroactively; the invitee can still cancel them from their existing
link.

## 1.20.2

A meeting type's working hours are now its whole week, so you can close a
weekday for one meeting type.

- A meeting type that defines any hours of its own is now driven by that grid
  alone, so a weekday you leave blank there is no longer bookable.
  ([#127](https://github.com/asm0dey/calit/issues/127))
- A meeting type with no hours of its own still follows your global schedule,
  including later edits to it.
  ([#127](https://github.com/asm0dey/calit/issues/127))
- The hours editor opens prefilled with your global hours — nothing is stored
  until you save — and each day row has a `Remove availability` button. Same on
  a co-host's own hours for a shared meeting type.

Upgrade: any meeting type with its own working hours is now bookable only on
the days its own grid lists — add the missing days there if you were relying on
the old fallback. No configuration or database changes.

## 1.20.1

Cancelling a booking works again when the Google event was already deleted.

- Google's `410 Gone` for an event that no longer exists is now treated as
  success, so the cancellation goes through and the invitee still gets the
  cancellation mail and `.ics`.
  ([#118](https://github.com/asm0dey/calit/issues/118))
- Any other Google failure still aborts the cancel, and rescheduling onto a
  deleted event still errors.
  ([#118](https://github.com/asm0dey/calit/issues/118))

## 1.20.0

Booking-page times now follow each visitor's own device, a per-account time
format setting, and diagnostics for Google Calendar sync failures.

- Booking-page times now follow the visitor's own device, not the page
  language, so an English page no longer shows `2:30 PM` for `14:30`. Weekday
  and month names still follow the page's language.
  ([#116](https://github.com/asm0dey/calit/issues/116))
- New per-account `Settings → Time format`: Automatic, 24-hour or 12-hour, for
  your own `/me` pages and the mail you receive. It defaults to Automatic and
  never changes what visitors, invitees or guests see.
  ([#122](https://github.com/asm0dey/calit/pull/122))
- `/me` and the pending-approvals list now render times in your configured
  timezone instead of a raw `2026-08-20T13:00:00Z UTC`, readable with
  JavaScript disabled. The same fix covers the public cancel and guest-decline
  pages.
  ([#122](https://github.com/asm0dey/calit/pull/122))
- Google Calendar sync failures are now logged — a failed calendar list, a
  refused token refresh, a revoked account — with Google's own status and
  message first. ([#98](https://github.com/asm0dey/calit/issues/98))
- A `403 Calendar API has not been used in project N` behind the `/me/google`
  warning is no longer buried in a cause chain.
  ([#98](https://github.com/asm0dey/calit/issues/98))
- A startup line reports the effective client id, redirect URIs and scope, and
  the client secret only as set or missing. No secrets are logged.
  ([#98](https://github.com/asm0dey/calit/issues/98))
- Set `QUARKUS_LOG_CATEGORY__SITE_ASM0DEY_CALIT_GOOGLE__LEVEL=DEBUG` for more
  detail — see [Google OAuth →
  Troubleshooting](/calit/installation/google-oauth/#troubleshooting).
- Dependencies: Quarkus 3.38.2, BouncyCastle 1.85.2, and refreshed Liberica and
  PostgreSQL base images.

## 1.19.0

Full timezone list for invitees, a Compose default fix, and a booking crash fix.

- The invitee timezone picker now offers the full IANA list (e.g.
  `Asia/Jerusalem`), from the browser's `Intl.supportedValuesOf('timeZone')`,
  with the curated short list kept as a pre-2022 fallback. No server change.
  ([#102](https://github.com/asm0dey/calit/pull/102))
- `docker compose up` now defaults to `image: ghcr.io/asm0dey/calit:latest`
  instead of `build: .`, with building from source documented in the Compose
  header comment. ([#104](https://github.com/asm0dey/calit/pull/104))
- Fixed a 500 when creating a booking on a fresh install made via `/setup`,
  before the first-login wizard had run. New installs seed the settings row up
  front, and existing installs are backfilled on upgrade — no manual step.
  ([#99](https://github.com/asm0dey/calit/issues/99))
- Security: Quarkus 3.38.0 (pulls fixed netty and the PostgreSQL JDBC driver)
  and pinned `jackson-core` 2.22.1, clearing several CVEs.
  ([#102](https://github.com/asm0dey/calit/pull/102))

## 1.18.0

Optional OpenID Connect single sign-on.

- calit can now sit behind any OpenID Connect provider (Authelia, Keycloak,
  Auth0, Zitadel, Authentik, …) as a relying party. It is optional and off by
  default (`OIDC_ENABLED=false`), and form login is unchanged.
- SSO is login-only: calit bridges into its normal session, so there is no OIDC
  session to manage.
- Accounts link by the verified `email` claim, and a verified email matching
  more than one account is rejected rather than guessed. An unmatched login
  provisions a new account only when `SIGNUP_ENABLED=true`.
- Optional `OIDC_ADMIN_GROUP` grants calit admin from a `groups` claim,
  grant-only: it never demotes a locally-granted admin.
- Configure with `OIDC_ENABLED`, `OIDC_ISSUER_URL`, `OIDC_CLIENT_ID`,
  `OIDC_CLIENT_SECRET` and optional `OIDC_ADMIN_GROUP`; the redirect URI is
  `${APP_BASE_URL}/api/oidc/login`. See
  [OIDC / SSO setup](/calit/installation/oidc-sso/).
- The timezone bar and slot-day date on the public booking pages now localise
  to the viewer's language (en/de/he).

## 1.17.0

Self-hosted ALTCHA CAPTCHA and email invitations for admin-created users.

- A site admin adding a user now sends an invitation email with a link to set
  their own password, instead of setting a temporary one to share out-of-band.
- The account stays dormant and shows `Awaiting activation` until the link is
  used; the link is valid for 48 hours, and admins can resend it for a fresh
  one.
- Uses the existing `MAIL_*` and `APP_BASE_URL` config — no new settings. See
  [Users & admin](/calit/usage/users-admin/).
- The booking form's bot protection is now pluggable via `CAPTCHA_PROVIDER`
  (`none` | `turnstile` | `altcha`).
- ALTCHA is a privacy-first, self-hosted proof-of-work challenge that needs no
  external service and works air-gapped.
- Configure it with `CAPTCHA_PROVIDER=altcha` and `ALTCHA_HMAC_KEY`, plus
  optional `ALTCHA_MAX_NUMBER`; the widget localises to en/de/he automatically.
  See [ALTCHA setup](/calit/installation/altcha/).

Upgrade: existing Turnstile deployments are unaffected —
`TURNSTILE_ENABLED=true` still selects Turnstile.

## 1.16.0

Multi-host meeting types — a meeting type can now require more than one host.

- A meeting type can now have up to 10 hosts: the creator plus up to 9
  co-hosts, added by username. An autocomplete suggests matching usernames as
  you type, and still works as a plain text field without JavaScript.
- The type becomes bookable only once every co-host has accepted. Each invited
  co-host gets a one-click accept/decline email link and a pending request in a
  new `Shared` section of their own `/me` dashboard.
- Each co-host sets their own working hours and buffers; duration, minimum
  notice and booking horizon come from the creator.
- Bookable slots are the intersection of every host's availability, and one
  booking creates a single calendar event shared by all hosts.
- The public page is reachable at `/<anyHost>/<slug>`; the creator's URL is the
  canonical one used in emails. A slug is blocked if it collides with any
  host's existing slugs, in either direction.
- A shared type offers no bookable slots while any host has not accepted, is
  disabled, or has a disconnected Google Calendar. See
  [Multi-host meeting types](/calit/usage/multi-host-meetings/).
- Cancelling or rescheduling a multi-host booking, from any host's Manage link
  or the invitee's, applies to every host at once. For an approval-required
  shared type, any host's decline kills the booking, and rescheduling returns
  the group to pending approval.
- Behaviour change: an owner-initiated reschedule of a single-host,
  approval-required booking now stays confirmed; only an invitee-initiated
  reschedule sends it back to pending approval.
- The overlapping-hold database constraint is now scoped per owner
  (V22 migration).
- calit's "no JavaScript at runtime" rule is now progressive enhancement: every
  feature works without JavaScript, which may only enhance it.
- Booking page: the time-slots column matches the calendar's height and scrolls
  on its own, available times render as a compact grid, the day and time you
  pick are echoed in the sidebar, and the month calendar is right-sized.
- Public pages are centered with the footer pinned to the bottom, in a single
  row with a clearer language switcher. Admin forms share one width, the
  co-host box is a roomier card, and time fields no longer overlap their picker
  icon.

## 1.15.1

A follow-up fix to the 1.15.0 booking editor.

- A too-long meeting name or description now shows the normal "too long" error
  instead of an opaque low-level one; the size limit was raised, so longer
  non-Latin text and emoji validate normally.

## 1.15.0

Both the host and the invitee can now edit a booking after it's made — its
name, description, and guest list — not just reschedule or cancel it.

- From the Manage page — yours via `/me`, the invitee's via their manage link —
  a booking can be renamed, redescribed, and have its guests changed without
  rescheduling.
- The change is emailed to the other party and pushed to the Google Calendar
  event and the `.ics` invite, guests included. An untouched save changes
  nothing and notifies no one.
- Reschedule is now time-only; guest editing moved into the new
  `Edit name & description` section, and the owner can now edit a booking's
  guest list too.
- The Google Calendar event description now uses the meeting's description
  instead of a fixed placeholder, and a per-booking rename follows through to
  emails, the `.ics` and the calendar event.

## 1.14.1

Reschedule and cancellation emails now name the right person.

- A host-initiated reschedule or cancellation is now attributed to the host:
  the guest reads "*{owner} rescheduled/cancelled your booking*" and the host
  gets a neutral notice. Guest-initiated changes are unchanged.

## 1.14.0

Google-native guest invites — when Google is connected, Google is the single
calendar source for everyone on a booking.

- With Google connected, invitee-added guests are now attendees on the Google
  event and get Google's own invitation.
- Declining a guest or rescheduling re-syncs the event's attendees, and a
  removed guest gets Google's cancellation.
- calit no longer attaches its own `.ics` when Google is connected; it still
  emails everyone, carrying the reschedule and decline links. When Google is
  not connected, calit's `.ics` is unchanged.
- Removed the redundant "This message was sent to the …" footer line from all
  booking emails.

Upgrade: no configuration or migration steps. A guest who answers through
Google's own Accept/Decline buttons will not update calit's guest list.

## 1.13.0

Owner-side booking management, plus a friendlier email sender name.

- Every upcoming booking on `/me` now has a Manage link that reschedules it
  from your own availability slots or cancels it, notifying the invitee and
  guests. Rescheduling preserves the booking's guests.
- The same reschedule-or-cancel link appears in your copy of the confirmation,
  reschedule and reminder emails; it is login-gated and only works for the
  signed-in owner.
- Booking emails are now sent from `<Owner name> via calit` instead of a bare
  address that some clients rendered as "Notify". The `MAIL_FROM` address and
  the `.ics` organizer are unchanged, so SPF/DKIM and Gmail invite rendering
  are unaffected.

Upgrade: no configuration or migration steps.

## 1.12.1

A fix for booking invites in Gmail.

- Booking `.ics` invitations now set the calendar `ORGANIZER` to the address
  mail is sent from (`MAIL_FROM`), keeping the owner's name as the organizer
  display name. Gmail no longer shows invitees and guests "Unable to load
  event".

Upgrade: no configuration or migration steps — pull `:1.12.1` (or
`:1.12.1-native`) as usual.

## 1.12.0

Invitee guests, plus internal code-formatting tooling.

- Invitees can now add guests to a booking: a chips field on the booking form
  and the reschedule page takes up to 10 guest emails.
- Guests get their own `.ics` invitation, an update when the meeting is
  rescheduled, and a cancellation when it is cancelled.
- Guests cannot reschedule or cancel; a guest who can't attend uses the decline
  link in their invitation, which removes them and notifies the invitee.
- Contributor-facing: the codebase is auto-formatted with Spotless +
  palantir-java-format (Java) and Prettier (JS/CSS), enforced by a lefthook
  pre-commit hook and the CI `verify` gate.

Upgrade: no configuration or migration steps beyond the usual — pull `:1.12.0`
(or `:1.12.0-native`).

## 1.11.1

A small fix for the native image.

- The footer on the native (`-native`) image showed `dev dev` instead of the
  release version and commit; `git.properties` is now explicitly bundled. The
  JVM image was unaffected.

Upgrade: nothing to do — pull `:1.11.1-native` (or `:latest-native`).

## 1.11.0

An optional GraalVM native container image, published alongside the default JVM
image.

- Every published tag now has a GraalVM native counterpart (`-native` tags):
  `:latest-native`, `:edge-native`, `:1.11.0-native`, built ahead-of-time and
  run on a minimal Alpaquita musl base with no JRE.
- It is roughly half the size (~115 MB vs ~205 MB), idles near ~60 MB instead
  of ~300 MB, and starts in well under a second.
- It is functionally identical and multi-arch (amd64 + arm64); the JVM image
  remains the default. See [Docker Compose
  install](/calit/installation/docker-compose/#native-image-lower-footprint).

## 1.10.0

Hebrew (right-to-left) localization, plus a round of booking-email
improvements.

- The entire UI and all notification emails are now available in Hebrew
  (`עברית`), alongside English and German, with untranslated phrases falling
  back to English.
- Hebrew mirrors the layout right-to-left (`<html dir="rtl">`) in both pages
  and emails, following the chosen language. See
  [Language & localization](/calit/usage/languages/).
- An approval-required booking request now carries one-click Approve and
  Decline links, usable only by the authenticated owner. See
  [Bookings & approvals](/calit/usage/bookings/).
- Booking emails now include a direct cancel link, which opens a confirmation
  page before releasing the slot.
- Owner and invitee copies of every booking email now differ, each showing only
  the links relevant to it.
- The attached `.ics` invite is now a valid iTIP request, so Gmail and other
  clients render the event card.
- Changing your admin language in Settings now updates the page in the same
  response.

## 1.9.0

Google OAuth verification, German localization, and footer & first-run polish.

- Set `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL` to serve a full privacy
  policy at `/privacy` and terms at `/terms`, including Google's required
  Limited Use disclosure.
- Optional `GOOGLE_SITE_VERIFICATION` renders the Search Console `<meta>` tag
  for domain verification. All three settings are optional; unset, the pages
  fall back to `APP_BASE_URL`. See
  [Google OAuth setup](/calit/installation/google-oauth/#oauth-verification).
- The entire UI and all notification emails are now available in English and
  German, with no configuration required and untranslated phrases falling back
  to English.
- Booking visitors get a language switcher in the page footer, persisted in a
  `calit_lang` cookie and otherwise detected from `Accept-Language`. The
  language used when booking is reused for that booking's follow-up emails.
- Account owners choose their own language in Settings, applied to their admin
  UI and the emails they receive. See
  [Language & localization](/calit/usage/languages/).
- Every page footer now shows the running release version and short git commit
  (e.g. `calit 1.8.0 · a1b2c3d`).
- The footer is one shared component on every page, and the language switcher
  is a no-JS dropdown that scales past a handful of languages.
- `/privacy` and `/terms` are now reachable before the first user is created,
  and carry the full canonical policy.
- First-run setup auto-detects the visitor's timezone, falling back to UTC, and
  the marketing landing page is pinned to its light theme.

## 1.8.0

Scheduler timing control and crash-safe dispatch.

- New `SCHEDULER_GRACE_SECONDS` setting (default `30`, `0` = exact): the
  reminder and pending-expiry ticks treat a row as due up to N seconds early
  (`send_at <= now() + grace`), so replicas ticking on independent timers fire
  on time instead of waiting up to a whole extra tick.
- Postgres `now()` remains the single clock authority, so app-replica clock
  skew never affects which rows are due.
- Both ticks now write the outgoing email to the email outbox inside the same
  transaction that claims the row. The manual owner-decline path is unchanged.
- Dependency updates: Quarkus 3.36.3, `google-api-services-calendar`, and
  `actions/checkout` v7.

## 1.7.0

Google Calendar disconnect detection.

- The public booking page now fails closed when Google is unreachable: it shows
  "Scheduling temporarily unavailable" and blocks new bookings.
- Each connected Google account is probed on a schedule with a forced
  refresh-token round-trip, which also keeps the token warm. Multi-node-safe
  with `SELECT … FOR UPDATE SKIP LOCKED`, no leader.
- The owner is emailed once per outage with a link to reconnect
  (`/me/google`); the alert re-arms after the account recovers.
- New `GOOGLE_PROBE_INTERVAL` setting (duration, default `1h`). New V15
  migration adds `reconnect_notified_at` and `last_probed_at` columns.
- Most recurring disconnects come from leaving the Google OAuth app in
  "Testing" publishing status (7-day refresh-token expiry) — publish it to "In
  production".

## 1.6.0

Resilient email delivery and health probes.

- A failed send is now parked in a new database outbox instead of being lost,
  and retried by a background tick every 60 s on every replica, claiming rows
  with `SELECT … FOR UPDATE SKIP LOCKED` — multi-node-safe, no leader.
- Retries use exponential backoff, 1 min doubling to 1 h, capped at 10
  attempts.
- Booking and password-reset flows no longer fail when SMTP is unavailable.
- A queued password-reset email is dropped once its 30-minute token has
  expired.
- New health probes: `GET /q/health/live` (liveness, process only) and
  `GET /q/health/ready` (readiness). The SMTP and Google checks are
  informational — always `UP`, exposing reachability under `data.state`.
- New V14 migration adds the `email_outbox` table; no new configuration, and
  the existing mailer settings are reused.

## 1.5.0

Self-service password reset.

- Users who forget their password can reset it from the sign-in page via
  "Forgot password?". Requesting by username emails a single-use,
  30-minute reset link to the account's stored address.
- The request never reveals whether an account exists (anti-enumeration);
  only a hashed token is stored server-side.
- Google-only accounts can set a password through the same flow.
- New V13 migration adds the `password_reset_token` table. No new
  configuration — reuses the existing mailer settings.

## 1.4.0

Token-at-rest encryption and security audit remediation.

- Google OAuth tokens are now encrypted at rest using AES-256-GCM
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
