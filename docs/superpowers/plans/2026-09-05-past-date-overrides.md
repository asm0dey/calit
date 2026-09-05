# Hide Past Date Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On `/me/date-overrides`, show upcoming date overrides first and fold past ones into a collapsed, no-JS `<details>` section so a host with many overrides can manage the list without deleting history.

**Architecture:** `AdminResource` splits the owner's overrides into `upcoming` (date >= today in the owner's timezone) and `past` (date < today), passes both to the Qute template, and the template renders upcoming cards normally plus a daisyUI `<details>`-based collapse holding the past cards. The card markup is extracted into a `_dateOverrideCard.html` partial so it is written once. No schema change, no new endpoint, no JavaScript.

**Tech Stack:** Quarkus 3.38 / Java 25, Panache, Qute `@CheckedTemplate`, Quarkus i18n message bundles (`AdminMessages`), Tailwind v4 + daisyUI 5, RestAssured + `@QuarkusTest`.

**Spec:** GitHub issue [#168 "Date overrides"](https://github.com/asm0dey/calit/issues/168). Verbatim requirement from the reporter (comment 2026-09-05): *"I would like to see only date overrides in the future first; I don't want to see overrides in the past."* Original report: *"because I have a lot of them, they are hard to manage. Perhaps you could move past date overrides to another screen or hide them, rather than delete them, as they may still be useful to someone."*

## Global Constraints

- **Owner scoping:** every query filters by `currentOwner.id()`. Never widen the existing `ownerId = ?1` predicate.
- **No JavaScript:** progressive enhancement is a project rule; the collapse must be native `<details>`/`<summary>`, not a JS toggle.
- **i18n parity is test-enforced:** `MultiHostMessageParityTest` fails the build if a new `@Message` method on `AdminMessages` lacks a `key=` line in **both** `src/main/resources/messages/adm_de.properties` and `adm_he.properties`. Placeholder names must be identical across locales.
- **CSRF:** `CsrfFormCoverageTest` walks every `.html` under `src/main/resources/templates` and requires `count("{inject:csrf.token}") >= count("method=\"post\"")` **per file**. The extracted partial contains one POST form and must carry one token.
- **Formatting gate:** `mvn verify` runs `spotless:check` (palantir-java-format). Run `mvn spotless:apply` (or `bun run format`) before committing Java changes. Qute `.html` templates are deliberately not Prettier-formatted.
- **Build JDK:** `export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` (or your Liberica 26 path) before `./mvnw` — the default `mvn` JDK is 21 and fails with "release 25 not supported".
- **Docker must be running** for `mvn test` (Dev Services Postgres).
- **Never open a PR with a red suite.** `mvn test` must be fully green before the branch becomes a PR.
- **Docs are part of done:** a user-facing change lands a `## Unreleased` changelog bullet and any needed doc-page edit on the `docs-site` branch in the same effort.

## Existing Code Map (read this before Task 1)

| What | Where |
|---|---|
| Page GET/POST/delete handlers | `src/main/java/site/asm0dey/calit/web/AdminResource.java:1474-1535` |
| `overridesWithWindows()` (the query being changed) | `src/main/java/site/asm0dey/calit/web/AdminResource.java:1468-1471` |
| `withWindows(...)` N+1-free window loader (reuse as-is) | `src/main/java/site/asm0dey/calit/web/AdminResource.java:662` |
| `ownerZone()` — owner's tz string, `"UTC"` fallback | `src/main/java/site/asm0dey/calit/web/AdminResource.java:288-291` |
| `Templates.dateOverrides(...)` native declaration | `src/main/java/site/asm0dey/calit/web/AdminResource.java:102-107` |
| Page template | `src/main/resources/templates/AdminResource/dateOverrides.html` |
| Partial-template convention (`_name.html` + `{#include AdminResource/_name p=v /}`) | `src/main/resources/templates/AdminResource/_workplanGrid.html`, `_meetingtypecard.html` |
| `AdminMessages` date-override key block | `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java:639-685` |
| Parameterized-message precedent (`{count}` named placeholder) | `AdminMessages.java:353-354`, used as `{adm:adm_shared_revokeConfirm_count(futureBookingCount)}` in `templates/SharedMeetingsResource/revokeConfirm.html:11` |
| Existing page tests | `src/test/java/site/asm0dey/calit/web/AdminDateOverridesTest.java` |

**Out of scope (deliberate):** the per-meeting-type override list inside `templates/AdminResource/meetingTypeDetail.html:213-230`. It is already behind a collapsed accordion and is scoped to one type, so it does not produce the reported "hard to manage" wall. Task 6 files a follow-up bean for it instead of widening this PR's diff.

## File Structure

- **Create** `src/main/resources/templates/AdminResource/_dateOverrideCard.html` — renders ONE override card (date, global/type label, day-off badge or window list, delete form). Written once, included from both the upcoming loop and the past loop. Responsibility: card markup only; the including page owns section headings and the collapse.
- **Modify** `src/main/resources/templates/AdminResource/dateOverrides.html` — two params (`upcoming`, `past`) instead of `overrides`; upcoming cards, then a `<details>` collapse with past cards; both loops delegate to the partial.
- **Modify** `src/main/java/site/asm0dey/calit/web/AdminResource.java` — `Templates.dateOverrides` signature; new `ownerZoneId()` and `dateOverridesInstance()` helpers; `overridesWithWindows()` re-ordered by date; the three handlers collapse to `return dateOverridesInstance();`.
- **Modify** `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` — one new `@Message` method.
- **Modify** `src/main/resources/messages/adm_de.properties`, `adm_he.properties` — the German and Hebrew values for that key.
- **Modify** `src/test/java/site/asm0dey/calit/web/AdminDateOverridesTest.java` — new tests for the split; existing tests stay.
- **On the `docs-site` branch:** `docs-site/src/content/docs/releases/changelog.md` and `docs-site/src/content/docs/usage/availability.md`.

---

## Task 0: Branch, bean, and a green baseline

**Files:** none modified.

**Interfaces:**
- Consumes: nothing.
- Produces: a feature branch `feat/past-date-overrides` off `main`, bean `calit-i3wt` moved to `in-progress`, and a verified-green baseline.

- [ ] **Step 1: Confirm you are on `main` and clean, then branch**

```bash
git status --short          # must print nothing
git branch --show-current   # must print: main
git switch -c feat/past-date-overrides
```

Never push to `main` on this project — branch + PR, always.

- [ ] **Step 2: Pick up the tracking bean**

The bean already exists — `calit-i3wt`, "GH #168: fold past date overrides into a collapsed section". Its todo list mirrors this plan's tasks.

```bash
beans show calit-i3wt
beans update calit-i3wt -s in-progress
```

Tick each item as its task lands, and include the `.beans/` file in that task's commit.

- [ ] **Step 3: Establish a green baseline BEFORE changing anything**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
docker info > /dev/null        # Dev Services needs a running Docker
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. If it is red on a clean `main`, stop and fix that first (its own commit) — a pre-existing break is still your branch's problem once you open the PR.

---

## Task 1: The past-section label, in English, German and Hebrew

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (date-override key block, after `adm_dateOverrides_to()` at line 684)
- Modify: `src/main/resources/messages/adm_de.properties:233` area
- Modify: `src/main/resources/messages/adm_he.properties:233` area
- Test: `src/test/java/site/asm0dey/calit/i18n/MultiHostMessageParityTest.java` (existing, no edit)

**Interfaces:**
- Consumes: nothing.
- Produces: `String adm_dateOverrides_past_summary(int count)` on `AdminMessages`, called from `dateOverrides.html` in Task 3 as `{adm:adm_dateOverrides_past_summary(past.size)}`.

- [ ] **Step 1: Run the parity test first and watch it fail**

Nothing references the key yet — this run is your "before" reading, and it must PASS. Run:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=MultiHostMessageParityTest
```

Expected: PASS, 4 tests. The key does not exist on the interface yet, so there is nothing to be missing. After Step 2 adds the method and before Steps 4-5 add the translations, this same command must FAIL naming `adm_de.properties` and `adm_he.properties` — that failure is the test proving it guards the change.

Doing i18n first is deliberate: it is the only piece of this feature that compiles on its own. The Java signature change and the template change invalidate each other until both land, so they cannot host a meaningful mid-way test run.

- [ ] **Step 2: Add the `@Message` method**

In `AdminMessages.java`, immediately after:

```java
    @Message("to")
    String adm_dateOverrides_to();
```

add:

```java
    @Message("Past overrides ({count})")
    String adm_dateOverrides_past_summary(int count);
```

The placeholder is the parameter NAME, not `{0}` — that is how `adm_shared_revokeConfirm_count(long count)` at line 354 does it, and it works because `maven.compiler.parameters=true` is set in `pom.xml`.

- [ ] **Step 3: Run the parity test and verify it now fails**

```bash
./mvnw test -Dtest=MultiHostMessageParityTest
```

Expected: FAIL — `everyAdminMessageKeyHasGermanAndHebrewTranslation` reports `messages/adm_de.properties is missing 1 key(s): adm_dateOverrides_past_summary` and the same for `adm_he.properties`.

- [ ] **Step 4: Add the German value**

`src/main/resources/messages/adm_de.properties`, at the end of the date-override block (after `adm_dateOverrides_to=bis` on line 233):

```properties
adm_dateOverrides_past_summary=Vergangene Ausnahmen ({count})
```

"Ausnahme" is the term the rest of this block already uses for an override (`adm_dateOverrides_h1=Datumsausnahmen`).

- [ ] **Step 5: Add the Hebrew value**

`src/main/resources/messages/adm_he.properties`, at the end of the same block (after `adm_dateOverrides_to=עד` on line 233):

```properties
adm_dateOverrides_past_summary=חריגות שעברו ({count})
```

"חריגה" is the term this block already uses (`adm_dateOverrides_h1=חריגות תאריך`). The `{count}` placeholder stays in Latin script and keeps the identical name — placeholder names must match across every locale.

- [ ] **Step 6: Run the parity test and verify it passes**

```bash
./mvnw test -Dtest=MultiHostMessageParityTest
```

Expected: PASS, 4 tests, 0 failures (`adminPropertyFilesHaveNoOrphanKeys` also has to stay green — it fails if you typo the key name in a properties file).

- [ ] **Step 7: Compile**

```bash
./mvnw -q compile
```

Expected: `BUILD SUCCESS`. Nothing consumes the key yet; Task 3 will.

- [ ] **Step 8: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties
git commit -m "i18n(overrides): add past-overrides summary key with de and he"
```

---

## Task 2: Split overrides into upcoming and past in `AdminResource`

Java-side only. The template is updated in Task 3; between the two tasks the project does not compile, so **Tasks 2 and 3 land in ONE commit** (Task 3 Step 5). Run the compile check at the end of Task 3, not here.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:102-107` (template declaration)
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:1465-1535` (query helper + three handlers)

**Interfaces:**
- Consumes: `withWindows(List<DateOverride>)` at `AdminResource.java:662` (already exists, unchanged); `ownerZone()` at `AdminResource.java:288`; `MeetingType.listForOwner(Long)`; `pendingCount()`; `isAdmin()`; `m().adm_dateOverrides_title()`.
- Produces:
  - `private ZoneId ownerZoneId()` — the current owner's zone, `ZoneOffset.UTC` when the stored value is null/blank/garbage.
  - `private TemplateInstance dateOverridesInstance()` — the single render path for GET, create-POST and delete-POST.
  - `Templates.dateOverrides(List<DateOverride> upcoming, List<DateOverride> past, List<MeetingType> types, Long pendingCount, boolean isAdmin, String title)` — Task 3's template must declare exactly these parameter names.

- [ ] **Step 1: Change the `@CheckedTemplate` declaration**

In the `Templates` class, replace:

```java
        public static native TemplateInstance dateOverrides(
                List<DateOverride> overrides,
                List<MeetingType> types,
                Long pendingCount,
                boolean isAdmin,
                String title);
```

with:

```java
        public static native TemplateInstance dateOverrides(
                List<DateOverride> upcoming,
                List<DateOverride> past,
                List<MeetingType> types,
                Long pendingCount,
                boolean isAdmin,
                String title);
```

- [ ] **Step 2: Add the owner-zone helper**

Insert directly after `ownerZone()` (which ends at `AdminResource.java:291`):

```java
    /**
     * {@link #ownerZone()} parsed, for "is this date in the past?" comparisons. A null, blank or
     * unparseable stored timezone falls back to UTC rather than 500ing the page — the same
     * defensive posture {@code DisplayExtensions.when} takes for the no-JS time fallback.
     */
    private ZoneId ownerZoneId() {
        try {
            return ZoneId.of(ownerZone());
        } catch (DateTimeException _) {
            return ZoneOffset.UTC;
        }
    }
```

`ZoneId`, `ZoneOffset` and `DateTimeException` all arrive via the existing `import java.time.*;`. `_` is Java 25's unnamed variable, already used at `AdminResource.java:1497`.

- [ ] **Step 3: Replace `overridesWithWindows()` with the splitting render helper**

Replace the whole existing block (`AdminResource.java:1465-1471`):

```java
    /**
     * All overrides with their (transient) {@code windows} loaded for display.
     * {@link DateOverride#windows} is @Transient (not cascade-mapped), so listAll()
     * leaves it empty; we populate each from {@link DateOverrideWindow} by id.
     */
    private List<DateOverride> overridesWithWindows() {
        return withWindows(
                DateOverride.list("ownerId = ?1 order by meetingTypeId nulls first, overrideDate", currentOwner.id()));
    }
```

with:

```java
    /**
     * Renders /me/date-overrides for the current owner. Shared by the GET and by the create/delete
     * POSTs, which all re-render the same page.
     *
     * <p>GH #168: a host with many overrides could not find the ones that still matter, because the
     * page listed every override they had ever created in one flat list. Overrides are now split on
     * "today" in the OWNER's timezone (an override for today is still upcoming — it can still be
     * booked): upcoming soonest-first as normal cards, past most-recent-first inside a collapsed
     * section. Nothing is deleted or hidden from the DOM; the past ones just start folded.
     *
     * <p>One query, split in memory: the row count here is per-owner and small, and a single
     * ordered fetch also keeps {@link #withWindows} to one extra query for the whole page.
     * {@link DateOverride#windows} is @Transient (not cascade-mapped), so the list query leaves it
     * empty and {@link #withWindows} populates it.
     */
    private TemplateInstance dateOverridesInstance() {
        List<DateOverride> all =
                withWindows(DateOverride.list("ownerId = ?1 order by overrideDate", currentOwner.id()));
        LocalDate today = LocalDate.now(ownerZoneId());
        List<DateOverride> upcoming =
                all.stream().filter(o -> !o.overrideDate.isBefore(today)).toList();
        List<DateOverride> past = all.stream()
                .filter(o -> o.overrideDate.isBefore(today))
                .sorted(Comparator.comparing((DateOverride o) -> o.overrideDate).reversed())
                .toList();
        return Templates.dateOverrides(
                upcoming,
                past,
                MeetingType.listForOwner(currentOwner.id()),
                pendingCount(),
                isAdmin(),
                m().adm_dateOverrides_title());
    }
```

`LocalDate` comes from `import java.time.*;` and `Comparator` from `import java.util.*;` — both already imported.

- [ ] **Step 4: Point the three handlers at the helper**

In `dateOverrides()` (GET), `createOverride(...)` and `deleteOverride(...)`, replace each of the three identical five-line `return Templates.dateOverrides(overridesWithWindows(), ...);` blocks with:

```java
        return dateOverridesInstance();
```

Leave every other line of those methods — the `QuarkusTransaction.requiringNew()` bodies, the `NumberFormatException` guard, the `requireType(typeId)` cross-owner 404, the owner check in delete — exactly as they are. After this step no reference to `overridesWithWindows` remains; grep to confirm:

```bash
grep -n "overridesWithWindows" src/main/java/site/asm0dey/calit/web/AdminResource.java
```

Expected: no output.

- [ ] **Step 5: Do NOT build yet**

The template still declares `{@java.util.List<...> overrides}` and Qute's build-time type check will fail. Continue to Task 3.

---

## Task 3: Extract the card partial and render the past collapse

**Files:**
- Create: `src/main/resources/templates/AdminResource/_dateOverrideCard.html`
- Modify: `src/main/resources/templates/AdminResource/dateOverrides.html`
- Test: covered by Task 4 (`AdminDateOverridesTest`) and by the existing `CsrfFormCoverageTest`

**Interfaces:**
- Consumes: `Templates.dateOverrides(upcoming, past, types, pendingCount, isAdmin, title)` from Task 2; the message key `adm_dateOverrides_past_summary(int count)` from Task 1.
- Produces: the marker `id="past-overrides"` on the `<details>` element — Task 4's tests locate the past section by that exact string, so do not rename it.

- [ ] **Step 1: Create the card partial**

`src/main/resources/templates/AdminResource/_dateOverrideCard.html` — the card body lifted verbatim from the current `dateOverrides.html` loop, with the loop variable renamed to the include parameter `o`:

```html
{@site.asm0dey.calit.domain.DateOverride o}
{! One date-override card: date, scope, day-off badge or window list, delete form.
   Included twice from dateOverrides.html — once for upcoming overrides, once inside the
   collapsed past section (GH #168) — so the markup exists in exactly one place. !}
<div class="card bg-base-100 border border-base-300"><div class="card-body py-3 gap-1">
  <p><strong>{o.overrideDate}</strong> &middot; {#if o.meetingTypeId}type #{o.meetingTypeId}{#else}{adm:adm_dateOverrides_global}{/if}</p>
  {#if o.windows.isEmpty()}
    <p><span class="badge badge-ghost badge-sm">{adm:adm_dateOverrides_badge_day_off}</span> ({adm:adm_common_blocked})</p>
  {#else}
    <ul class="list-disc ms-5">{#for w in o.windows}<li>{w.startTime} &ndash; {w.endTime}</li>{/for}</ul>
  {/if}
  <form method="post" action="/me/date-overrides/{o.id}/delete"><input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}"><button type="submit" class="btn btn-error btn-outline btn-sm">{adm:adm_dateOverrides_btn_delete}</button></form>
</div></div>
```

One `method="post"` and one `{inject:csrf.token}` — `CsrfFormCoverageTest` counts per file and is satisfied.

- [ ] **Step 2: Rewrite the head of `dateOverrides.html`**

Replace the first line:

```html
{@java.util.List<site.asm0dey.calit.domain.DateOverride> overrides}
```

with:

```html
{@java.util.List<site.asm0dey.calit.domain.DateOverride> upcoming}
{@java.util.List<site.asm0dey.calit.domain.DateOverride> past}
```

Leave the other `{@...}` parameter declarations and the `{#include adminBase ...}` line untouched.

- [ ] **Step 3: Replace the override list block**

Replace this entire block:

```html
  <div class="space-y-2 mb-6">
  {#for o in overrides}
    <div class="card bg-base-100 border border-base-300"><div class="card-body py-3 gap-1">
      <p><strong>{o.overrideDate}</strong> &middot; {#if o.meetingTypeId}type #{o.meetingTypeId}{#else}{adm:adm_dateOverrides_global}{/if}</p>
      {#if o.windows.isEmpty()}
        <p><span class="badge badge-ghost badge-sm">{adm:adm_dateOverrides_badge_day_off}</span> ({adm:adm_common_blocked})</p>
      {#else}
        <ul class="list-disc ms-5">{#for w in o.windows}<li>{w.startTime} &ndash; {w.endTime}</li>{/for}</ul>
      {/if}
      <form method="post" action="/me/date-overrides/{o.id}/delete"><input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}"><button type="submit" class="btn btn-error btn-outline btn-sm">{adm:adm_dateOverrides_btn_delete}</button></form>
    </div></div>
  {/for}
  </div>
```

with:

```html
  <div class="space-y-2 mb-4">
  {#for o in upcoming}
    {#include AdminResource/_dateOverrideCard o=o /}
  {/for}
  </div>

  {! GH #168: past overrides are kept, not deleted, but start folded so the upcoming ones are
     what the page leads with. Native <details> — no JS, works with scripting off. !}
  {#if !past.isEmpty()}
  <details id="past-overrides" class="collapse collapse-arrow bg-base-100 border border-base-300 mb-6">
    <summary class="collapse-title font-semibold">{adm:adm_dateOverrides_past_summary(past.size)}</summary>
    <div class="collapse-content">
      <div class="space-y-2">
      {#for o in past}
        {#include AdminResource/_dateOverrideCard o=o /}
      {/for}
      </div>
    </div>
  </details>
  {/if}
```

The `{#if !past.isEmpty()}` guard means an owner with no history sees no empty collapse. daisyUI 5's `collapse` supports the `<details>`/`<summary>` form natively, which is why no `<input type="checkbox">` is needed here (the accordion in `meetingTypeDetail.html` uses the checkbox form; both are valid daisyUI).

- [ ] **Step 4: Compile and let Qute type-check the templates**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw -q compile
```

Expected: `BUILD SUCCESS`. Qute validates `@CheckedTemplate` parameters at build time, so a name mismatch between Task 2's Java signature and the template's `{@...}` declarations fails here with a message naming the parameter. The message key already exists (Task 1), so this must be fully green before you commit.

- [ ] **Step 5: Format and commit Tasks 2 + 3 together**

The Java signature change and the template change invalidate each other, so
they are one commit — never commit a tree that does not build; `git bisect`
and CI both assume every commit compiles.

```bash
./mvnw spotless:apply
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/dateOverrides.html \
        src/main/resources/templates/AdminResource/_dateOverrideCard.html
git commit -m "feat(overrides): split date overrides into upcoming and past

GH #168: the page listed every override ever created in one flat list, so a
host with many of them could not find the ones that still matter. Split on
today in the owner's timezone; past ones move into a folded section instead
of being deleted. Card markup extracted to a partial so it exists once."
```

---

## Task 4: Tests for the split

`<details>` keeps its content in the HTML, so a plain `containsString` on a past date passes whether or not the split works. Every assertion below therefore checks WHERE in the document the date lands, relative to the `id="past-overrides"` marker.

**Files:**
- Modify: `src/test/java/site/asm0dey/calit/web/AdminDateOverridesTest.java`

**Interfaces:**
- Consumes: `FormAuth.login()` (existing test helper, returns the `quarkus-credential` cookie value); the admin owner is always id 1 (`DatabaseResetCallback` invariant); `owner_settings` is truncated per test and never seeded, so `ownerZone()` returns `"UTC"` and "today" in these tests is `LocalDate.now(ZoneOffset.UTC)`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing tests**

Append these imports to the existing import block and these three tests to `AdminDateOverridesTest`:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
```

```java
    private static final String PAST_MARKER = "id=\"past-overrides\"";

    /** Seeds one global day-off override on the given date for owner 1. */
    @Transactional
    void seedOverrideOn(LocalDate date) {
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = null;
        o.overrideDate = date;
        o.windows = new java.util.ArrayList<>();
        o.persist();
    }

    private String pageBody() {
        return given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/date-overrides")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();
    }

    @Test
    void pastOverridesRenderInsideTheCollapsedSectionAndUpcomingOnesAboveIt() {
        // Owner 1 has no owner_settings row in tests, so the page's "today" is UTC today.
        // +/-30 days keeps both sides of the split unambiguous under any timezone.
        LocalDate future = LocalDate.now(ZoneOffset.UTC).plusDays(30);
        LocalDate history = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        seedOverrideOn(future);
        seedOverrideOn(history);

        String body = pageBody();
        int marker = body.indexOf(PAST_MARKER);
        assertTrue(marker >= 0, "expected the past-overrides collapse to be rendered");

        String beforeCollapse = body.substring(0, marker);
        String insideCollapse = body.substring(marker);

        assertTrue(beforeCollapse.contains(future.toString()), "upcoming override must render above the collapse");
        assertFalse(beforeCollapse.contains(history.toString()), "past override must not render above the collapse");
        assertTrue(insideCollapse.contains(history.toString()), "past override must render inside the collapse");
    }

    @Test
    void todaysOverrideCountsAsUpcoming() {
        // An override for today still governs today's bookable slots, so it belongs above the fold.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        seedOverrideOn(today);

        String body = pageBody();
        int marker = body.indexOf(PAST_MARKER);
        String beforeCollapse = marker >= 0 ? body.substring(0, marker) : body;

        assertTrue(beforeCollapse.contains(today.toString()), "today's override must be treated as upcoming");
    }

    @Test
    void noCollapseIsRenderedWhenThereAreNoPastOverrides() {
        seedOverrideOn(LocalDate.now(ZoneOffset.UTC).plusDays(30));

        assertFalse(pageBody().contains(PAST_MARKER), "an owner with no past overrides gets no empty collapse");
    }
```

- [ ] **Step 2: Run the new tests and verify they pass**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=AdminDateOverridesTest
```

Expected: PASS — Tasks 1-3 already implemented the behaviour, so these tests confirm it rather than drive it. **Verify they are real tests, not tautologies:** temporarily change `!o.overrideDate.isBefore(today)` to `true` in `AdminResource.dateOverridesInstance()`, re-run, and confirm `pastOverridesRenderInsideTheCollapsedSectionAndUpcomingOnesAboveIt` FAILS with "past override must not render above the collapse". Then revert that edit. A test that cannot fail is not a test.

- [ ] **Step 3: Run the full suite**

```bash
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Pay attention to these three, which touch the same surface:
- `CsrfFormCoverageTest` — the new `_dateOverrideCard.html` has one POST form and one token.
- `MultiHostMessageParityTest` — Task 1's key exists in both locale files.
- `AdminI18nTest` — it exercises `/me/date-overrides` in a non-English locale.

If `AdminI18nTest` fails on a missing/garbled string, the cause is a typo in a `.properties` line from Task 1, not the split.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/site/asm0dey/calit/web/AdminDateOverridesTest.java
git commit -m "test(overrides): pin the upcoming/past split on /me/date-overrides"
```

---

## Task 5: Verify it in a browser, then document it

A green suite does not prove the collapse is usable — RestAssured cannot render CSS. Look at the page.

**Files:**
- Modify (on the **`docs-site`** branch): `docs-site/src/content/docs/releases/changelog.md`
- Modify (on the **`docs-site`** branch): `docs-site/src/content/docs/usage/availability.md`

**Interfaces:**
- Consumes: the shipped behaviour from Tasks 1-4.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Run the app and look at the page**

```bash
bun run css:build        # /calit.css is gitignored; without this the page renders unstyled
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw quarkus:dev -Dgoogle.oauth.client-id=dummy -Dgoogle.oauth.client-secret=dummy
```

Docker must be running. Open `http://localhost:8080`, complete `/setup` if the DB is fresh, then create a handful of overrides — some dated in the past, some in the future — at `/me/date-overrides`.

Confirm by eye:
1. Future-dated overrides are listed at the top, soonest first.
2. A single collapsed bar reads "Past overrides (N)" with the right N.
3. Clicking it expands to the past overrides, most recent first.
4. Delete still works from inside the expanded section.
5. With every override in the future, no collapse bar appears at all.

- [ ] **Step 2: Confirm it works with JavaScript disabled**

In the browser devtools, disable JavaScript, reload `/me/date-overrides`, and confirm the collapse still opens and closes. `<details>` is native HTML; if it does not toggle, something turned it into a JS widget and that is a bug to fix before merging.

- [ ] **Step 3: Add the changelog bullet on `docs-site`**

Changelog entries land at merge, under `## Unreleased` — not at release time.

```bash
git switch docs-site
git pull --ff-only
```

In `docs-site/src/content/docs/releases/changelog.md`, add this as the FIRST bullet under the existing `## Unreleased` heading (keep the "Merged but not yet in a tagged release." subtitle line above it):

```markdown
- **Past date overrides no longer clutter the overrides page.** A host with a
  long history of date overrides saw every one of them, past and future, in a
  single flat list, which made the ones that still mattered hard to find. The
  overrides page now leads with upcoming overrides, soonest first, and folds
  everything before today into a collapsed **Past overrides (N)** section,
  newest first. Nothing is deleted — the old overrides are one click away, and
  the section only appears once you actually have some. "Today" is your own
  configured timezone, and an override dated today still counts as upcoming.
  ([#PR](https://github.com/asm0dey/calit/pull/PR))
```

Replace both `PR` placeholders with the real pull-request number once the PR exists (Task 6 Step 3). Do not leave the literal text `PR` in the file.

- [ ] **Step 4: Document the behaviour in the usage docs**

In `docs-site/src/content/docs/usage/availability.md`, in the `## Date overrides` section, after the line that begins "Like rules, overrides can be global or scoped to a single meeting type.", add:

```markdown
The overrides page lists upcoming overrides first, soonest first. Overrides
dated before today are folded into a collapsed **Past overrides** section at
the bottom of the list — they are kept, not deleted, and expand with one
click. An override dated today still counts as upcoming, and "today" is read
in the timezone configured in your account settings.
```

- [ ] **Step 5: Commit the docs and return to the feature branch**

```bash
git add docs-site/src/content/docs/releases/changelog.md \
        docs-site/src/content/docs/usage/availability.md
git commit -m "docs: past date overrides fold into a collapsed section"
git switch feat/past-date-overrides
```

Do not push `docs-site` yet — Step 3's PR number is still a placeholder. Task 6 Step 3 fills it in and pushes.

The `date-overrides.png` screenshot in `availability.md` now shows a stale layout. Refreshing it is worth doing but is a separate, screenshot-only change (there is precedent: commit `fe934b9`, "docs screenshot refresh"); Task 6 files it as a follow-up bean rather than mixing binary assets into this PR.

---

## Task 6: Ship it

**Files:**
- Modify: the bean file under `.beans/`

**Interfaces:**
- Consumes: everything above.
- Produces: an open pull request against `main`, and two follow-up beans.

- [ ] **Step 1: Final full-suite run**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw verify
```

`verify` (not just `test`) also runs `spotless:check` and the `*IT` failsafe tests — the same gate CI applies. Expected: `BUILD SUCCESS`. **A red suite is not a PR.** If `spotless:check` fails, run `./mvnw spotless:apply`, amend, and re-run.

- [ ] **Step 2: Close out the bean**

Tick every todo item and append the summary:

```bash
beans update calit-i3wt -s completed --body-append "## Summary of Changes

- \`AdminResource.dateOverridesInstance()\` replaces \`overridesWithWindows()\` and splits the owner's overrides on today in \`ownerZoneId()\` — upcoming (date >= today) ascending, past descending. GET, create-POST and delete-POST all render through it.
- \`templates/AdminResource/_dateOverrideCard.html\` extracted so the card markup is written once; \`dateOverrides.html\` includes it from both loops and wraps the past loop in a native \`<details id=\"past-overrides\">\` daisyUI collapse, rendered only when past overrides exist.
- New \`adm_dateOverrides_past_summary(int count)\` key with German and Hebrew values.
- Three tests in \`AdminDateOverridesTest\` assert placement relative to the collapse marker, not mere substring presence (\`<details>\` content is in the DOM either way).
- docs-site: \`## Unreleased\` changelog bullet + a paragraph in \`usage/availability.md\`."
```

Use the todo-ticking form for each line, e.g.:

```bash
beans update calit-i3wt --body-replace-old "- [ ] Split overrides into upcoming/past in AdminResource" --body-replace-new "- [x] Split overrides into upcoming/past in AdminResource"
```

Repeat for each of the six items, then commit the bean file:

```bash
git add .beans
git commit -m "chore(beans): complete GH #168 past date overrides"
```

- [ ] **Step 3: Open the PR**

```bash
git push -u origin feat/past-date-overrides
```

Generate a diagram of the split with the `show-me` skill and put it in the PR body — this project's PRs have readers beyond the author. Then:

```bash
gh pr create --base main --title "feat(overrides): fold past date overrides into a collapsed section" --body "$(cat <<'BODY'
Closes #168.

## What

`/me/date-overrides` listed every override the owner had ever created in one
flat list ordered global-first-then-by-date, so a host with many of them could
not find the ones that still matter. The page now leads with upcoming
overrides (soonest first) and folds everything dated before today into a
collapsed `Past overrides (N)` section (most recent first). Nothing is
deleted, matching what the reporter asked for: *"hide them, rather than
delete them, as they may still be useful to someone."*

- "Today" is read in the **owner's** configured timezone; a garbage or unset
  timezone falls back to UTC rather than 500ing the page.
- An override dated **today** counts as upcoming — it still governs today's
  bookable slots.
- The collapse is a native `<details>`, so it works with JavaScript off.
- The section is omitted entirely when there is no history.

## Diagram

<!-- paste the show-me diagram here -->

## Out of scope

The per-meeting-type override list on the meeting-type detail page is already
behind a collapsed accordion and is scoped to one type, so it does not produce
the same wall. Tracked as a follow-up bean.

## Testing

`mvn verify` green. Three new tests in `AdminDateOverridesTest` assert
placement relative to the `id="past-overrides"` marker rather than substring
presence — `<details>` keeps its content in the DOM, so a naive
`containsString` would pass even with the feature broken.

## Docs

Changelog `## Unreleased` bullet and a `usage/availability.md` paragraph are
on the `docs-site` branch.
BODY
)"
```

Then fill the real PR number into the two `PR` placeholders in the `docs-site` changelog bullet and push that branch:

```bash
git switch docs-site
# edit changelog.md: replace both PR placeholders with the real number
git commit -am "docs(changelog): link the past-overrides PR"
git push
git switch feat/past-date-overrides
```

- [ ] **Step 4: File the two follow-ups**

```bash
beans create "Fold past overrides on the meeting-type detail page too" -t task -s todo -p low -d "GH #168 fixed /me/date-overrides but left the per-type override list in templates/AdminResource/meetingTypeDetail.html unsplit. It is already inside a collapsed accordion and scoped to one type, so it is a smaller problem — but the same _dateOverrideCard.html partial and the same adm_dateOverrides_past_summary key can be reused. detailInstance() would split overridesForType(id) the same way dateOverridesInstance() splits the global list."

beans create "Refresh the date-overrides docs screenshot" -t task -s todo -p low -d "docs-site/src/content/docs/usage/availability.md embeds /calit/img/date-overrides.png, which predates the upcoming/past split. Retake it with a few upcoming overrides and a collapsed 'Past overrides (N)' bar visible. Screenshot-only change, kept out of the feature PR — precedent: commit fe934b9."
```

---

## Notes for the reviewer

- **Ordering changed on purpose.** The old query ordered by `meetingTypeId nulls first, overrideDate`, which interleaved dates. Since the page's whole job after this change is "what is coming up", date order is the ordering that answers it; each card still labels itself global or per-type.
- **One query, split in memory.** Two queries (`>= today` / `< today`) would also work, but the row count is per-owner and small, and one fetch keeps `withWindows` to a single extra query for the entire page instead of two.
- **No `Clock` injection.** Tests seed dates relative to `LocalDate.now(ZoneOffset.UTC)` at ±30 days, which is unambiguous under any timezone; a `Clock` bean would be machinery this feature does not need.
- **Card markup duplication was avoided, not tolerated.** The `_dateOverrideCard.html` partial follows the established `_workplanGrid.html` / `_meetingtypecard.html` convention in this codebase.
