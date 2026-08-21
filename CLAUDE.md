# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**calit** — self-hosted, multi-user Calendly alternative on Quarkus 3.38 / Java 25. Each user get isolated scheduling page at `/<username>/<slug>`: own meeting types, availability, bookings, settings, Google account. Server-rendered HTML via Qute; **progressive enhancement** — every feature works without JavaScript; JS is optional, kept minimal and simple, and only enhances (e.g. a small inline typeahead over a plain input that already submits fine on its own). Stateless — run as N identical replicas; all shared state in Postgres.

## Library source / javadocs

Need a dependency's API or source? Prefer the **`javadocs` MCP** (configured in `.mcp.json`, server `https://www.javadocs.dev/mcp`) over decompiling jars. Decompile only when the MCP lacks the artifact.

## Agent skills

Skills live in `.agents/skills/`; `.claude/skills/` and `.crush/skills/` are symlinks into it. Nothing under either is tracked — restore after cloning with `bunx skills experimental_install`, which reads `skills-lock.json` and recreates the per-harness symlinks. The `quarkus` skill is not in the lock: it is written by the `quarkus-agent` MCP (`quarkus_skills` / `quarkus_saveSkill`, configured in `.mcp.json`), or comes from a global install.

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
- Dev Services Postgres is **reused between runs** (`quarkus.datasource.devservices.reuse=true` in
  `src/test/resources/application.properties`) and the reuse hash knows nothing about your branch or
  worktree — a container parked by a branch with more migrations would otherwise fail the next boot with
  `FlywayValidateException: Detected applied migration not resolved locally: NN`. `quarkus.flyway.clean-at-start=true`
  (same file) drops and re-migrates the schema on every `%test` boot, so a stale container is harmless.
  If you ever need a genuinely fresh container, run with `-Dquarkus.datasource.devservices.reuse=false`.
  The reused container serves one suite run at a time: `clean-at-start` drops the schema at boot, so a
  second concurrent `mvn test` against the same container fails with "relation does not exist". (Not new
  — `DatabaseResetCallback`'s `TRUNCATE … RESTART IDENTITY CASCADE` already made concurrent runs unusable
  — but this failure mode is more confusing, so it's worth naming.)
- `DatabaseResetCallback` (registered via `src/test/resources/META-INF/services/`) truncates + reseeds DB per test. Admin user **always id 1**. Write owner-scoped tests against that invariant.
- Mailer mocked in `%dev`/`%test`; Google + Turnstile disabled by default. Full booking flow runs zero external accounts.
- RestAssured can't execute JS — tests assert on stable marker comments (e.g. `CALIT_TZ_REFORMAT`) instead of running scripts.

**Never open a PR while the test suite is red.** `mvn test` must be fully green —
0 failures, 0 errors, `BUILD SUCCESS` — before a branch becomes a pull request,
and the run has to be the *whole* suite, not the classes you happened to touch.
No exceptions for "that failure is unrelated" or "it was already broken on
`main`": if a test fails on your branch, fixing it is part of your branch. Land
the repair first (its own commit, or merged in from its own branch) and say in
the PR that you did.

A failure you did not cause is still a failure the reviewer has to triage, and
"known red" is how a suite stops being a signal at all. When a pre-existing
break genuinely blocks you, fix it as a prerequisite rather than annotating it —
that is what makes the green run mean something.

## Formatting

- **Java**: **Spotless + palantir-java-format** (PALANTIR) + curated **CleanThat** mutators (diamond operator, `var`, method refs, redundant-code cleanup). Config in `pom.xml` (`spotless-maven-plugin`).
- **JS/CSS**: **Prettier** (`.prettierignore` skips the generated `calit.css`). Qute `.html` templates are deliberately **not** formatted — Prettier mangles `{#if}`/`{msg:}` tags.

```bash
bun run format       # format everything (format:java = mvn spotless:apply, format:fe = prettier)
mvn spotless:check   # verify Java (bound to `verify` phase → CI gate)
```

- **Pre-commit auto-format** (`lefthook.yml`, re-staged via `stage_fixed`): staged `*.java` → `./mvnw spotless:apply -DspotlessFiles=…` (Maven directly); staged `*.{js,ts,css}` → `bunx prettier --write`. Hooks are wired automatically by `bun install` (package.json `prepare` → `lefthook install`); run `bun install` once after cloning.
- Pin palantir **≥ 2.71.0** (we use 2.94.0): older versions hit `NoSuchMethodError: …DeferredDiagnosticHandler.getDiagnostics()` and crash under **JDK 25/26** (in-process javac internals; the build JDK is Liberica 26). 2.71.0+ also parses Java 25 unnamed variables (`_`) fine.
- `verify` (hence CI) fails on unformatted code. `mvn test` is unaffected (test phase < verify).

## Architecture

Packages under `src/main/java/site/asm0dey/calit/`:

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

### Internationalization (i18n)

User-facing strings are type-safe `@Message` keys: UI bundle `AppMessages` (`{msg:key}`, namespace `msg`) and admin bundle `AdminMessages` (`adm`). The English text is the `@Message` default on the method; translations live in `src/main/resources/messages/{msg,adm}_{de,he}.properties`, keyed by method name. A missing key silently falls back to the English default.

**Every new or changed user-facing string MUST be translated in the same change** — add the `de` **and** `he` value to the matching `messages/*.properties` file alongside the new `@Message` default. Do not lean on the English fallback. If you genuinely can't provide a translation (e.g. no confident Hebrew), open a GitHub issue labelled for translation and reference it in the PR — the key still ships with its English default, but the gap is tracked, never silent. Keep `{placeholder}` names identical across all locales. Quick parity check: every `String key()` in a bundle interface must have a matching `key=` line in each locale file.

## Database / migrations

Flyway migrations `V1…V25` in `src/main/resources/db/migration/`, applied at boot (`quarkus.flyway.migrate-at-start=true`). Hibernate **validate-only** (`schema-management.strategy=validate`) — never creates schema; migrations own it. **Never edit applied migration** (Flyway checksum validation fails — even comment changes break it). Add new `V*.sql` for every change.

## Config

12-factor: all prod config via env vars (see `.env.example`, full reference in `README.md`). Key ones: `DB_*`, `APP_BASE_URL`, `SESSION_ENCRYPTION_KEY` (≥16 chars, identical on every replica), `MAIL_*`, optional `GOOGLE_OAUTH_*` + `GOOGLE_OAUTH_STATE_SECRET`, `TURNSTILE_*`, `SIGNUP_ENABLED`. Profiles: `%dev`/`%test` mock mailer; `%prod` requires real SMTP and secure cookies.

## Docker / CI

`Dockerfile` multi-stage: Bun compiles CSS → BellSoft **Liberica JDK 26** builds → **Liberica JRE 26 (musl)** runs. Tests skipped in image — run `mvn test` on host (with Docker) before building. CI is `.github/workflows/ci.yml` (test/build/merge/release, native multi-arch images to `ghcr.io/asm0dey/calit`). Dependency updates via **Renovate** (`renovate.json`), not Dependabot. The `changes` job gates the image matrix: a push to `main` that touched only `.beans/**`, root-level markdown (`README.md`, `CLAUDE.md`, …), `docs/**`, `.agents/**`, `.claude/**` or `LICENSE` builds no image and publishes no `edge`/`sha-*` tag — that is intentional, not a broken run. `v*` tag pushes always build.

## Documentation

Public docs site lives on **`docs-site`** branch (Astro Starlight project in `docs-site/`, deployed to GitHub Pages at `https://asm0dey.github.io/calit/` by `.github/workflows/docs.yml` on push). Homepage reuses marketing landing; doc pages cover install, configuration, reverse-proxy, Google/Turnstile setup, usage, releases.

**On every interesting change, update docs too.** Any user-facing change — new/changed env var, route, config flag, setup step, feature, or upgrade/migration note — must land on `docs-site` branch same effort. Docs are part of "done", not follow-up.

**Changelog entries land at merge, under `## Unreleased` — not at release.** The manually-maintained changelog lives on the `docs-site` branch (`docs-site/src/content/docs/releases/changelog.md`). When a user-facing change merges to `main`, add it as a bullet under a `## Unreleased` section at the top of that file (create the section if absent; its standing subtitle is "Merged but not yet in a tagged release."). Do **not** wait for a release to write it — by then the reasoning is cold and the entry gets skipped. Publishing early is fine and is the established practice here: `git log -i -S unreleased` on `docs-site` shows the pattern going back to 1.10.0.

**Cutting a release promotes that section.** A release (`release: X.Y.Z` commit on `main` + `vX.Y.Z` tag) renames `## Unreleased` to `## X.Y.Z` (commit style: `docs(changelog): cut X.Y.Z`, or `promote changelog Unreleased -> X.Y.Z`), adds the release's one-line summary paragraph under the heading, and bumps the example image tags in `README.md`. If work merged without its `## Unreleased` bullet, write it now — the changelog entry is part of the release, not follow-up.

Entry style, matching the existing sections: one bold lead sentence naming the user-visible change, then what was wrong before and what happens now, then a `([#N](https://github.com/asm0dey/calit/pull/N))` link. Close each section with an upgrade note, even when it is just "Nothing to do on upgrade — no configuration or database changes." State any caveat a user could be bitten by (e.g. a fix that is not retrofitted to existing rows).

**IMPORTANT**: before you do anything else, run the `beans prime` command and heed its output.
