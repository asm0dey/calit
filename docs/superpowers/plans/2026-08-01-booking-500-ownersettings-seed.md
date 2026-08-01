# Booking 500 — Seed OwnerSettings for /setup user Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop public bookings 500-ing (NPE) when an owner has no `owner_settings` row, by restoring the invariant "every `app_user` has one `owner_settings` row" — for new `/setup` users and already-broken installs.

**Architecture:** `SetupResource` creates the first admin `AppUser` but historically seeded no `OwnerSettings`; every other creation path (invite, Google/OIDC sign-in) does. `BookingService` reads `OwnerSettings.forOwner(id).timezone` unguarded, so a missing row → NPE → uncaught 500 (`submitBooking`'s catch block only handles booking exceptions). Fix at the source: seed the row in `SetupResource`, and backfill existing DBs with a Flyway migration. No read-side default needed — the invariant makes the unguarded reads safe.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache, Flyway, RestAssured `@QuarkusTest`.

## Global Constraints

- Docker MUST be running for `mvn test` (Dev Services Postgres). Build JDK: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` (or equivalent) before `./mvnw` — default JDK 21 fails "release 25 not supported".
- Never edit an applied Flyway migration; new `V*.sql` only. Latest applied is `V23`; new file is `V24`.
- `OwnerSettings.owner_name`, `owner_email`, `timezone` are `NOT NULL` — seed placeholders (`''`, `''`, `'UTC'`) the first-login wizard overwrites, matching `UsersResource` invite seed.
- Release commits go straight to `main` (no PR). Cutting a release = `release: X.Y.Z` commit on `main` + `vX.Y.Z` tag + pom bump + README image tags + docs-site changelog entry.

---

### Task 1: Seed OwnerSettings at /setup + backfill migration

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/user/SetupResource.java` (import + seed after `u.persist()`) — DONE
- Create: `src/main/resources/db/migration/V24__backfill_owner_settings.sql` — DONE
- Test: `src/test/java/site/asm0dey/calit/user/SetupFlowTest.java`

**Interfaces:**
- Consumes: `OwnerSettings.forOwner(Long ownerId) -> OwnerSettings | null`; `AppUser.findByUsername(String) -> AppUser`.
- Produces: post-`/setup` invariant — `OwnerSettings.forOwner(newUser.id) != null` with `timezone == "UTC"`.

- [x] **Step 1: Implementation — seed in SetupResource**

```java
// after u.persist():
OwnerSettings s = new OwnerSettings();
s.ownerId = u.id;
s.ownerName = "";
s.ownerEmail = "";
s.timezone = "UTC";
s.persist();
```
Plus `import site.asm0dey.calit.domain.OwnerSettings;`.

- [x] **Step 2: Implementation — V24 backfill**

```sql
INSERT INTO owner_settings (owner_id, owner_name, owner_email, timezone, locale, owner_notifications_enabled)
SELECT u.id, '', '', 'UTC', 'en', TRUE
FROM app_user u
WHERE NOT EXISTS (SELECT 1 FROM owner_settings s WHERE s.owner_id = u.id);
```

- [ ] **Step 3: Write the regression test** in `SetupFlowTest.java`

```java
@Test
void setupSeedsOwnerSettingsSoBookingWontNpe() {
    deleteAllUsers();
    given().redirects().follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "Boss").formParam("password", "boss-pw-123")
            .when().post("/setup").then().statusCode(302);

    QuarkusTransaction.requiringNew().run(() -> {
        AppUser u = AppUser.findByUsername("boss");
        org.junit.jupiter.api.Assertions.assertNotNull(u);
        OwnerSettings s = OwnerSettings.forOwner(u.id);
        org.junit.jupiter.api.Assertions.assertNotNull(s, "first /setup user must get an OwnerSettings row");
        org.junit.jupiter.api.Assertions.assertEquals("UTC", s.timezone);
    });
}
```
Add import `import site.asm0dey.calit.domain.OwnerSettings;`.

- [ ] **Step 4: Run test — expect PASS** (implementation already in place)

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca; ./mvnw test -Dtest=SetupFlowTest`
Expected: all methods green, incl. `setupSeedsOwnerSettingsSoBookingWontNpe`.

- [ ] **Step 5: Run the full suite** (guard against the truncate/reseed invariant and booking tests)

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/user/SetupResource.java \
        src/main/resources/db/migration/V24__backfill_owner_settings.sql \
        src/test/java/site/asm0dey/calit/user/SetupFlowTest.java \
        .beans/  # bean file
git commit -m "fix: seed OwnerSettings for first /setup user; backfill existing (#99)"
```

---

### Task 2: Cut minor release 1.19.0

**Files:**
- Modify: `pom.xml:8` (`<version>1.18.0</version>` → `1.19.0`)
- Modify: `README.md` (example image tags `:1.18.0` → `:1.19.0`, if pinned)
- Modify (on `docs-site` branch): `docs-site/src/content/docs/releases/changelog.md` (new top section)

**Interfaces:**
- Consumes: green full test suite from Task 1.
- Produces: `release: 1.19.0` commit on `main` + `v1.19.0` tag.

- [ ] **Step 1: Bump pom version** `1.18.0` → `1.19.0`.

- [ ] **Step 2: Bump README example image tags** to `1.19.0` (grep `1.18.0` in README.md first; skip if it uses `latest`).

- [ ] **Step 3: Verify build** — `./mvnw -q -DskipTests package` → BUILD SUCCESS.

- [ ] **Step 4: Commit + tag on main**

```bash
git add pom.xml README.md
git commit -m "release: 1.19.0"
git tag v1.19.0
git push origin main --tags   # only when user confirms push
```

- [ ] **Step 5: Changelog on docs-site branch** — add a `## 1.19.0` section at the top of `docs-site/src/content/docs/releases/changelog.md`:

```markdown
## 1.19.0

Bug fix.

- **Fixed a 500 when creating a booking on a fresh install.** The first user
  created via `/setup` had no settings row until the first-login wizard ran, so
  a booking made before completing the wizard failed with an internal error.
  New installs seed the row up front, and existing installs are backfilled on
  upgrade. (#99)
```
Commit on `docs-site`: `docs: changelog 1.19.0`.

---

### Task 3: Draft reporter comment for #99 (await user review before posting)

**Files:** none (GitHub comment).

**Interfaces:**
- Consumes: released fix `v1.19.0`.
- Produces: draft text for issue #99; user reviews exact wording before it is posted.

- [ ] **Step 1: Draft the comment** — diagnosis (missing `owner_settings` row for the first `/setup` user → NPE), the fix, that upgrading to the image built from `1.19.0` backfills their DB automatically, and to reopen with the stack trace behind the error id if it recurs. Also nudge to the prebuilt ghcr image (`dev·dev` footer = local build).

- [ ] **Step 2: Present draft to user. Do NOT post** until the user approves the exact text.

- [ ] **Step 3:** On approval — `gh issue comment 99 --body "<approved text>"`.

---

## Self-Review

- **Spec coverage:** fix (seed + backfill) → Task 1; minor release → Task 2; reporter comment held for review → Task 3. Covered.
- **Placeholder scan:** none — all code/SQL/commands are concrete.
- **Type consistency:** `OwnerSettings.forOwner(Long)`, `AppUser.findByUsername(String)`, field names `ownerId/ownerName/ownerEmail/timezone` match `OwnerSettings.java` and the existing seed in `UsersResource`.
