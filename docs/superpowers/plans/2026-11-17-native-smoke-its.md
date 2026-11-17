# Native Smoke ITs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a small set of `@QuarkusIntegrationTest` smoke tests against the **native binary** in a separate CI job that runs in parallel with the main test job. The app-level curl checks move out of the Docker smoke step into those ITs.

**Architecture:** The ITs are short, black-box and purpose-written, like `CaldavFlowIT`. They can't `extends` the `@QuarkusTest` classes because those log in as the seeded `admin/testpass`, and `DatabaseResetCallback` skips itself when `Arc.container() == null` (IT mode). A new `native` Maven profile builds the binary in a glibc Liberica NIK container and skips surefire. Failsafe then runs every `*IT` against `native.image.path`, which `pom.xml` already passes. The main `test` job stops running ITs (`-DskipITs`). The Docker smoke step keeps only the checks that need the real image.

**Tech Stack:** Quarkus 3.40.1.2, failsafe, RestAssured, Testcontainers (`PostgresResource`, `RadicaleResource`), Liberica NIK 25, GitHub Actions.

**Spec:** none. The requirements come from the 2026-11-17 conversation and are summarised in Global Constraints.

## Global Constraints

- Smoke subset stays small: the 2 existing ITs (`OgImageResourceIT`, `CaldavFlowIT`) plus 2 new ones (`OwnerPagesIT`, `PublicBookingIT`). No other `*IT` classes.
- The `@QuarkusTest` classes whose scenarios the ITs copy stay unchanged. They remain the fast JVM coverage.
- ITs talk HTTP only: no `@Inject`, Panache, `QuarkusTransaction` or `QuarkusMock`. The app runs as `%prod`, so CSRF is on and cookies are `Secure` (keep `CaldavFlowIT`'s manual cookie jar).
- Each IT creates its own owner with a random suffix and never assumes an empty DB. Locally, `verify` runs ITs after surefire on a DB left over from surefire.
- Native build flags live in exactly one place (`application.properties`). `Dockerfile.native` and the `native` profile both read them.
- The Docker image smoke step keeps only image-specific checks: boot to ready, the build-arg footer (`APP_VERSION`/`GIT_COMMIT`), `/og.png` returning PNG bytes (runtime `.so` files and fonts), and the reflection-error grep.
- The image `build` job must not publish when the native IT job failed.
- Branch: `native-smoke-its` (worktree `../calit-native-smoke`, from `origin/main`). PR into `main`, never push `main`. The PR body includes a show-me diagram (read the show-me SKILL.md and apply it by hand).
- Flyway: no migration changes in this plan.

## Smoke subset: native risk → source scenario → IT

| Native risk (fails at request time, green build) | Copied from `@QuarkusTest` | IT |
|---|---|---|
| AWT + fonts | `OgImageResourceTest` | `OgImageResourceIT` (exists) |
| ical4j parse, CalDAV client, form login, argon2id | `CaldavCalendarPortTest`, `SetupFlowTest`, `LoginResourceTest` | `CaldavFlowIT` (exists) |
| Qute pages + message bundles (#342), DB-backed OG card, owner JSON export | `AdminAuthTest.dashboardServedWhenLoggedIn`, `MeOwnerFilterTest`, `AdminNavTest`, `OwnerExportTest`, `OgImageResourceTest` | `OwnerPagesIT` (new) |
| Public Qute pages, i18n bundles, static resources, booking write, mail templates (mock mailer), ICS output, invitee JSON export | `PublicLandingTest`, `LegalPagesTest`, `StaticAssetsTest`, `LangQueryParamTest`, `BookingPostTest`, `GuestIcsDownloadTest`, `BookingExportTest` | `PublicBookingIT` (new) |

## Review Focus

1. **The native binary can't find AWT `.so` files on the runner.** In the image they sit in `/app/lib` via `LD_LIBRARY_PATH`. The profile must point `LD_LIBRARY_PATH` at `target/*-native-image-source-jar/` (Task 3). Otherwise `/og.png` returns 500 only in the native job. `OgImageResourceIT` pins this.
2. **IT order and a leftover DB.** Locally the DB holds surefire's `admin`, so `/setup` is not 200. In CI's fresh DB the first IT gets `/setup`. `ItBrowser.signUpOwner` handles both (Task 1), and every IT runs both ways: locally in JVM mode, in CI in native mode.
3. **A smoke page renders a 200 error page.** Assert page-specific copy, not only the status (each IT step lists its marker string).
4. **A reflection or init error logged without a failed response.** The CI step greps `target/quarkus.log` for the same pattern the Docker step uses (Task 4). It must fail when the file is missing, so a renamed log can't silently pass.
5. **cribro (`.mvn/extensions.xml`) behaves unexpectedly in the native job.** It has no `-Dcribro.diffBase` there. Task 4 Step 3 checks its output in the job log and skips it if it runs PIT or fails.

---

### Task 1: Shared IT browser + signup on for ITs

**Files:**
- Create: `src/test/java/site/asm0dey/calit/test/ItBrowser.java`
- Modify: `src/test/java/site/asm0dey/calit/caldav/CaldavFlowIT.java` (drop its `jar`/`get`/`post` and the owner block, use `ItBrowser`; drop `config.put("calit.signup.enabled", "true")`)
- Modify: `pom.xml` failsafe `<environmentVariables>`: add `<SIGNUP_ENABLED>true</SIGNUP_ENABLED>` (`application.properties:63` reads `${SIGNUP_ENABLED:false}`); fix the stale "Dev Services Postgres" sentence in the comment above it (the DB comes from `PostgresResource`, a GLOBAL `@WithTestResource`)

**Interfaces:**
- Produces:
  - `public final class ItBrowser` with `Response get(String path)`, `Response post(String path, Map<String, ?> form)` (moved verbatim from `CaldavFlowIT`, including the `csrf-token` form param and `redirects().follow(false)`), and `Map<String, String> cookies()`.
  - `public record Owner(String username, String password)` nested in `ItBrowser`.
  - `public Owner signUpOwner(String prefix, ZoneId zone)`: random 8-char suffix; `/setup` if GET `/setup` is 200 (POST → 302), else `/signup` (GET 200, POST → 303); then `/j_security_check` → 302 and a `quarkus-credential` cookie; then GET `/me/setup` 200, POST `/me/setup` (`ownerName`, `ownerEmail` = `<username>@example.com`, `timezone`) → 303. Asserts each status with AssertJ. Lifted from `CaldavFlowIT.caldavBusyTimeBlocksSlotsOnThePublicPage`.
  - `public void createMeetingType(String slug)`: POST `/me/meeting-types` with the exact form `CaldavFlowIT` uses (`durationMinutes=30`, `minNoticeMinutes=0`, `horizonDays=30`, `locationType=PHONE`) → 200.

- [ ] **Step 1: Refactor `CaldavFlowIT` onto `ItBrowser`** (zone `Europe/Berlin`, slug `caldav-it`). The CalDAV-specific code stays in the IT.
- [ ] **Step 2: Run it in JVM IT mode**

Run: `./mvnw -B verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CaldavFlowIT` (Docker running, `calit.css` built: `mise run css`)
Expected: `Tests run: 1, Failures: 0` from failsafe, `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/site/asm0dey/calit/test/ItBrowser.java src/test/java/site/asm0dey/calit/caldav/CaldavFlowIT.java pom.xml
git commit -m "test(it): share the IT cookie-jar browser and owner signup"
```

### Task 2: `OwnerPagesIT` and `PublicBookingIT`

**Files:**
- Create: `src/test/java/site/asm0dey/calit/web/OwnerPagesIT.java`
- Create: `src/test/java/site/asm0dey/calit/web/PublicBookingIT.java`

**Interfaces:**
- Consumes: `ItBrowser`, `ItBrowser.Owner`, `signUpOwner`, `createMeetingType` (Task 1).
- Both classes: `@QuarkusIntegrationTest @Tag("integration")`, one `ItBrowser` field, javadoc naming the `@QuarkusTest` classes they copy (see the subset table).

- [ ] **Step 1: Write `OwnerPagesIT`**
  - `everyOwnerPageRenders()`: `signUpOwner("pagesit", ZoneId.of("Europe/Berlin"))`, then GET each of `/me`, `/me/meeting-types`, `/me/availability`, `/me/date-overrides`, `/me/booking-fields`, `/me/calendars`, `/me/pending`, `/me/shared`, `/me/shared/requests`, `/me/settings`, `/me/settings/delete` → `statusCode` 200. `/me` body contains `Dashboard` (the `AdminAuthTest` marker). Use AssertJ `as(path)` so a failure names the page.
  - `ownerExportIsJson()`: GET `/me/export` → 200, content type contains `application/json`, and the body parses (RestAssured `jsonPath().getMap("$")` not empty).
  - `ownerCardRendersFromTheDatabase()`: GET `/og.png` and `/og/<username>.png`. Both start with the PNG magic (reuse `OgImageResourceIT.PNG_MAGIC`, make it package-visible), and their bytes differ (the check moved from `ci.yml`).
- [ ] **Step 2: Write `PublicBookingIT`**
  - `publicPagesRender()`: no login. GET `/` → 200 containing `Self-hosted scheduling`; `/privacy` and `/terms` → 200; `/favicon.svg` → 200; `/calit.css` → 200 and not empty.
  - `inviteeBooksAndGetsIcsAndExport()`: `signUpOwner("bookit", Europe/Berlin)`, `createMeetingType("book-it")`, then with a **new** `ItBrowser` (the invitee, not logged in): GET `/<user>` 200; GET `/<user>/book-it` 200, extract the first `name="startUtc" value="..."` with the regex `CaldavFlowIT` uses for its row; GET `/<user>/book-it?lang=he` 200 containing `lang="he"` (the `LangQueryParamTest` marker); POST `/<user>/book-it` with `startUtc`, `inviteeName=Dana Vogel`, `inviteeEmail=dana@example.com`, `website=` → 200 and the body matches `/booking/([^/"]+)/manage`. With that token: GET `/booking/<t>/manage` → 200; GET `/booking/<t>/invite.ics` → content type contains `text/calendar` and the body contains `BEGIN:VCALENDAR`; GET `/booking/<t>/data` → JSON `invitee.email` equals `dana@example.com`, `booking.startUtc` not null (the `BookingExportTest` assertions).
- [ ] **Step 3: Run both in JVM IT mode**

Run: `./mvnw -B verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false '-Dit.test=OwnerPagesIT,PublicBookingIT'`
Expected: failsafe `Tests run: 5, Failures: 0`. If a `/me/*` page needs an extra wizard step or the booking form needs a field this plan missed, read the matching `@QuarkusTest` (table above) and copy its form. Don't drop the assertion.

- [ ] **Step 4: Run all four ITs together to prove order independence**

Run: `./mvnw -B verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false`
Expected: failsafe `Tests run: 7, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/site/asm0dey/calit/web/OwnerPagesIT.java src/test/java/site/asm0dey/calit/web/PublicBookingIT.java src/test/java/site/asm0dey/calit/web/OgImageResourceIT.java
git commit -m "test(it): owner pages and public booking smoke ITs"
```

### Task 3: One home for native flags + `native` Maven profile

**Files:**
- Modify: `src/main/resources/application.properties`: add `quarkus.native.additional-build-args=` with the exact comma list from `Dockerfile.native`'s `RUN ./mvnw ... package` line (`-H:Preserve=package=com.google.api.services.calendar.model`, … `--initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig`), next to the existing `quarkus.native.resources.includes`. Move the long rationale comment from `Dockerfile.native` with it.
- Modify: `Dockerfile.native`: the build `RUN` becomes `./mvnw -B -DskipTests package -Dquarkus.native.enabled=true` (no args). Leave a one-line pointer comment to `application.properties`.
- Modify: `pom.xml` `<profiles>`: add a profile with id `native`.

**Interfaces:**
- Produces: `./mvnw verify -Pnative` builds `target/calit-*-runner` in a container and runs failsafe against it.
- Profile `native` contents:
  - properties `quarkus.native.enabled=true`, `quarkus.native.container-build=true`, `quarkus.native.builder-image=bellsoft/liberica-native-image-kit-container:jdk-25-nik-25.0.3-glibc@sha256:<digest>`. Use glibc, not musl, so the binary runs on the Ubuntu runner. The version must match `Dockerfile.native`'s `jdk-25-nik-25.0.3`.
  - surefire `<configuration><skipTests>true</skipTests></configuration>` (this job runs only ITs).
  - failsafe `<environmentVariables><LD_LIBRARY_PATH>${project.build.directory}/${project.build.finalName}-native-image-source-jar</LD_LIBRARY_PATH></environmentVariables>` (Review Focus 1; mirrors the `/app/lib` copy in `Dockerfile.native`).

- [ ] **Step 1: Resolve and pin the builder image**

Run: `docker buildx imagetools inspect bellsoft/liberica-native-image-kit-container:jdk-25-nik-25.0.3-glibc --format '{{json .Manifest.Digest}}'`
Expected: a `sha256:` digest. If the tag doesn't exist, list tags on Docker Hub and pick the glibc tag with the same NIK version. Stop and ask if there isn't one.

- [ ] **Step 2: Add the properties line, trim `Dockerfile.native`, add the profile**
- [ ] **Step 3: Prove the Docker native image still builds with the moved flags**

Run: `docker build -f Dockerfile.native -t calit:native-check .`
Expected: success (about 10 min, needs about 8 GB RAM). A missing flag shows up as a build failure on `java.awt.Color` or `HttpClientFacade`.

- [ ] **Step 4: Run the native ITs locally**

Run: `./mvnw -B verify -Pnative`
Expected: surefire skipped; failsafe log shows `Executing ... calit-...-runner` (the native binary, not `quarkus-run.jar`); `Tests run: 7, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.properties Dockerfile.native pom.xml
git commit -m "build(native): one home for native-image flags; -Pnative runs ITs on the binary"
```

### Task 4: CI: parallel native IT job, ITs out of the main job, trimmed image smoke

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/fork-ci.yml:58` (add `-DskipITs`, keeping the two build step lists in sync as its comment requires)
- Modify: `CLAUDE.md:73` (the `*IT` bullet: ITs are native smoke tests; `mvn verify` still runs them in JVM mode; CI runs them only in the `native-it` job via `-Pnative`)

**Interfaces:**
- Produces: job id `native-it`, display name `Native smoke ITs`.

- [ ] **Step 1: Edit `ci.yml`**
  - `test` job, "Build and run tests": `verify -DskipITs $CRIBRO_ARGS`.
  - New job `native-it`, with no `needs` and no fork `if` (it uses no secrets), on `ubuntu-latest`. Steps: checkout; `jdx/mise-action` pinned to the same SHA as `test` (`install_args: "java bun"`); the same Maven cache; the same CSS build step; `sudo apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core libfreetype6`; `./mvnw --batch-mode --no-transfer-progress verify -Pnative`; then a step that fails when `target/quarkus.log` is missing (`test -s`) or matches `grep -iE 'NoSuchMethodException|InstantiationException|no accessible default constructor|UnsupportedFeatureException'`; then, `if: failure()`, upload `target/quarkus.log` and `target/failsafe-reports/` as an artifact (retention 7 days).
  - `build` job: `needs: [test, changes, native-it]`, and add `needs.native-it.result == 'success' &&` to its `if`, with one comment line saying why.
  - "Smoke test image" step: delete the `/setup` POST block, the `/og/admin.png` fetch and the `cmp` check. Keep: ready wait, GET `/setup` + footer grep, `/og.png` PNG check, log grep. Update the step's leading comment: app behaviour lives in the `*IT` classes; this step only covers what the image adds (runtime libs, fonts, build args).
- [ ] **Step 2: Lint**

Run: `mise run lint-ci`
Expected: no output, exit 0.

- [ ] **Step 3: Push the branch, open the PR, watch CI**

```bash
git add .github/workflows/ci.yml .github/workflows/fork-ci.yml CLAUDE.md
git commit -m "ci: native smoke ITs in a parallel job; image smoke keeps image-only checks"
git push -u origin native-smoke-its
```

Open the PR with `gh pr create` (show-me diagram in the body). Expected: `Build & test (Maven)` green without failsafe output; `Native smoke ITs` green with `Tests run: 7`, running in parallel with it; its wall time noted in the PR body. Check the `native-it` log for cribro (Review Focus 5). If it runs PIT or fails without a diff base, add whatever skip property its docs name to that job's Maven line and push again.

- [ ] **Step 4: Prove the gate bites** by temporarily deleting `NativeTemplateData`'s `@TemplateData` for one type, pushing, and confirming `native-it` fails on `OwnerPagesIT`. Then revert, push, and confirm green. Record the failing run URL in the PR body.
