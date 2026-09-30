---
# calit-e8id
title: Migrate test assertions to AssertJ
status: completed
type: task
priority: low
created_at: 2026-09-30T10:12:16Z
updated_at: 2026-09-30T11:23:41Z
---

Replace JUnit assertEquals/assertTrue/assertNull (and plain Hamcrest assertThat) in src/test with AssertJ assertThat, so failures describe the actual value instead of "expected true".

## How (checked 2026-09-30 via mcp-steroid on IntelliJ 2026.2.2)
- IntelliJ has no JUnit→AssertJ quick-fix: `MigrateAssertToMatcherAssert` targets Hamcrest `MatcherAssert`, and the OpenRewrite IDE plugin is not installed.
- Mechanical route: one-off OpenRewrite Maven run, no permanent build dependency:
  `mvn -U org.openrewrite.maven:rewrite-maven-plugin:run -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-testing-frameworks:RELEASE -Drewrite.activeRecipes=org.openrewrite.java.testing.assertj.Assertj`
- Then batch-apply IntelliJ `SimplifiableAssertion` over src/test through steroid (AssertJ-aware cleanup, e.g. `assertThat(x.isEmpty()).isTrue()` -> `assertThat(x).isEmpty()`).
- Keep RestAssured `.body(containsString(...))` Hamcrest matchers — that is RestAssured's API.

## Todo
- [x] Add assertj-core (test scope, pinned via assertj.version property; not in the Quarkus BOM) to pom.xml
- [x] Run the OpenRewrite Assertj recipe
- [x] Hand pass on collection assertions (pattern map below), then steroid SimplifiableAssertion over src/test
- [x] Fold any single-type imports the recipe adds into the project's import style; `mvn spotless:apply`
- [x] Full `mvn test` green
- [x] Record the choice in precedent (AssertJ over JUnit asserts/Hamcrest)

## Focus: collection assertions first

Collection checks are where JUnit asserts read worst. Today they're written as size + loop/`allMatch`, or by flattening into strings. Snapshot 2026-09-30 (single-line matches only, the formatter wraps many more): ~240 assertion lines over ~60 test files touch `.size()`, `isEmpty()`, `allMatch/anyMatch/noneMatch` or `List/Set/Map.of`. `.contains(` hits (~160) are mostly `String.contains` on response bodies, which are not collection checks. Those belong to RestAssured `.body(containsString(...))` instead.

Example: `AdminDateOverridesTest.createOverrideDropsInvertedWindowsAndKeepsAtMostThree` (PR #245). It first checked `size() == 3` + `assertTrue(stream().allMatch(end.isAfter(start)))` + the first start, and is now `assertEquals(List.of("08:00-08:30", ...), windows.stream().map(w -> w.startTime + "-" + w.endTime).toList())`. AssertJ form:

```java
assertThat(windowsOn(LocalDate.of(2026, 7, 2)))
    .extracting(w -> w.startTime, w -> w.endTime)
    .containsExactly(
        tuple(LocalTime.of(8, 0), LocalTime.of(8, 30)),
        tuple(LocalTime.of(9, 0), LocalTime.of(9, 30)),
        tuple(LocalTime.of(10, 0), LocalTime.of(10, 30)));
// when only the invariant matters, not the exact rows:
assertThat(windows).hasSize(3).allSatisfy(w -> assertThat(w.endTime).isAfter(w.startTime));
```

Pattern map:
- `assertEquals(n, xs.size())` -> `assertThat(xs).hasSize(n)`
- `assertTrue(xs.isEmpty())` / `assertFalse(...)` -> `assertThat(xs).isEmpty()` / `isNotEmpty()`
- `assertTrue(xs.stream().allMatch(p))` -> `assertThat(xs).allSatisfy(x -> ...)` (or `allMatch(p)`)
- `assertTrue(xs.contains(x))` -> `assertThat(xs).contains(x)`
- map-then-`assertEquals(List.of(...))` -> `extracting(...)` + `containsExactly(...)` / `containsExactlyInAnyOrder(...)`, with `tuple(...)` for several fields

## Method

1. Dependency. The Quarkus BOM does NOT manage `org.assertj:assertj-core` (an unversioned dependency fails the build). Pin it with a `<assertj.version>` property so Renovate tracks it. Latest on Central (2026-09-30) is `4.0.0-M1`, a milestone. Use the latest stable 3.x unless 4.0 is GA by then.
2. Mechanical pass (OpenRewrite, one-off, no plugin left in the pom):
   `mvn -U org.openrewrite.maven:rewrite-maven-plugin:run -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-testing-frameworks:RELEASE -Drewrite.activeRecipes=org.openrewrite.java.testing.assertj.Assertj`
   IntelliJ can't do this: checked via mcp-steroid on IntelliJ 2026.2.2. `MigrateAssertToMatcherAssert` targets Hamcrest `MatcherAssert`, and the OpenRewrite IDE plugin isn't installed.
3. Collection pass by hand. The recipe may leave `assertThat(xs.size()).isEqualTo(3)`-style results (unverified; its AssertJ best-practice sub-recipes might already simplify some), and it cannot rewrite stream/map-flattening shapes into `extracting`/`allSatisfy`. Rewrite those to the pattern map above. Then run IntelliJ `SimplifiableAssertion` over src/test via steroid (AssertJ-aware, e.g. `assertThat(xs.isEmpty()).isTrue()` -> `assertThat(xs).isEmpty()`).
4. Leave RestAssured `.body(containsString(...))` Hamcrest matchers alone.
5. Imports: `import static org.assertj.core.api.Assertions.*` style per file as the formatter leaves it, and no FQNs. Then `mvn spotless:apply`.
6. Full `mvn test` green, then record the choice in precedent.

## Summary of Changes

Added assertj-core 3.27.7 (test scope, assertj.version property). One-off OpenRewrite run (rewrite-testing-frameworks 3.44.0, stock Assertj composite incl. JUnit5BestPractices) converted JUnit/Hamcrest asserts; it needed JDK 25 and a temporary expansion of `import module` lines, which it cannot parse. Hand/scripted passes: assertDoesNotThrow -> assertThatCode, assertAll -> assertSoftly, boolean-wrapped checks -> anyMatch/extracting/hasSize/isGreaterThan/isAfter/isNotBlank. RestAssured .body() matchers untouched. Convention in CLAUDE.md and precedent.
