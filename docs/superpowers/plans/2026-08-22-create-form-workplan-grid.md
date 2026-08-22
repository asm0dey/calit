# Meeting-Type Create Form: Workplan Working-Hours Grid — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the meeting-type *create* form use the same rich weekly workplan grid the *edit* page and `/me/availability` already use, and delete the second, stripped-down `ruleDay/ruleStart/ruleEnd` parser it needed.

**Architecture:** Purely a template + wiring change. `meetingTypes.html`'s seven-fixed-rows block is replaced with the workplan grid markup (per-day cards, `+ Frame` / copy / clear buttons, `<template data-frame-template>`, `<script src="/workplan.js">`), with `data-workplan` on the create `<form>` itself so `workplan.js`'s `closest("[data-workplan]")` scoping resolves. Server-side, `AdminResource.createInitialWorkingHours` (the `ruleDay` parser) is deleted and the create handler calls the already-existing `AdminResource.persistFrames(ownerId, typeId, form)` — one parser for every workplan grid in the app. No entity, migration, or route change.

**Tech Stack:** Quarkus 3.38 / Java 25, Qute templates, JAX-RS `MultivaluedMap` form params, RestAssured tests, Tailwind v4 + daisyUI 5, plain-DOM `workplan.js`.

**Spec:** bean `calit-9d76` (`beans show calit-9d76`), upstream https://github.com/asm0dey/calit/issues/120

## Global Constraints

- **Progressive enhancement is mandatory.** Without JavaScript the grid must still submit usable frames: the server pre-renders one blank frame row per weekday, so a no-JS user types into those rows exactly as they do today. JS only *adds* frames and copies days.
- **One parser only.** After this change nothing in `src/main` may read `ruleDay` / `ruleStart` / `ruleEnd`. `AdminResource.persistFrames` is the single frame parser.
- **i18n parity is test-enforced.** `MultiHostMessageParityTest` fails if an `@Message` method on `AdminMessages` lacks a key in `messages/adm_de.properties` **and** `messages/adm_he.properties`, and `adminPropertyFilesHaveNoOrphanKeys` fails if a properties file carries a key with no matching method. Every added key needs both locales in the same commit; every removed key must be removed from both.
- **Formatting gate.** `mvn spotless:check` runs in `verify` (CI). Run `mvn spotless:apply` before committing Java. Qute `.html` templates are deliberately NOT Prettier-formatted — do not run Prettier on them.
- **Build JDK.** `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` (or the local Liberica 26 path) before `mvn`; the default JDK 21 fails with "release 25 not supported".
- **Docker must be running** for `mvn test` (Dev Services Postgres). Only one `mvn test` at a time against the reused container.
- **Never open a PR while the suite is red.** Full `mvn test` green before the branch becomes a PR.
- **Branch, don't push `main`.** Work on a feature branch; PR into `main`.

---

## File Structure

| File | Responsibility after this change |
|---|---|
| `src/main/resources/templates/AdminResource/meetingTypes.html` | Create form; its Working-hours section renders the workplan grid (7 day cards × 1 seeded blank frame), the frame `<template>`, and loads `/workplan.js`. |
| `src/main/java/site/asm0dey/calit/web/AdminResource.java` | `createMeetingType` calls `persistFrames`; `createInitialWorkingHours` deleted. |
| `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` | Gains 4 `adm_meetingTypes_*` frame-button keys (twins of the existing `adm_detail_*` set, matching the per-page twin convention already used by `adm_availability_*`). |
| `src/main/resources/messages/adm_de.properties`, `adm_he.properties` | German + Hebrew values for those 4 keys (copied verbatim from the existing `adm_detail_*` twins). |
| `src/test/java/site/asm0dey/calit/web/AdminMeetingTypeFormTest.java` | Existing `createFormExposesWorkingHoursAndOverrideInputs` + `createPersistsPerTypeWorkingHours` retargeted to frames; two new tests for multi-frame and no-JS submits. |

Not touched: `workplan.js` (already generic), `AvailabilityRule`, any migration, the date-override block on the create form (separate concern, per the bean).

---

### Task 1: Create form posts frames instead of rule rows

Single task: template, handler, i18n and tests all describe one behaviour change and a reviewer would accept or reject them together. The old parser cannot be deleted before the template stops posting `ruleDay`, and the template cannot post frames before the handler reads them — splitting would leave an intermediate commit with a broken create form.

**Files:**
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html:118-141` (the `adm_meetingTypes_section_working_hours` collapse) and the end of the form (add the `/workplan.js` script tag)
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:450` (call site) and `:543-566` (`createInitialWorkingHours`, deleted)
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (after `adm_meetingTypes_to()`, around line 239)
- Modify: `src/main/resources/messages/adm_de.properties`, `src/main/resources/messages/adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/AdminMeetingTypeFormTest.java`

**Interfaces:**
- Consumes: `static void AdminResource.persistFrames(Long ownerId, Long meetingTypeId, MultivaluedMap<String,String> form)` — already exists at `AdminResource.java:1165`; zips parallel `frameDay[]`/`frameStart[]`/`frameEnd[]`, skipping blank, unparseable, zero-length and inverted frames. It does **not** delete existing rows (the bulk-save endpoints wipe their scope before calling it); on create there is nothing to wipe, so calling it directly is correct.
- Consumes: `java.time.DayOfWeek[] daysOfWeek` — already a `meetingTypes.html` template parameter, passed as `DayOfWeek.values()` by `renderMeetingTypes`. No resource-signature change.
- Consumes: `/workplan.js` — acts on any `[data-workplan]` ancestor containing `[data-day]` rows, each with a `[data-frames]` box, plus one `[data-frame-template]`. Buttons: `data-add-frame="DAY"`, `data-remove-frame`, `data-copy-all="DAY"`, `data-copy-weekdays="DAY"`, `data-clear-day="DAY"`.
- Produces: nothing new for later tasks — this is the only task.

- [ ] **Step 1: Create the branch**

```bash
cd /home/finkel/work_self/calit
git checkout -b feat/create-form-workplan-grid
beans update calit-9d76 -s in-progress
```

- [ ] **Step 2: Write the failing tests**

Replace the whole body of `createFormExposesWorkingHoursAndOverrideInputs` and `createPersistsPerTypeWorkingHours` in `src/test/java/site/asm0dey/calit/web/AdminMeetingTypeFormTest.java` (currently lines 120-158) with the four tests below. RestAssured cannot execute JS, so the rendered-markup test asserts on the `data-*` markers `workplan.js` binds to — the same technique `AdminTypeHoursPrefillTest` uses.

```java
    @Test
    void createFormRendersWorkplanGrid() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                // grid scope + the markers workplan.js binds to
                .body(containsString("data-workplan"))
                .body(containsString("data-day=\"MONDAY\""))
                .body(containsString("data-day=\"SUNDAY\""))
                .body(containsString("data-frame-template"))
                .body(containsString("data-add-frame=\"MONDAY\""))
                .body(containsString("data-copy-all=\"MONDAY\""))
                .body(containsString("data-copy-weekdays=\"MONDAY\""))
                .body(containsString("data-clear-day=\"SUNDAY\""))
                .body(containsString("/workplan.js"))
                // frame inputs replace the old ruleDay/ruleStart/ruleEnd trio
                .body(containsString("name=\"frameDay\""))
                .body(containsString("name=\"frameStart\""))
                .body(containsString("name=\"frameEnd\""))
                .body(not(containsString("name=\"ruleDay\"")))
                .body(containsString("name=\"overrideDate\"")); // date-override block untouched
    }

    @Test
    void createPersistsPerTypeWorkingHoursFromFrames() {
        var slug = "wh-create-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "With Hours")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                // one filled frame + one blank frame (must be skipped)
                .formParam("frameDay", "MONDAY", "TUESDAY")
                .formParam("frameStart", "09:00", "")
                .formParam("frameEnd", "17:00", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1", t.id)); // blank Tuesday skipped
    }

    @Test
    void createPersistsSeveralFramesOnOneDay() {
        var slug = "wh-multi-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Split Day")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                // what "+ Frame" produces: two Monday frames, plus one inverted frame to drop
                .formParam("frameDay", "MONDAY", "MONDAY", "FRIDAY")
                .formParam("frameStart", "09:00", "13:00", "17:00")
                .formParam("frameEnd", "12:00", "17:00", "09:00")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(
                2,
                AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY),
                "both Monday frames persist");
        assertEquals(
                0,
                AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.FRIDAY),
                "inverted frame dropped, not 500");
    }

    @Test
    void noJsSubmitOfTheSeededGridCreatesTheTypedRules() {
        var slug = "wh-nojs-" + System.nanoTime();
        // Exactly what the browser posts with JS disabled: the seven server-seeded rows,
        // two of them filled in by hand, the rest left blank.
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "No JS")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam(
                        "frameDay",
                        "MONDAY",
                        "TUESDAY",
                        "WEDNESDAY",
                        "THURSDAY",
                        "FRIDAY",
                        "SATURDAY",
                        "SUNDAY")
                .formParam("frameStart", "09:00", "", "10:00", "", "", "", "")
                .formParam("frameEnd", "17:00", "", "16:00", "", "", "", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(2, AvailabilityRule.count("meetingTypeId = ?1", t.id));
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY));
        assertEquals(
                1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.WEDNESDAY));
    }
```

Add the imports these need at the top of the file (the class currently imports only `containsString`, `assertEquals`, `assertNotNull`, `MeetingType`, `Slugs`, and referred to `AvailabilityRule` by fully-qualified name):

```java
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.time.DayOfWeek;
import site.asm0dey.calit.domain.AvailabilityRule;
```

The other pre-existing test in this file, `createPersistsPerTypeDateOverrideWithWindow`, uses the fully-qualified `site.asm0dey.calit.domain.AvailabilityRule`-style names for `DateOverride`/`DateOverrideWindow` — leave it exactly as it is; this task does not touch the date-override path.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminMeetingTypeFormTest
```

Expected: FAIL. `createFormRendersWorkplanGrid` fails first (no `data-workplan` in the response body); the three persistence tests fail because nothing reads `frameDay` on create, so `AvailabilityRule.count` is `0`.

- [ ] **Step 4: Replace the working-hours block in the create template**

In `src/main/resources/templates/AdminResource/meetingTypes.html`, put `data-workplan` on the create `<form>` so `workplan.js`'s `btn.closest("[data-workplan]")` resolves — the create page is one big form, so the attribute belongs on it, not on an inner div:

```html
  <form method="post" action="/me/meeting-types" data-workplan class="space-y-3 max-w-2xl">
```

Then replace the entire working-hours collapse (the `{#for d in daysOfWeek}` block, currently lines 118-141) with:

```html
    <div class="collapse collapse-arrow bg-base-100 border border-base-300">
      <input type="checkbox">
      <div class="collapse-title font-semibold">{adm:adm_meetingTypes_section_working_hours}</div>
      <div class="collapse-content">
        <p class="text-sm text-base-content/60 mb-2">{adm:adm_meetingTypes_working_hours_hint}</p>
        <div class="space-y-2">
        {#for d in daysOfWeek}
          <div data-day="{d}" class="card bg-base-200 border border-base-300">
            <div class="card-body py-3 gap-2">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <strong class="w-24">{display:of(d)}</strong>
                <div class="flex flex-wrap gap-1">
                  <button type="button" class="btn btn-ghost btn-xs" data-add-frame="{d}">{adm:adm_meetingTypes_frame_add}</button>
                  <button type="button" class="btn btn-ghost btn-xs" data-copy-all="{d}">{adm:adm_meetingTypes_copy_all}</button>
                  <button type="button" class="btn btn-ghost btn-xs" data-copy-weekdays="{d}">{adm:adm_meetingTypes_copy_weekdays}</button>
                  <button type="button" class="btn btn-ghost btn-xs text-error" data-clear-day="{d}">{adm:adm_workplan_clear_day}</button>
                </div>
              </div>
              <div data-frames class="space-y-1">
                <!-- One blank frame seeded server-side: without JS this row IS the editor. -->
                <div data-frame class="flex items-center gap-2">
                  <input type="hidden" name="frameDay" value="{d}">
                  <input class="input input-sm" type="time" name="frameStart">
                  <span class="text-base-content/60">{adm:adm_meetingTypes_to}</span>
                  <input class="input input-sm" type="time" name="frameEnd">
                  <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="{adm:adm_meetingTypes_remove_frame_aria}">&times;</button>
                </div>
              </div>
            </div>
          </div>
        {/for}
        </div>

        <template data-frame-template>
          <div data-frame class="flex items-center gap-2">
            <input type="hidden" name="frameDay" value="">
            <input class="input input-sm" type="time" name="frameStart">
            <span class="text-base-content/60">{adm:adm_meetingTypes_to}</span>
            <input class="input input-sm" type="time" name="frameEnd">
            <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="{adm:adm_meetingTypes_remove_frame_aria}">&times;</button>
          </div>
        </template>
      </div>
    </div>
```

Every button is `type="button"` — that is what keeps `+ Frame` and the copy buttons from submitting the create form. `workplan.js` also calls `e.preventDefault()`, but the attribute is the real guard; do not drop it.

Finally, load the script. Add it immediately after `{#include AdminResource/_copyToast /}`, near the end of the file — same placement the other three workplan pages use:

```html
  <script src="/workplan.js"></script>
```

- [ ] **Step 5: Point the create handler at the shared frame parser**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, inside `createMeetingType`, change the call (currently line 450):

```java
                createInitialWorkingHours(t.id, t.ownerId, form);
```

to:

```java
                persistFrames(t.ownerId, t.id, form);
```

Then delete `createInitialWorkingHours` entirely — its javadoc and body, lines 543-566, i.e. from the comment `/** * Per-type weekly working hours captured on the create form.` through the closing brace of the method. Leave `createInitialDateOverride` and `persistWindows` untouched.

Verify nothing else references the removed names:

```bash
grep -rn "createInitialWorkingHours\|ruleDay\|ruleStart\|ruleEnd" src/main src/test
```

Expected: no hits at all (the two test hits from the old test are gone as of Step 2).

- [ ] **Step 6: Add the four i18n keys with German and Hebrew**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, directly after `adm_meetingTypes_to()` (around line 239), add:

```java
    @Message("+ Frame")
    String adm_meetingTypes_frame_add();

    @Message("Copy to all days")
    String adm_meetingTypes_copy_all();

    @Message("Copy to weekdays")
    String adm_meetingTypes_copy_weekdays();

    @Message("Remove frame")
    String adm_meetingTypes_remove_frame_aria();
```

`adm_workplan_clear_day` and `adm_meetingTypes_to` already exist — reuse them, add nothing for those.

In `src/main/resources/messages/adm_de.properties`, next to the other `adm_meetingTypes_*` keys (after `adm_meetingTypes_to=bis`, line 82), add:

```properties
adm_meetingTypes_frame_add=+ Zeitraum
adm_meetingTypes_copy_all=Auf alle Tage kopieren
adm_meetingTypes_copy_weekdays=Auf Werktage kopieren
adm_meetingTypes_remove_frame_aria=Zeitraum entfernen
```

In `src/main/resources/messages/adm_he.properties`, after `adm_meetingTypes_to=עד` (line 82), add:

```properties
adm_meetingTypes_frame_add=+ טווח זמן
adm_meetingTypes_copy_all=העתק לכל הימים
adm_meetingTypes_copy_weekdays=העתק לימי חול
adm_meetingTypes_remove_frame_aria=הסר טווח זמן
```

These values are the exact strings the existing `adm_detail_frame_add` / `adm_detail_copy_all` / `adm_detail_copy_weekdays` / `adm_detail_remove_frame_aria` twins already carry in each file (`adm_de.properties:161-163,175`, `adm_he.properties:161-163,175`), so no new translation is being invented.

- [ ] **Step 7: Run the targeted tests to verify they pass**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest='AdminMeetingTypeFormTest+MultiHostMessageParityTest'
```

Expected: PASS, 0 failures, 0 errors. `MultiHostMessageParityTest` confirms both the new keys are present in `de` + `he` and that no orphan key was left behind.

- [ ] **Step 8: Format and run the full suite**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
mvn test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Docker must be running, and no other `mvn test` may be running against the reused Dev Services container. If a failure appears in a class this change did not touch, fix it on this branch before proceeding — a red suite blocks the PR either way.

- [ ] **Step 9: Eyeball the page in dev (JS on, then JS off)**

```bash
bun run css:build
mvn quarkus:dev -Dgoogle.oauth.client-id=x -Dgoogle.oauth.client-secret=x -Dgoogle.oauth.state-secret=0123456789abcdef
```

At `http://localhost:8080/me/meeting-types`, open Working hours and confirm: `+ Frame` adds a row without submitting the form; `Copy to all days` / `Copy to weekdays` mirror the source day; `×` removes a row; `Remove availability` empties a day. Then disable JavaScript, reload, type `09:00`–`17:00` into Monday, submit, and confirm the new type's edit page shows that Monday frame.

- [ ] **Step 10: Update the bean and commit**

```bash
beans update calit-9d76 \
  --body-replace-old "[ ] Replace the ruleDay/ruleStart/ruleEnd block in meetingTypes.html with the workplan grid markup (+ <template data-frame-template>, <script src=\"/workplan.js\">)" \
  --body-replace-new "[x] Replace the ruleDay/ruleStart/ruleEnd block in meetingTypes.html with the workplan grid markup (+ <template data-frame-template>, <script src=\"/workplan.js\">)"
```

Repeat one `--body-replace-old/--body-replace-new` pair per remaining todo line (frame parser, i18n, both tests, spotless+test, docs), then:

```bash
git add src/main/resources/templates/AdminResource/meetingTypes.html \
        src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/test/java/site/asm0dey/calit/web/AdminMeetingTypeFormTest.java \
        .beans
git commit -m "feat(meeting-types): use the workplan grid on the create form

The create form had a stripped-down working-hours block — seven fixed rows,
one start/end pair per weekday — while the edit page and /me/availability
both use the workplan grid with multiple frames per day and copy buttons.
The create form now renders the same grid, and the create handler reuses
persistFrames instead of a second ruleDay/ruleStart/ruleEnd parser, which
is deleted. One blank frame per day is still seeded server-side, so the
form works unchanged without JavaScript.

Closes #120"
```

- [ ] **Step 11: Open the PR**

```bash
git push -u origin feat/create-form-workplan-grid
gh pr create --fill
```

Only after Step 8 reported `BUILD SUCCESS`.

---

## Docs

No docs-site change. This alters no env var, route, config flag, or setup step — it makes one form use a UI pattern the app already documents. No changelog entry is warranted for a form-parity fix of this size; if a reviewer disagrees, add one bullet under `## Unreleased` in `docs-site/src/content/docs/releases/changelog.md` on the `docs-site` branch: *"**The meeting-type create form now uses the same working-hours grid as the edit page.** Creating a type only allowed one time frame per weekday; it now supports several frames per day plus the copy-to-all-days and copy-to-weekdays buttons."*

## Deliberately out of scope

- **Extracting a shared workplan Qute fragment.** The grid markup is now duplicated across four templates (`availability.html`, `meetingTypeDetail.html`, `sharedAvailability.html`, `meetingTypes.html`). A shared include is a four-template refactor with no behaviour change, and the pages differ in form scope, action and i18n namespace. If it is wanted, file it as its own bean.
- **The date-override block** on the create form (`meetingTypes.html`, three fixed `windowStart`/`windowEnd` rows). The bean explicitly leaves it alone.
