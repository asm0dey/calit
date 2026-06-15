---
title: Changelog
description: Notable changes per release.
---

This changelog is maintained manually. The canonical release notes, including
asset downloads, are on
[GitHub Releases](https://github.com/asm0dey/calit/releases).

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
