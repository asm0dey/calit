---
title: Changelog
description: Notable changes per release.
---

This changelog is maintained manually. The canonical release notes, including
asset downloads, are on
[GitHub Releases](https://github.com/asm0dey/calit/releases).

## Unreleased

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
