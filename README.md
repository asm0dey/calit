# calit — self-hosted Calendly alternative

A single-owner scheduling app built on Quarkus. You publish bookable meeting types; invitees pick a
slot and book. Bookings sync to Google Calendar (optional), auto-create a Google Meet link, and email
both parties. Includes per-type buffers, min-notice/booking-horizon, date-specific availability
overrides, an approval workflow, custom booking-form fields, reminders, and public-form abuse
protection (Cloudflare Turnstile + honeypot + per-email daily cap).

It runs as **N identical stateless replicas** behind a load balancer — there is no in-process session
state, all shared state lives in Postgres, and background work (reminders, pending-booking expiry) is
multi-node-safe via Postgres `SELECT … FOR UPDATE SKIP LOCKED` with no leader election.

---

## Requirements

- **Java 25** and **Maven** to build.
- The UI is styled with **Pico CSS v2**, bundled as a Maven WebJar (`org.webjars.npm:picocss__pico`)
  and served locally via `quarkus-web-dependency-locator` — there is **no runtime CDN dependency**.
- **PostgreSQL** at runtime. (For local dev/tests, Quarkus Dev Services starts a throwaway Postgres in
  **Docker** automatically — Docker must be running to run the test suite or `quarkus:dev`.)
- An **SMTP** account for outbound email.
- *(Optional)* A **Google Cloud** OAuth client to sync the owner's calendar + create Meet links.
- *(Optional)* A **Cloudflare Turnstile** widget to harden the public booking form.

---

## Quick start (local dev)

```bash
# Docker must be running (Dev Services provisions Postgres + a mock mailbox).
mvn quarkus:dev
```

- Public booking site: <http://localhost:8080/>
- Owner admin: <http://localhost:8080/admin> (HTTP Basic — user `admin`, password `changeme` by default;
  override with `ADMIN_PASSWORD`).
- Health: `/q/health/live`, `/q/health/ready`.

In dev/test the mailer is mocked (no real email is sent) and Google/Turnstile are disabled by default,
so you can exercise the whole booking flow with no external accounts.

Run the tests (Docker required):

```bash
mvn test
```

## Run with Docker Compose (recommended for self-hosting)

The repo ships a `Dockerfile` (multi-stage, BellSoft **Liberica JDK 25**) and a `docker-compose.yml`
that runs the app plus its Postgres.

```bash
cp .env.example .env          # then edit .env — at minimum set DB_PASSWORD, ADMIN_PASSWORD,
                              # APP_BASE_URL, and the MAIL_* values
docker compose up --build -d
```

The app image builds from source (tests are skipped in the image — run `mvn test` on the host with
Docker first), waits for a healthy Postgres, and Flyway applies the `V1…V6` migrations at boot. The
DB is persisted in the `calit-db` volume. Reach it at `http://localhost:${APP_PORT:-8080}/`.

Scale the stateless app behind your own load balancer:

```bash
docker compose up -d --scale app=3
```

(Everything below also applies to the compose deployment — the same env vars, set in `.env`.)

## Build & run for production

```bash
mvn package
java -Dquarkus.profile=prod -jar target/quarkus-app/quarkus-run.jar
```

The schema is created and kept up to date automatically: **Flyway runs the `V1…V6` migrations at
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
| `ADMIN_PASSWORD` | Password for the single `admin` owner login (HTTP Basic). **Change it.** |
| `APP_BASE_URL` | Public origin, e.g. `https://book.example.com`. Used to build invitee manage links in emails and the Google OAuth redirect; must match what users hit. |
| `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP server + the "from" address. |

### Common / defaulted

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/calit` | JDBC URL. |
| `DB_USER` | `calit` | Postgres user. |
| `MAIL_PORT` | `587` | SMTP port. |
| `MAIL_START_TLS` | `REQUIRED` | STARTTLS policy (`REQUIRED`/`OPTIONAL`/`DISABLED`). |
| `REMINDER_LEAD_MINUTES` | `1440` | How long before a meeting the reminder email fires (24h). Also shown on the admin settings page. |
| `APPROVAL_HOLD_HOURS` | `24` | How long an approval-mode booking is held as PENDING before it auto-declines (or until its start, whichever comes first). |
| `PER_EMAIL_DAILY_CAP` | `10` | Max bookings one invitee email may create per day (abuse guard). |

### Google Calendar sync (optional)

Leave these unset to run in **degraded mode**: bookings still work, but no calendar events or Meet
links are created and the app emails the invitee directly (instead of Google sending the invite).

| Variable | Purpose |
|---|---|
| `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` | OAuth client credentials (see below). |
| `GOOGLE_OAUTH_REDIRECT_URI` | Must be `${APP_BASE_URL}/api/google/callback` and match the authorized redirect URI registered with Google. Defaults to `http://localhost:8080/api/google/callback`. |
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
3. **APIs & Services → OAuth consent screen**: configure it (External or Internal), add your owner
   Google account as a test user if the app stays in "testing", and add the scope
   `https://www.googleapis.com/auth/calendar`.
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID → Web application**.
5. Under **Authorized redirect URIs** add exactly `${APP_BASE_URL}/api/google/callback`
   (e.g. `https://book.example.com/api/google/callback`).
6. Copy the **Client ID** and **Client secret** into `GOOGLE_OAUTH_CLIENT_ID` /
   `GOOGLE_OAUTH_CLIENT_SECRET`.
7. After deploy, the owner connects the calendar **once** from the admin UI (Admin → Google → Connect
   Google), grants offline access, and selects which calendars to read for busy time and which one to
   write events to. The refresh token is stored in Postgres, so any replica can call Google.

### Cloudflare Turnstile

1. In the [Cloudflare dashboard → Turnstile](https://dash.cloudflare.com/?to=/:account/turnstile),
   add a site/widget for your booking domain.
2. Copy the **Site Key** → `TURNSTILE_SITE_KEY` and the **Secret Key** → `TURNSTILE_SECRET_KEY`, then
   set `TURNSTILE_ENABLED=true`.

### SMTP

Use any provider (e.g. a transactional-email service or your own server). Set `MAIL_HOST`,
`MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `MAIL_START_TLS` to match it.

---

## First-run checklist

1. Deploy with at least the **required** env vars set (a strong `ADMIN_PASSWORD`, DB, SMTP, `APP_BASE_URL`).
2. Log in to `/admin`.
3. **Settings**: set the owner name, email, and IANA **timezone** (this is the canonical zone for all
   stored-time interpretation, emails, admin pages, and Google events), and the owner-notification
   opt-out + reminder lead.
4. *(Optional)* **Google**: connect the calendar and choose read/write calendars.
5. **Availability**: set weekly work hours (global and/or per meeting type); add date-specific overrides.
6. **Meeting types**: create your bookable types (duration, buffers, min-notice, horizon, slot interval,
   location type — Google Meet / phone / in-person / custom, approval-required, and `secret` for
   link-only types).
7. *(Optional)* **Booking fields**: add custom questions to the booking form.
8. Share your booking links. Public types appear on `/`; secret types are reachable only by direct link
   `/book/{slug}`.

---

## Notes & operational details

- **Timezones:** the owner timezone is authoritative for storage interpretation, emails, and Google
  events. Invitee-facing pages additionally relabel times into the *viewer's* local timezone in the
  browser (the booked instant is unchanged). Invitee timezones are never stored.
- **Degraded mode:** with Google not connected, bookings are confirmed without a calendar event/Meet
  link and the app emails the invitee directly. When connected, Google emails the invite/change/cancel
  (`sendUpdates=all`) and the app suppresses the duplicate invitee mail; the owner always gets the app
  email (unless opted out).
- **Background jobs** run on every replica every 60s and are single-delivery via `FOR UPDATE SKIP
  LOCKED` — reminder dispatch and pending-booking auto-expiry. No clustered scheduler is needed.
- **Double-booking** is prevented at the database level by a Postgres exclusion constraint covering
  PENDING+CONFIRMED bookings, so concurrent replicas cannot both win the same slot.
- **Migrations** are plain SQL under `src/main/resources/db/migration` (`V1`…`V6`) and run at boot.
