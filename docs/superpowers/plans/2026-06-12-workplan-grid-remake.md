# Workplan Grid Remake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the add-one-rule-at-a-time weekly availability editor with a seven-row grid (Mon–Sun) where each day holds any number of time frames, all days visible at once, with per-row "copy to all days" / "copy to weekdays" buttons — applied to both the global schedule and per-type schedules.

**Architecture:** No schema change — `AvailabilityRule` already allows N rows per `(owner, meetingTypeId, dayOfWeek)`, so multiple time frames per day = multiple rows. The grid is one `<form>` posting parallel `frameDay[]/frameStart[]/frameEnd[]` arrays to a new **bulk replace-all** endpoint per scope (global; per-type). Add/remove-frame and copy-day buttons run in a single shared static JS file (`/workplan.js`); the project already uses small inline vanilla JS and ships no JS framework. Save semantics: the bulk endpoint deletes all rules in that scope, then re-inserts from the posted frames.

**Tech Stack:** Quarkus 3.36 · Java 25 · Hibernate Panache · Qute templates · RESTEasy Reactive (`@RestForm` / `MultivaluedMap`) · Tailwind v4 + daisyUI 5 · RestAssured + JUnit 5 tests.

**Scope:** Weekly recurring schedule only (`AvailabilityRule`). Date overrides keep their current UI. The meeting-type **create** form's optional initial working-hours block (`ruleDay[]` arrays in `meetingTypes.html`) is intentionally left as-is — see "Out of scope" at the end.

**Carry-forward facts (verified against the codebase):**
- `DayOfWeek.values()` returns `MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY` (ISO order) — these are the seven rows.
- Existing legacy endpoints stay in place (do **not** delete): `POST /me/availability` (`createRule`), `POST /me/availability/{id}/delete`, `POST /me/meeting-types/{id}/availability`, `.../availability/{rid}/delete`. They are no longer wired to UI buttons but back `AdminAvailabilityTest.createGlobalRuleViaForm` and are reused for seeding in new tests. Removing them is out of scope.
- Static assets live in `src/main/resources/META-INF/resources/` and are served from `/` (e.g. `/calit.css`). A new `/workplan.js` goes there.
- `{fr.startTime}` in Qute renders a `LocalTime` as `HH:mm` (e.g. `09:00`) — valid for `<input type="time" value="...">`.
- Web tests (`@QuarkusTest`) reseed to a single admin owner per the test infra, so counting global rules by `meetingTypeId is null and dayOfWeek` is unambiguous without hardcoding the owner id. `FormAuth.login()` returns the `quarkus-credential` cookie for that admin.

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `src/main/java/com/calit/web/WeekRow.java` | View-model record: one weekday + its ordered frames | Create |
| `src/main/java/com/calit/web/AdminResource.java` | Add `weekRows(...)` helper, two bulk-save endpoints, pass `List<WeekRow>` to both templates | Modify |
| `src/main/resources/META-INF/resources/workplan.js` | Client-side add/remove-frame + copy-day logic | Create |
| `src/main/resources/templates/AdminResource/availability.html` | Global weekly grid | Rewrite body |
| `src/main/resources/templates/AdminResource/meetingTypeDetail.html` | Per-type "Working hours" section → grid | Modify one section |
| `src/test/java/com/calit/web/WeekRowTest.java` | Unit test for grouping/ordering/empty days | Create |
| `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java` | Bulk replace-all + grid-render web tests (global + per-type) | Create |

---

### Task 1: `WeekRow` view-model + `weekRows()` helper

**Files:**
- Create: `src/main/java/com/calit/web/WeekRow.java`
- Modify: `src/main/java/com/calit/web/AdminResource.java`
- Test: `src/test/java/com/calit/web/WeekRowTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/web/WeekRowTest.java`:

```java
package com.calit.web;

import com.calit.domain.AvailabilityRule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeekRowTest {

    private static AvailabilityRule rule(DayOfWeek day, String start, String end) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = day;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        return r;
    }

    @Test
    void buildsSevenRowsInIsoOrderEvenWhenEmpty() {
        List<WeekRow> rows = WeekRow.fromRules(List.of());
        assertEquals(7, rows.size());
        assertEquals(DayOfWeek.MONDAY, rows.get(0).day());
        assertEquals(DayOfWeek.SUNDAY, rows.get(6).day());
        assertTrue(rows.get(0).frames().isEmpty());
    }

    @Test
    void groupsFramesByDayAndSortsByStartTime() {
        List<WeekRow> rows = WeekRow.fromRules(List.of(
                rule(DayOfWeek.MONDAY, "13:00", "17:00"),
                rule(DayOfWeek.MONDAY, "09:00", "12:00"),
                rule(DayOfWeek.WEDNESDAY, "10:00", "11:00")));

        WeekRow monday = rows.get(0);
        assertEquals(2, monday.frames().size());
        assertEquals(LocalTime.parse("09:00"), monday.frames().get(0).startTime); // sorted
        assertEquals(LocalTime.parse("13:00"), monday.frames().get(1).startTime);

        assertTrue(rows.get(1).frames().isEmpty()); // TUESDAY
        assertEquals(1, rows.get(2).frames().size()); // WEDNESDAY
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=WeekRowTest`
Expected: FAIL — compilation error, `WeekRow` does not exist.

- [ ] **Step 3: Create the `WeekRow` record**

Create `src/main/java/com/calit/web/WeekRow.java`:

```java
package com.calit.web;

import com.calit.domain.AvailabilityRule;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One weekday's worth of the weekly-schedule grid: the day plus its time frames,
 * sorted by start time. {@link #fromRules} always yields all seven days in ISO order
 * (Monday first), each with an (possibly empty) frame list, so the template can render
 * a fixed seven-row grid.
 */
public record WeekRow(DayOfWeek day, List<AvailabilityRule> frames) {

    public static List<WeekRow> fromRules(List<AvailabilityRule> rules) {
        Map<DayOfWeek, List<AvailabilityRule>> byDay = rules.stream()
                .collect(Collectors.groupingBy(r -> r.dayOfWeek));
        List<WeekRow> rows = new ArrayList<>(7);
        for (DayOfWeek d : DayOfWeek.values()) {
            List<AvailabilityRule> frames = byDay.getOrDefault(d, List.of()).stream()
                    .sorted(Comparator.comparing(r -> r.startTime))
                    .toList();
            rows.add(new WeekRow(d, frames));
        }
        return rows;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=WeekRowTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/WeekRow.java src/test/java/com/calit/web/WeekRowTest.java
git commit -m "feat: add WeekRow view-model for weekly-schedule grid"
```

---

### Task 2: Global bulk replace-all endpoint

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java:434-471` (near `ownerRules()` / `createRule`)
- Test: `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java`:

```java
package com.calit.web;

import com.calit.domain.AvailabilityRule;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AdminAvailabilityBulkTest {

    private static long globalCount(DayOfWeek day) {
        return AvailabilityRule.count("meetingTypeId is null and dayOfWeek = ?1", day);
    }

    @Test
    void bulkSaveReplacesAllGlobalRules() {
        String cred = FormAuth.login();

        // Seed a stale global rule (legacy single-add endpoint) that the bulk save must wipe.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "SUNDAY")
                .formParam("startTime", "08:00").formParam("endTime", "09:00")
                .formParam("meetingTypeId", "")
                .when().post("/me/availability").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.SUNDAY));

        // Bulk replace: two Monday frames, nothing else.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY", "MONDAY")
                .formParam("frameStart", "09:00", "13:00")
                .formParam("frameEnd", "12:00", "17:00")
                .when().post("/me/availability/bulk").then().statusCode(200);

        assertEquals(0, globalCount(DayOfWeek.SUNDAY), "stale rule should be wiped");
        assertEquals(2, globalCount(DayOfWeek.MONDAY), "two new Monday frames");
    }

    @Test
    void bulkSaveSkipsBlankAndInvertedFrames() {
        String cred = FormAuth.login();
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "TUESDAY", "TUESDAY", "TUESDAY")
                .formParam("frameStart", "09:00", "", "17:00") // blank start; inverted end<=start
                .formParam("frameEnd", "12:00", "12:00", "09:00")
                .when().post("/me/availability/bulk").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.TUESDAY), "only the valid frame persists");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest#bulkSaveReplacesAllGlobalRules`
Expected: FAIL — `POST /me/availability/bulk` returns 404 (endpoint not defined).

- [ ] **Step 3: Add the bulk endpoint**

In `src/main/java/com/calit/web/AdminResource.java`, insert this method immediately after `createRule(...)` (after line 471, before `deleteRule`). It reuses the existing `ownerRules()`, `currentOwner`, and `Templates.availability(...)`:

```java
    @POST
    @Path("/availability/bulk")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance saveWeeklyRules(MultivaluedMap<String, String> form) {
        // Replace-all for this owner's GLOBAL schedule: wipe the scope, re-insert posted frames.
        AvailabilityRule.delete("ownerId = ?1 and meetingTypeId is null", currentOwner.id());
        persistFrames(currentOwner.id(), null, form);
        return Templates.availability(ownerRules(),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount(), isAdmin());
    }

    /**
     * Zip parallel frameDay[]/frameStart[]/frameEnd[] arrays into AvailabilityRule rows for one
     * scope (meetingTypeId null = global, non-null = per-type). Skips a frame whose start or end is
     * blank, or whose end is not strictly after its start.
     */
    private void persistFrames(Long ownerId, Long meetingTypeId, MultivaluedMap<String, String> form) {
        List<String> days = form.getOrDefault("frameDay", List.of());
        List<String> starts = form.getOrDefault("frameStart", List.of());
        List<String> ends = form.getOrDefault("frameEnd", List.of());
        for (int i = 0; i < days.size() && i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
            LocalTime start = LocalTime.parse(starts.get(i));
            LocalTime end = LocalTime.parse(ends.get(i));
            if (!end.isAfter(start)) { continue; } // drop zero-length / inverted frames
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.meetingTypeId = meetingTypeId;
            r.dayOfWeek = DayOfWeek.valueOf(days.get(i));
            r.startTime = start;
            r.endTime = end;
            r.persist();
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java src/test/java/com/calit/web/AdminAvailabilityBulkTest.java
git commit -m "feat: add global weekly-schedule bulk replace-all endpoint"
```

---

### Task 3: Per-type bulk replace-all endpoint

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java:358-390` (near `addTypeRule`)
- Test: `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java` (extend)

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java` (add the `MeetingType` import: `import com.calit.domain.MeetingType;`):

```java
    @Test
    void bulkSavePerTypeReplacesOnlyThatTypesRules() {
        String cred = FormAuth.login();

        // Create a meeting type to scope per-type rules to.
        Long typeId = Long.valueOf(given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Bulk Type").formParam("slug", "bulk-type")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0").formParam("horizonDays", "30")
                .formParam("locationType", "PHONE")
                .when().post("/me/meeting-types").then().statusCode(200)
                .extract().response().asString().length() > -1 ? 0L : 0L); // placeholder, replaced below

        // The create response is the meeting-types list page; look the id up by slug instead.
        MeetingType t = MeetingType.find("slug = ?1", "bulk-type").firstResult();

        // Seed a stale per-type rule via the legacy endpoint, then bulk-replace.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "FRIDAY")
                .formParam("startTime", "08:00").formParam("endTime", "09:00")
                .when().post("/me/meeting-types/" + t.id + "/availability").then().statusCode(200);

        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY")
                .formParam("frameStart", "10:00").formParam("frameEnd", "14:00")
                .when().post("/me/meeting-types/" + t.id + "/availability/bulk").then().statusCode(200);

        assertEquals(0, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.FRIDAY));
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY));
    }
```

> Note: the placeholder-looking `typeId` line above is unused — replace the whole `Long typeId = ...` statement with nothing and rely on the `MeetingType.find(...).firstResult()` lookup. Simpler final form of the test header:
>
> ```java
>         String cred = FormAuth.login();
>         given().cookie("quarkus-credential", cred)
>                 .contentType("application/x-www-form-urlencoded")
>                 .formParam("name", "Bulk Type").formParam("slug", "bulk-type")
>                 .formParam("durationMinutes", "30")
>                 .formParam("minNoticeMinutes", "0").formParam("horizonDays", "30")
>                 .formParam("locationType", "PHONE")
>                 .when().post("/me/meeting-types").then().statusCode(200);
>         MeetingType t = MeetingType.find("slug = ?1", "bulk-type").firstResult();
> ```
> Use this simpler header when writing the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest#bulkSavePerTypeReplacesOnlyThatTypesRules`
Expected: FAIL — `POST /me/meeting-types/{id}/availability/bulk` returns 404.

- [ ] **Step 3: Add the per-type bulk endpoint**

In `src/main/java/com/calit/web/AdminResource.java`, insert immediately after `addTypeRule(...)` (after line 376, before `deleteTypeRule`). It reuses `requireType`, `persistFrames` (from Task 2), and `detailInstance`:

```java
    @POST
    @Path("/meeting-types/{id}/availability/bulk")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance saveTypeWeeklyRules(@PathParam("id") Long id,
                                                MultivaluedMap<String, String> form) {
        requireType(id); // 404 a cross-owner type
        // Replace-all for this type's schedule only; global rules (meetingTypeId null) are untouched.
        AvailabilityRule.delete("ownerId = ?1 and meetingTypeId = ?2", currentOwner.id(), id);
        persistFrames(currentOwner.id(), id, form);
        return detailInstance(id);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java src/test/java/com/calit/web/AdminAvailabilityBulkTest.java
git commit -m "feat: add per-type weekly-schedule bulk replace-all endpoint"
```

---

### Task 4: Pass `List<WeekRow>` to both templates

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java` — `Templates.availability` (line 59-61) and `Templates.meetingTypeDetail` (line 49-57) signatures, plus call sites.

This task only changes the controller so the templates (Tasks 5–6) can iterate `week`. Templates still compile against the old signature until Task 5/6, so build the controller and templates together — but commit the controller change here and verify with the existing test suite (templates not yet using the new param will simply ignore it; Qute checked-template parameter declarations are added in Task 5/6).

- [ ] **Step 1: Add `week` parameter to the `availability` checked template**

In `AdminResource.java`, change the `availability` native method (lines 59-61) to:

```java
        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<WeekRow> week, List<MeetingType> types,
                DayOfWeek[] daysOfWeek, Long pendingCount, boolean isAdmin);
```

- [ ] **Step 2: Add `week` parameter to the `meetingTypeDetail` checked template**

Change the `meetingTypeDetail` native method (lines 49-57) to add `List<WeekRow> week` after `rules`:

```java
        public static native TemplateInstance meetingTypeDetail(
                MeetingType type,
                List<BookingField> fields,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                LocationType[] locationTypes,
                BookingField.FieldType[] fieldTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount, boolean isAdmin);
```

- [ ] **Step 3: Update the `availability()` GET and the two POST call sites**

In `availability()` (line 443-446), `createRule(...)` return (line 469-470), `deleteRule(...)` return (line 483-484), and the new `saveWeeklyRules(...)` return (Task 2), build `week` from this owner's **global** rules only and pass it. Replace each `Templates.availability(ownerRules(), ...)` call with:

```java
        return Templates.availability(ownerRules(), weekRows(globalRules()),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount(), isAdmin());
```

Add these two helpers next to `ownerRules()` (after line 438):

```java
    /** This owner's GLOBAL default rules only (meetingTypeId IS NULL), for the weekly grid. */
    private List<AvailabilityRule> globalRules() {
        return AvailabilityRule.list(
                "ownerId = ?1 and meetingTypeId is null order by dayOfWeek", currentOwner.id());
    }

    /** Group rules into the fixed seven-row weekly grid. */
    private static List<WeekRow> weekRows(List<AvailabilityRule> rules) {
        return WeekRow.fromRules(rules);
    }
```

- [ ] **Step 4: Update `detailInstance(...)` to pass `week`**

In `detailInstance(Long id)` (line 265-273), the local `rules` is already this type's rules. Pass `weekRows(rules)`:

```java
    private TemplateInstance detailInstance(Long id) {
        MeetingType t = requireType(id);
        List<BookingField> fields = BookingField.list("meetingTypeId = ?1 order by position", id);
        List<AvailabilityRule> rules = AvailabilityRule.list("meetingTypeId = ?1 order by dayOfWeek", id);
        List<DateOverride> overrides = overridesForType(id);
        return Templates.meetingTypeDetail(t, fields, rules, weekRows(rules), overrides,
                LocationType.values(), BookingField.FieldType.values(),
                DayOfWeek.values(), pendingCount(), isAdmin());
    }
```

- [ ] **Step 5: Add the `week` parameter declaration to both templates (so checked-template binding compiles)**

At the top of `src/main/resources/templates/AdminResource/availability.html`, after line 1 (`{@java.util.List<com.calit.domain.AvailabilityRule> rules}`), add:

```html
{@java.util.List<com.calit.web.WeekRow> week}
```

At the top of `src/main/resources/templates/AdminResource/meetingTypeDetail.html`, after line 3 (`{@java.util.List<com.calit.domain.AvailabilityRule> rules}`), add:

```html
{@java.util.List<com.calit.web.WeekRow> week}
```

- [ ] **Step 6: Compile and run the full suite to confirm nothing broke**

Run: `./mvnw test`
Expected: PASS — existing tests still green; the new `week` param is declared and passed but not yet rendered.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/availability.html \
        src/main/resources/templates/AdminResource/meetingTypeDetail.html
git commit -m "feat: thread WeekRow grid model into availability templates"
```

---

### Task 5: Static grid JavaScript (`/workplan.js`)

**Files:**
- Create: `src/main/resources/META-INF/resources/workplan.js`

This is pure client-side logic (no JS test runner in the project). It is written first so Tasks 6–7 can reference it; it is verified by the manual/Playwright step in Task 8.

- [ ] **Step 1: Create `src/main/resources/META-INF/resources/workplan.js`**

```javascript
(function () {
  // Weekly-schedule grid editor. Each [data-workplan] form contains seven [data-day]
  // rows; each row has a [data-frames] box of [data-frame] start/end pairs. Buttons:
  //   [data-add-frame="DAY"]      append a blank frame to that day
  //   [data-remove-frame]         remove the frame it sits in
  //   [data-copy-all="DAY"]       replace every other day's frames with DAY's
  //   [data-copy-weekdays="DAY"]  replace Mon–Fri frames with DAY's
  // The server bulk-saves parallel frameDay[]/frameStart[]/frameEnd[] arrays; each frame
  // carries a hidden frameDay so copied frames are reassigned to their target day.
  var WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'];

  function dayRow(grid, day) { return grid.querySelector('[data-day="' + day + '"]'); }
  function frameBox(row) { return row.querySelector('[data-frames]'); }

  function makeFrame(grid, day, start, end) {
    var tpl = grid.querySelector('[data-frame-template]');
    var node = tpl.content.firstElementChild.cloneNode(true);
    node.querySelector('[name="frameDay"]').value = day;
    if (start) { node.querySelector('[name="frameStart"]').value = start; }
    if (end) { node.querySelector('[name="frameEnd"]').value = end; }
    return node;
  }

  function readFrames(grid, day) {
    var out = [];
    frameBox(dayRow(grid, day)).querySelectorAll('[data-frame]').forEach(function (f) {
      out.push({
        start: f.querySelector('[name="frameStart"]').value,
        end: f.querySelector('[name="frameEnd"]').value
      });
    });
    return out;
  }

  function copyDay(grid, sourceDay, targets) {
    var frames = readFrames(grid, sourceDay);
    targets.forEach(function (day) {
      if (day === sourceDay) { return; }
      var box = frameBox(dayRow(grid, day));
      box.innerHTML = '';
      frames.forEach(function (fr) { box.appendChild(makeFrame(grid, day, fr.start, fr.end)); });
    });
  }

  function allDays(grid) {
    var days = [];
    grid.querySelectorAll('[data-day]').forEach(function (r) { days.push(r.getAttribute('data-day')); });
    return days;
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('button');
    if (!btn) { return; }
    var grid = btn.closest('[data-workplan]');
    if (!grid) { return; }

    if (btn.hasAttribute('data-add-frame')) {
      e.preventDefault();
      frameBox(dayRow(grid, btn.getAttribute('data-add-frame')))
        .appendChild(makeFrame(grid, btn.getAttribute('data-add-frame'), '', ''));
    } else if (btn.hasAttribute('data-remove-frame')) {
      e.preventDefault();
      btn.closest('[data-frame]').remove();
    } else if (btn.hasAttribute('data-copy-all')) {
      e.preventDefault();
      copyDay(grid, btn.getAttribute('data-copy-all'), allDays(grid));
    } else if (btn.hasAttribute('data-copy-weekdays')) {
      e.preventDefault();
      copyDay(grid, btn.getAttribute('data-copy-weekdays'), WEEKDAYS);
    }
  });
})();
```

- [ ] **Step 2: Sanity-check the file is syntactically valid**

Run: `node --check src/main/resources/META-INF/resources/workplan.js`
Expected: no output, exit 0. (If `node` is unavailable, skip — the file is validated in Task 8's browser step.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/resources/workplan.js
git commit -m "feat: add client-side weekly-schedule grid editor"
```

---

### Task 6: Render the global grid in `availability.html`

**Files:**
- Modify: `src/main/resources/templates/AdminResource/availability.html`
- Test: `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java` (extend with a render assertion)

- [ ] **Step 1: Write the failing render test**

Append to `AdminAvailabilityBulkTest.java`:

```java
    @Test
    void availabilityPageRendersSevenDayGridWithCopyButtons() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when().get("/me/availability")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("data-workplan"))
                .body(org.hamcrest.Matchers.containsString("data-day=\"MONDAY\""))
                .body(org.hamcrest.Matchers.containsString("data-day=\"SUNDAY\""))
                .body(org.hamcrest.Matchers.containsString("Copy to all days"))
                .body(org.hamcrest.Matchers.containsString("Copy to weekdays"))
                .body(org.hamcrest.Matchers.containsString("/workplan.js"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest#availabilityPageRendersSevenDayGridWithCopyButtons`
Expected: FAIL — body does not contain `data-workplan` (old template still rendered).

- [ ] **Step 3: Rewrite `availability.html`**

Replace the entire file with:

```html
{@java.util.List<com.calit.domain.AvailabilityRule> rules}
{@java.util.List<com.calit.web.WeekRow> week}
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.time.DayOfWeek[] daysOfWeek}
{@java.lang.Long pendingCount}
{@java.lang.Boolean isAdmin}
{#include adminBase title="Admin — Availability" pendingCount=pendingCount active="availability" isAdmin=isAdmin}
  <h1 class="text-2xl font-bold mb-1">Availability (work hours)</h1>
  <p class="text-sm text-base-content/70 mb-4">Your default weekly schedule. Each day can hold several time frames. Use the copy buttons to mirror one day across the week, then Save.</p>

  <form method="post" action="/me/availability/bulk" data-workplan class="space-y-2">
    {#for row in week}
    <div data-day="{row.day}" class="card bg-base-100 border border-base-300">
      <div class="card-body py-3 gap-2">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <strong class="w-24">{display:of(row.day)}</strong>
          <div class="flex flex-wrap gap-1">
            <button type="button" class="btn btn-ghost btn-xs" data-add-frame="{row.day}">+ Frame</button>
            <button type="button" class="btn btn-ghost btn-xs" data-copy-all="{row.day}">Copy to all days</button>
            <button type="button" class="btn btn-ghost btn-xs" data-copy-weekdays="{row.day}">Copy to weekdays</button>
          </div>
        </div>
        <div data-frames class="space-y-1">
          {#for fr in row.frames}
          <div data-frame class="flex items-center gap-2">
            <input type="hidden" name="frameDay" value="{row.day}">
            <input class="input input-sm" type="time" name="frameStart" value="{fr.startTime}">
            <span class="text-base-content/60">to</span>
            <input class="input input-sm" type="time" name="frameEnd" value="{fr.endTime}">
            <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="Remove frame">&times;</button>
          </div>
          {/for}
        </div>
      </div>
    </div>
    {/for}

    <template data-frame-template>
      <div data-frame class="flex items-center gap-2">
        <input type="hidden" name="frameDay" value="">
        <input class="input input-sm" type="time" name="frameStart">
        <span class="text-base-content/60">to</span>
        <input class="input input-sm" type="time" name="frameEnd">
        <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="Remove frame">&times;</button>
      </div>
    </template>

    <button type="submit" class="btn btn-primary mt-2">Save schedule</button>
  </form>

  <script src="/workplan.js"></script>
{/include}
```

> The `rules` and `types` params remain declared (still passed by the controller) but are no longer rendered here; leaving them avoids touching the controller signature again. Per-type schedules are edited on each meeting type's detail page.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest`
Expected: PASS (all tests in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/AdminResource/availability.html \
        src/test/java/com/calit/web/AdminAvailabilityBulkTest.java
git commit -m "feat: render global weekly schedule as a seven-day grid"
```

---

### Task 7: Render the per-type grid in `meetingTypeDetail.html`

**Files:**
- Modify: `src/main/resources/templates/AdminResource/meetingTypeDetail.html:97-120` (the "Working hours" collapse section)
- Test: `src/test/java/com/calit/web/AdminAvailabilityBulkTest.java` (extend)

- [ ] **Step 1: Write the failing render test**

Append to `AdminAvailabilityBulkTest.java` (reuses the `MeetingType` import added in Task 3):

```java
    @Test
    void typeDetailRendersSevenDayGrid() {
        String cred = FormAuth.login();
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Grid Type").formParam("slug", "grid-type")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0").formParam("horizonDays", "30")
                .formParam("locationType", "PHONE")
                .when().post("/me/meeting-types").then().statusCode(200);
        com.calit.domain.MeetingType t = com.calit.domain.MeetingType.find("slug = ?1", "grid-type").firstResult();

        given().cookie("quarkus-credential", cred)
                .when().get("/me/meeting-types/" + t.id)
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("data-workplan"))
                .body(org.hamcrest.Matchers.containsString("data-day=\"MONDAY\""))
                .body(org.hamcrest.Matchers.containsString("/me/meeting-types/" + t.id + "/availability/bulk"))
                .body(org.hamcrest.Matchers.containsString("Copy to weekdays"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest#typeDetailRendersSevenDayGrid`
Expected: FAIL — body lacks `data-workplan` / the `/availability/bulk` action.

- [ ] **Step 3: Replace the "Working hours" collapse section**

In `src/main/resources/templates/AdminResource/meetingTypeDetail.html`, replace lines 97-120 (the entire `<div class="collapse collapse-arrow ...">` block whose title is "Working hours") with:

```html
    <div class="collapse collapse-arrow bg-base-100 border border-base-300">
      <input type="checkbox">
      <div class="collapse-title font-semibold">Working hours</div>
      <div class="collapse-content">
        <p class="text-sm text-base-content/70 mb-2">Weekly hours for this meeting type. When any frame is set for a day, it replaces the global default hours for that day. Each day can hold several time frames; use the copy buttons to mirror one day, then Save.</p>
        <form method="post" action="/me/meeting-types/{type.id}/availability/bulk" data-workplan class="space-y-2">
          {#for row in week}
          <div data-day="{row.day}" class="card bg-base-200 border border-base-300">
            <div class="card-body py-3 gap-2">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <strong class="w-24">{display:of(row.day)}</strong>
                <div class="flex flex-wrap gap-1">
                  <button type="button" class="btn btn-ghost btn-xs" data-add-frame="{row.day}">+ Frame</button>
                  <button type="button" class="btn btn-ghost btn-xs" data-copy-all="{row.day}">Copy to all days</button>
                  <button type="button" class="btn btn-ghost btn-xs" data-copy-weekdays="{row.day}">Copy to weekdays</button>
                </div>
              </div>
              <div data-frames class="space-y-1">
                {#for fr in row.frames}
                <div data-frame class="flex items-center gap-2">
                  <input type="hidden" name="frameDay" value="{row.day}">
                  <input class="input input-sm" type="time" name="frameStart" value="{fr.startTime}">
                  <span class="text-base-content/60">to</span>
                  <input class="input input-sm" type="time" name="frameEnd" value="{fr.endTime}">
                  <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="Remove frame">&times;</button>
                </div>
                {/for}
              </div>
            </div>
          </div>
          {/for}

          <template data-frame-template>
            <div data-frame class="flex items-center gap-2">
              <input type="hidden" name="frameDay" value="">
              <input class="input input-sm" type="time" name="frameStart">
              <span class="text-base-content/60">to</span>
              <input class="input input-sm" type="time" name="frameEnd">
              <button type="button" class="btn btn-ghost btn-xs text-error" data-remove-frame aria-label="Remove frame">&times;</button>
            </div>
          </template>

          <button type="submit" class="btn btn-primary mt-2">Save working hours</button>
        </form>
      </div>
    </div>
```

- [ ] **Step 4: Add the script include once on the detail page**

At the very end of `meetingTypeDetail.html`, immediately before the closing `{/include}` (currently line 154), add:

```html
  <script src="/workplan.js"></script>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=AdminAvailabilityBulkTest`
Expected: PASS (all tests in the class).

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS — full suite green (existing `AdminAvailabilityTest`, `SlotService*`, override tests, plus the new ones).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/AdminResource/meetingTypeDetail.html \
        src/test/java/com/calit/web/AdminAvailabilityBulkTest.java
git commit -m "feat: render per-type working hours as a seven-day grid"
```

---

### Task 8: Manual / browser verification of grid interactivity

The copy/add/remove buttons are client-side and not covered by JUnit. Verify them in a real browser.

- [ ] **Step 1: Build CSS and start the app**

```bash
bun run css:build
./mvnw quarkus:dev
```

- [ ] **Step 2: Verify the global grid (use Playwright MCP or a manual browser)**

Navigate to `http://localhost:8080/me/availability` (log in as the seeded admin), then confirm:
1. Seven rows render, **Monday** at top through **Sunday**.
2. On Monday, click **+ Frame** twice; set the two frames to `09:00–12:00` and `13:00–17:00`.
3. Click Monday's **Copy to weekdays** → Tuesday–Friday each show the same two frames; Saturday/Sunday stay empty.
4. Click Monday's **Copy to all days** → Saturday and Sunday now match too.
5. Remove one Saturday frame with the **×** button.
6. Click **Save schedule**. Page reloads; reopen `/me/availability` and confirm the saved frames persist and match what was on screen.

Expected: all six behaviours hold; no JS console errors.

- [ ] **Step 3: Verify the per-type grid**

Open any meeting type at `/me/meeting-types/{id}`, expand **Working hours**, repeat add/copy/save. Confirm saving posts to `/me/meeting-types/{id}/availability/bulk` and that global hours (`/me/availability`) are unchanged.

- [ ] **Step 4: Commit (docs only, if any notes were added)**

No code change expected here. If verification surfaced a bug, fix it under TDD (add a failing test first) before continuing.

---

## Out of scope (explicit)

- **Meeting-type create form** (`meetingTypes.html`) still uses the old single-row-per-weekday `ruleDay[]/ruleStart[]/ruleEnd[]` block handled by `createInitialWorkingHours`. Gridifying the create form is a natural follow-up but is not part of this remake.
- **Date overrides** keep their existing UI and endpoints.
- **Legacy single-add/delete availability endpoints** are retained (used by `AdminAvailabilityTest` and for seeding in the new tests); removing them is a separate cleanup.

## Self-Review notes

- **Spec coverage:** seven rows [Task 6/7 render `{#for row in week}` over all `DayOfWeek.values()`]; each row holds multiple time frames [multiple `AvailabilityRule` rows + `[data-frames]` container + add-frame]; copy current day → all days [`data-copy-all`] and → work days [`data-copy-weekdays` over `WEEKDAYS`]; all days visible simultaneously [single form, no day selector]; same logic for per-type schedules [Task 7 reuses the identical grid + `persistFrames`]. ✔
- **Type consistency:** `WeekRow.fromRules` (Task 1) ↔ `weekRows(...)` wrapper (Task 4) ↔ `{@...WeekRow> week}` template param (Tasks 4–7); `persistFrames(ownerId, meetingTypeId, form)` defined in Task 2, reused in Task 3; `frameDay/frameStart/frameEnd` names identical across JS template, server-rendered rows, and `persistFrames`. ✔
- **No placeholders:** every step ships full code/markup and an exact command. The one `Long typeId = ...` placeholder in Task 3 Step 1 is explicitly flagged and replaced by the simpler header shown directly beneath it. ✔
