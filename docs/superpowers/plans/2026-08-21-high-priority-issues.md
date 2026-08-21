# High-Priority Issues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clear the three open high-priority beans — stop Dev Services container drift from breaking `mvn test`, give every new user working default availability, and close the already-fixed Sonar NPE bean.

**Architecture:** Three small changes plus a bookkeeping close. (1) `src/test/resources/application.properties` gains `quarkus.flyway.clean-at-start=true`, so a reused Postgres container carrying a *newer* migration set is wiped and re-migrated at boot instead of failing Flyway validation. (2) `DefaultAvailabilitySeeder` loses its dead CDI/startup wiring and becomes a plain static helper; `MeSetupResource.submit()` — the single mandatory onboarding chokepoint every user passes through — seeds `weekdayDefaults()` stamped with the owner id, guarded so it never double-seeds. (3) `settingsComplete` is written once and never again, so already-onboarded accounts never re-enter the wizard: Flyway `V28` backfills default hours for every owner that has none. (4) `calit-wvtl`'s fix already shipped in merged PR #142; only its last checkbox is stale.

**Task order:** Task 1 first (it makes every later test run reliable), then Task 2 → Task 3 (both serve bean `calit-sjwh`; the bean closes at the end of Task 3). Task 4 is independent.

**Tech Stack:** Quarkus 3.38, Java 25 (built on Liberica JDK 26), Hibernate ORM with Panache, Flyway, Quarkus Dev Services + Testcontainers, JUnit 5 + RestAssured, Maven Surefire (`reuseForks=true`).

**Spec:** The beans themselves — `calit-szew`, `calit-sjwh`, `calit-wvtl`. Read them with `beans show calit-szew calit-sjwh calit-wvtl`.

## Global Constraints

- **Build JDK:** `export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca` before any `mvn`/`./mvnw`. The default sdkman JDK is 21 and fails with `error: release version 25 not supported`. `-o` (offline) is faster.
- **Docker must be running** for `mvn test` — Dev Services provisions a throwaway Postgres. No H2/embedded fallback.
- **Never edit an applied Flyway migration.** Flyway checksum validation fails even on a comment change. Task 3 adds `V28`; nothing in `V1…V27` may be touched.
- **Owner scoping:** every tenant row carries `owner_id` and every query filters by it. Any `AvailabilityRule` created here MUST have `ownerId` stamped.
- **Formatting:** Java is Spotless + palantir-java-format. `bun run format` or `mvn spotless:apply` before commit; `verify` (and CI) fails on unformatted code. The lefthook pre-commit hook does this automatically if `bun install` has been run.
- **i18n:** every new or changed user-facing string needs `de` **and** `he` values in `src/main/resources/messages/{msg,adm}_{de,he}.properties` in the same change. This plan deliberately adds no new strings — see Task 2 Step 8.
- **Branch + PR only.** Do not push to `main`. Do not open a PR while the suite is red: `mvn test` must be fully green (0 failures, 0 errors, `BUILD SUCCESS`) over the *whole* suite.
- **Beans, not TodoWrite.** Tick each bean's checkboxes as work lands, and commit the bean file alongside the code.

---

### Task 1: Stop Dev Services container drift from failing the suite

Bean: `calit-szew`. `quarkus.datasource.devservices.reuse=true` makes Testcontainers match a parked Postgres by hash, and that hash does not distinguish git worktrees *or* branches. A container last used by a branch with `V27` makes a `V26` branch die at boot with:

```
FlywayValidateException: Detected applied migration not resolved locally: 27
```

Fix: keep reuse (the ~35s cold start it saves is real), and make every Quarkus boot in `%test` drop and re-migrate the schema. A stale container then carries no stale schema. This also covers branch-switching inside a single worktree, which the bean's own wording misses.

**Files:**
- Modify: `src/test/resources/application.properties` (append at end of file)
- Modify: `CLAUDE.md` (the `## Tests` section)
- Modify: `.beans/` — the `calit-szew` bean file (via the `beans` CLI)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing later tasks call. It makes Task 2's test runs reliable, so do it first.

- [ ] **Step 1: Read the current test properties file**

```bash
cd /home/finkel/work_self/calit
cat src/test/resources/application.properties
```

Confirm it ends with the `quarkus.datasource.devservices.reuse=true` line and its comment block.

- [ ] **Step 2: Reproduce the failure signature (optional but informative)**

Only possible if a sibling worktree has parked a container from a branch with more migrations. Check:

```bash
docker ps --filter "label=org.testcontainers=true" --format '{{.ID}}  {{.Image}}  {{.CreatedAt}}'
```

If nothing is parked, skip — the fix is not reproduction-gated.

- [ ] **Step 3: Add the clean-at-start config**

Append to `src/test/resources/application.properties`:

```properties
# The reused container above is matched by a Testcontainers hash that knows nothing about which
# branch or worktree last used it. A container parked by a branch carrying V27 makes a V26 branch
# die at boot with "FlywayValidateException: Detected applied migration not resolved locally: 27".
# Dropping and re-migrating the schema on every %test boot makes a stale container harmless: the
# container is reused, its schema never is. Boots are rare (reuseForks=true means one, plus one per
# @TestProfile restart), so the DDL replay costs ~1s a run, not per test.
# clean-disabled must be explicitly false — Flyway refuses to clean otherwise.
quarkus.flyway.clean-disabled=false
quarkus.flyway.clean-at-start=true
```

- [ ] **Step 4: Run one Quarkus test class to prove the app still boots and migrates**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=MeSetupResourceTest
```

Expected: `BUILD SUCCESS`, 6 tests run, 0 failures. If it fails with `FlywayException: Unable to execute clean as it has been disabled`, the `clean-disabled=false` line is missing or misspelled — fix and re-run.

- [ ] **Step 5: Prove the drift case is actually healed**

Force the exact failure the bean reports: park a container whose schema is ahead of this branch. Cheapest simulation — stamp a bogus future migration into the running container's history, then boot again:

```bash
CID=$(docker ps --filter "ancestor=postgres" --format '{{.ID}}' | head -1)
docker exec "$CID" psql -U quarkus -d quarkus -c \
  "insert into flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) \
   values (999, '99', 'bogus drift', 'SQL', 'V99__bogus_drift.sql', 0, 'drift-test', 0, true);"
./mvnw -o test -Dtest=MeSetupResourceTest
```

Expected: `BUILD SUCCESS`. Before this task's change the same setup fails with `Detected applied migration not resolved locally: 99`. (If no postgres container is running because reuse had nothing parked, run the test once first to create one.)

- [ ] **Step 6: Document the failure signature and the escape hatch**

In `CLAUDE.md`, in the `## Tests` section, immediately after the existing `reuseForks` bullet, add:

```markdown
- Dev Services Postgres is **reused between runs** (`quarkus.datasource.devservices.reuse=true` in
  `src/test/resources/application.properties`) and the reuse hash knows nothing about your branch or
  worktree — a container parked by a branch with more migrations would otherwise fail the next boot with
  `FlywayValidateException: Detected applied migration not resolved locally: NN`. `quarkus.flyway.clean-at-start=true`
  (same file) drops and re-migrates the schema on every `%test` boot, so a stale container is harmless.
  If you ever need a genuinely fresh container, run with `-Dquarkus.datasource.devservices.reuse=false`.
```

- [ ] **Step 7: Run the full suite**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. This is the gate — do not proceed with a red suite.

- [ ] **Step 8: Format and verify**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o spotless:apply && ./mvnw -o spotless:check
```

Expected: `BUILD SUCCESS`. (No Java changed in this task, so this should be a no-op; run it anyway so a stray edit can't slip through.)

- [ ] **Step 9: Tick the bean and commit**

```bash
cd /home/finkel/work_self/calit
beans update calit-szew -s completed \
  --body-replace-old "[ ] Decide the fix: scope the reuse label per branch/worktree, or turn reuse off in %test and accept the cold-start cost" \
  --body-replace-new "[x] Decide the fix: scope the reuse label per branch/worktree, or turn reuse off in %test and accept the cold-start cost"
beans update calit-szew \
  --body-replace-old "[ ] If reuse stays on, document the failure signature and the -Dquarkus.datasource.devservices.reuse=false escape hatch where someone will find it (CLAUDE.md test section)" \
  --body-replace-new "[x] If reuse stays on, document the failure signature and the -Dquarkus.datasource.devservices.reuse=false escape hatch where someone will find it (CLAUDE.md test section)"
beans update calit-szew --body-append "## Summary of Changes

Reuse stays on. \`quarkus.flyway.clean-at-start=true\` (with \`clean-disabled=false\`) in \`src/test/resources/application.properties\` drops and re-migrates the schema on every %test boot, so a container parked by a branch with a different migration set can no longer fail validation. This also covers branch-switching inside one worktree, which per-worktree label scoping would have missed. Failure signature and the \`-Dquarkus.datasource.devservices.reuse=false\` escape hatch documented in CLAUDE.md's Tests section."
```

**Note:** if the exact checkbox text above does not match the bean body byte-for-byte, `beans update` errors rather than guessing. Run `beans show calit-szew` and copy the lines verbatim.

```bash
git add src/test/resources/application.properties CLAUDE.md .beans
git commit -m "test(devservices): re-migrate schema on boot so a reused container can't drift

A parked Testcontainers Postgres is matched by a hash that knows nothing
about the branch or worktree that last used it, so a container carrying V27
made a V26 branch die at boot with 'Detected applied migration not resolved
locally'. Clean-at-start in %test drops and re-migrates the schema each boot:
the container is reused, its schema never is.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QF9JXW4V2AFDBicwBfV5zH"
```

---

### Task 2: Seed default availability for every new user

Bean: `calit-sjwh`. `DefaultAvailabilitySeeder` is dead code — its `onStart` observer is an explicit no-op and `weekdayDefaults()` has no production caller. Every new user therefore starts with zero global availability rules, so their meeting types offer no slots, and the working-hours help text ("Until you save them the grid shows your global default hours") describes a grid that is empty.

Seeding goes in `MeSetupResource.submit()`. `MeOwnerFilter:55` bounces anyone with `settingsComplete == false` to `/me/setup`, and `MeSetupResource:105` is the only writer of `settingsComplete = true`, so all five creation paths (`/setup`, `/signup`, admin invite, Google sign-in, OIDC sign-in) pass through this one method before they can use anything.

`DefaultAvailabilitySeeder` is currently `@ApplicationScoped @UnlessBuildProfile("test")` — it does not exist as a bean under `%test`, so it cannot be injected into the wizard. Strip the CDI entirely: no annotations, no `onStart`, just a static factory. The existing `DefaultAvailabilitySeederTest` calls only `weekdayDefaults()` and keeps passing untouched.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/availability/DefaultAvailabilitySeeder.java` (whole file rewritten)
- Modify: `src/main/java/site/asm0dey/calit/web/MeSetupResource.java` (inside `submit()`, after the `OwnerSettings` block at lines 94–103)
- Test: `src/test/java/site/asm0dey/calit/web/MeSetupResourceTest.java` (add tests)
- Modify: `.beans/` — the `calit-sjwh` bean file (via the `beans` CLI)
- Modify (on the `docs-site` branch): `docs-site/src/content/docs/releases/changelog.md`

**Interfaces:**
- Consumes: nothing from Task 1 (Task 1 only makes these test runs reliable).
- Produces:
  - `static List<AvailabilityRule> DefaultAvailabilitySeeder.weekdayDefaults()` — unchanged signature, still package-private, Mon–Fri 09:00–18:00, `meetingTypeId == null`, `ownerId` **not** set (the caller stamps it).
  - `static int DefaultAvailabilitySeeder.seedGlobalDefaults(Long ownerId)` — new, package-private is not enough (called from `site.asm0dey.calit.web`), so **public**. Persists `weekdayDefaults()` stamped with `ownerId` and returns the number of rules written; returns `0` without writing when the owner already has any global rule. Must be called inside a transaction.

- [ ] **Step 1: Write the failing test for the seeding helper**

Create `src/test/java/site/asm0dey/calit/availability/DefaultAvailabilitySeederPersistenceTest.java`:

```java
package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

@QuarkusTest
class DefaultAvailabilitySeederPersistenceTest {

    @Transactional
    int seed(Long ownerId) {
        return DefaultAvailabilitySeeder.seedGlobalDefaults(ownerId);
    }

    @Transactional
    long globalCount(Long ownerId) {
        return AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    @Test
    void seedsFiveOwnerStampedWeekdayRules() {
        assertEquals(5, seed(1L)); // admin is always id 1 (DatabaseResetCallback)
        assertEquals(5, globalCount(1L));

        List<AvailabilityRule> monday = AvailabilityRule.globalForOwner(1L, DayOfWeek.MONDAY);
        assertEquals(1, monday.size());
        assertEquals(1L, monday.getFirst().ownerId, "every seeded rule must carry the owner id");
        assertEquals(LocalTime.of(9, 0), monday.getFirst().startTime);
        assertEquals(LocalTime.of(18, 0), monday.getFirst().endTime);
    }

    @Test
    void isIdempotent() {
        assertEquals(5, seed(1L));
        assertEquals(0, seed(1L), "second call must write nothing");
        assertEquals(5, globalCount(1L), "rules must not double");
    }

    @Test
    void doesNotSeedWhenOwnerAlreadyHasGlobalRules() {
        seedOneSaturdayRule(1L);
        assertEquals(0, seed(1L));
        assertEquals(1, globalCount(1L), "an existing hand-made rule means the owner is not new");
    }

    @Transactional
    void seedOneSaturdayRule(Long ownerId) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = ownerId;
        r.dayOfWeek = DayOfWeek.SATURDAY;
        r.startTime = LocalTime.of(10, 0);
        r.endTime = LocalTime.of(12, 0);
        r.meetingTypeId = null;
        r.persist();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=DefaultAvailabilitySeederPersistenceTest
```

Expected: compilation failure — `cannot find symbol: method seedGlobalDefaults(java.lang.Long)`.

- [ ] **Step 3: Rewrite `DefaultAvailabilitySeeder` as a static helper**

Replace the whole of `src/main/java/site/asm0dey/calit/availability/DefaultAvailabilitySeeder.java` with:

```java
package site.asm0dey.calit.availability;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.calit.domain.AvailabilityRule;

/**
 * Default availability for a brand-new owner: Mon–Fri 09:00–18:00, global (meetingTypeId == null).
 *
 * <p>Not a CDI bean and not boot-time: under owner scoping a rule needs an owner_id, and at boot no
 * {@code app_user} need exist. Seeding is a per-user concern, driven from the first-login wizard
 * ({@code MeSetupResource#submit}) — the one place every user must pass through before they can use
 * {@code /me} at all, whichever of the five creation paths made their row.</p>
 */
public final class DefaultAvailabilitySeeder {

    private DefaultAvailabilitySeeder() {}

    /**
     * Persists this owner's Mon–Fri 09:00–18:00 global defaults and returns how many rules were
     * written. Idempotent: an owner who already has ANY global rule is left alone and 0 is returned,
     * so completing the wizard twice — or a user who set hours by hand before finishing it — never
     * ends up with doubled rules. Must be called inside a transaction.
     */
    public static int seedGlobalDefaults(Long ownerId) {
        if (ownerId == null) {
            return 0;
        }
        if (AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId) > 0) {
            return 0;
        }
        List<AvailabilityRule> rules = weekdayDefaults();
        for (AvailabilityRule r : rules) {
            r.ownerId = ownerId;
            r.persist();
        }
        return rules.size();
    }

    /** Mon–Fri 09:00–18:00, global (meetingTypeId == null). Unstamped — the caller sets ownerId. */
    static List<AvailabilityRule> weekdayDefaults() {
        List<AvailabilityRule> rules = new ArrayList<>();
        for (DayOfWeek d : List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(18, 0);
            r.meetingTypeId = null;
            rules.add(r);
        }
        return rules;
    }
}
```

- [ ] **Step 4: Run the seeder tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest='DefaultAvailabilitySeeder*Test'
```

Expected: PASS — 4 tests (1 from the old `DefaultAvailabilitySeederTest`, 3 new), 0 failures.

- [ ] **Step 5: Write the failing wizard tests**

Append these two tests inside `src/test/java/site/asm0dey/calit/web/MeSetupResourceTest.java`, before the closing brace. They reuse the file's existing `seed(...)` and `reload(...)` helpers.

Add these imports at the top of the file:

```java
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.MeetingType;
```

Add this field next to the existing `@Inject EntityManager em;`:

```java
    @Inject
    SlotService slotService;
```

Add the tests:

```java
    @Test
    @TestSecurity(
            user = "wiz6",
            roles = {"user"})
    void completingTheWizardSeedsWeekdayDefaults() {
        var id = seed("wiz6", false);
        assertEquals(0, countGlobalRules(id), "precondition: a fresh user has no availability");

        completeWizard();

        assertEquals(5, countGlobalRules(id), "Mon–Fri seeded");
        var monday = AvailabilityRule.globalForOwner(id, DayOfWeek.MONDAY);
        assertEquals(1, monday.size());
        assertEquals(LocalTime.of(9, 0), monday.getFirst().startTime);
        assertEquals(LocalTime.of(18, 0), monday.getFirst().endTime);
        assertNull(monday.getFirst().meetingTypeId, "defaults are global, not per-type");

        // The point of the bean: a meeting type made right after onboarding is bookable, with the
        // availability editor never opened.
        MeetingType t = seedMeetingType(id);
        LocalDate monday1 = LocalDate.of(2026, 9, 7); // a Monday
        assertFalse(
                slotService.generateRawSlots(t, monday1, monday1.plusDays(1)).isEmpty(),
                "a new user's meeting type must offer slots without touching the availability editor");
    }

    @Test
    @TestSecurity(
            user = "wiz7",
            roles = {"user"})
    void seedingIsIdempotentAcrossRepeatedWizardSubmits() {
        var id = seed("wiz7", false);
        completeWizard();
        completeWizard(); // the wizard is still POST-able; a second submit must not double the rules
        assertEquals(5, countGlobalRules(id));
    }

    private void completeWizard() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "Wiz")
                .formParam("ownerEmail", "wiz@example.com")
                .formParam("timezone", "Europe/Amsterdam")
                .redirects()
                .follow(false)
                .when()
                .post("/me/setup")
                .then()
                .statusCode(303);
    }

    @Transactional
    long countGlobalRules(Long ownerId) {
        em.clear();
        return AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    @Transactional
    MeetingType seedMeetingType(Long ownerId) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Intro";
        t.slug = "intro";
        t.durationMinutes = 30;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        return t;
    }
```

- [ ] **Step 6: Run them to verify they fail**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=MeSetupResourceTest
```

Expected: FAIL — `completingTheWizardSeedsWeekdayDefaults` reports `expected: <5> but was: <0>`.

- [ ] **Step 7: Wire the seeder into the wizard**

In `src/main/java/site/asm0dey/calit/web/MeSetupResource.java`, inside `submit()`, insert between `s.persist();` and `me.settingsComplete = true;`:

```java
        // Step 3: a brand-new owner has no availability at all, so their meeting types would offer no
        // slots and the working-hours grid would render empty. Seed Mon–Fri 09:00–18:00 globals here —
        // MeOwnerFilter forces every user through this wizard before they can use /me, whichever path
        // created their row. Idempotent, so a second submit can't double the rules.
        DefaultAvailabilitySeeder.seedGlobalDefaults(ownerId);

```

Add the import:

```java
import site.asm0dey.calit.availability.DefaultAvailabilitySeeder;
```

- [ ] **Step 8: Confirm the working-hours help text is now true**

Read `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java:460`. The string is:

> "Weekly hours for this meeting type. These hours ARE its week: a day with no frames is not bookable. Until you save them the grid shows your global default hours. …"

With defaults seeded this sentence is now accurate, so **no string changes and no translation work**. Verify the text still reads that way; if it has drifted, and only then, reword it — and if you reword it, add the matching `de` and `he` values in `src/main/resources/messages/adm_de.properties` and `adm_he.properties` in the same commit, keeping every `{placeholder}` name identical.

- [ ] **Step 9: Run the wizard tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=MeSetupResourceTest
```

Expected: PASS — 8 tests, 0 failures.

- [ ] **Step 10: Run the full suite**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

Watch specifically for availability/slot tests that assumed a wizard-completing user had *no* rules. If one breaks, the fix is in the test's expectations, not in the seeding — but read it first: a genuine collision means some flow seeds hours twice.

- [ ] **Step 11: Format**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o spotless:apply && ./mvnw -o spotless:check
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 12: Tick the bean and commit**

```bash
cd /home/finkel/work_self/calit
beans show calit-sjwh   # copy the checkbox lines verbatim — replace-old must match byte-for-byte
```

Tick the boxes this task actually finished — **all five**, since deciding the seed point, wiring it, making it idempotent, testing it, and confirming the help text all land here. Do **not** set `status: completed`: the bean stays `in-progress` until Task 3 backfills the accounts that onboarded before this change. Use the exact box text from `beans show`:

```bash
beans query 'mutation {
  updateBean(id: "calit-sjwh", input: {
    status: "in-progress"
    bodyMod: {
      replace: [
        { old: "[ ] Decide where seeding belongs", new: "[x] Decide where seeding belongs" }
        { old: "[ ] Wire weekdayDefaults() there", new: "[x] Wire weekdayDefaults() there" }
        { old: "[ ] Make it idempotent", new: "[x] Make it idempotent" }
        { old: "[ ] Test that a newly created user", new: "[x] Test that a newly created user" }
        { old: "[ ] Confirm the working-hours help text", new: "[x] Confirm the working-hours help text" }
      ]
    }
  }) { id status body }
}'
```

Each `old` must occur exactly once in the body or the whole mutation aborts — the prefixes above are chosen to be unique, but check the output.

Then commit:

```bash
git add src/main/java/site/asm0dey/calit/availability/DefaultAvailabilitySeeder.java \
        src/main/java/site/asm0dey/calit/web/MeSetupResource.java \
        src/test/java/site/asm0dey/calit/availability/DefaultAvailabilitySeederPersistenceTest.java \
        src/test/java/site/asm0dey/calit/web/MeSetupResourceTest.java \
        .beans
git commit -m "fix(availability): seed weekday defaults when a user finishes onboarding

DefaultAvailabilitySeeder was dead code — a no-op startup observer and a
weekdayDefaults() with no production caller — so every new user started with
zero global availability rules: their meeting types offered no slots and the
working-hours grid rendered empty under help text promising defaults.

The first-login wizard is the one place every user must pass through, so it
seeds Mon-Fri 09:00-18:00 globals stamped with the owner id. Seeding no-ops
when the owner already has a global rule, so a repeated submit can't double
them. The seeder loses its CDI wiring and becomes a static helper.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QF9JXW4V2AFDBicwBfV5zH"
```

- [ ] **Step 13: Add the changelog entry on the `docs-site` branch**

This is a user-facing behaviour change, so the changelog bullet lands at merge, not at release. On the `docs-site` branch, in `docs-site/src/content/docs/releases/changelog.md`, under `## Unreleased` (create the section with its standing subtitle "Merged but not yet in a tagged release." if it is absent), add:

```markdown
- **New accounts now start with working hours already set.** Finishing the first-login wizard used to
  leave an account with no availability at all: its meeting types offered no bookable slots, and the
  working-hours grid rendered empty even though its help text promised your global defaults. Completing
  the wizard now seeds Monday–Friday 09:00–18:00 as your global default hours, which you can edit or
  replace as usual. Accounts that onboarded before this release are backfilled on upgrade, but only when
  they have no global hours at all — anything you already set is left untouched. If you had deliberately
  left an account with no global hours, note that meeting types with no per-type hours of their own become
  bookable again on those default hours. ([#N](https://github.com/asm0dey/calit/pull/N))
```

Replace `#N` with the real PR number once the PR is open. Do this on a branch off `docs-site` and open a PR — the direct-push carve-out does not apply to this repo.

---

### Task 3: Backfill default availability for accounts that onboarded before Task 2

Also `calit-sjwh`. Task 2 only seeds at the wizard, and `MeSetupResource:105` is the sole writer of `settingsComplete = true` — so an account that finished onboarding *before* this change never re-enters the wizard and keeps its zero global rules forever. That includes the two accounts that surfaced the bean. A Flyway backfill closes it on upgrade.

`V24__backfill_owner_settings.sql` is the precedent for exactly this shape (it backfilled the `owner_settings` row `SetupResource` used to skip); follow it.

Scope note: the `WHERE NOT EXISTS` clause means an owner who has *any* global rule is untouched, so nothing a user set by hand is overwritten. The one behaviour change is for an owner who deliberately kept zero global rules — their meeting types that have no per-type hours become bookable on 09:00–18:00. Those types offer no slots at all today, so they were already effectively parked; the changelog names this.

**Files:**
- Create: `src/main/resources/db/migration/V28__seed_default_availability.sql`
- Test: `src/test/java/site/asm0dey/calit/availability/DefaultAvailabilityBackfillTest.java`

**Interfaces:**
- Consumes: `DefaultAvailabilitySeeder.seedGlobalDefaults(Long)` from Task 2 only as the semantics it mirrors — the migration itself is pure SQL and calls no Java. Do Task 2 first so the two definitions of "default hours" are written together.
- Produces: nothing later tasks call.

- [ ] **Step 1: Confirm the next free migration number**

```bash
cd /home/finkel/work_self/calit
ls src/main/resources/db/migration/ | sort -V | tail -3
```

Expected: `V25__owner_time_format.sql`, `V26__booking_calendar_address.sql`, `V27__meeting_type_write_target.sql` — so the new file is **V28**. If a higher number exists (another branch landed first), take the next one and rename accordingly throughout this task. Never edit an existing migration; Flyway checksum validation fails even on a comment change.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/site/asm0dey/calit/availability/DefaultAvailabilityBackfillTest.java`. It runs the migration's own SQL text — read from the classpath, never retyped — against a seeded database, so the test and the migration can never drift apart.

```java
package site.asm0dey.calit.availability;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.user.AppUser;

/**
 * The backfill runs at boot against a database the test callback then truncates, so its effect can
 * never be observed in situ. Instead this executes the migration's OWN sql text against a seeded
 * database — the statement is read from the classpath, so the test cannot drift from the migration.
 */
@QuarkusTest
class DefaultAvailabilityBackfillTest {

    private static final String MIGRATION = "/db/migration/V28__seed_default_availability.sql";

    @Inject
    EntityManager em;

    private String migrationSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, MIGRATION + " must be on the test classpath");
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    @Transactional
    int runBackfill() throws IOException {
        return em.createNativeQuery(migrationSql()).executeUpdate();
    }

    @Transactional
    Long seedUser(String username) {
        AppUser u = AppUser.create(username, null, false);
        u.settingsComplete = true; // onboarded before the wizard learned to seed
        u.persist();
        return u.id;
    }

    @Transactional
    void seedOneRule(Long ownerId, DayOfWeek day) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = ownerId;
        r.dayOfWeek = day;
        r.startTime = LocalTime.of(10, 0);
        r.endTime = LocalTime.of(12, 0);
        r.meetingTypeId = null;
        r.persist();
    }

    @Transactional
    long globalCount(Long ownerId) {
        em.clear();
        return AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    @Test
    void backfillsOwnersWithNoGlobalRules() throws IOException {
        Long bare = seedUser("legacy1");
        runBackfill();
        assertEquals(5, globalCount(bare));
        var monday = AvailabilityRule.globalForOwner(bare, DayOfWeek.MONDAY);
        assertEquals(1, monday.size());
        assertEquals(LocalTime.of(9, 0), monday.getFirst().startTime);
        assertEquals(LocalTime.of(18, 0), monday.getFirst().endTime);
        assertNull(monday.getFirst().meetingTypeId);
    }

    @Test
    void leavesOwnersWithExistingGlobalRulesAlone() throws IOException {
        Long configured = seedUser("legacy2");
        seedOneRule(configured, DayOfWeek.SATURDAY);
        runBackfill();
        assertEquals(1, globalCount(configured), "hand-set hours must survive untouched");
        assertTrue(AvailabilityRule.globalForOwner(configured, DayOfWeek.MONDAY).isEmpty());
    }

    @Test
    void isIdempotent() throws IOException {
        Long bare = seedUser("legacy3");
        runBackfill();
        runBackfill();
        assertEquals(5, globalCount(bare), "a second run must add nothing");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=DefaultAvailabilityBackfillTest
```

Expected: FAIL — all three tests fail on `assertNotNull(in, ...)` because the migration file does not exist yet.

- [ ] **Step 4: Write the migration**

Create `src/main/resources/db/migration/V28__seed_default_availability.sql`:

```sql
-- Backfill default availability for accounts that onboarded before the first-login wizard learned
-- to seed it (calit-sjwh). DefaultAvailabilitySeeder was dead code: its startup observer was a
-- no-op and weekdayDefaults() had no production caller, so every user created up to now has zero
-- global availability rules -- their meeting types offer no slots and the working-hours grid renders
-- empty under help text promising defaults. MeSetupResource#submit now seeds Mon-Fri 09:00-18:00 at
-- onboarding, but an already-onboarded account never re-enters the wizard, so it needs this.
--
-- Mirrors DefaultAvailabilitySeeder.seedGlobalDefaults: same hours, same global scope
-- (meeting_type_id IS NULL), same "skip an owner who already has ANY global rule" guard -- so
-- hand-set hours are never overwritten and re-running adds nothing.
INSERT INTO availability_rule (owner_id, day_of_week, start_time, end_time, meeting_type_id)
SELECT u.id, d.day_of_week, TIME '09:00', TIME '18:00', NULL
FROM app_user u
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'))
    AS d(day_of_week)
WHERE NOT EXISTS (
    SELECT 1 FROM availability_rule r
    WHERE r.owner_id = u.id AND r.meeting_type_id IS NULL
);
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=DefaultAvailabilityBackfillTest
```

Expected: PASS — 3 tests, 0 failures.

If Hibernate's schema validation refuses to boot, the migration touched a column that does not exist — check the `availability_rule` column names against `AvailabilityRule` (`owner_id`, `day_of_week`, `start_time`, `end_time`, `meeting_type_id`).

- [ ] **Step 6: Run the full suite**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

The migration is a no-op in the suite (it runs at boot against an empty `app_user`, and `DatabaseResetCallback` truncates everything before each test anyway), so nothing else should move. If something does, read it — it means a test was depending on boot-time database state.

- [ ] **Step 7: Format**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o spotless:apply && ./mvnw -o spotless:check
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V28__seed_default_availability.sql \
        src/test/java/site/asm0dey/calit/availability/DefaultAvailabilityBackfillTest.java
git commit -m "fix(availability): backfill default hours for already-onboarded accounts

The wizard now seeds Mon-Fri 09:00-18:00, but settingsComplete is written
once and never again, so an account that onboarded before that change would
keep its zero global rules forever. V28 gives default hours to every owner
that has none, skipping any owner who already set global hours, so nothing
hand-configured is overwritten.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QF9JXW4V2AFDBicwBfV5zH"
```

- [ ] **Step 9: Add the upgrade note to the changelog**

On the `docs-site` branch, in the same `## Unreleased` section Task 2 Step 13 wrote, make sure the section closes with an upgrade note naming the backfill:

```markdown
**On upgrade:** V28 runs automatically at boot and gives Monday–Friday 09:00–18:00 global default hours
to any account that has none. Accounts with global hours already set are untouched. No configuration
changes.
```

- [ ] **Step 10: Close the bean**

Every box on `calit-sjwh` was ticked in Task 2 Step 12, and the retrofit gap that kept it open is now closed:

```bash
cd /home/finkel/work_self/calit
beans update calit-sjwh -s completed --body-append "## Summary of Changes

Seeding lives in the first-login wizard (\`MeSetupResource#submit\`), not at user creation: \`MeOwnerFilter\` bounces anyone with \`settingsComplete == false\` to \`/me/setup\` and that method is the only writer of \`settingsComplete = true\`, so all five creation paths (/setup, /signup, admin invite, Google, OIDC) are covered by one call. \`DefaultAvailabilitySeeder\` dropped its CDI annotations and its no-op \`onStart\` and became a static helper with a new \`seedGlobalDefaults(ownerId)\` that stamps \`owner_id\` and no-ops when the owner already has any global rule, so a repeated submit cannot double the rules.

\`settingsComplete\` is written once and never again, so accounts that onboarded before this change would never have re-entered the wizard. \`V28__seed_default_availability.sql\` backfills them with the same hours and the same skip-if-any-global-rule guard, verified by a test that executes the migration's own SQL text off the classpath rather than a retyped copy.

The working-hours help text (AdminMessages:460) is true as written now that defaults exist, so no string or translation changes."
git add .beans && git commit -m "chore(beans): close the default-availability bean

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QF9JXW4V2AFDBicwBfV5zH"
```

---

### Task 4: Close the already-fixed Sonar NPE bean

Bean: `calit-wvtl`, status `in-progress`, last box unticked: "Confirm the gate goes green on the PR". PR #142 merged at 2026-08-21T19:30:04Z, and the guard is present on `main` — `WriteTargetResolver.writeOverride` opens with `if (ownerId == null || type == null) { return null; }`. A merged PR means the gate cleared. Nothing to build; this is bookkeeping.

**Files:**
- Modify: `.beans/` — the `calit-wvtl` bean file (via the `beans` CLI)

**Interfaces:**
- Consumes: nothing. Produces: nothing. Independent of Tasks 1–3 — do it in any order.

- [ ] **Step 1: Re-verify the guard is on `main` and the PR merged**

```bash
cd /home/finkel/work_self/calit
gh pr view 142 --json number,state,mergedAt
grep -n "ownerId == null" src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java
```

Expected: `"state":"MERGED"` with a `mergedAt` timestamp, and a hit on the null guard. If either is missing, STOP — the bean is genuinely still open and its remaining work must be done rather than closed.

- [ ] **Step 2: Tick the last box and close the bean**

```bash
beans update calit-wvtl -s completed \
  --body-replace-old "[ ] Confirm the gate goes green on the PR" \
  --body-replace-new "[x] Confirm the gate goes green on the PR"
beans update calit-wvtl --body-append "## Summary of Changes

\`WriteTargetResolver.writeOverride\` guards \`ownerId == null || type == null\` and returns null (\"no override\") instead of throwing, clearing javabugs:S2259 and the new-reliability-rating gate. Shipped in PR #142, merged 2026-08-21."
```

- [ ] **Step 3: Commit the bean**

```bash
git add .beans
git commit -m "chore(beans): close the WriteTargetResolver NPE bean

PR #142 merged with the null guard in place, so the Sonar gate cleared.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QF9JXW4V2AFDBicwBfV5zH"
```

---

## Deferred / follow-up

Found while planning, **out of scope for this plan** — filed as bean `calit-a4yj`:

- `SignupResource:76` creates the `AppUser` but **no `OwnerSettings` row**, unlike `SetupResource:78`, `UsersResource:123` and `GoogleSignInService.provision`. `OwnerSettings.ownerName`/`ownerEmail`/`timezone` are NOT NULL and the public booking path reads `OwnerSettings.forOwner(...).timezone` — the exact NPE class that issue #99 was about. It is likely masked today because the wizard creates the row before the user can be booked, but the asymmetry is a live trap. The structural fix is a single `provisionNewUser()` helper called by all five creation sites, which would also be the natural home for the availability seeding this plan puts in the wizard.
