# Min-Notice Default (4× Duration, Dynamic) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the create-meeting-type form, the "Min scheduling notice" field defaults to 4× the duration and **recomputes live** as the user changes the duration — until the user manually edits the notice field.

**Architecture:** A small inline vanilla `<script>` in the create form recomputes `minNoticeMinutes = durationMinutes × 4` on every duration change, stopping once the user touches the notice field. This mirrors the existing `data-slug-autofill` live-fill script already in the same template (same `edited`-flag pattern). The input also ships a static `value="120"` (= default duration 30 × 4) so users with JS disabled still get a sane default. No server logic, no migration, no change to edit/detail or existing rows.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute templates, inline vanilla JS, RestAssured + JUnit 5 (`@QuarkusTest`).

---

## Scope & decisions (locked)

- **Where:** create form only (`meetingTypes.html`). Inline JS recomputes the notice value dynamically; static `value="120"` is the no-JS fallback.
- **Dynamic behavior:** while the user has **not** manually edited the notice field, every change to duration sets notice = `duration × 4`. The moment the user edits the notice field, the auto-recompute stops (one-way latch, same as slug autofill).
- **When:** create form only. Edit/detail form (`meetingTypeDetail.html`) binds to the saved entity value and is **left untouched**.
- **Backfill:** none. Existing `meeting_type` rows stay as-is. No Flyway migration.
- **Why JS is allowed here:** the app ships "no JavaScript at runtime (few inline vanilla scripts aside)". The create form already contains two inline scripts (slug autofill, copy-link toast); this is one more in the same style.

## Testing note

RestAssured cannot execute JS, so the test does **not** assert recomputed values. Per the codebase convention (assert on stable markers, e.g. `data-slug-autofill`), the test asserts the form ships the `data-min-notice-auto` marker the script hooks onto, plus the static `value="120"` fallback. The live recompute is verified manually in a browser.

## File Structure

- **Modify:** `src/main/resources/templates/AdminResource/meetingTypes.html`
  - Line 104: `minNoticeMinutes` input — change `value="0"` → `value="120"` and add the `data-min-notice-auto` marker attribute.
  - After the existing slug-autofill `<script>` block (the one ending ~line 160), add one new inline `<script>` that wires duration → notice recompute.
- **Modify:** `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java` — add one test asserting the marker + static default are present.
- **Docs (separate branch):** `docs-site` branch — note the dynamic default in the meeting-type usage docs.

No Java production code, entity, migration, or `AdminResource` handler changes. The POST handlers already pass submitted `minNoticeMinutes` through verbatim, so existing form-post tests (which send `minNoticeMinutes=0` explicitly) keep passing unchanged.

---

### Task 1: Dynamic min-notice = 4× duration on the create form

**Files:**
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html:104` and after `:160`
- Test: `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java`

- [ ] **Step 1: Write the failing test**

Add this method inside `AdminMeetingTypeFormTest` (e.g. after `createFormExposesBufferInputs`, ~line 25). `containsString` and `FormAuth.login()` are already imported/used in this file.

```java
    @Test
    void createFormHasDynamicMinNoticeDefault() {
        // Static fallback = default duration 30 * 4 = 120; marker = script hook for live recompute.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("name=\"minNoticeMinutes\" value=\"120\""))
                .body(containsString("data-min-notice-auto"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AdminMeetingTypeFormTest#createFormHasDynamicMinNoticeDefault`
Expected: FAIL — form still renders `value="0"` and has no `data-min-notice-auto` marker. (Docker must be running for Dev Services Postgres.)

- [ ] **Step 3: Update the min-notice input**

In `src/main/resources/templates/AdminResource/meetingTypes.html`, line 104, replace:

```html
        <input class="input w-full" type="number" name="minNoticeMinutes" value="0" required>
```

with:

```html
        <input class="input w-full" type="number" name="minNoticeMinutes" value="120" required data-min-notice-auto>
```

- [ ] **Step 4: Add the inline recompute script**

In the same file, immediately after the existing slug-autofill script block (the `</script>` at ~line 160, before the `<button type="submit" ...>Create</button>` line), add this new block. It uses `document.currentScript.closest('form')` and the one-way `edited` latch, matching the slug-autofill script exactly.

```html
    <script>
    (function () {
      var form = document.currentScript.closest('form');
      var duration = form.querySelector('[name="durationMinutes"]');
      var notice = form.querySelector('[data-min-notice-auto]');
      if (!duration || !notice) { return; }
      var edited = false;
      notice.addEventListener('input', function () { edited = true; });
      duration.addEventListener('input', function () {
        if (edited) { return; }
        var d = parseInt(duration.value, 10);
        notice.value = (isNaN(d) ? 0 : d) * 4;
      });
    })();
    </script>
```

- [ ] **Step 5: Run the new test to verify it passes**

Run: `mvn test -Dtest=AdminMeetingTypeFormTest#createFormHasDynamicMinNoticeDefault`
Expected: PASS.

- [ ] **Step 6: Run the full form test class to confirm no regressions**

Run: `mvn test -Dtest=AdminMeetingTypeFormTest`
Expected: PASS — all methods green. Existing create-POST tests send `minNoticeMinutes` explicitly, so they are unaffected.

- [ ] **Step 7: Manual browser check (the JS path RestAssured can't cover)**

With `mvn quarkus:dev` running (and `bun run css:build` done at least once), log in, open `/me/meeting-types`:
1. Notice field shows `120` on load.
2. Change Duration to `45` → notice updates to `180`. Change to `15` → notice updates to `60`.
3. Manually edit the notice field to `90`, then change duration again → notice stays `90` (latch held).
Confirm all three before committing.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/templates/AdminResource/meetingTypes.html src/test/java/com/calit/web/AdminMeetingTypeFormTest.java
git commit -m "feat(meeting-types): create form min notice dynamically defaults to 4x duration"
```

---

### Task 2: Update public docs on the `docs-site` branch

CLAUDE.md requires user-facing changes to land docs in the same effort. Docs live on the **`docs-site`** branch (Astro Starlight in `docs-site/`), separate from `main`.

**Files:**
- Modify: on `docs-site` branch, the usage/meeting-types page (locate with the grep in Step 2).

- [ ] **Step 1: Get the docs branch into an isolated worktree**

```bash
git fetch origin docs-site
git worktree add ../calit-docs-site docs-site
```

Expected: a new working tree at `../calit-docs-site` on `docs-site`.

- [ ] **Step 2: Find the page that documents creating a meeting type**

```bash
grep -rni "min scheduling notice\|minimum notice\|meeting type" ../calit-docs-site/docs-site/src/content
```

Expected: one or more `.md`/`.mdx` files under the usage section. Pick the page walking through creating a meeting type / scheduling limits.

- [ ] **Step 3: Add a sentence about the dynamic default**

Where the "Min scheduling notice" / scheduling-limits field is described, add:

```markdown
On a new meeting type, **Min scheduling notice** defaults to 4× the duration and updates automatically as you change the duration (e.g. a 45-minute meeting suggests 180 minutes' notice). Once you edit the notice field yourself, it stops auto-updating. Set it to any value — including `0` for instant bookings — before saving.
```

If no scheduling-limits description exists, add a short "Scheduling limits" subsection under the creation steps containing that sentence.

- [ ] **Step 4: Build the docs site to confirm it still compiles**

```bash
cd ../calit-docs-site/docs-site && bun install && bun run build
```

Expected: Astro build succeeds. (If the docs-site uses npm, run `npm install && npm run build` — check the lockfile in that dir.)

- [ ] **Step 5: Commit and push the docs branch**

```bash
git -C ../calit-docs-site add -A
git -C ../calit-docs-site commit -m "docs: note dynamic min-notice default (4x duration) on create form"
git -C ../calit-docs-site push origin docs-site
```

Expected: push succeeds; GitHub Pages `docs.yml` redeploys.

- [ ] **Step 6: Remove the docs worktree**

```bash
git worktree remove ../calit-docs-site
```

Expected: worktree cleaned up; back on `main` only.

---

## Self-Review

- **Spec coverage:** Requirement = "default minimal time before meeting should be its length × 4, dynamic — when I change duration the default also changes." Task 1's inline script recomputes notice = duration × 4 on every duration change (until manual edit), with `value="120"` as the no-JS fallback. ✅ Covered.
- **Placeholder scan:** No TBD / "handle edge cases" / "write tests for the above". Full test body, exact template line, and complete script shown. ✅
- **Type/name consistency:** Input names `durationMinutes` and `minNoticeMinutes` match the entity fields, template inputs, and existing tests. Marker `data-min-notice-auto` is used identically in the template (Step 3), the script selector (Step 4), and the test assertion (Step 1). Route `/me/meeting-types`, cookie `quarkus-credential`, `FormAuth.login()` match existing patterns. ✅
- **JS convention:** New script mirrors the existing slug-autofill block (`document.currentScript.closest('form')`, one-way `edited` latch) — consistent style, scoped to this form. ✅
- **Create-only / no-backfill:** No migration, no edit-template change, no `AdminResource` change — consistent with locked decisions. ✅
