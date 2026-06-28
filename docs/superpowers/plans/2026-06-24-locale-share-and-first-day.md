# Locale Share Link + Locale-Based First Day-of-Week Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let anyone share a calit page in a chosen language via `?lang=en|he|de`, and make the public booking calendar start the week on the correct day for the active locale (Sunday for Hebrew, Monday for German).

**Architecture:** Two independent changes on the existing i18n seam. (1) `LocaleResolutionFilter` already resolves the per-request locale into `ActiveLocale`; add a top-priority `?lang=` query check there. (2) `LocaleTemplateInitializer` already injects per-request `lang`/`returnPath` into every Qute template; add a `firstDow` integer (0=Sun..6=Sat) computed from the active locale via `java.time.temporal.WeekFields`. The static `CALENDAR_SCRIPT` JS reads that value off a `data-first-dow` attribute on `#calendar` and rotates both the weekday header and the leading-blank offset. No DB change, no new dependency, no Java string interpolation in the JS constant.

**Tech Stack:** Quarkus 3.36 / Java 25, JAX-RS `ContainerRequestFilter`, Qute `TemplateInstance.Initializer`, `java.time.temporal.WeekFields`, RestAssured + `@QuarkusTest`.

## Global Constraints

- Owner scoping unaffected — these changes touch only locale resolution + presentation, no tenant data queries.
- `?lang=` is **ephemeral**: it must NOT write the `calit_lang` cookie. Persistent switching stays the existing `/lang/{code}` route. Sharing a link must not mutate the visitor's saved preference.
- Unknown/unsupported `?lang=xx` must fall through to existing resolution (cookie → Accept-Language → default). Guard with `AppLocales.isSupported`.
- Supported-locale set is auto-discovered from message bundles (`AppLocales.supported()`). Do NOT hardcode an `{en,he,de}` list anywhere.
- `firstDow` mapping: `DayOfWeek.getValue() % 7` yields JS `Date.getDay()` indexing (MON=1…SAT=6, SUN=0). Verified: Hebrew→SUNDAY→0, German→MONDAY→1.
- RestAssured cannot execute JS (per CLAUDE.md). Test the **rendered `data-first-dow` attribute**, never the rotated grid.
- Out of scope (separate follow-up, do NOT fold in): the untranslated `" min"` literal and `LocationType.display` ("Custom") on `landing.html:20`.

---

### Task 1: `?lang=` query override in locale resolution

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/LocaleResolutionFilter.java` (method `resolve`, ~line 78-86)
- Test: `src/test/java/site/asm0dey/calit/web/LangQueryParamTest.java` (create)

**Interfaces:**
- Consumes: `AppLocales.isSupported(String)`, `AppLocales.pick(String)` (existing static methods), `ContainerRequestContext.getUriInfo().getQueryParameters()`.
- Produces: no new symbols. Behavior change only — when request has supported `?lang=`, `ActiveLocale` is set to it regardless of owner/cookie/header.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/LangQueryParamTest.java`. Reuses the seed pattern from `RtlDirMarkerTest`.

```java
package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class LangQueryParamTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("langq");
        if (owner == null) {
            owner = AppUser.create("langq", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "intro");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Lang Q Owner";
        s.ownerEmail = "langq@example.com";
        s.timezone = "Asia/Jerusalem";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Intro"; t.slug = "intro"; t.durationMinutes = 30;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    private void mockCal() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void queryParamOverridesToHebrew() {
        mockCal(); seed();
        given().when().get("/langq/intro?lang=he")
            .then().statusCode(200)
            .body(containsString("lang=\"he\""))
            .body(containsString("dir=\"rtl\""));
    }

    @Test
    void queryParamBeatsCookie() {
        mockCal(); seed();
        // cookie says English, query says Hebrew -> query wins
        given().cookie("calit_lang", "en").when().get("/langq/intro?lang=he")
            .then().statusCode(200)
            .body(containsString("lang=\"he\""));
    }

    @Test
    void unknownQueryParamFallsThroughToCookie() {
        mockCal(); seed();
        given().cookie("calit_lang", "he").when().get("/langq/intro?lang=zz")
            .then().statusCode(200)
            .body(containsString("lang=\"he\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LangQueryParamTest`
Expected: FAIL — `queryParamOverridesToHebrew` / `queryParamBeatsCookie` get `lang="en"` (cookie/Accept-Language/default wins; query ignored).

- [ ] **Step 3: Add the query override at the top of `resolve`**

In `src/main/java/site/asm0dey/calit/i18n/LocaleResolutionFilter.java`, the current method:

```java
    private Locale resolve(ContainerRequestContext ctx) {
        if (currentOwner.isSet()) {
            OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
            return AppLocales.pick(s != null ? s.locale : null);
        }
        Cookie c = ctx.getCookies().get("calit_lang");
        if (c != null && AppLocales.isSupported(c.getValue())) return AppLocales.pick(c.getValue());
        return AppLocales.fromAcceptLanguage(ctx.getHeaderString("Accept-Language"));
    }
```

Replace with (adds ONLY the first two lines):

```java
    private Locale resolve(ContainerRequestContext ctx) {
        // Shareable per-request override: ?lang=xx wins over owner/cookie/header. Ephemeral — sets no cookie.
        String q = ctx.getUriInfo().getQueryParameters().getFirst("lang");
        if (q != null && AppLocales.isSupported(q)) return AppLocales.pick(q);
        if (currentOwner.isSet()) {
            OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
            return AppLocales.pick(s != null ? s.locale : null);
        }
        Cookie c = ctx.getCookies().get("calit_lang");
        if (c != null && AppLocales.isSupported(c.getValue())) return AppLocales.pick(c.getValue());
        return AppLocales.fromAcceptLanguage(ctx.getHeaderString("Accept-Language"));
    }
```

`getUriInfo()` is already reachable (used in `computeReturnPath`). No new imports.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LangQueryParamTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/i18n/LocaleResolutionFilter.java src/test/java/site/asm0dey/calit/web/LangQueryParamTest.java
git commit -m "feat(i18n): ?lang= query param overrides locale for shareable links"
```

---

### Task 2: Expose locale-based `firstDow` to all templates

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/LocaleTemplateInitializer.java` (method `accept`, the `if (locale != null) … else …` block, ~line 60-70)
- Test: `src/test/java/site/asm0dey/calit/web/FirstDowAttributeTest.java` (create) — asserts the attribute appears in Task 3; here we only wire the data + a unit check on the mapping helper.

**Interfaces:**
- Consumes: `ActiveLocale` (already read in `accept`), `AppLocales.DEFAULT`.
- Produces: every `TemplateInstance` now carries `firstDow` (an `int`, 0=Sun..6=Sat) usable as `{firstDow}` in any Qute template. Hebrew→0, German→1, English→whatever the JDK reports for `Locale.ENGLISH` (do not assert its exact value).

- [ ] **Step 1: Write the failing test (mapping correctness, no Quarkus needed)**

Create `src/test/java/site/asm0dey/calit/web/FirstDowAttributeTest.java`:

```java
package site.asm0dey.calit.web;

import org.junit.jupiter.api.Test;

import java.time.temporal.WeekFields;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the DayOfWeek -> JS getDay() index mapping used for the calendar's first column. */
class FirstDowAttributeTest {

    private static int jsFirstDow(Locale l) {
        return WeekFields.of(l).getFirstDayOfWeek().getValue() % 7; // 0=Sun..6=Sat
    }

    @Test
    void hebrewStartsSunday() {
        assertEquals(0, jsFirstDow(Locale.forLanguageTag("he")));
    }

    @Test
    void germanStartsMonday() {
        assertEquals(1, jsFirstDow(Locale.forLanguageTag("de")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FirstDowAttributeTest`
Expected: FAIL — compiles and runs, but this is a plain JUnit class with the helper inlined; it actually PASSES immediately because it tests the JDK directly. That is fine — it exists to pin the mapping the production code must match. If it does NOT pass, the JDK locale data differs from assumptions and Task 3's HTML expectations must be revisited before continuing.

(Note: this is the one place where "verify it fails" doesn't apply — the test pins a library invariant the implementation depends on. Run it, confirm PASS, proceed.)

- [ ] **Step 3: Inject `firstDow` in the template initializer**

In `src/main/java/site/asm0dey/calit/i18n/LocaleTemplateInitializer.java`, add the import:

```java
import java.time.temporal.WeekFields;
```

Current block:

```java
        if (locale != null) {
            instance.setLocale(locale);
            instance.data("lang", locale.toLanguageTag());
        } else {
            instance.data("lang", AppLocales.DEFAULT.toLanguageTag());
        }
        instance.data("returnPath", returnPath);
        instance.data("localeOptions", localeOptions);
```

Replace with:

```java
        Locale effective = (locale != null) ? locale : AppLocales.DEFAULT;
        if (locale != null) {
            instance.setLocale(locale);
        }
        instance.data("lang", effective.toLanguageTag());
        // First column of the booking calendar grid, as a JS Date.getDay() index (0=Sun..6=Sat).
        instance.data("firstDow", WeekFields.of(effective).getFirstDayOfWeek().getValue() % 7);
        instance.data("returnPath", returnPath);
        instance.data("localeOptions", localeOptions);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FirstDowAttributeTest`
Expected: PASS (2 tests). The template wiring is exercised end-to-end in Task 3.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/i18n/LocaleTemplateInitializer.java src/test/java/site/asm0dey/calit/web/FirstDowAttributeTest.java
git commit -m "feat(i18n): expose locale firstDow to every Qute template"
```

---

### Task 3: Render `data-first-dow` and rotate the calendar grid

**Files:**
- Modify: `src/main/resources/templates/PublicResource/book.html:57`
- Modify: `src/main/resources/templates/PublicResource/manage.html:26`
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java` (`CALENDAR_SCRIPT`, the `DOW` builder ~line 100-104 and the `firstDow` offset ~line 133)
- Test: `src/test/java/site/asm0dey/calit/web/FirstDowAttributeTest.java` (extend with `@QuarkusTest` HTML assertions — see note)

**Interfaces:**
- Consumes: `{firstDow}` template data from Task 2.
- Produces: `<div id="calendar" … data-first-dow="N">` in rendered booking + manage pages. JS reads `cal.dataset.firstDow`.

- [ ] **Step 1: Write the failing HTML test**

The mapping test from Task 2 is plain JUnit; HTML rendering needs `@QuarkusTest`. Create a SEPARATE class `src/test/java/site/asm0dey/calit/web/FirstDowRenderTest.java` (keeps the plain unit test framework-free):

```java
package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class FirstDowRenderTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("fdow");
        if (owner == null) {
            owner = AppUser.create("fdow", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "intro");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Fdow Owner";
        s.ownerEmail = "fdow@example.com";
        s.timezone = "Asia/Jerusalem";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Intro"; t.slug = "intro"; t.durationMinutes = 30;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    private void mockCal() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void hebrewCalendarStartsSunday() {
        mockCal(); seed();
        given().when().get("/fdow/intro?lang=he")
            .then().statusCode(200)
            .body(containsString("data-first-dow=\"0\""));
    }

    @Test
    void germanCalendarStartsMonday() {
        mockCal(); seed();
        given().when().get("/fdow/intro?lang=de")
            .then().statusCode(200)
            .body(containsString("data-first-dow=\"1\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FirstDowRenderTest`
Expected: FAIL — `#calendar` has no `data-first-dow` attribute yet.

- [ ] **Step 3a: Add the attribute to `book.html`**

`src/main/resources/templates/PublicResource/book.html:57` — change:

```html
                <div id="calendar" class="calendar"></div>
```

to:

```html
                <div id="calendar" class="calendar" data-first-dow="{firstDow}"></div>
```

- [ ] **Step 3b: Add the attribute to `manage.html`**

`src/main/resources/templates/PublicResource/manage.html:26` — change:

```html
            <div id="calendar" class="calendar"></div>
```

to:

```html
            <div id="calendar" class="calendar" data-first-dow="{firstDow}"></div>
```

- [ ] **Step 3c: Rotate the weekday header in `CALENDAR_SCRIPT`**

`src/main/java/site/asm0dey/calit/web/Layout.java` — current `DOW` builder:

```java
              // week starts Monday: 2021-03-01 is a Monday
              var DOW = [];
              for (var di = 0; di < 7; di++) {
                DOW.push(new Intl.DateTimeFormat(LANG, {weekday:'short'}).format(new Date(2021, 2, 1 + di)));
              }
```

Replace with:

```java
              // First weekday column from the active locale (0=Sun..6=Sat), via data-first-dow.
              var FIRST = parseInt(cal.dataset.firstDow, 10);
              if (isNaN(FIRST) || FIRST < 0 || FIRST > 6) { FIRST = 1; } // fallback: Monday
              // 2021-08-01 is a Sunday, so new Date(2021,7,1+k) has getDay() === k.
              var DOW = [];
              for (var di = 0; di < 7; di++) {
                DOW.push(new Intl.DateTimeFormat(LANG, {weekday:'short'}).format(new Date(2021, 7, 1 + ((FIRST + di) % 7))));
              }
```

- [ ] **Step 3d: Rotate the leading-blank offset in `CALENDAR_SCRIPT`**

`src/main/java/site/asm0dey/calit/web/Layout.java` — current offset line inside `render()`:

```java
                var firstDow = (new Date(y, m, 1).getDay() + 6) % 7; // Mon=0
```

Replace with:

```java
                var firstDow = ((new Date(y, m, 1).getDay() - FIRST) + 7) % 7; // blanks before day 1
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FirstDowRenderTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Rebuild CSS is NOT needed (no style change); run the two touched suites together**

Run: `mvn test -Dtest=FirstDowRenderTest,LangQueryParamTest,RtlDirMarkerTest`
Expected: PASS (all). `RtlDirMarkerTest` confirms cookie-based switching still works (no regression).

- [ ] **Step 6: Manual smoke (optional, requires running app)**

```bash
bun run css:build && mvn quarkus:dev   # then open in browser
```
Open `http://localhost:8080/<user>/<slug>?lang=he` → calendar weekday header starts Sunday (rightmost in RTL); `?lang=de` → starts Monday. Confirm day numbers land under the correct weekday.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/PublicResource/book.html src/main/resources/templates/PublicResource/manage.html src/main/java/site/asm0dey/calit/web/Layout.java src/test/java/site/asm0dey/calit/web/FirstDowRenderTest.java
git commit -m "feat(calendar): start week on locale-correct day (Sunday for he, Monday for de)"
```

---

### Task 4: Full suite + docs

**Files:**
- Modify (on `docs-site` branch): `docs-site/src/content/docs/` page covering invitee language switching.

**Interfaces:** none.

- [ ] **Step 1: Run the full suite (Docker required)**

Run: `mvn test`
Expected: PASS — all prior tests plus the 3 new classes. No regressions.

- [ ] **Step 2: Document `?lang=` on the docs-site branch**

Per CLAUDE.md, user-facing changes land on `docs-site` same effort. On that branch, in the page that documents invitee language switching (the `/lang/{code}` footer switch), add a short note:

> Append `?lang=en`, `?lang=he`, or `?lang=de` to any booking URL to share it pre-rendered in that language. The override applies to that visit only and does not change the visitor's saved preference. The public booking calendar also starts the week on the locale's conventional first day (Sunday for Hebrew, Monday for German/English-ISO).

(No automated test — prose docs. Verify the page builds locally if the Astro toolchain is set up, otherwise commit and let the docs CI build it.)

- [ ] **Step 3: Commit docs (on docs-site branch)**

```bash
git add docs-site/src/content/docs/<edited-page>.md
git commit -m "docs: document ?lang= share override and locale-based week start"
```

---

## Notes / Deliberate Simplifications

- `?lang=` deliberately writes no cookie (`// ephemeral` in Task 1) — sharing must not hijack the recipient's saved language.
- English (`Locale.ENGLISH`, no country) `firstDow` is whatever the JDK reports; tests assert only the unambiguous `he`→0 and `de`→1. Add a region-specific `en-US`/`en-GB` distinction only if a user asks.
- Out of scope: translating `" min"` and the `LocationType` display name ("Custom") on `landing.html:20`. That is a separate `@Message`/bundle change tracked independently; folding it here would mix concerns.
- No Jewish (lunisolar) calendar — Gregorian only, consistent with Calendly-class scheduling. Hebrew = translation + RTL + week-start, not a calendar-system swap.
