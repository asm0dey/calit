# AssertJ Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JUnit `Assertions.*` and Hamcrest `MatcherAssert.assertThat` in `src/test` with AssertJ, so a failing test prints the actual value instead of "expected: <true> but was: <false>".

**Architecture:** One-off OpenRewrite run of the stock AssertJ composite, JUnit 5 best practices included (no build plugin left behind), then a hand pass on what the recipe can't express (`assertAll`, captured `assertThrows`, stream/`allMatch` shapes), then docs + precedent. Pure test-code refactor: no production file changes, and the existing suite is the regression test.

**Tech Stack:** Java 25, JUnit 5 (via `quarkus-junit5`), AssertJ 3.27.7, OpenRewrite `rewrite-maven-plugin` 6.46.1 + `rewrite-testing-frameworks` 3.44.0 (run once, never added to `pom.xml`), Spotless/Prince of Space, IntelliJ via mcp-steroid (optional cleanup).

**Spec:** bean `calit-e8id` (`.beans/calit-e8id--migrate-test-assertions-to-assertj.md`). Read it first — it holds the pattern map and the worked example this plan refers to.

## Global Constraints

- Work on a new branch cut from `origin/main`, never from local `main` or the current branch.
- `assertj-core` is test scope, version via an `<assertj.version>` property (Renovate tracks properties). The Quarkus BOM does not manage it. Use `3.27.7` (latest stable; `4.0.0-M1` is a milestone — do not use).
- No OpenRewrite plugin or recipe file is committed; the run is a one-off CLI invocation of the stock `org.openrewrite.java.testing.assertj.Assertj` composite (includes `JUnit5BestPractices`).
- Leave RestAssured `.body(containsString(...))`, `.body(not(...))`, `.body("x", hasSize(...))` etc. alone — those Hamcrest matchers are RestAssured's API. Only `org.hamcrest.MatcherAssert.assertThat` gets migrated.
- AssertJ static imports as single-type imports (`import static org.assertj.core.api.Assertions.assertThat;`) or the wildcard, whatever the recipe/formatter leaves. No FQNs in test bodies (`org.assertj.core.api.Assertions.assertThat(...)` inline is wrong). JEP 511 module imports apply only to `java.*`/`javax.*`, not AssertJ.
- No production code under `src/main` changes. No `@Test` method is deleted. Renames only from `RemoveTestPrefix` (`testX` → `x`).
- Never stage with `git add -A` / `git add .`. Stage `src/test`, `pom.xml` and named files explicitly.
- Full `mvn test` green (0 failures, 0 errors, `BUILD SUCCESS`) before opening the PR; `mvn verify` too, because `*IT` classes (e.g. `OgImageResourceIT`) are touched and only run there.
- Track progress by checking off the bean's todo items (`beans update calit-e8id --body-replace-old "- [ ] X" --body-replace-new "- [x] X"`, one replace per call).

## Baseline (snapshot 2026-09-30, for the "is everything gone" checks)

- 312 test `.java` files; 220 import `org.junit.jupiter.api.Assertions`.
- JUnit calls: `assertEquals` 1107, `assertTrue` 459, `assertNull` 208, `assertFalse` 200, `assertNotNull` 126, `assertThrows` 74, `assertNotEquals` 24, `assertDoesNotThrow` 20, `assertArrayEquals` 17, `fail` 3, `assertAll` 3, `assertSame` 2, `assertNotSame` 1, `assertInstanceOf` 1.
- Hamcrest: 104 files import `org.hamcrest.Matchers.containsString`, 35 import `not` — nearly all RestAssured `.body(...)`. The only `MatcherAssert.assertThat` use is `web/HostSuggestTest.java:71-74` (inline FQN).
- `assertAll`/`assertArrayEquals` live in `web/CardCsrfCookieFilterTest`, `web/DisplayExtensionsTest`, `web/OgImageResourceIT`, `web/OgImageResourceTest`, `web/og/CardRendererTest`.

## Review Focus

1. **Vacuous assertions.** A rewrite that leaves `assertThat(x);` with no terminal call compiles and always passes. Task 2 greps for it.
2. **Identity vs equality.** `assertTrue(a == b)` on objects becomes `isSameAs`; on boxed numbers/strings that may have passed by accident before, but `assertEquals(a, b)` must never turn into `isSameAs`. Task 2 greps every new `isSameAs`/`isNotSameAs` and reviews each one.
3. **Assertions that stopped biting.** A green suite after a mass rewrite proves nothing if the rewrite weakened checks. Task 2 flips one converted assertion per assertion kind (equality, collection, exception) and confirms the test fails.
4. **RestAssured matchers touched.** If `MigrateHamcrestToAssertJ` or import cleanup removed a `org.hamcrest.Matchers` import still used by `.body(...)`, compile fails; if it rewrote a `.body` argument, semantics change. Task 1 compares Hamcrest import counts before/after.
5. **Prince of Space string double-escape.** POS 2.2.0 double-escapes split string literals (upstream #100). Running `spotless:apply` over ~220 files may touch literals. Task 1 greps the diff for newly added `\\\\`.

---

### Task 1: Dependency + mechanical OpenRewrite pass

**Files:**
- Modify: `pom.xml` (`<properties>` block near line 18-30; test dependencies near line 114)
- Modify: every `src/test/java/**/*.java` the recipe rewrites (~220)

**Interfaces:**
- Produces: `org.assertj:assertj-core:${assertj.version}` on the test classpath; test sources using `org.assertj.core.api.Assertions.*`. Later tasks rely on the remaining-JUnit grep list printed in Step 6.

- [ ] **Step 1: Branch from origin/main and mark the bean in progress**

```bash
git fetch origin
git switch -c test/assertj-migration origin/main
beans update calit-e8id -s in-progress
```

- [ ] **Step 2: Add the dependency**

In `pom.xml` `<properties>`, after `<sonar-maven-plugin.version>`:

```xml
    <assertj.version>3.27.7</assertj.version>
```

Directly after the `quarkus-junit5` dependency line:

```xml
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <version>${assertj.version}</version>
      <scope>test</scope>
    </dependency>
```

Run: `mvn -q dependency:tree -Dincludes=org.assertj -Dscope=test`
Expected: one line `org.assertj:assertj-core:jar:3.27.7:test`.

- [ ] **Step 3: Record baseline counts**

```bash
grep -rl "import static org.hamcrest.Matchers" src/test | wc -l > target/hamcrest-before.txt
cat target/hamcrest-before.txt
```

- [ ] **Step 4: Run the recipe**

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-testing-frameworks:3.44.0 \
  -Drewrite.activeRecipes=org.openrewrite.java.testing.assertj.Assertj
```

The stock composite also runs `JUnit5BestPractices` (wanted): `TestsShouldNotBePublic`, `RemoveTestPrefix` (renames `testFoo()` → `foo()`), `AssertThrowsOnLastStatement`, `RemoveTryCatchFailBlocks`, `LifecycleNonPrivate`, `UseAssertSame`, and friends. Its `AddDependency assertj-core 3.x` skips because Step 2 already added the artifact.

Expected: `BUILD SUCCESS` and a list of changed files under `src/test/`. `JUnit5BestPractices` also carries `UpgradeToJUnit514` (dependency version bumps). If `pom.xml` changed beyond Step 2's edits, check `git diff pom.xml`: JUnit is managed by the Quarkus BOM, so revert any JUnit version the recipe pinned or bumped. The Step 2 lines stay.

- [ ] **Step 5: Format and check the guard-rails**

```bash
mvn spotless:apply
git diff --stat -- src/main | tail -1          # expected: empty (no production changes)
grep -rl "import static org.hamcrest.Matchers" src/test | wc -l   # expected: equal to target/hamcrest-before.txt
git diff -U0 -- src/test | grep '^+' | grep -F '\\\\' | head      # expected: empty (POS double-escape, Review Focus 5)
```

If the double-escape grep hits, fix those literals by hand back to their pre-rewrite text (`git diff` shows the original).

- [ ] **Step 6: Compile and list leftovers for Task 2**

```bash
mvn -q test-compile
grep -rn "org.junit.jupiter.api.Assertions\|MatcherAssert" src/test > target/assertj-leftovers.txt; wc -l target/assertj-leftovers.txt
```

Expected: `test-compile` succeeds. Leftovers are expected (`assertAll`, captured `assertThrows`, maybe `assertDoesNotThrow`) — Task 2 handles them. If compile fails, fix the reported lines by hand using the pattern map in the bean; do not revert the recipe.

- [ ] **Step 7: Run the full suite**

Run: `mise run test` (or `mvn test`; Docker must be running)
Expected: `BUILD SUCCESS`, 0 failures, 0 errors. A failure here means a conversion changed semantics — inspect that assertion's diff and fix it; don't `@Disabled` anything.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/test
git status --short    # confirm only pom.xml + src/test staged; no target/, no .beans stray files
beans update calit-e8id --body-replace-old "- [ ] Add assertj-core (test scope, pinned via assertj.version property; not in the Quarkus BOM) to pom.xml" --body-replace-new "- [x] Add assertj-core (test scope, pinned via assertj.version property; not in the Quarkus BOM) to pom.xml"
beans update calit-e8id --body-replace-old "- [ ] Run the OpenRewrite Assertj recipe" --body-replace-new "- [x] Run the OpenRewrite Assertj recipe"
git add .beans/calit-e8id--migrate-test-assertions-to-assertj.md
git commit -m "test: migrate JUnit and Hamcrest assertions to AssertJ via OpenRewrite

One-off run of rewrite-testing-frameworks 3.44.0 Assertj composite,
including JUnit5BestPractices (non-public test classes/methods, test
prefix removal). RestAssured .body() Hamcrest matchers untouched.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Leftovers + verify assertions still bite

**Files:**
- Modify: every file listed in `target/assertj-leftovers.txt`; known: `web/CardCsrfCookieFilterTest.java`, `web/DisplayExtensionsTest.java`, `web/OgImageResourceIT.java`, `web/OgImageResourceTest.java`, `web/og/CardRendererTest.java`, `web/HostSuggestTest.java` (all under `src/test/java/site/asm0dey/calit/`)

**Interfaces:**
- Consumes: `target/assertj-leftovers.txt` from Task 1 Step 6.
- Produces: zero `org.junit.jupiter.api.Assertions` / `MatcherAssert` references in `src/test`.

- [ ] **Step 1: Convert each leftover by shape**

`assertAll(...)` → soft assertions:

```java
// before
assertAll(() -> assertEquals(a, x), () -> assertTrue(y.isBlank()));
// after
assertSoftly(s -> {
    s.assertThat(x).isEqualTo(a);
    s.assertThat(y).isBlank();
});
// import static org.assertj.core.api.SoftAssertions.assertSoftly;
```

Captured `assertThrows`:

```java
// before
var ex = assertThrows(BookingConflictException.class, () -> service.book(req));
assertEquals("slot taken", ex.getMessage());
// after
assertThatThrownBy(() -> service.book(req))
    .isInstanceOf(BookingConflictException.class)
    .hasMessage("slot taken");
// when later lines need the exception object itself:
var ex = catchThrowableOfType(BookingConflictException.class, () -> service.book(req));
```

`assertDoesNotThrow(() -> x())` → `assertThatCode(() -> x()).doesNotThrowAnyException();`. When its return value is used (`var r = assertDoesNotThrow(() -> x());`), just call `var r = x();` — a thrown exception already fails the test.

`HostSuggestTest` lines 71-74 (if the recipe missed the FQN form):

```java
assertThat(body)
    .contains(eligible.username)
    .doesNotContain(disabled.username, incomplete.username, alreadyHost.username);
```

- [ ] **Step 2: Confirm nothing JUnit/Hamcrest-assert remains**

```bash
grep -rn "org.junit.jupiter.api.Assertions\|MatcherAssert" src/test
```

Expected: no output.

- [ ] **Step 3: Hunt vacuous and identity assertions (Review Focus 1, 2)**

```bash
# assertThat(...) statement with no chained call
grep -rnE "^\s*assertThat\([^;]*\);\s*$" src/test | grep -vE "\)\.\w+\(" 
# every identity assertion introduced by the migration
git diff origin/main -U0 -- src/test | grep -nE '^\+.*is(Not)?SameAs\('
```

Expected: first grep empty. For each `isSameAs`/`isNotSameAs` hit, check the pre-migration line: if it was `assertEquals`/`assertNotEquals`, change it to `isEqualTo`/`isNotEqualTo`; if it was `assertSame` or `assertTrue(a == b)` on enum/reference identity, keep it.

- [ ] **Step 4: Prove assertions still fail (Review Focus 3)**

Pick one converted assertion of each kind, break it, run just that test, see it fail, revert:

1. Equality — in `src/test/java/site/asm0dey/calit/booking/BookingServiceTest.java`, change the expected value of the first `isEqualTo(...)` to something wrong.
2. Collection — in `src/test/java/site/asm0dey/calit/web/AdminDateOverridesTest.java` `createOverrideDropsInvertedWindowsAndKeepsAtMostThree`, change one expected window.
3. Exception — in any test with `assertThatThrownBy`, change `.isInstanceOf(X.class)` to `.isInstanceOf(IllegalStateException.class)` (or another wrong type).

```bash
mvn test -Dtest=BookingServiceTest          # expected: FAIL, message shows actual value
mvn test -Dtest=AdminDateOverridesTest      # expected: FAIL
git checkout -- src/test/java/site/asm0dey/calit/booking/BookingServiceTest.java src/test/java/site/asm0dey/calit/web/AdminDateOverridesTest.java <exception-test-file>
```

Run this step on a clean tree (`git status --short src/test` empty) — either first thing in Task 2, or after committing Step 1-3 edits — so `git checkout --` only throws away the deliberate breakage.

- [ ] **Step 5: Format, full suite, commit**

```bash
mvn spotless:apply
mise run test        # expected: BUILD SUCCESS, 0 failures, 0 errors
git add src/test
git commit -m "test: convert assertAll, captured assertThrows and leftovers to AssertJ

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Collection assertions by hand

**Files:**
- Modify: test files matched by the greps below (snapshot estimate ~60 files; `web/`, `booking/`, `google/` hold most)

**Interfaces:**
- Consumes: AssertJ-only test sources from Task 2.
- Produces: collection checks written as `hasSize` / `isEmpty` / `allSatisfy` / `extracting(...).containsExactly(...)`.

- [ ] **Step 1: Find the shapes the recipe left**

```bash
grep -rnE "assertThat\([^)]*\.(stream\(\)\.(allMatch|anyMatch|noneMatch)|isEmpty\(\)|size\(\)|contains\()" src/test
grep -rnE "\.stream\(\)\s*\.map\(.*\)\s*\.toList\(\)\)\s*\.(isEqualTo|containsExactly)" src/test
grep -rnE "\.stream\(\)$" src/test   # formatter-wrapped chains; inspect each
```

The recipe's `SimplifyHasSizeAssertion`, `SimplifyStreamMapToExtracting` and Picnic iterable rules already cover the simple ones, so expect fewer hits than the bean's ~240 snapshot.

- [ ] **Step 2: Rewrite per the pattern map**

```java
assertThat(xs.stream().allMatch(w -> w.endTime.isAfter(w.startTime))).isTrue();
// ->
assertThat(xs).allSatisfy(w -> assertThat(w.endTime).isAfter(w.startTime));

assertThat(xs.stream().anyMatch(b -> b.status == CANCELLED)).isTrue();
// ->
assertThat(xs).anySatisfy(b -> assertThat(b.status).isEqualTo(CANCELLED));

assertThat(xs.stream().noneMatch(b -> b.ownerId == 2L)).isTrue();
// ->
assertThat(xs).noneSatisfy(b -> assertThat(b.ownerId).isEqualTo(2L));

assertThat(windows.stream().map(w -> w.startTime + "-" + w.endTime).toList())
    .isEqualTo(List.of("08:00-08:30", "09:00-09:30", "10:00-10:30"));
// ->
assertThat(windows)
    .extracting(w -> w.startTime, w -> w.endTime)
    .containsExactly(
        tuple(LocalTime.of(8, 0), LocalTime.of(8, 30)),
        tuple(LocalTime.of(9, 0), LocalTime.of(9, 30)),
        tuple(LocalTime.of(10, 0), LocalTime.of(10, 30)));
// import static org.assertj.core.api.Assertions.tuple;
```

Use `containsExactlyInAnyOrder` only where the production query has no `ORDER BY` (check the code under test — don't loosen an order the code guarantees). Keep `String.contains` on response bodies as `assertThat(body).contains("...")`; that is not a collection check.

- [ ] **Step 3: Optional IntelliJ cleanup via steroid**

If the IDE is connected (`steroid_list_projects` shows calit), batch-run the `SimplifiableAssertion` inspection's quick-fixes over `src/test/java`. Skip if unavailable — Step 1's greps are the gate, not this.

- [ ] **Step 4: Re-run Step 1 greps, format, full suite, commit**

```bash
mvn spotless:apply
mise run test        # expected: BUILD SUCCESS, 0 failures, 0 errors
git add src/test
beans update calit-e8id --body-replace-old "- [ ] Hand pass on collection assertions (pattern map below), then steroid SimplifiableAssertion over src/test" --body-replace-new "- [x] Hand pass on collection assertions (pattern map below), then steroid SimplifiableAssertion over src/test"
beans update calit-e8id --body-replace-old "- [ ] Fold any single-type imports the recipe adds into the project's import style; \`mvn spotless:apply\`" --body-replace-new "- [x] Fold any single-type imports the recipe adds into the project's import style; \`mvn spotless:apply\`"
git add .beans/calit-e8id--migrate-test-assertions-to-assertj.md
git commit -m "test: express collection checks with AssertJ extracting/allSatisfy

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Expected: first Step 1 grep returns only lines you deliberately kept (note why in the PR).

---

### Task 4: Convention docs, precedent, final verify, PR

**Files:**
- Modify: `CLAUDE.md` (Tests section)
- Modify: `/home/finkel/.claude/projects/-home-finkel-work-self-calit/memory/avoid-asserttrue.md` (not in repo)
- Modify: `.beans/calit-e8id--migrate-test-assertions-to-assertj.md`

- [ ] **Step 1: Add the convention to CLAUDE.md**

Append to the `## Tests` bullet list (after the RestAssured bullet):

```markdown
- Assertions are **AssertJ** (`assertThat(...)`, `assertThatThrownBy(...)`, `assertSoftly(...)`), not JUnit `Assertions.*` or Hamcrest `MatcherAssert`. Collections: `hasSize`/`allSatisfy`/`extracting(...).containsExactly(...)`, never `size()` + `assertTrue`. Hamcrest stays only inside RestAssured `.body(...)`.
```

Nothing enforces this; IDE templates still generate `assertEquals`. The CLAUDE.md line is what keeps agents from reintroducing them.

- [ ] **Step 2: Update the memory file**

Replace the body of `avoid-asserttrue.md` so it says: tests use AssertJ (migrated 2026-09, bean calit-e8id); no JUnit `Assertions`; the old "assertEquals over assertTrue" advice is superseded. Update its `MEMORY.md` line to `- [AssertJ in tests](avoid-asserttrue.md) — AssertJ only; no JUnit Assertions/assertTrue; Hamcrest only in RestAssured .body()`.

- [ ] **Step 3: Record precedent**

```bash
uv run <precedent plugin dir>/precedent.py check --topic testing --chose assertj
uv run <precedent plugin dir>/precedent.py record --topic testing --chose "AssertJ for test assertions" \
  --rejected "JUnit Assertions (failure messages say expected true)" \
  --rejected "Hamcrest MatcherAssert (weaker collection API, poor IDE discovery)" \
  --rationale "Fluent collection assertions (extracting/allSatisfy) and failure messages that print the actual value; migrated mechanically with a one-off OpenRewrite run, no build plugin kept"
```

(Find the plugin dir with `ls ~/.claude/plugins/cache/*/precedent/*/`; see memory `precedent-cli-via-uv`.)

- [ ] **Step 4: Final verify**

```bash
mvn verify        # runs spotless:check, full test suite, and *IT classes
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 5: Close the bean and commit**

```bash
beans update calit-e8id --body-replace-old "- [ ] Full \`mvn test\` green" --body-replace-new "- [x] Full \`mvn test\` green"
beans update calit-e8id --body-replace-old "- [ ] Record the choice in precedent (AssertJ over JUnit asserts/Hamcrest)" --body-replace-new "- [x] Record the choice in precedent (AssertJ over JUnit asserts/Hamcrest)"
beans update calit-e8id -s completed --body-append "## Summary of Changes

Added assertj-core 3.27.7 (test, via assertj.version). One-off OpenRewrite run (rewrite-testing-frameworks 3.44.0, stock Assertj composite incl. JUnit5BestPractices) converted JUnit/Hamcrest asserts; assertAll, captured assertThrows and collection shapes converted by hand. RestAssured .body() matchers untouched. Convention recorded in CLAUDE.md and precedent."
git add CLAUDE.md .beans/calit-e8id--migrate-test-assertions-to-assertj.md
git commit -m "docs: record AssertJ as the test assertion convention

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Open the PR (after the user confirms)**

Push and open a PR titled `test: migrate assertions to AssertJ`. Body: what changed, the exact OpenRewrite command, a note that JUnit5BestPractices changes (visibility, test-prefix renames) are in the diff on purpose, the before/after baseline counts, the three "assertions still bite" checks from Task 2 Step 4, any greps hits deliberately kept, a show-me diagram (memory `show-me-in-prs`), and the Claude Code attribution footer. No changelog entry and no docs-site change: nothing user-facing.
