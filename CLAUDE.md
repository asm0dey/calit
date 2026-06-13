# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**calit** — self-hosted, multi-user Calendly alternative on Quarkus 3.36 / Java 25. Each user get isolated scheduling page at `/<username>/<slug>`: own meeting types, availability, bookings, settings, Google account. Server-rendered HTML via Qute; **no JavaScript ships at runtime** (few inline vanilla scripts aside). Stateless — run as N identical replicas; all shared state in Postgres.

## Build & run

```bash
bun install              # once — installs Tailwind/daisyUI CLI
bun run css:build        # compile src/main/css/input.css -> /calit.css (gitignored; build at least once or pages render unstyled)
bun run css:watch &      # rebuild CSS on change during dev
mvn quarkus:dev          # dev server at :8080 — Docker MUST be running (Dev Services provisions Postgres + mock mailer)
mvn package              # production build -> target/quarkus-app/quarkus-run.jar
```

- **Docker mandatory** for `quarkus:dev` and test suite: Quarkus Dev Services starts throwaway Postgres container. No embedded/H2 fallback.
- Fresh DB no users → every request redirects to `/setup` to create first (admin) user. No default password.

## Tests

```bash
mvn test                                              # full suite (Docker required)
mvn test -Dtest=BookingServiceTest                    # one class
mvn test -Dtest=BookingServiceTest#booksAvailableSlot # one method
```

- Surefire runs **`reuseForks=true`**: ONE reused JVM fork + ONE Dev Services Postgres shared across all same-profile `@QuarkusTest` classes (cold-boot per class took minutes). `@TestProfile` classes trigger in-JVM Quarkus restart. Heap pinned `-Xms512m -Xmx6g`.
- `DatabaseResetCallback` (registered via `src/test/resources/META-INF/services/`) truncates + reseeds DB per test. Admin user **always id 1**. Write owner-scoped tests against that invariant.
- Mailer mocked in `%dev`/`%test`; Google + Turnstile disabled by default. Full booking flow runs zero external accounts.
- RestAssured can't execute JS — tests assert on stable marker comments (e.g. `CALIT_TZ_REFORMAT`) instead of running scripts.

## Architecture

Packages under `src/main/java/com/calit/`:

- **`domain/`** — Panache `PanacheEntityBase` entities (public fields, no getters/setters): `MeetingType`, `AvailabilityRule`, `DateOverride[Window]`, `BookingField`, `OwnerSettings`. `Slugs`/`Usernames` for slug rules.
- **`user/`** — auth + tenancy. Custom `AppUserIdentityProvider` (passwords **argon2id** via `PasswordHasher`/BouncyCastle). NOTE: `quarkus-security-jpa` deliberately dropped — its generated Elytron provider raced custom one and rejected valid logins; only core `quarkus-security` used. `FirstRunRedirectFilter` drives `/setup` bootstrap. `SetupResource`, `EnabledUserAugmentor` (locked accounts).
- **`web/`** — Qute-backed JAX-RS resources (`AdminResource` = `/me` management UI, `PublicResource` = `/{username}/{slug}` booking, `UsersResource` = `/me/users` admin, plus Login/Signup/MeSetup/GooglePage). View-model records (`AccountView`, `WeekRow`, `CalendarRow`). `MeOwnerFilter`/`RememberMeFilter` are request filters.
- **`booking/`** — `BookingService` (core booking transaction), `BookingResource`, conflict/validation/rate-limit exceptions each with paired JAX-RS `*Mapper`. `TurnstileVerifier` + abuse protection. `events/` for domain events.
- **`availability/`** — `SlotService` computes bookable `TimeSlot`s from rules/overrides/buffers/min-notice/horizon. `DefaultAvailabilitySeeder` seeds new users.
- **`google/`** — Google Calendar OAuth + sync, behind ports (`CalendarPort`, `CalendarListPort`) with `Google*` implementations so it run degraded (no-Google) mode. `GoogleTokenService`, multi-account support.
- **`email/`** — `EmailService` (Qute email templates), `IcsBuilder` (.ics invites).
- **`scheduler/`** — `ReminderScheduler`, `PendingExpiryScheduler`. Multi-node-safe via Postgres `SELECT … FOR UPDATE SKIP LOCKED` — **no leader election**; any replica run background work.

### Owner scoping (multi-tenancy) — critical invariant

Every tenant row carries `owner_id`. `CurrentOwner` is `@RequestScoped` holder set by `MeOwnerFilter` for `/me` routes; **every query must filter by `currentOwner.id()`**. One user must never read or write another's data. Adding any query or entity → scope by owner. `CurrentOwner.require()` throws 401 if unset.

### Routes

`/me`, `/me/*` = logged-in user's own management UI (`@RolesAllowed("user")`). `/me/users` = site admins (`is_admin`). `/me/setup` = first-login wizard. `/{username}` + `/{username}/{slug}` = public. `/setup` = first-run bootstrap (404 once any user exists). `/signup` = 404 unless `SIGNUP_ENABLED=true` (restart to toggle). Health: `/q/health/live`, `/q/health/ready`.

### Templates / styling

Qute `@CheckedTemplate` (static native `TemplateInstance` methods in resource's inner `Templates` class) → `src/main/resources/templates/<ResourceName>/`. `maven.compiler.parameters=true` required so Qute sees template param names. UI is **Tailwind v4 + daisyUI 5** (custom `calit-light` theme) compiled to self-hosted `/calit.css` — no runtime CDN. (Some Java comments still mention "Pico CSS"; Pico removed.)

## Database / migrations

Flyway migrations `V1…V10` in `src/main/resources/db/migration/`, applied at boot (`quarkus.flyway.migrate-at-start=true`). Hibernate **validate-only** (`schema-management.strategy=validate`) — never creates schema; migrations own it. **Never edit applied migration** (Flyway checksum validation fails — even comment changes break it). Add new `V*.sql` for every change.

## Config

12-factor: all prod config via env vars (see `.env.example`, full reference in `README.md`). Key ones: `DB_*`, `APP_BASE_URL`, `SESSION_ENCRYPTION_KEY` (≥16 chars, identical on every replica), `MAIL_*`, optional `GOOGLE_OAUTH_*` + `GOOGLE_OAUTH_STATE_SECRET`, `TURNSTILE_*`, `SIGNUP_ENABLED`. Profiles: `%dev`/`%test` mock mailer; `%prod` requires real SMTP and secure cookies.

## Docker / CI

`Dockerfile` multi-stage: Bun compiles CSS → BellSoft **Liberica JDK 26** builds → **Liberica JRE 26 (musl)** runs. Tests skipped in image — run `mvn test` on host (with Docker) before building. CI is `.github/workflows/ci.yml` (test/build/merge/release, native multi-arch images to `ghcr.io/asm0dey/calit`). Dependency updates via **Renovate** (`renovate.json`), not Dependabot.

## Documentation

Public docs site lives on **`docs-site`** branch (Astro Starlight project in `docs-site/`, deployed to GitHub Pages at `https://asm0dey.github.io/calit/` by `.github/workflows/docs.yml` on push). Homepage reuses marketing landing; doc pages cover install, configuration, reverse-proxy, Google/Turnstile setup, usage, releases.

**On every interesting change, update docs too.** Any user-facing change — new/changed env var, route, config flag, setup step, feature, or upgrade/migration note — must land on `docs-site` branch same effort. Docs are part of "done", not follow-up.