# Meeting-Type Admin Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the calit admin UI: humanize enum labels, give location tiles equal size, expose per-meeting-type buffers, auto-generate slugs from the name, and move per-type booking fields and per-type working-time overrides into a new meeting-type detail/edit page.

**Architecture:** Quarkus REST + Qute server-rendered HTML, Hibernate Panache active-record entities, Flyway/Postgres. All required DB columns (`buffer_before_minutes`, `buffer_after_minutes`, `slug`, `meeting_type_id` on `booking_field`/`availability_rule`/`date_override`) already exist — **no new migration**. A new `meetingTypeDetail.html` page plus handlers on `AdminResource` host per-type booking fields, weekly availability rules, and date overrides (these already support a nullable `meetingTypeId`). Enum humanization is a single Qute `@TemplateExtension`. Form values keep raw enum constants (e.g. `GOOGLE_MEET`); only the human-facing label changes.

**Tech Stack:** Java 21+, Quarkus 3.36, Qute (`@CheckedTemplate`), Hibernate ORM Panache, RESTEasy Reactive (`@RestForm`), Pico CSS v2, JUnit 5 + RestAssured.

**Decisions captured from brainstorming:**
- Booking fields → managed **inside the meeting type** (detail page). The standalone `/admin/booking-fields` page is repurposed to manage **global default** fields only.
- Working-time override → **surfaced in the type editor** (detail page), shared `/admin/availability` and `/admin/date-overrides` pages kept as-is.
- Slug → optional input with **JS live-fill from name** + **server-side slugify + uniqueness** when left blank.

**Conventions to follow (from existing code):**
- Unchecked checkboxes send no value; handlers test `"on".equals(param)`.
- Web tests authenticate with `FormAuth.login()` → `.cookie("quarkus-credential", ...)`.
- Form POSTs return the re-rendered `TemplateInstance` (no redirect) and assert `statusCode(200)`.
- Auth-guarded GET without cookie returns `302`.

---

## File Structure

**New files:**
- `src/main/java/com/calit/web/DisplayExtensions.java` — Qute extension humanizing enum constants.
- `src/main/java/com/calit/domain/Slugs.java` — slugify + DB-unique slug helper.
- `src/main/resources/templates/AdminResource/meetingTypeDetail.html` — per-type detail/edit page (basics, booking fields, working hours, date overrides).
- `src/test/java/com/calit/web/DisplayExtensionsTest.java`
- `src/test/java/com/calit/domain/SlugsTest.java`
- `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java`
- `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java`

**Modified files:**
- `src/main/java/com/calit/web/AdminResource.java` — buffer params, slug autogen, scope standalone booking-fields to global, new detail handlers, updated `Templates` signatures.
- `src/main/resources/templates/AdminResource/meetingTypes.html` — buffer inputs, optional slug + JS live-fill, humanized location label, Edit link, buffer display.
- `src/main/resources/templates/AdminResource/bookingFields.html` — global-only relabel, drop "Applies to", humanized type options/list.
- `src/main/resources/templates/AdminResource/availability.html` — humanized day options/list.
- `src/main/resources/META-INF/resources/calit.css` — equal-size location tiles.

---

## Task 1: Humanize enum labels in the UI

**Files:**
- Create: `src/main/java/com/calit/web/DisplayExtensions.java`
- Create: `src/test/java/com/calit/web/DisplayExtensionsTest.java`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (the `Templates.bookingFields` and `Templates.availability` signatures + their handlers)
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` (location tile labels + list line)
- Modify: `src/main/resources/templates/AdminResource/bookingFields.html` (type options + list)
- Modify: `src/main/resources/templates/AdminResource/availability.html` (day options + list)

- [ ] **Step 1: Write the failing unit test for the humanizer**

Create `src/test/java/com/calit/web/DisplayExtensionsTest.java`:

```java
package com.calit.web;

import com.calit.domain.BookingField.FieldType;
import com.calit.domain.MeetingType.LocationType;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayExtensionsTest {

    @Test
    void humanizesUpperSnakeEnums() {
        assertEquals("Google Meet", DisplayExtensions.display(LocationType.GOOGLE_MEET));
        assertEquals("In Person", DisplayExtensions.display(LocationType.IN_PERSON));
        assertEquals("Phone", DisplayExtensions.display(LocationType.PHONE));
        assertEquals("Long Text", DisplayExtensions.display(FieldType.LONG_TEXT));
        assertEquals("Short Text", DisplayExtensions.display(FieldType.SHORT_TEXT));
        assertEquals("Monday", DisplayExtensions.display(DayOfWeek.MONDAY));
    }

    @Test
    void nullRendersAsEmptyString() {
        assertEquals("", DisplayExtensions.display(null));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DisplayExtensionsTest`
Expected: FAIL — compile error, `DisplayExtensions` does not exist.

- [ ] **Step 3: Implement the Qute template extension**

Create `src/main/java/com/calit/web/DisplayExtensions.java`:

```java
package com.calit.web;

import io.quarkus.qute.TemplateExtension;

/**
 * Qute template extensions that humanize UPPER_SNAKE_CASE enum constants for display:
 * GOOGLE_MEET -> "Google Meet", LONG_TEXT -> "Long Text", MONDAY -> "Monday".
 *
 * <p>Only the human-facing label changes; form &lt;option&gt;/radio VALUES keep the raw
 * enum constant (e.g. value="GOOGLE_MEET") so server-side {@code Enum.valueOf} still works.
 * Usage in a template: {@code {someEnumValue.display}}.
 */
@TemplateExtension
public class DisplayExtensions {

    /** Title-cases an enum's {@code name()} into space-separated words. Null -> "". */
    public static String display(Enum<?> e) {
        if (e == null) {
            return "";
        }
        String[] words = e.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=DisplayExtensionsTest`
Expected: PASS.

- [ ] **Step 5: Add `FieldType[]` to the booking-fields template and `DayOfWeek[]` to the availability template**

These templates currently hardcode `<option>` text. To humanize them we loop the enum values. Update the `Templates` inner class and handlers in `src/main/java/com/calit/web/AdminResource.java`.

Replace the `bookingFields` native method declaration (currently lines 53-54):

```java
        public static native TemplateInstance bookingFields(
                List<BookingField> fields, BookingField.FieldType[] fieldTypes, Long pendingCount);
```

Replace the `availability` native method declaration (currently lines 45-46):

```java
        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<MeetingType> types,
                DayOfWeek[] daysOfWeek, Long pendingCount);
```

Update the three availability handlers to pass `DayOfWeek.values()`. In `availability()` (currently lines 149-153):

```java
    public TemplateInstance availability() {
        return Templates.availability(
                AvailabilityRule.listAll(),
                MeetingType.listAll(), DayOfWeek.values(), pendingCount());
    }
```

In `createRule(...)` replace the return (currently lines 171-173):

```java
        return Templates.availability(
                AvailabilityRule.<AvailabilityRule>listAll(),
                MeetingType.listAll(), DayOfWeek.values(), pendingCount());
```

In `deleteRule(...)` replace the return (currently lines 183-185):

```java
        return Templates.availability(
                AvailabilityRule.<AvailabilityRule>listAll(),
                MeetingType.listAll(), DayOfWeek.values(), pendingCount());
```

Update the three booking-field handlers to pass global-only list + `FieldType.values()`. In `bookingFields()` (currently lines 231-235):

```java
    public TemplateInstance bookingFields() {
        return Templates.bookingFields(
                BookingField.list("meetingTypeId is null order by position"),
                BookingField.FieldType.values(), pendingCount());
    }
```

In `createBookingField(...)` replace its return (currently lines 257-259):

```java
        return Templates.bookingFields(
                BookingField.list("meetingTypeId is null order by position"),
                BookingField.FieldType.values(), pendingCount());
```

In `deleteBookingField(...)` replace its return (currently lines 269-271):

```java
        return Templates.bookingFields(
                BookingField.list("meetingTypeId is null order by position"),
                BookingField.FieldType.values(), pendingCount());
```

> Note: scoping the standalone booking-fields page to globals is finalized in Task 6; here we only change the template-data plumbing so option text can be humanized. `createBookingField`'s `meetingTypeId` param is still accepted (removed in Task 6).

- [ ] **Step 6: Humanize the booking-fields template**

In `src/main/resources/templates/AdminResource/bookingFields.html`, replace the parameter declarations at the top (currently lines 1-3):

```html
{@java.util.List<com.calit.domain.BookingField> fields}
{@com.calit.domain.BookingField.FieldType[] fieldTypes}
{@java.lang.Long pendingCount}
```

Replace the list-line that prints the raw type (currently line 13) with:

```html
      <p><code>{f.fieldKey}</code> &middot; {f.type.display} &middot; position {f.position}
        &middot; {#if f.meetingTypeId}type #{f.meetingTypeId}{#else}global{/if}</p>
```

Replace the Type `<select>` (currently lines 25-30) with a loop over the enum:

```html
    <label>Type
      <select name="type">
        {#for ft in fieldTypes}<option value="{ft}">{ft.display}</option>{/for}
      </select>
    </label>
```

- [ ] **Step 7: Humanize the availability template**

In `src/main/resources/templates/AdminResource/availability.html`, replace the parameter declarations (currently lines 1-3):

```html
{@java.util.List<com.calit.domain.AvailabilityRule> rules}
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.time.DayOfWeek[] daysOfWeek}
{@java.lang.Long pendingCount}
```

Replace the rule list-line printing the raw day (currently lines 9-10):

```html
      <p><strong>{r.dayOfWeek.display}</strong> {r.startTime} &ndash; {r.endTime}
        &middot; {#if r.meetingTypeId}type #{r.meetingTypeId}{#else}global{/if}</p>
```

Replace the Day `<select>` (currently lines 19-24) with a loop:

```html
    <label>Day
      <select name="dayOfWeek">
        {#for d in daysOfWeek}<option value="{d}">{d.display}</option>{/for}
      </select>
    </label>
```

- [ ] **Step 8: Humanize the meeting-types location label**

In `src/main/resources/templates/AdminResource/meetingTypes.html`, replace the type summary line (currently line 14) so the location type is humanized:

```html
      <p>/{t.slug} &middot; {t.durationMinutes} min &middot; {t.locationType.display}{#if t.locationDetail} ({t.locationDetail}){/if}</p>
```

Replace the two location tile label outputs `{lt}` (currently the bare `{lt}` on line 75) with `{lt.display}`:

```html
              {lt.display}
```

> Leave the radio `value="{lt}"` (line 61) untouched — the form must still POST the raw constant.

- [ ] **Step 9: Verify humanized labels render (and raw values are preserved)**

Add these tests to `src/test/java/com/calit/web/DisplayExtensionsTest.java`? No — those are web assertions. Instead extend the existing `AdminBookingFieldsTest` and `AdminAvailabilityTest` is unnecessary; verify by running the full existing suites which already assert raw values still appear:

Run: `./mvnw test -Dtest=AdminBookingFieldsTest,AdminAvailabilityTest,AdminMeetingTypesTest`
Expected: PASS — these assert `name="type"`, `containsString("TUESDAY")` (option value), `value="GOOGLE_MEET"` (radio value); all still present because only label text changed.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/calit/web/DisplayExtensions.java \
        src/test/java/com/calit/web/DisplayExtensionsTest.java \
        src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypes.html \
        src/main/resources/templates/AdminResource/bookingFields.html \
        src/main/resources/templates/AdminResource/availability.html
git commit -m "feat: humanize enum labels in admin UI (keep raw form values)"
```

---

## Task 2: Equal-size location tiles

**Files:**
- Modify: `src/main/resources/META-INF/resources/calit.css:217-223`
- Modify: `src/test/java/com/calit/web/AdminMeetingTypesTest.java` (add one assertion)

- [ ] **Step 1: Write the failing test that the served CSS sizes tiles**

Add this test method to `src/test/java/com/calit/web/AdminMeetingTypesTest.java` (inside the class):

```java
    @Test
    void locationTilesHaveEqualFixedHeight() {
        given()
            .when().get("/calit.css")
            .then()
                .statusCode(200)
                .body(containsString(".loc-tiles .tile"))
                .body(containsString("min-height"))           // tiles get a fixed minimum height
                .body(containsString("justify-content: center")); // content vertically centered
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#locationTilesHaveEqualFixedHeight`
Expected: FAIL — current `.loc-tiles .tile` has neither `min-height` nor `justify-content: center`.

- [ ] **Step 3: Update the CSS so all tiles share one size**

In `src/main/resources/META-INF/resources/calit.css`, replace the `.loc-tiles` grid rule (currently line 218):

```css
.loc-tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr)); gap: .6rem; margin-bottom: .6rem; align-items: stretch; }
```

Replace the `.loc-tiles .tile` rule (currently lines 219-223):

```css
.loc-tiles .tile {
  border: 1px solid var(--pico-muted-border-color); border-radius: 10px; padding: .85rem .5rem;
  text-align: center; cursor: pointer; font-size: .85rem; font-weight: 600;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: .4rem; margin: 0;
  min-height: 5.5rem;
}
```

> `align-items: stretch` on the grid makes every tile fill the row's height; `min-height` guarantees a baseline; `justify-content: center` keeps the icon+label vertically centered so a one-word label and a two-word label render the same height.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#locationTilesHaveEqualFixedHeight`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/META-INF/resources/calit.css \
        src/test/java/com/calit/web/AdminMeetingTypesTest.java
git commit -m "style: give Location picker tiles equal fixed size"
```

---

## Task 3: Buffers (before/after) on the meeting-type create form

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java:98-123` (`createMeetingType`)
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` (Duration section + list line)
- Create: `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java`

The entity (`MeetingType.bufferBeforeMinutes` / `bufferAfterMinutes`) and DB columns already exist; only the form + handler are missing.

- [ ] **Step 1: Write the failing test for buffer persistence**

Create `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java`:

```java
package com.calit.web;

import com.calit.domain.MeetingType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class AdminMeetingTypeFormTest {

    @Test
    void createFormExposesBufferInputs() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("name=\"bufferBeforeMinutes\""))
                .body(containsString("name=\"bufferAfterMinutes\""));
    }

    @Test
    void createPersistsSeparateBuffers() {
        String slug = "buffers-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Buffered Call")
            .formParam("slug", slug)
            .formParam("durationMinutes", "30")
            .formParam("bufferBeforeMinutes", "10")
            .formParam("bufferAfterMinutes", "15")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types")
            .then().statusCode(200);

        MeetingType t = MeetingType.findBySlug(slug);
        assertNotNull(t);
        assertEquals(10, t.bufferBeforeMinutes);
        assertEquals(15, t.bufferAfterMinutes);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=AdminMeetingTypeFormTest`
Expected: FAIL — form has no buffer inputs; handler ignores buffers (both 0).

- [ ] **Step 3: Add buffer params to the create handler**

In `src/main/java/com/calit/web/AdminResource.java`, add the import near the other `jakarta.ws.rs` imports:

```java
import jakarta.ws.rs.DefaultValue;
```

Replace the `createMeetingType` signature (currently lines 98-107) to add two buffer params with a default of 0 (so the existing tests that omit them still pass):

```java
    public TemplateInstance createMeetingType(@RestForm String name,
                                              @RestForm String slug,
                                              @RestForm int durationMinutes,
                                              @RestForm @DefaultValue("0") int bufferBeforeMinutes,
                                              @RestForm @DefaultValue("0") int bufferAfterMinutes,
                                              @RestForm String secret,
                                              @RestForm int minNoticeMinutes,
                                              @RestForm int horizonDays,
                                              @RestForm String locationType,
                                              @RestForm String locationDetail,
                                              @RestForm String slotIntervalMinutes,
                                              @RestForm String requiresApproval) {
```

Inside the method body, after `t.durationMinutes = durationMinutes;` (currently line 111), add:

```java
        t.bufferBeforeMinutes = bufferBeforeMinutes;
        t.bufferAfterMinutes = bufferAfterMinutes;
```

- [ ] **Step 4: Add buffer inputs to the form and show them in the list**

In `src/main/resources/templates/AdminResource/meetingTypes.html`, inside the Duration section `<div class="sec-body">` (currently lines 45-49), add the two inputs after the slot-interval label:

```html
        <label>Buffer before (minutes) <input type="number" name="bufferBeforeMinutes" value="0" min="0"></label>
        <label>Buffer after (minutes) <input type="number" name="bufferAfterMinutes" value="0" min="0"></label>
```

In the per-type listing, replace the second `<p>` (currently line 15) so buffers are visible:

```html
      <p>min notice {t.minNoticeMinutes} min &middot; horizon {t.horizonDays} days{#if t.slotIntervalMinutes} &middot; slot interval {t.slotIntervalMinutes} min{/if}
        &middot; buffer {t.bufferBeforeMinutes}/{t.bufferAfterMinutes} min</p>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AdminMeetingTypeFormTest`
Expected: PASS.

- [ ] **Step 6: Run the existing meeting-types suite to confirm no regression**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest`
Expected: PASS — older create tests omit buffers; `@DefaultValue("0")` keeps them at 0.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypes.html \
        src/test/java/com/calit/web/AdminMeetingTypeFormTest.java
git commit -m "feat: per-type buffer before/after inputs on meeting-type form"
```

---

## Task 4: Auto-generate slug from name

**Files:**
- Create: `src/main/java/com/calit/domain/Slugs.java`
- Create: `src/test/java/com/calit/domain/SlugsTest.java`
- Modify: `src/main/java/com/calit/web/AdminResource.java:108-110` (`createMeetingType` slug assignment)
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` (slug input + JS)
- Modify: `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java` (add slug tests)

- [ ] **Step 1: Write the failing unit test for slugify**

Create `src/test/java/com/calit/domain/SlugsTest.java`:

```java
package com.calit.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlugsTest {

    @Test
    void slugifyLowercasesAndHyphenates() {
        assertEquals("intro-call", Slugs.slugify("Intro Call"));
        assertEquals("intro-call", Slugs.slugify("  Intro   Call!! "));
        assertEquals("30-min-sync", Slugs.slugify("30 Min Sync"));
    }

    @Test
    void slugifyStripsAccents() {
        assertEquals("cafe-meeting", Slugs.slugify("Café Meeting"));
    }

    @Test
    void slugifyHandlesNullAndEmpty() {
        assertEquals("", Slugs.slugify(null));
        assertEquals("", Slugs.slugify("   "));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SlugsTest`
Expected: FAIL — compile error, `Slugs` does not exist.

- [ ] **Step 3: Implement the slug helper**

Create `src/main/java/com/calit/domain/Slugs.java`:

```java
package com.calit.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Slug helpers: turn a display name into a URL slug and guarantee meeting_type uniqueness. */
public final class Slugs {

    private Slugs() {}

    /** Lowercase, strip accents, collapse non-alphanumerics to single hyphens, trim hyphens. Null/blank -> "". */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String stripped = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    /**
     * Returns {@code base} (or "meeting" if blank) made unique against existing meeting_type
     * slugs by appending -2, -3, ... A row with id {@code excludeId} is ignored, so re-saving
     * a type with its own current slug is allowed.
     */
    public static String uniqueMeetingTypeSlug(String base, Long excludeId) {
        String root = (base == null || base.isBlank()) ? "meeting" : base;
        String candidate = root;
        int n = 1;
        while (slugTaken(candidate, excludeId)) {
            n++;
            candidate = root + "-" + n;
        }
        return candidate;
    }

    private static boolean slugTaken(String slug, Long excludeId) {
        MeetingType existing = MeetingType.findBySlug(slug);
        return existing != null && !existing.id.equals(excludeId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=SlugsTest`
Expected: PASS.

- [ ] **Step 5: Write the failing web tests for autogeneration + uniqueness**

Add these tests to `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java` (add the `Slugs` import: `import com.calit.domain.Slugs;`):

```java
    @Test
    void blankSlugIsGeneratedFromName() {
        String name = "Discovery Chat " + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", name)
            .formParam("slug", "") // blank -> server generates
            .formParam("durationMinutes", "30")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types")
            .then().statusCode(200);

        MeetingType t = MeetingType.findBySlug(Slugs.slugify(name));
        org.junit.jupiter.api.Assertions.assertNotNull(t);
    }

    @Test
    void duplicateGeneratedSlugGetsSuffix() {
        String name = "Repeat Topic " + System.nanoTime();
        String base = Slugs.slugify(name);
        for (int i = 0; i < 2; i++) {
            given()
                .cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", name)
                .formParam("slug", "")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when().post("/admin/meeting-types")
                .then().statusCode(200);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(MeetingType.findBySlug(base));
        org.junit.jupiter.api.Assertions.assertNotNull(MeetingType.findBySlug(base + "-2"));
    }

    @Test
    void slugInputHasLiveFillScript() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("data-slug-autofill")); // marker the JS hooks onto
    }
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=AdminMeetingTypeFormTest`
Expected: FAIL — slug taken verbatim from form (blank slug would violate `NOT NULL`/unique); no autofill marker.

- [ ] **Step 7: Apply server-side slug generation in the create handler**

In `src/main/java/com/calit/web/AdminResource.java`, add the import:

```java
import com.calit.domain.Slugs;
```

Replace the slug assignment in `createMeetingType` (currently line 110, `t.slug = slug;`) with:

```java
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(slugBase, null);
```

- [ ] **Step 8: Make the slug input optional + add the live-fill script**

In `src/main/resources/templates/AdminResource/meetingTypes.html`, replace the Name + Slug labels in the Basics section (currently lines 33-34) with:

```html
        <label>Name <input type="text" name="name" data-slug-name required></label>
        <label>Slug <small>(leave blank to auto-generate from the name)</small>
          <input type="text" name="slug" data-slug-autofill></label>
```

Immediately before the final `<button type="submit">Create</button>` (currently line 99), add the live-fill script:

```html
    <script>
    (function () {
      var form = document.currentScript.closest('form');
      var name = form.querySelector('[data-slug-name]');
      var slug = form.querySelector('[data-slug-autofill]');
      var edited = false;
      slug.addEventListener('input', function () { edited = true; });
      name.addEventListener('input', function () {
        if (edited) { return; }
        slug.value = name.value.toLowerCase().normalize('NFD')
          .replace(/[̀-ͯ]/g, '')
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/^-+|-+$/g, '');
      });
    })();
    </script>
```

> The JS mirrors `Slugs.slugify`. It stops auto-filling once the admin manually edits the slug. The server still slugifies + de-duplicates on submit, so a JS-disabled client or a blank field is handled correctly.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=AdminMeetingTypeFormTest,SlugsTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/calit/domain/Slugs.java \
        src/test/java/com/calit/domain/SlugsTest.java \
        src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypes.html \
        src/test/java/com/calit/web/AdminMeetingTypeFormTest.java
git commit -m "feat: auto-generate meeting-type slug from name (server + JS live-fill)"
```

---

## Task 5: Meeting-type detail/edit page (scaffold + basics edit)

This page is the new home for per-type booking fields (Task 6) and working-time overrides (Task 7). This task builds the page shell and a basics-edit form.

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java` (`Templates` + new handlers + helpers)
- Create: `src/main/resources/templates/AdminResource/meetingTypeDetail.html`
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` (Edit link)
- Create: `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java`

- [ ] **Step 1: Write the failing tests for the detail page + basics edit**

Create `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java`:

```java
package com.calit.web;

import com.calit.domain.MeetingType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AdminMeetingTypeDetailTest {

    @Transactional
    Long seedType(String slug) {
        MeetingType t = new MeetingType();
        t.name = "Detail Seed"; t.slug = slug; t.durationMinutes = 30;
        t.persist();
        return t.id;
    }

    @Test
    void detailPageRendersSectionsAndEditForm() {
        Long id = seedType("detail-render-" + System.nanoTime());
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types/" + id)
            .then()
                .statusCode(200)
                .body(containsString("Detail Seed"))
                .body(containsString("Booking fields"))     // section heading
                .body(containsString("Working hours"))       // section heading
                .body(containsString("Date overrides"))      // section heading
                .body(containsString("name=\"name\""))       // basics edit form
                .body(containsString("name=\"bufferBeforeMinutes\""));
    }

    @Test
    void detailPageRequiresAuth() {
        Long id = seedType("detail-auth-" + System.nanoTime());
        given().redirects().follow(false)
            .when().get("/admin/meeting-types/" + id)
            .then().statusCode(302);
    }

    @Test
    void editPersistsBasicsAndBuffers() {
        Long id = seedType("detail-edit-" + System.nanoTime());
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Renamed Type")
            .formParam("slug", "")  // blank -> regenerate from name
            .formParam("durationMinutes", "45")
            .formParam("bufferBeforeMinutes", "5")
            .formParam("bufferAfterMinutes", "20")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "PHONE")
            .formParam("locationDetail", "+1-555-0123")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types/" + id + "/edit")
            .then().statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertEquals("Renamed Type", t.name);
        assertEquals("renamed-type", t.slug);
        assertEquals(45, t.durationMinutes);
        assertEquals(5, t.bufferBeforeMinutes);
        assertEquals(20, t.bufferAfterMinutes);
        assertEquals(MeetingType.LocationType.PHONE, t.locationType);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=AdminMeetingTypeDetailTest`
Expected: FAIL — no `/admin/meeting-types/{id}` route, template missing.

- [ ] **Step 3: Add the detail template native method and handlers**

In `src/main/java/com/calit/web/AdminResource.java`, add to the `Templates` inner class (after the `meetingTypes` declaration, around line 43):

```java
        public static native TemplateInstance meetingTypeDetail(
                MeetingType type,
                List<BookingField> fields,
                List<AvailabilityRule> rules,
                List<DateOverride> overrides,
                LocationType[] locationTypes,
                BookingField.FieldType[] fieldTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount);
```

Add these private helpers (place them near `overridesWithWindows`, around line 285):

```java
    /** Date overrides scoped to one meeting type, each with its (transient) windows loaded. */
    private List<DateOverride> overridesForType(Long typeId) {
        List<DateOverride> all = DateOverride.list("meetingTypeId = ?1 order by overrideDate", typeId);
        for (DateOverride o : all) {
            o.windows = DateOverrideWindow.list("dateOverrideId = ?1 order by startTime asc", o.id);
        }
        return all;
    }

    /** Re-render the detail page for one meeting type (shared by every detail-scoped handler). */
    private TemplateInstance detailInstance(Long id) {
        MeetingType t = MeetingType.findById(id);
        List<BookingField> fields = BookingField.list("meetingTypeId = ?1 order by position", id);
        List<AvailabilityRule> rules = AvailabilityRule.list("meetingTypeId = ?1 order by dayOfWeek", id);
        List<DateOverride> overrides = overridesForType(id);
        return Templates.meetingTypeDetail(t, fields, rules, overrides,
                LocationType.values(), BookingField.FieldType.values(),
                DayOfWeek.values(), pendingCount());
    }
```

Add the GET + edit handlers (place after `deleteMeetingType`, around line 144):

```java
    @GET
    @Path("/meeting-types/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance meetingTypeDetail(@PathParam("id") Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t == null) {
            throw new jakarta.ws.rs.NotFoundException("No meeting type " + id);
        }
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance editMeetingType(@PathParam("id") Long id,
                                            @RestForm String name,
                                            @RestForm String slug,
                                            @RestForm int durationMinutes,
                                            @RestForm @DefaultValue("0") int bufferBeforeMinutes,
                                            @RestForm @DefaultValue("0") int bufferAfterMinutes,
                                            @RestForm String secret,
                                            @RestForm int minNoticeMinutes,
                                            @RestForm int horizonDays,
                                            @RestForm String locationType,
                                            @RestForm String locationDetail,
                                            @RestForm String slotIntervalMinutes,
                                            @RestForm String requiresApproval) {
        MeetingType t = MeetingType.findById(id);
        t.name = name;
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(slugBase, id);
        t.durationMinutes = durationMinutes;
        t.bufferBeforeMinutes = bufferBeforeMinutes;
        t.bufferAfterMinutes = bufferAfterMinutes;
        t.secret = "on".equals(secret);
        t.minNoticeMinutes = minNoticeMinutes;
        t.horizonDays = horizonDays;
        t.locationType = LocationType.valueOf(locationType);
        t.locationDetail = (locationDetail == null || locationDetail.isBlank()) ? null : locationDetail;
        t.slotIntervalMinutes = (slotIntervalMinutes == null || slotIntervalMinutes.isBlank())
                ? null : Integer.valueOf(slotIntervalMinutes);
        t.requiresApproval = "on".equals(requiresApproval);
        return detailInstance(id); // managed entity flushes on commit
    }
```

- [ ] **Step 4: Create the detail template**

Create `src/main/resources/templates/AdminResource/meetingTypeDetail.html`:

```html
{@com.calit.domain.MeetingType type}
{@java.util.List<com.calit.domain.BookingField> fields}
{@java.util.List<com.calit.domain.AvailabilityRule> rules}
{@java.util.List<com.calit.domain.DateOverride> overrides}
{@com.calit.domain.MeetingType.LocationType[] locationTypes}
{@com.calit.domain.BookingField.FieldType[] fieldTypes}
{@java.time.DayOfWeek[] daysOfWeek}
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — {type.name}" pendingCount=pendingCount active="meetingTypes"}
  <p><a href="/admin/meeting-types">&larr; All meeting types</a></p>
  <h1>{type.name}</h1>
  <p>/{type.slug} &middot; {type.durationMinutes} min &middot; {type.locationType.display}</p>

  <h2>Basics</h2>
  <form method="post" action="/admin/meeting-types/{type.id}/edit" class="editor">
    <label>Name <input type="text" name="name" data-slug-name value="{type.name}" required></label>
    <label>Slug <small>(leave blank to auto-generate from the name)</small>
      <input type="text" name="slug" data-slug-autofill value="{type.slug}"></label>
    <label>Duration (minutes) <input type="number" name="durationMinutes" value="{type.durationMinutes}" required></label>
    <label>Buffer before (minutes) <input type="number" name="bufferBeforeMinutes" value="{type.bufferBeforeMinutes}" min="0"></label>
    <label>Buffer after (minutes) <input type="number" name="bufferAfterMinutes" value="{type.bufferAfterMinutes}" min="0"></label>
    <label>Slot interval (minutes, blank = back-to-back)
      <input type="number" name="slotIntervalMinutes" value="{#if type.slotIntervalMinutes}{type.slotIntervalMinutes}{/if}"></label>
    <label>Min scheduling notice (minutes) <input type="number" name="minNoticeMinutes" value="{type.minNoticeMinutes}" required></label>
    <label>Booking horizon (days) <input type="number" name="horizonDays" value="{type.horizonDays}" required></label>
    <label>Location
      <select name="locationType">
        {#for lt in locationTypes}
          <option value="{lt}"{#if type.locationType == lt} selected{/if}>{lt.display}</option>
        {/for}
      </select>
    </label>
    <label>Location detail (phone / address / custom; ignored for Google Meet)
      <input type="text" name="locationDetail" value="{#if type.locationDetail}{type.locationDetail}{/if}"></label>
    <label><input type="checkbox" name="secret"{#if type.secret} checked{/if}> Secret (hidden from public landing)</label>
    <label><input type="checkbox" name="requiresApproval"{#if type.requiresApproval} checked{/if}> Requires owner approval</label>
    <script>
    (function () {
      var form = document.currentScript.closest('form');
      var name = form.querySelector('[data-slug-name]');
      var slug = form.querySelector('[data-slug-autofill]');
      var edited = slug.value.length > 0;
      slug.addEventListener('input', function () { edited = true; });
      name.addEventListener('input', function () {
        if (edited) { return; }
        slug.value = name.value.toLowerCase().normalize('NFD')
          .replace(/[̀-ͯ]/g, '')
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/^-+|-+$/g, '');
      });
    })();
    </script>
    <button type="submit">Save changes</button>
  </form>

  <h2>Booking fields</h2>
  {#include AdminResource/_detailBookingFields/}

  <h2>Working hours</h2>
  {#include AdminResource/_detailWorkingHours/}

  <h2>Date overrides</h2>
  {#include AdminResource/_detailDateOverrides/}
{/include}
```

> The three `{#include ...}` lines reference fragments added in Tasks 6 and 7. For THIS task, replace those three include lines with literal placeholder markup so the page renders and the headings exist:

```html
  <h2>Booking fields</h2>
  <p>Per-type booking fields appear here (added in a later task).</p>

  <h2>Working hours</h2>
  <p>Per-type weekly working hours appear here (added in a later task).</p>

  <h2>Date overrides</h2>
  <p>Per-type date overrides appear here (added in a later task).</p>
```

> Tasks 6 and 7 replace each placeholder block with the real form + list. Do NOT add the `{#include}` fragment lines now — they would fail to resolve.

- [ ] **Step 5: Add an Edit link from the meeting-types list**

In `src/main/resources/templates/AdminResource/meetingTypes.html`, inside each type's `<article>`, add an Edit link before the toggle form (currently before line 16):

```html
      <a href="/admin/meeting-types/{t.id}" role="button" class="secondary" style="display:inline">Edit</a>
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=AdminMeetingTypeDetailTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypeDetail.html \
        src/main/resources/templates/AdminResource/meetingTypes.html \
        src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java
git commit -m "feat: meeting-type detail page with basics edit"
```

---

## Task 6: Per-type booking fields inside the detail page

Move per-type booking-field management into the detail page and repurpose the standalone `/admin/booking-fields` page to manage GLOBAL defaults only.

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java` (`createBookingField` → global; new detail field handlers)
- Modify: `src/main/resources/templates/AdminResource/meetingTypeDetail.html` (replace booking-fields placeholder)
- Modify: `src/main/resources/templates/AdminResource/bookingFields.html` (relabel global-only, drop "Applies to")
- Modify: `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java` (add field tests)

- [ ] **Step 1: Write the failing tests for per-type fields**

Add to `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java` (add import `import com.calit.domain.BookingField;`):

```java
    @Test
    void addsBookingFieldScopedToThisType() {
        Long id = seedType("detail-fields-" + System.nanoTime());
        String key = "linkedin-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("label", "LinkedIn")
            .formParam("fieldKey", key)
            .formParam("type", "SHORT_TEXT")
            .formParam("required", "on")
            .formParam("position", "1")
            .when().post("/admin/meeting-types/" + id + "/booking-fields")
            .then().statusCode(200).body(containsString("LinkedIn"));

        BookingField f = BookingField.find("fieldKey", key).firstResult();
        org.junit.jupiter.api.Assertions.assertNotNull(f);
        assertEquals(id, f.meetingTypeId); // scoped to THIS type, not global
    }

    @Test
    void deletesBookingFieldFromThisType() {
        Long id = seedType("detail-fielddel-" + System.nanoTime());
        String key = "todelete-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("label", "Temp").formParam("fieldKey", key)
            .formParam("type", "SHORT_TEXT").formParam("position", "1")
            .when().post("/admin/meeting-types/" + id + "/booking-fields")
            .then().statusCode(200);

        BookingField f = BookingField.find("fieldKey", key).firstResult();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .when().post("/admin/meeting-types/" + id + "/booking-fields/" + f.id + "/delete")
            .then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertNull(BookingField.findById(f.id));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=AdminMeetingTypeDetailTest`
Expected: FAIL — the `/meeting-types/{id}/booking-fields` routes do not exist.

- [ ] **Step 3: Add per-type booking-field handlers**

In `src/main/java/com/calit/web/AdminResource.java`, add (after `editMeetingType`):

```java
    @POST
    @Path("/meeting-types/{id}/booking-fields")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeField(@PathParam("id") Long id,
                                         @RestForm String label,
                                         @RestForm String fieldKey,
                                         @RestForm String type,
                                         @RestForm String required,
                                         @RestForm @DefaultValue("0") int position) {
        BookingField f = new BookingField();
        f.meetingTypeId = id;
        f.label = label;
        f.fieldKey = fieldKey;
        f.type = FieldType.valueOf(type);
        f.required = "on".equals(required);
        f.position = position;
        f.persist();
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/booking-fields/{fid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteTypeField(@PathParam("id") Long id, @PathParam("fid") Long fid) {
        BookingField.deleteById(fid);
        return detailInstance(id);
    }
```

- [ ] **Step 4: Replace the booking-fields placeholder in the detail template**

In `src/main/resources/templates/AdminResource/meetingTypeDetail.html`, replace the booking-fields placeholder block (the `<h2>Booking fields</h2>` + placeholder `<p>`) with:

```html
  <h2>Booking fields</h2>
  <p>These are asked only for this meeting type, in addition to the always-present name and email.</p>
  {#for f in fields}
    <article>
      <h3>{f.label}{#if f.required} <span class="badge">required</span>{/if}</h3>
      <p><code>{f.fieldKey}</code> &middot; {f.type.display} &middot; position {f.position}</p>
      <form method="post" action="/admin/meeting-types/{type.id}/booking-fields/{f.id}/delete" style="display:inline">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}
  <form method="post" action="/admin/meeting-types/{type.id}/booking-fields">
    <label>Label <input type="text" name="label" required></label>
    <label>Field key <input type="text" name="fieldKey" required></label>
    <label>Type
      <select name="type">
        {#for ft in fieldTypes}<option value="{ft}">{ft.display}</option>{/for}
      </select>
    </label>
    <label><input type="checkbox" name="required"> Required</label>
    <label>Position <input type="number" name="position" value="0" required></label>
    <button type="submit">Add field</button>
  </form>
```

- [ ] **Step 5: Repurpose the standalone booking-fields page to globals only**

In `src/main/java/com/calit/web/AdminResource.java`, replace the `createBookingField` signature + body (currently lines 242-260) so it always creates a GLOBAL field:

```java
    public TemplateInstance createBookingField(@RestForm String label,
                                               @RestForm String fieldKey,
                                               @RestForm String type,
                                               @RestForm String required,
                                               @RestForm int position) {
        BookingField f = new BookingField();
        f.label = label;
        f.fieldKey = fieldKey;
        f.type = FieldType.valueOf(type);
        f.required = "on".equals(required); // unchecked checkbox sends no value
        f.position = position;
        f.meetingTypeId = null; // standalone page manages global defaults only
        f.persist();
        return Templates.bookingFields(
                BookingField.list("meetingTypeId is null order by position"),
                BookingField.FieldType.values(), pendingCount());
    }
```

In `src/main/resources/templates/AdminResource/bookingFields.html`, replace the intro `<h1>`/`<p>` (currently lines 5-6) with global-scope wording:

```html
  <h1>Default booking fields</h1>
  <p>Full name and email are always asked. These default extra fields apply to every meeting type
     that has no fields of its own. Set per-type fields from each meeting type's page.</p>
```

Remove the "Applies to" `<label>` block (currently lines 33-38) entirely. The remaining form (label, fieldKey, type, required, position) is unchanged.

- [ ] **Step 6: Run the affected suites**

Run: `./mvnw test -Dtest=AdminMeetingTypeDetailTest,AdminBookingFieldsTest`
Expected: PASS — detail field tests green; `AdminBookingFieldsTest` still passes (it seeds/creates GLOBAL fields and asserts they render; the extra `meetingTypeId` form param it sends is now ignored).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypeDetail.html \
        src/main/resources/templates/AdminResource/bookingFields.html \
        src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java
git commit -m "feat: manage per-type booking fields on the meeting-type page"
```

---

## Task 7: Per-type working hours + date overrides inside the detail page

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java` (new detail availability + date-override handlers)
- Modify: `src/main/resources/templates/AdminResource/meetingTypeDetail.html` (replace both placeholders)
- Modify: `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java` (add tests)

- [ ] **Step 1: Write the failing tests for per-type working hours + overrides**

Add to `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java` (add imports `import com.calit.domain.AvailabilityRule;` and `import com.calit.domain.DateOverride;`):

```java
    @Test
    void addsWorkingHourRuleScopedToThisType() {
        Long id = seedType("detail-hours-" + System.nanoTime());
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("dayOfWeek", "WEDNESDAY")
            .formParam("startTime", "09:00")
            .formParam("endTime", "12:00")
            .when().post("/admin/meeting-types/" + id + "/availability")
            .then().statusCode(200).body(containsString("Wednesday")); // humanized label

        long count = AvailabilityRule.count("meetingTypeId = ?1", id);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    @Test
    void addsDateOverrideScopedToThisType() {
        Long id = seedType("detail-override-" + System.nanoTime());
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "2026-12-24")
            .formParam("windowStart", "09:00")
            .formParam("windowEnd", "11:00")
            .when().post("/admin/meeting-types/" + id + "/date-overrides")
            .then().statusCode(200).body(containsString("2026-12-24"));

        long count = DateOverride.count("meetingTypeId = ?1", id);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=AdminMeetingTypeDetailTest`
Expected: FAIL — the per-type availability/date-override routes do not exist.

- [ ] **Step 3: Add per-type availability + date-override handlers**

In `src/main/java/com/calit/web/AdminResource.java`, add (after `deleteTypeField`):

```java
    @POST
    @Path("/meeting-types/{id}/availability")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeRule(@PathParam("id") Long id,
                                        @RestForm String dayOfWeek,
                                        @RestForm String startTime,
                                        @RestForm String endTime) {
        AvailabilityRule r = new AvailabilityRule();
        r.meetingTypeId = id;
        r.dayOfWeek = DayOfWeek.valueOf(dayOfWeek);
        r.startTime = LocalTime.parse(startTime);
        r.endTime = LocalTime.parse(endTime);
        r.persist();
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/availability/{rid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteTypeRule(@PathParam("id") Long id, @PathParam("rid") Long rid) {
        AvailabilityRule.deleteById(rid);
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addTypeOverride(@PathParam("id") Long id,
                                            @RestForm String date,
                                            MultivaluedMap<String, String> form) {
        DateOverride o = new DateOverride();
        o.meetingTypeId = id;
        o.overrideDate = LocalDate.parse(date);
        o.persist(); // need the generated id before persisting child windows
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (int i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
            DateOverrideWindow w = new DateOverrideWindow();
            w.dateOverrideId = o.id;
            w.startTime = LocalTime.parse(starts.get(i));
            w.endTime = LocalTime.parse(ends.get(i));
            w.persist();
        }
        return detailInstance(id);
    }

    @POST
    @Path("/meeting-types/{id}/date-overrides/{oid}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteTypeOverride(@PathParam("id") Long id, @PathParam("oid") Long oid) {
        DateOverrideWindow.delete("dateOverrideId = ?1", oid);
        DateOverride.deleteById(oid);
        return detailInstance(id);
    }
```

- [ ] **Step 4: Replace the working-hours and date-overrides placeholders in the detail template**

In `src/main/resources/templates/AdminResource/meetingTypeDetail.html`, replace the working-hours placeholder block with:

```html
  <h2>Working hours</h2>
  <p>Weekly hours for this meeting type. When set, they replace the global default hours for this type.</p>
  {#for r in rules}
    <article>
      <p><strong>{r.dayOfWeek.display}</strong> {r.startTime} &ndash; {r.endTime}</p>
      <form method="post" action="/admin/meeting-types/{type.id}/availability/{r.id}/delete" style="display:inline">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}
  <form method="post" action="/admin/meeting-types/{type.id}/availability">
    <label>Day
      <select name="dayOfWeek">
        {#for d in daysOfWeek}<option value="{d}">{d.display}</option>{/for}
      </select>
    </label>
    <label>Start <input type="time" name="startTime" value="09:00" required></label>
    <label>End <input type="time" name="endTime" value="17:00" required></label>
    <button type="submit">Add rule</button>
  </form>
```

Replace the date-overrides placeholder block with:

```html
  <h2>Date overrides</h2>
  <p>An override REPLACES this date's normal hours for this meeting type. Leave the windows blank to mark the date as a day off.</p>
  {#for o in overrides}
    <article>
      <p><strong>{o.overrideDate}</strong></p>
      {#if o.windows.isEmpty()}
        <p><span class="badge">day off</span> (blocked)</p>
      {#else}
        <ul>{#for w in o.windows}<li>{w.startTime} &ndash; {w.endTime}</li>{/for}</ul>
      {/if}
      <form method="post" action="/admin/meeting-types/{type.id}/date-overrides/{o.id}/delete" style="display:inline">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}
  <form method="post" action="/admin/meeting-types/{type.id}/date-overrides">
    <label>Date <input type="date" name="date" required></label>
    <fieldset>
      <legend>Bookable windows (leave all blank = day off)</legend>
      <label>Window 1 <input type="time" name="windowStart"> to <input type="time" name="windowEnd"></label>
      <label>Window 2 <input type="time" name="windowStart"> to <input type="time" name="windowEnd"></label>
      <label>Window 3 <input type="time" name="windowStart"> to <input type="time" name="windowEnd"></label>
    </fieldset>
    <button type="submit">Save override</button>
  </form>
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=AdminMeetingTypeDetailTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypeDetail.html \
        src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java
git commit -m "feat: manage per-type working hours and date overrides on the meeting-type page"
```

---

## Task 8: Full regression run

**Files:** none (verification only)

- [ ] **Step 1: Run the entire test suite**

Run: `./mvnw test`
Expected: PASS — all suites green (domain, availability, booking, google, email, web, scheduler).

- [ ] **Step 2: If anything fails, fix it before finishing**

Likely suspects if red:
- A template parameter declaration (`{@...}`) not matching the updated native method signature — line up the order/types exactly.
- A Qute `{enum.display}` not resolving — confirm `DisplayExtensions` is annotated `@TemplateExtension` and `display(Enum<?>)` is `public static`.
- An older test asserting a now-humanized label as display text — it should assert the raw form VALUE instead (e.g. `value="GOOGLE_MEET"`, option `value="TUESDAY"`), which is preserved.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "test: fix regressions from meeting-type admin improvements"
```

---

## Self-Review

**Spec coverage:**
1. *Booking fields by meeting type* → Task 6 (managed inside the detail page; standalone page repurposed to global defaults). ✓
2. *Enums in UI not like constants* → Task 1 (`DisplayExtensions.display` applied to LocationType, FieldType, DayOfWeek across meetingTypes/bookingFields/availability/detail templates; raw values kept for form submission). ✓
3. *Buffers before/after per type and separate* → already separate columns; surfaced in create form (Task 3) and edit form (Task 5). ✓
4. *Slug auto-generated from name by default* → Task 4 (`Slugs.slugify` + `uniqueMeetingTypeSlug` server-side when blank; JS live-fill; optional input) and applied on edit in Task 5. ✓
5. *Working-time override per meeting* → Task 7 (per-type weekly hours + date overrides on the detail page). ✓
6. *Location buttons same size* → Task 2 (`align-items: stretch`, `min-height`, `justify-content: center`). ✓

**Type/name consistency:** `Slugs.slugify` / `Slugs.uniqueMeetingTypeSlug(base, excludeId)`, `DisplayExtensions.display(Enum)`, `detailInstance(id)`, `overridesForType(id)`, and `Templates.meetingTypeDetail(...)` are referenced identically everywhere. Updated native methods (`bookingFields`, `availability`, `meetingTypeDetail`) match their template `{@...}` declarations in parameter order and type. Form field names (`bufferBeforeMinutes`, `bufferAfterMinutes`, `data-slug-name`, `data-slug-autofill`) match between templates, handlers, and tests.

**Placeholder scan:** No TBD/TODO; every code step shows complete code; the detail-template "appears in a later task" placeholders are explicitly literal markup replaced in Tasks 6–7 (and the plan warns NOT to add `{#include}` fragment lines).

**No new migration required** — verified `buffer_before_minutes`, `buffer_after_minutes`, `slug UNIQUE`, and nullable `meeting_type_id` FKs on `booking_field`/`availability_rule`/`date_override` all already exist (V1–V5).
</content>
</invoke>
