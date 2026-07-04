---
title: Configuration
description: Every environment variable calit reads, with defaults and how to obtain secrets.
---

calit is configured entirely through environment variables. In a Docker Compose deployment these come from your `.env` file (loaded via `env_file`). Copy `.env.example` to `.env` and edit it before starting the stack.

## Database

| Variable | Description | Default |
|---|---|---|
| `DB_NAME` | Postgres database name | `calit` |
| `DB_USER` | Postgres user | `calit` |
| `DB_PASSWORD` | Postgres password | **required** |

The compose file derives `DB_URL=jdbc:postgresql://db:5432/${DB_NAME}` automatically — you do not set `DB_URL` in `.env`.

## App

| Variable | Description | Default |
|---|---|---|
| `APP_PORT` | Host port to expose (the container always listens on 8080) | `8080` |
| `APP_BASE_URL` | Public origin users hit — e.g. `https://book.example.com`. Used as the base for invitee manage links and the Google OAuth redirect URIs. | **required** |

On first run with an empty database every request redirects to `/setup` to create the first admin user. There is no default password.

## SMTP (email)

| Variable | Description | Default |
|---|---|---|
| `MAIL_HOST` | SMTP hostname | **required** |
| `MAIL_USERNAME` | SMTP username | **required** |
| `MAIL_PASSWORD` | SMTP password | **required** |
| `MAIL_FROM` | Sender address (e.g. `calit@example.com`) | **required** |
| `MAIL_PORT` | SMTP port | **required** |
| `MAIL_START_TLS` | STARTTLS mode: `REQUIRED`, `OPTIONAL`, or `DISABLED` | **required** |
| `MAIL_TLS` | Implicit TLS (SMTPS): `true` or `false` | **required** |

### Encryption mode — set explicitly

The port number does **not** automatically select an encryption mode. You must set `MAIL_PORT`, `MAIL_START_TLS`, and `MAIL_TLS` together. There are two valid combinations:

**Option A — port 587, STARTTLS (most common)**

Connection starts plaintext and upgrades to TLS via STARTTLS.

```dotenv
MAIL_PORT=587
MAIL_START_TLS=REQUIRED
MAIL_TLS=false
```

**Option B — port 465, implicit TLS / SMTPS**

Connection is encrypted from the first byte.

```dotenv
MAIL_PORT=465
MAIL_TLS=true
MAIL_START_TLS=OPTIONAL
```

`MAIL_START_TLS` is an **enum** (`REQUIRED` / `OPTIONAL` / `DISABLED`), not a boolean. `MAIL_TLS` is a boolean.

### Surviving SMTP outages

Mail is sent synchronously. If a send fails (SMTP down, refused, timing out), the message is parked in a database outbox instead of being lost, and a background tick retries it — every 60 s, on every replica, claimed with `SELECT … FOR UPDATE SKIP LOCKED` so it is multi-node-safe with no leader election. Retries use exponential backoff (1 min, doubling, capped at 1 h) and stop after 10 attempts; failed rows are kept for inspection.

Booking and password-reset flows therefore never fail just because SMTP is unavailable. Time-sensitive mail carries a deadline: a queued password-reset email is dropped (not delivered) once its 30-minute reset token has expired, so a recovered SMTP server never hands out a dead reset link. No configuration is required — the outbox is always on.

## Behaviour

| Variable | Description | Default |
|---|---|---|
| `REMINDER_LEAD_MINUTES` | Minutes before a meeting to send the reminder email | `1440` (24 h) |
| `APPROVAL_HOLD_HOURS` | How long a pending (approval-required) booking is held before it expires | `24` |
| `SCHEDULER_GRACE_SECONDS` | Treat reminder / pending-expiry rows as due up to this many seconds early, so replicas on unsynchronised tick timers fire on time instead of a tick late. `0` = exact | `30` |
| `PER_EMAIL_DAILY_CAP` | Maximum bookings an invitee email address may make per day (abuse protection) | `10` |

## Language

No configuration is required. English and German are built in and always available. See [Language & localization](/calit/usage/languages/) for how visitors and account owners select their preferred language.

## Health probes

calit exposes standard MicroProfile Health endpoints — point your orchestrator or load balancer at these:

| Endpoint | Purpose |
|---|---|
| `GET /q/health/live` | **Liveness** — the process is up. Does *not* check SMTP or Google, so a flapping external dependency can never get a healthy replica restarted. |
| `GET /q/health/ready` | **Readiness** — safe to route traffic. Includes *informational* SMTP and Google checks. |

The SMTP and Google checks are **informational**: they always report `UP` and expose reachability under `data.state` (`reachable`, `unreachable`, `mocked-or-unconfigured`, or `not-configured`). They never mark a replica `DOWN` — a down mail server does not pull the replica out of rotation, because outgoing mail falls back to the [outbox](#surviving-smtp-outages). Use `data.state` for observability, not as a gate.

## Secrets

| Variable | Description | Default |
|---|---|---|
| `SESSION_ENCRYPTION_KEY` | Signs and encrypts the login session cookie. At least 16 characters. Must be **identical on every replica**. | **required** |
| `TOKEN_ENCRYPTION_KEY` | AES-256-GCM key that encrypts Google OAuth tokens at rest. Must be exactly 64 hex characters. Must be **identical on every replica**. | **required in prod** |

Generate both keys with:

```bash
openssl rand -hex 32
```

:::caution[TOKEN_ENCRYPTION_KEY rotation]
Changing `TOKEN_ENCRYPTION_KEY` after deployment **strands all existing encrypted Google OAuth tokens** — users will need to re-authorise Google Calendar. Keep this key stable and back it up alongside your database.
:::

## Google Calendar (optional)

Leave `GOOGLE_OAUTH_CLIENT_ID` blank to run calit in degraded mode without Google Calendar integration. See [Google OAuth setup](/calit/installation/google-oauth/) for full instructions.

| Variable | Description | Default |
|---|---|---|
| `GOOGLE_OAUTH_CLIENT_ID` | Google OAuth client ID | *(blank — disables Google)* |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Google OAuth client secret | *(blank)* |
| `GOOGLE_OAUTH_REDIRECT_URI` | Override the calendar sync redirect URI | Derived: `${APP_BASE_URL}/api/google/callback` |
| `GOOGLE_OAUTH_LOGIN_REDIRECT_URI` | Override the sign-in redirect URI | Derived: `${APP_BASE_URL}/api/google/login/callback` |
| `GOOGLE_OAUTH_STATE_SECRET` | Strong random string shared by all replicas. Generate: `openssl rand -hex 32`. Required when Google is enabled. | *(blank)* |
| `GOOGLE_PROBE_INTERVAL` | How often each connected Google account is probed for a still-valid connection (a duration string, e.g. `30m`, `1h`, `2h`). This also keeps refresh tokens warm and sets how often a reconnect alert is evaluated. See [disconnect detection](/calit/installation/google-oauth/#disconnect-detection). | `1h` |

Register **both** derived redirect URIs in your Google OAuth client even if you do not override them.

## Public site & legal pages (optional)

These let a hosted instance pass Google OAuth verification. calit serves a privacy policy at `/privacy` and terms at `/terms`; each deployment is its own data controller, so the operator details are configurable. All three are optional and the feature is off/safe when unset.

| Variable | Description | Default |
|---|---|---|
| `GOOGLE_SITE_VERIFICATION` | Google Search Console domain-verification token. When set, every page renders `<meta name="google-site-verification">`. Leave blank to verify ownership via a DNS TXT record instead. | *(blank — no tag)* |
| `OPERATOR_NAME` | Legal entity running this instance, shown as the data controller on `/privacy` and `/terms`. | `APP_BASE_URL` |
| `PRIVACY_CONTACT_EMAIL` | Contact address shown on `/privacy` for privacy/data requests. Hidden when blank. | *(blank — line hidden)* |

See [Google OAuth setup → OAuth verification](/calit/installation/google-oauth/#oauth-verification) for when these are needed.

## CAPTCHA / bot protection (optional)

calit can add a bot challenge to the public booking form. Pick **one** provider with `CAPTCHA_PROVIDER`.

| Variable | Description | Default |
|---|---|---|
| `CAPTCHA_PROVIDER` | Which CAPTCHA to use: `none`, `turnstile`, or `altcha`. When unset, falls back to `turnstile` if `TURNSTILE_ENABLED=true`, otherwise `none`. | `none` |

### Cloudflare Turnstile

Hosted by Cloudflare (needs a Cloudflare account). See [Turnstile setup](/calit/installation/turnstile/).

| Variable | Description | Default |
|---|---|---|
| `TURNSTILE_ENABLED` | Enable Turnstile (also selects it when `CAPTCHA_PROVIDER` is unset) | `false` |
| `TURNSTILE_SITE_KEY` | Turnstile site key from the Cloudflare dashboard | *(blank)* |
| `TURNSTILE_SECRET_KEY` | Turnstile secret key | *(blank)* |

### ALTCHA

Self-hosted proof-of-work — no external service or account. See [ALTCHA setup](/calit/installation/altcha/).

| Variable | Description | Default |
|---|---|---|
| `ALTCHA_HMAC_KEY` | Secret that signs ALTCHA challenges. **Required** when `CAPTCHA_PROVIDER=altcha` (startup fails without it). Generate: `openssl rand -hex 32`. | *(blank)* |
| `ALTCHA_MAX_NUMBER` | Proof-of-work difficulty — max number the browser brute-forces | `100000` |
