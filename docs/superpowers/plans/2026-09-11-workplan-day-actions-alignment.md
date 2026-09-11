# Workplan Day-Actions Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move each day card's action buttons off the day-name row and onto the same axis as the time frames they operate on, and stop the label↔buttons gap from scaling with viewport width.

**Architecture:** One template change in the shared `_workplanGrid.html` fragment. The day card becomes: a plain day label on its own line, then a single wrapping flex row holding the `[data-frames]` box on the left and the action-button group immediately to its right, with a fixed `gap-x-4` instead of `justify-between`. No Java, no JS, no CSS changes — `workplan.js` only ever uses descendant selectors (`querySelector('[data-frames]')`, `closest('[data-frame]')`, `closest('[data-workplan]')`), so re-nesting is safe.

**Tech Stack:** Qute HTML templates, Tailwind v4 + daisyUI 5, RestAssured + JUnit 5 (`@QuarkusTest`).

**Spec:** GitHub issue [#197](https://github.com/asm0dey/calit/issues/197) — *"Availability: day actions align with the day name instead of the time frames, and the gap grows with window width"*. Bean: `calit-3aen`.

## Global Constraints

- Docker must be running for `mvn test` (Dev Services Postgres). No embedded/H2 fallback.
- Build JDK: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` before `./mvnw` — the default `mvn` is JDK 21 and fails with *"release 25 not supported"*.
- `src/main/resources/templates/AdminResource/_workplanGrid.html` is included by `availability.html`, `meetingTypeDetail.html`, `sharedAvailability.html` and `meetingTypes.html`. One edit changes all four; do not fork the fragment.
- The `data-*` markers (`data-day`, `data-frames`, `data-frame`, `data-frame-template`, `data-add-frame`, `data-copy-all`, `data-copy-weekdays`, `data-clear-day`, `data-remove-frame`, and the `frameDay`/`frameStart`/`frameEnd` input names) are the JS contract. **None of them may be renamed or removed.** Only the wrapper elements and their Tailwind classes change.
- Qute `.html` templates are deliberately NOT Prettier-formatted. Do not run `bunx prettier` on them.
- User-facing strings: this change adds none. If you find yourself adding one, it needs `@Message` + `de` + `he` values in the same commit (see `CLAUDE.md` → Internationalization).
- Never open a PR while `mvn test` is red — the *whole* suite, not just touched classes.

---

### Task 1: Re-nest the day card so actions share the frames' axis

**Files:**
- Modify: `src/main/resources/templates/AdminResource/_workplanGrid.html:9-20` (the `.card-body` block inside `{#for row in week}`)
- Test: `src/test/java/site/asm0dey/calit/web/AdminWorkplanLayoutTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks (this is the only task).
- Produces: nothing later tasks rely on. The rendered DOM contract is unchanged except for nesting; `src/main/js/workplan.js` needs no edit.

#### Why a string-order assertion is the right test here

RestAssured cannot execute JS and the project has no HTML parser on the test classpath, so structural assertions are made against the response body string (this is the established pattern — see `AdminMeetingTypeFormTest:136-137`, which asserts `containsString("data-add-frame=\"MONDAY\"")`). The behaviour this task changes is *ordering*: the actions must now come **after** the frames box within the day card. Comparing `indexOf` positions in the body captures exactly that and nothing else — it does not lock in Tailwind class names, so a later restyle will not falsely fail.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminWorkplanLayoutTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// #197: the per-day action buttons must sit on the same axis as the time frames they act on,
// not on the day-name row. Asserted as document order inside the Monday card: the [data-frames]
// box opens before the first action button. RestAssured can't run JS and there's no HTML parser
// on the test classpath, so document order is checked by substring position -- deliberately not
// by Tailwind class names, so a restyle doesn't falsely fail this.
@QuarkusTest
class AdminWorkplanLayoutTest {

    @Test
    void dayActionsRenderAfterTheFramesBox() {
        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/availability")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        int cardStart = body.indexOf("data-day=\"MONDAY\"");
        assertTrue(cardStart > 0, "Monday day card is rendered");
        int frames = body.indexOf("data-frames", cardStart);
        int actions = body.indexOf("data-copy-all=\"MONDAY\"", cardStart);
        assertTrue(frames > 0 && actions > 0, "Monday card has both a frames box and its actions");
        assertTrue(
                actions > frames,
                "day actions come after the frames box, so they share the frames' axis (frames at " + frames
                        + ", actions at " + actions + ")");
    }

    @Test
    void dayLabelDoesNotShareAJustifyBetweenRowWithTheActions() {
        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/availability")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        int cardStart = body.indexOf("data-day=\"MONDAY\"");
        int cardEnd = body.indexOf("data-day=\"TUESDAY\"");
        assertTrue(cardStart > 0 && cardEnd > cardStart, "Monday and Tuesday cards both rendered");
        String mondayCard = body.substring(cardStart, cardEnd);
        assertTrue(
                !mondayCard.contains("justify-between"),
                "no justify-between inside a day card: all slack would land between the label and the "
                        + "buttons, making the gap a function of viewport width instead of a spacing step");
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=AdminWorkplanLayoutTest
```

Expected: both tests FAIL.
- `dayActionsRenderAfterTheFramesBox` fails on the last assertion — today the actions are emitted before `data-frames`, so `actions < frames`.
- `dayLabelDoesNotShareAJustifyBetweenRowWithTheActions` fails because the Monday card still contains `justify-between`.

If `FormAuth.login()` does not resolve, check `src/test/java/site/asm0dey/calit/web/FormAuth.java` — it is the shared login helper every `Admin*Test` in that package uses.

- [ ] **Step 3: Re-nest the card**

In `src/main/resources/templates/AdminResource/_workplanGrid.html`, replace the whole `<div class="card-body py-3 gap-2"> … </div>` block (the header row plus the frames box) with:

```html
  <div class="card-body py-3 gap-2">
    {! #197: the day label sits on its own line and the actions share the frames' axis --
       they act on the frames, so proximity and alignment both put them there. The wrapper is a
       plain flex-wrap row with a fixed gap-x-4, NOT justify-between: with two content-sized
       children justify-between hands every pixel of slack to the gap, so the spacing would grow
       with the viewport instead of staying a spacing step. !}
    <strong class="block">{display:of(row.day)}</strong>
    <div class="flex flex-wrap items-start gap-x-4 gap-y-2">
      <div data-frames class="space-y-1">
        {#for fr in row.frames}
        <div data-frame class="flex items-center gap-2">
          <input type="hidden" name="frameDay" value="{row.day}">
          <input class="input input-sm" type="time" name="frameStart" value="{#if fr.startTime}{fr.startTime}{/if}">
          <span class="text-base-content/60">{adm:adm_workplan_to}</span>
          <input class="input input-sm" type="time" name="frameEnd" value="{#if fr.endTime}{fr.endTime}{/if}">
          <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="{adm:adm_workplan_remove_frame_aria}">&times;</button>
        </div>
        {/for}
      </div>
      <div class="flex flex-wrap gap-1">
        <button type="button" class="btn btn-ghost btn-xs" data-add-frame="{row.day}">{adm:adm_workplan_frame_add}</button>
        <button type="button" class="btn btn-ghost btn-xs" data-copy-all="{row.day}">{adm:adm_workplan_copy_all}</button>
        <button type="button" class="btn btn-ghost btn-xs" data-copy-weekdays="{row.day}">{adm:adm_workplan_copy_weekdays}</button>
        <button type="button" class="btn btn-ghost btn-xs text-error" data-clear-day="{row.day}">{adm:adm_workplan_clear_day}</button>
      </div>
    </div>
  </div>
```

Leave everything else in the file untouched: the `{@…}` param declarations at the top, the `{! … !}` fragment comment, the `{#for row in week}` / `<div data-day="{row.day}" class="card {cardClass} border border-base-300">` wrapper, and the trailing `<template data-frame-template>` block.

Three things changed and nothing else:
1. `<strong class="w-24">` → `<strong class="block">` — the fixed 24-unit column existed only to hold a place in the old two-child `justify-between` row. On its own line it is not needed, and dropping it lets long localised day names (German `Donnerstag`, Hebrew) render without truncation risk.
2. The old `<div class="flex flex-wrap items-center justify-between gap-2">` header wrapper is gone.
3. `[data-frames]` and the button group are now siblings inside one `flex flex-wrap items-start gap-x-4 gap-y-2` row, frames first.

`items-start` (not `items-center`) keeps the buttons aligned to the top of a multi-frame day rather than floating to its vertical middle. `gap-y-2` gives the wrapped layout breathing room on narrow viewports, where the button group drops below the frames.

- [ ] **Step 4: Run the test and verify it passes**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=AdminWorkplanLayoutTest
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Run the pages that reuse the fragment**

The fragment is shared. Run the existing suites that render it, to prove the four including pages still work and that no test was asserting on the old nesting:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='AdminAvailabilityTest,AdminAvailabilityBulkTest,AdminMeetingTypeFormTest,AdminMeetingTypeDetailTest,AdminMeetingTypesTest,AdminTypeHoursPrefillTest'
```

Expected: PASS. `AdminMeetingTypeFormTest` asserts `containsString("data-add-frame=\"MONDAY\"")` and `containsString("data-copy-all=\"MONDAY\"")` — both attributes survive the re-nesting unchanged, so it should stay green. If any of these fail, the assertion is on markup this task moved: read it, and fix the *test* only if it was pinning nesting rather than behaviour.

- [ ] **Step 6: Eyeball it in the browser**

```bash
bun run css:build
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw quarkus:dev -Dgoogle.oauth.client-id=x -Dgoogle.oauth.client-secret=x -Dgoogle.oauth.state-secret=0123456789abcdef
```

Open `http://localhost:8080/me/availability`, log in, and check three widths (narrow ~500px, default, and a maximised wide window):
- The buttons sit next to the time inputs, not next to the day name.
- The distance between the frames box and the button group does **not** grow when the window widens.
- On the narrow width the button group wraps below the frames instead of overflowing.

This step is manual because RestAssured cannot render CSS; the automated tests above cover document order, not visual result.

- [ ] **Step 7: Run the full suite**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Do not proceed to a PR on a red suite — see Global Constraints.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/templates/AdminResource/_workplanGrid.html \
        src/test/java/site/asm0dey/calit/web/AdminWorkplanLayoutTest.java \
        .beans/
git commit -m "fix(availability): day actions share the frames' axis (#197)

The per-day buttons sat on the day-name row, aligned with the label
instead of the frames they operate on, and that row was justify-between
with two content-sized children -- so every pixel of slack landed in the
gap and the label/button distance scaled with viewport width.

Day label now stands alone; frames box and action group are siblings in
one flex-wrap row with a fixed gap-x-4. _workplanGrid.html is shared, so
availability, meetingTypeDetail, sharedAvailability and meetingTypes all
get it at once.

Closes #197"
```

Before committing, mark the bean's todos done and set its status:

```bash
beans update calit-3aen -s completed --body-append "## Summary of Changes

_workplanGrid.html: day label moved to its own line; [data-frames] and the
action-button group are now siblings in a single flex-wrap gap-x-4 row, frames
first. No JS, CSS or Java change -- workplan.js only uses descendant selectors.
New AdminWorkplanLayoutTest pins document order (actions after frames) and the
absence of justify-between inside a day card."
```

---

### Task 2: Documentation

**Files:**
- Modify: `docs-site/src/content/docs/releases/changelog.md` (on the `docs-site` branch)

**Interfaces:**
- Consumes: the merged PR number from Task 1.
- Produces: nothing.

Per `CLAUDE.md`, changelog entries land **at merge**, under `## Unreleased`, not at release time.

- [ ] **Step 1: Decide whether a changelog entry is warranted**

This is a visual-layout fix to an existing admin screen with no new config, route, or behaviour. It is still user-visible (the availability editor looks different), so it gets a bullet. There is no docs page to update — no screenshots in `docs-site` show the day card at button-level detail; confirm with:

```bash
git grep -il "workplan\|availability" docs-site -- 'docs-site/src/content/docs/**' | head
```

If a page does show an annotated screenshot of a day card, refresh that screenshot too.

- [ ] **Step 2: Add the entry on the `docs-site` branch**

```bash
git fetch origin docs-site
git worktree add /tmp/finkel/calit-docs docs-site
```

In `/tmp/finkel/calit-docs/docs-site/src/content/docs/releases/changelog.md`, under the `## Unreleased` heading (create it at the top of the release list if it is absent, with the standing subtitle line `Merged but not yet in a tagged release.`), add:

```markdown
- **Availability editor: the per-day buttons now sit beside the time frames.** The "+ Frame", "Copy to all days", "Copy to weekdays" and "Remove availability" buttons shared a row with the day name, so they lined up with the label rather than with the frames they act on — and because that row spread two items to its edges, the space between the day name and its own buttons grew with the browser window, until on a wide screen they stopped reading as one group. The day name now stands on its own line and the buttons sit directly to the right of the time inputs, at a fixed distance from them at every width. ([#N](https://github.com/asm0dey/calit/pull/N))
```

Replace `#N` with the actual PR number once it exists.

If the `## Unreleased` section is being created fresh, close it with an upgrade note:

```markdown
Nothing to do on upgrade — no configuration or database changes.
```

- [ ] **Step 3: Commit and push the docs branch**

```bash
cd /tmp/finkel/calit-docs
git add docs-site/src/content/docs/releases/changelog.md
git commit -m "docs(changelog): day actions share the frames' axis (#197)"
git push origin docs-site
cd /home/finkel/work_self/calit
git worktree remove /tmp/finkel/calit-docs
```

Note: `docs-site` is a content branch and the repo's convention allows the changelog to be pushed there directly; the app change itself still goes through a PR on `main`.
