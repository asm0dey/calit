# Contributing to calit

Thanks for helping! This guide covers the build, the test loop, and the conventions a PR must follow.
For the full architecture, see [`CLAUDE.md`](CLAUDE.md); for user-facing docs, see
<https://asm0dey.github.io/calit/>.

## Prerequisites

- **JDK 26** to build (BellSoft Liberica is what CI and the Docker image use). The app *targets* Java
  25, but the build toolchain needs 26 — a plain JDK 21 fails with `release 25 not supported`.
- **Maven** — the bundled wrapper `./mvnw` works everywhere; the commands below write `mvn`, and either
  is fine as long as `JAVA_HOME` points at JDK 26.
- **Bun** — compiles the stylesheet and wires the git hooks.
- **Docker** — **mandatory** for `quarkus:dev` and the test suite. Quarkus Dev Services starts a
  throwaway Postgres (and a mock mailer) in Docker; there is no embedded/H2 fallback.

## Build & run

```bash
bun install            # once — installs the Tailwind/daisyUI CLI + installs the lefthook pre-commit hook
bun run css:build      # compile src/main/css/input.css -> /calit.css (gitignored; build once or pages render unstyled)
bun run css:watch &    # rebuild CSS on change during dev
mvn quarkus:dev        # dev server at http://localhost:8080 — Docker MUST be running
```

On a fresh database every request redirects to `/setup` to create the first (admin) user. No default
password.

## Tests

```bash
mvn test                                              # full suite (Docker required)
mvn test -Dtest=BookingServiceTest                    # one class
mvn test -Dtest=BookingServiceTest#booksAvailableSlot # one method
```

- Surefire runs `reuseForks=true`: one reused JVM fork + one Dev Services Postgres shared across all
  same-profile `@QuarkusTest` classes. A `@TestProfile` triggers an in-JVM Quarkus restart — avoid
  adding new profiles when an existing one fits; each one costs a reboot.
- The DB is truncated and reseeded per test; the admin user is **always id 1** — write owner-scoped
  tests against that invariant.
- Mailer is mocked in `%test`; Google and CAPTCHA are disabled by default, so the full booking flow
  runs with zero external accounts.
- RestAssured can't execute JS, so tests assert on stable marker comments (e.g. `CALIT_TZ_REFORMAT`),
  not on script behavior.

**Every feature or bugfix ships with a test.**

**Never open a PR while the suite is red.** The *whole* `mvn test` run must be green (0 failures,
0 errors) — not just the classes you touched. A failure you didn't cause is still yours to fix first:
land the repair as its own commit and say so in the PR.

## Formatting (CI gate)

- **Java**: Spotless + Prince of Space (120 cols) plus curated CleanThat cleanups (diamond, `var`,
  method refs). **JS/CSS**: Prettier. Qute `.html` templates are deliberately not formatted.
- **JDK imports are module imports** (JEP 511): `import module java.base;` instead of single-type
  `java.*`/`javax.*` imports. Nothing enforces it and IDE auto-import adds single-type imports back,
  so fold them in by hand. Keep one single-type import only to disambiguate a clashing simple name
  (e.g. `java.util.List` vs `java.awt.List`).
- `bun install` wires a lefthook pre-commit hook that auto-formats staged files, so you usually don't
  think about it. To format the whole tree manually: `bun run format`.
- `mvn verify` (hence CI) **fails on unformatted Java** (`mvn test` is unaffected). Run
  `mvn spotless:check` if in doubt.

## Conventions a PR must follow

- **Owner scoping (critical).** Every tenant row carries `owner_id`. Every query must filter by
  `currentOwner.id()`. One user must never read or write another's data. Any new query or entity is
  scoped by owner.
- **Internationalization.** User-facing strings are type-safe `@Message` keys. **Every new or changed
  string must be translated in the same change** — add the German **and** Hebrew value to
  `src/main/resources/messages/{msg,adm}_{de,he}.properties` alongside the English `@Message` default.
  Don't rely on the English fallback. If you genuinely can't translate, open a translation issue and
  reference it in the PR — the key ships with its English default, but the gap is tracked.
- **Migrations.** Flyway migrations under `src/main/resources/db/migration` are applied at boot and
  Hibernate only *validates* the schema. **Never edit an applied migration** (even a comment change
  breaks Flyway's checksum) — add a new `V*.sql` for every change.
- **Docs are part of "done".** Any user-facing change (new/changed env var, route, config flag, setup
  step, feature) must land the matching docs update on the **`docs-site`** branch in the same effort.
- **Changelog at merge.** A user-facing change adds one terse bullet (~20–35 words, ending with the PR
  link) under `## Unreleased` in `docs-site/src/content/docs/releases/changelog.md` when it merges —
  not at release time.
- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`,
  `refactor:`, `test:`, `docs:`, …), as in the existing history.

## Opening a PR

1. Branch off `origin/main` (a local `main` may carry unpushed commits).
2. Make the change with tests, translations, and docs as above.
3. Ensure `mvn verify` passes locally — the full suite, green (format + tests).
4. Open the PR. CI must be green (test/build, CodeQL, SonarCloud, Trivy) before merge.

## Releases

Maintainers cut a release with a `release: X.Y.Z` commit on `main` and a `vX.Y.Z` tag (which triggers
the GitHub release + multi-arch JVM and native image publish to GHCR and Docker Hub). The tag supplies the version displayed by release
images; `pom.xml` remains at the stable `dev` revision for build-layer cache reuse. A release also
renames the changelog's `## Unreleased` section on `docs-site` to `## X.Y.Z` (with a one-line summary
and an upgrade note) and bumps the example image tags in the README.
