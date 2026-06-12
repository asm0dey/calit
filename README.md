# calit — self-hosted Calendly alternative

[![CI](https://github.com/asm0dey/calit/actions/workflows/ci.yml/badge.svg)](https://github.com/asm0dey/calit/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/asm0dey/calit?sort=semver)](https://github.com/asm0dey/calit/releases/latest)
[![Container](https://img.shields.io/badge/ghcr.io-asm0dey%2Fcalit-2496ED?logo=docker&logoColor=white)](https://github.com/asm0dey/calit/pkgs/container/calit)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.36-4695EB?logo=quarkus&logoColor=white)](https://quarkus.io)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://bell-sw.com/libericajdk/)

A **multi-user** scheduling app built on Quarkus — each user runs their own independent
scheduling page: isolated meeting types, availability, bookings, settings, and Google account, served
from a personal public URL `/<username>/<slug>`. You publish bookable meeting types; invitees pick
a slot and book. Bookings sync to Google Calendar (optional), auto-create a Google Meet link, and
email both parties. Includes per-type buffers, min-notice/booking-horizon, date-specific availability
overrides, an approval workflow, custom booking-form fields, reminders, and public-form abuse
protection (Cloudflare Turnstile + honeypot + per-email daily cap).

**Users & isolation.** Every user is an `app_user` row (passwords hashed with **argon2id**). All
tenant data carries an `owner_id`, and every query is owner-scoped — one user can never see or edit
another's meeting types, bookings, settings, or calendar. Site admins (`is_admin`) manage users at
`/me/users`: create users (with a one-time temporary password), grant/revoke admin, and lock/unlock
accounts (a locked account can no longer log in, and its existing session cookie stops working).

It runs as **N identical stateless replicas** behind a load balancer — there is no in-process session
state, all shared state lives in Postgres, and background work (reminders, pending-booking expiry) is
multi-node-safe via Postgres `SELECT … FOR UPDATE SKIP LOCKED` with no leader election.

---

## Screenshots

| Public landing | Booking page |
|---|---|
| ![A user's public landing page listing their bookable meeting types](src/main/resources/META-INF/resources/img/product-landing.png) | ![Booking page: a monthly calendar of available days beside a column of bookable time slots](src/main/resources/META-INF/resources/img/product-booking.png) |

| Owner dashboard | Booking confirmation |
|---|---|
| ![Owner dashboard showing upcoming bookings and a side navigation](src/main/resources/META-INF/resources/img/product-dashboard.png) | ![Booking confirmation screen shown to the invitee after they pick a time](src/main/resources/META-INF/resources/img/product-confirmation.png) |

---

## User accounts & onboarding

- **First run (bootstrap).** When the database has no users, every request redirects to `/setup`,
  which creates the first user as a **site admin**. Once any user exists, `/setup` returns 404.
- **Admin-created users.** A site admin creates accounts at `/me/users` with a username and a
  temporary password. The new user must change that password and complete the settings wizard on
  first login.
- **Opt-in self-service sign-up.** Public `/signup` is **off by default**. Set `SIGNUP_ENABLED=true`
  to let anyone register (username + their own password); when off, `/signup` returns 404. Changing
  the flag requires a restart — there is no runtime toggle.
- **Sign in with Google.** When a Google OAuth client is configured, `/login` shows a
  "Sign in with Google" button. A returning user (matched by the Google account's stable id, or
  auto-linked on first use when their *verified* Google email matches exactly one existing account)
  is logged straight in. An unknown Google account is provisioned a new user **only when
  `SIGNUP_ENABLED=true`** (otherwise sign-in is refused), and is sent through the first-login wizard
  with their email pre-filled. Register **both** `${APP_BASE_URL}/api/google/callback` (calendar) and
  `${APP_BASE_URL}/api/google/login/callback` (sign-in) as authorized redirect URIs in Google. The
  sign-in consent requests only your identity (email), not calendar access.
- **First-login wizard (`/me/setup`).** On first login a user is sent to `/me/setup` and kept there
  until onboarding is done: set a new password (only for admin-created temp-password accounts) and
  fill in display name, email, and timezone. After that they land on `/me`.

### URL scheme

| Path | Audience |
|---|---|
| `/me`, `/me/meeting-types`, `/me/availability`, `/me/settings`, … | The logged-in user's own management UI. |
| `/me/users` | Site admins only — user management. |
| `/me/setup` | First-login onboarding wizard. |
| `/{username}` | A user's public landing page (their active meeting types). |
| `/{username}/{slug}` | Public booking page for one meeting type. |
| `/setup` | First-run bootstrap (404 once a user exists). |
| `/signup` | Self-service registration (404 unless `SIGNUP_ENABLED=true`). |

---

## Requirements

- **Java 25** and **Maven** to build (the Maven wrapper `./mvnw` is included). The Docker image builds
  and runs on **BellSoft Liberica JDK/JRE 26**.
- **Bun** to compile the stylesheet. The UI is styled with **Tailwind CSS v4 + daisyUI 5** (custom
  `calit-light` theme); `bun run css:build` compiles `src/main/css/input.css` into the self-hosted
  `/calit.css` — there is **no runtime CDN dependency** (web fonts aside). No JavaScript ships at runtime.
- **PostgreSQL** at runtime. (For local dev/tests, Quarkus Dev Services starts a throwaway Postgres in
  **Docker** automatically — Docker must be running to run the test suite or `quarkus:dev`.)
- An **SMTP** account for outbound email.
- *(Optional)* A **Google Cloud** OAuth client to sync each user's calendar + create Meet links.
- *(Optional)* A **Cloudflare Turnstile** widget to harden the public booking form.

---

## Quick start (local dev)

```bash
# Docker must be running (Dev Services provisions Postgres + a mock mailbox).
bun install            # once
bun run css:watch &    # compiles src/main/css/input.css -> /calit.css and rebuilds on change
mvn quarkus:dev
```

(`/calit.css` is gitignored, so build it at least once or the pages render unstyled.)

- Public booking site: <http://localhost:8080/>
- Management UI: <http://localhost:8080/me> (form login at `/login`). On a fresh database, visit any
  page and you'll be redirected to `/setup` to create the first (admin) user — there is **no** default
  password.
- Health: `/q/health/live`, `/q/health/ready`.

In dev/test the mailer is mocked (no real email is sent) and Google/Turnstile are disabled by default,
so you can exercise the whole booking flow with no external accounts.

Run the tests (Docker required):

```bash
mvn test
```

## Run with Docker Compose (recommended for self-hosting)

The repo ships a `Dockerfile` (multi-stage: Bun compiles the CSS, BellSoft **Liberica JDK 26** builds
the app, **Liberica JRE 26** runs it) and a `docker-compose.yml` that runs the app plus its Postgres.

```bash
cp .env.example .env          # then edit .env — at minimum set DB_PASSWORD, SESSION_ENCRYPTION_KEY,
                              # APP_BASE_URL, and the MAIL_* values
docker compose up --build -d
```

The app image builds from source (tests are skipped in the image — run `mvn test` on the host with
Docker first), waits for a healthy Postgres, and Flyway applies the `V1…V11` migrations at boot. The
DB is persisted in the `calit-db` volume. Reach it at `http://localhost:${APP_PORT:-8080}/`.

Scale the stateless app behind your own load balancer:

```bash
docker compose up -d --scale app=3
```

(Everything below also applies to the compose deployment — the same env vars, set in `.env`.)

### Or run the prebuilt image (no local build)

Released versions are published as multi-arch images (linux/amd64 + linux/arm64) to GitHub
Container Registry: **`ghcr.io/asm0dey/calit`** (tags: `latest`, `1.3.1`, `1.3`). To deploy without
building from source, drop the `build:` and pull the image instead. Save this as `compose.yaml`:

```yaml
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: ${DB_NAME:-calit}
      POSTGRES_USER: ${DB_USER:-calit}
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
    volumes:
      - calit-db:/var/lib/postgresql   # postgres:18 default PGDATA moved under here
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER:-calit} -d ${DB_NAME:-calit}"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  app:
    image: ghcr.io/asm0dey/calit:1.3.1   # or :latest
    depends_on:
      db:
        condition: service_healthy
    env_file:
      - path: .env
        required: false
    environment:
      DB_URL: jdbc:postgresql://db:5432/${DB_NAME:-calit}
      DB_USER: ${DB_USER:-calit}
      DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
    ports:
      - "${APP_PORT:-8080}:8080"
    restart: unless-stopped

volumes:
  calit-db:
```

```bash
cp .env.example .env   # set DB_PASSWORD, SESSION_ENCRYPTION_KEY, APP_BASE_URL, MAIL_*
docker compose up -d   # pulls the image; Flyway migrates on boot
```

The image is public — no `docker login` needed. (If you fork and keep the package private, run
`docker login ghcr.io` with a token that has `read:packages` first.)

## Build & run for production

```bash
mvn package
java -Dquarkus.profile=prod -jar target/quarkus-app/quarkus-run.jar
```

The schema is created and kept up to date automatically: **Flyway runs the `V1…V11` migrations at
boot** (`quarkus.flyway.migrate-at-start=true`), and Hibernate validates the entities against it.
Point all replicas at the same database; each can serve any request.

---

## Configuration (environment variables)

All production config is supplied via environment variables (12-factor). Everything is read at startup;
the same values must be present on every replica.

### Required

| Variable | Purpose |
|---|---|
| `DB_PASSWORD` | Postgres password. |
| `SESSION_ENCRYPTION_KEY` | Encrypts the login cookie (>=16 chars). Must be the same on every replica. Generate with `openssl rand -hex 32`. **Required in prod.** |
| `APP_BASE_URL` | Public origin, e.g. `https://book.example.com`. Used to build invitee manage links in emails and the Google OAuth redirect; must match what users hit. |
| `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP server + the "from" address. |

### Common / defaulted

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/calit` | JDBC URL. |
| `DB_USER` | `calit` | Postgres user. |
| `MAIL_PORT` | `587` | SMTP port. `587` for STARTTLS, `465` for implicit TLS. |
| `MAIL_START_TLS` | `REQUIRED` | STARTTLS policy (`REQUIRED`/`OPTIONAL`/`DISABLED`). Use `REQUIRED` on port 587. |
| `MAIL_TLS` | `false` | Implicit TLS (SMTPS). Set `true` for port 465; keep `false` for STARTTLS on 587. |
| `REMINDER_LEAD_MINUTES` | `1440` | How long before a meeting the reminder email fires (24h). Also shown on the `/me/settings` page. |
| `APPROVAL_HOLD_HOURS` | `24` | How long an approval-mode booking is held as PENDING before it auto-declines (or until its start, whichever comes first). |
| `PER_EMAIL_DAILY_CAP` | `10` | Max bookings one invitee email may create per day (abuse guard). |
| `SIGNUP_ENABLED` | `false` | Allow public self-service sign-up at `/signup`. When `false`, `/signup` returns 404. |

### Google Calendar sync (optional)

Leave these unset to run in **degraded mode**: bookings still work, but no calendar events or Meet
links are created and the app emails the invitee directly (instead of Google sending the invite).

| Variable | Purpose |
|---|---|
| `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` | OAuth client credentials (see below). |
| `GOOGLE_OAUTH_REDIRECT_URI` | Must be `${APP_BASE_URL}/api/google/callback` and match the authorized redirect URI registered with Google. Defaults to `http://localhost:8080/api/google/callback`. |
| `GOOGLE_OAUTH_LOGIN_REDIRECT_URI` | Sign-in redirect URI for "Sign in with Google" (separate from the calendar one). Must be `${APP_BASE_URL}/api/google/login/callback` and registered as an authorized redirect URI in the same Google OAuth client. Defaults to `http://localhost:8080/api/google/login/callback`. |
| `GOOGLE_OAUTH_STATE_SECRET` | A strong random string shared by all replicas (signs the stateless OAuth CSRF token). Generate e.g. `openssl rand -hex 32`. |

### Cloudflare Turnstile (optional, public-form bot protection)

One switch turns on **both** the booking-form widget and server-side verification.

| Variable | Default | Purpose |
|---|---|---|
| `TURNSTILE_ENABLED` | `false` | Enable the widget + server verification together. |
| `TURNSTILE_SITE_KEY` | — | Public site key (rendered into the booking page). |
| `TURNSTILE_SECRET_KEY` | — | Secret key (server-side verification only; never rendered). |

> The honeypot field and the per-email daily cap are always on and need no configuration.

---

## How to obtain the keys

### Google OAuth client (Calendar + Meet)

1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and create (or pick) a project.
2. **APIs & Services → Library → enable the "Google Calendar API"**.
3. **APIs & Services → OAuth consent screen**: configure it (External or Internal), add each user's
   Google account as a test user if the app stays in "testing", and add the scope
   `https://www.googleapis.com/auth/calendar`.
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID → Web application**.
5. Under **Authorized redirect URIs** add exactly `${APP_BASE_URL}/api/google/callback`
   (e.g. `https://book.example.com/api/google/callback`).
6. Copy the **Client ID** and **Client secret** into `GOOGLE_OAUTH_CLIENT_ID` /
   `GOOGLE_OAUTH_CLIENT_SECRET`.
7. After deploy, each user connects their calendar **once** from the management UI (`/me/google` →
   Connect Google), grants offline access, and selects which calendars to read for busy time and which
   one to write events to. The refresh token is stored in Postgres, so any replica can call Google.

### Cloudflare Turnstile

1. In the [Cloudflare dashboard → Turnstile](https://dash.cloudflare.com/?to=/:account/turnstile),
   add a site/widget for your booking domain.
2. Copy the **Site Key** → `TURNSTILE_SITE_KEY` and the **Secret Key** → `TURNSTILE_SECRET_KEY`, then
   set `TURNSTILE_ENABLED=true`.

### SMTP

Use any provider (e.g. a transactional-email service or your own server). Set `MAIL_HOST`,
`MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and the encryption mode to match it:

- **Port 587 (STARTTLS)** — `MAIL_PORT=587`, `MAIL_START_TLS=REQUIRED`, `MAIL_TLS=false`.
- **Port 465 (implicit TLS / SMTPS)** — `MAIL_PORT=465`, `MAIL_TLS=true`, `MAIL_START_TLS=OPTIONAL`.

The port number alone does **not** pick the mode — set `MAIL_TLS` explicitly for 465.

---

## First-run checklist

1. Deploy with at least the **required** env vars set (DB, `SESSION_ENCRYPTION_KEY`, SMTP, `APP_BASE_URL`).
2. Visit any page; you'll be redirected to `/setup`. Create the first user — they become a **site admin**.
   There is no default password.
3. On first login you're sent to the **`/me/setup`** wizard: set your password (if applicable) and fill
   in display name, email, and IANA **timezone** (the canonical zone for all stored-time interpretation,
   emails, your management pages, and Google events). Then you land on `/me`.
4. *(Optional)* **Google** (`/me/google`): connect the calendar and choose read/write calendars.
5. **Availability**: set weekly work hours (global and/or per meeting type); add date-specific overrides.
6. **Meeting types**: create your bookable types (duration, buffers, min-notice, horizon, slot interval,
   location type — Google Meet / phone / in-person / custom, approval-required, and `secret` for
   link-only types).
7. *(Optional)* **Booking fields**: add custom questions to the booking form.
8. *(Admins)* Add more users at **`/me/users`** (each with a temporary password), or enable public
   `/signup` with `SIGNUP_ENABLED=true`.
9. Share your booking links. Your public types appear on `/{username}`; secret types are reachable only
   by direct link `/{username}/{slug}`.

---

## Notes & operational details

- **Authentication:** users live in the `app_user` table with **argon2id**-hashed passwords — there is
  **no** `ADMIN_PASSWORD` or embedded/env user. Login is via the `/login` form; the session is an
  encrypted **stateless cookie** (no server-side session store), so any replica can validate it. Account
  locks are enforced at authentication time — a locked user can't log in and their existing cookie stops
  working.
- **Per-user isolation:** every tenant table carries an `owner_id` and all queries are owner-scoped, so
  no user can see or edit another's data. `meeting_type.slug` is unique **per user**, so two users can
  both have e.g. `intro-call`.
- **Timezones:** each user's timezone is authoritative for storage interpretation, emails, and Google
  events. Invitee-facing pages additionally relabel times into the *viewer's* local timezone in the
  browser (the booked instant is unchanged). Invitee timezones are never stored.
- **Degraded mode:** with Google not connected, bookings are confirmed without a calendar event/Meet
  link and the app emails the invitee directly. When connected, Google emails the invite/change/cancel
  (`sendUpdates=all`) and the app suppresses the duplicate invitee mail; the user always gets the app
  email (unless opted out).
- **Background jobs** run on every replica every 60s and are single-delivery via `FOR UPDATE SKIP
  LOCKED` — reminder dispatch and pending-booking auto-expiry. No clustered scheduler is needed.
- **Double-booking** is prevented at the database level by a Postgres exclusion constraint covering
  PENDING+CONFIRMED bookings, so concurrent replicas cannot both win the same slot.
- **Migrations** are plain SQL under `src/main/resources/db/migration` (`V1`…`V11`) and run at boot.

---

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0). If you run a modified version
to provide a network service, you must offer its complete source to that service's users. See
[LICENSE](LICENSE) for the full text.

### Trademarks

"Calendly" is a trademark of Calendly LLC. calit is an independent, self-hosted project and is **not
affiliated with, endorsed by, or sponsored by Calendly**. The name is used only descriptively, to
indicate the category of tool calit replaces.
