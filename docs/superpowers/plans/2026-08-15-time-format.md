# Viewer-local time format + host time-format preference — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make times on every calit page follow the viewer's 12h/24h convention instead of the page's translation language, and give hosts an explicit override for their own pages and emails.

**Architecture:** `Layout.TZ_SCRIPT` currently formats with `document.documentElement.lang` — the region-less UI locale, where bare `en` means US, hence AM/PM for everyone. Phase 1 keeps that locale for *words* and takes only `hourCycle` from the viewer's device, and un-bails the script so `/me` pages format at all (they have no `#tz-picker`). Phase 2 adds an `auto`/`h12`/`h23` column on `owner_settings` that overrides the device on `/me` and selects between two translated email patterns.

**Tech Stack:** Quarkus 3.38 / Java 25 (build JDK Liberica 26), Qute templates, Panache entities, Flyway, vanilla inline JS, RestAssured + `@QuarkusTest`, `MockMailbox`.

**Spec:** `docs/superpowers/specs/2026-08-15-time-format-design.md`
**Issue:** [#116](https://github.com/asm0dey/calit/issues/116)
**Tracking bean:** `calit-0hyn`

## Global Constraints

- **Build JDK.** Plain `mvn` picks JDK 21 and fails with "release 25 not supported". Every Maven command in this plan must be preceded by:
  `export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca`
- **Docker must be running.** Quarkus Dev Services provisions the test Postgres. No H2 fallback.
- **Never edit an applied Flyway migration.** New `V*.sql` only. Next free number is **V25** (tree ends at `V24__backfill_owner_settings.sql`).
- **Every new user-facing string ships with `de` and `he` translations in the same commit.** English is the `@Message` default on the bundle method; `de`/`he` go in `src/main/resources/messages/{msg,adm}_{de,he}.properties`, keyed by method name. Placeholder names identical across locales.
- **Owner scoping.** Any query added must filter by `currentOwner.id()`. One user must never read another's data.
- **Formatting gate.** `mvn spotless:check` runs in `verify` and CI fails on unformatted Java. The lefthook pre-commit hook auto-applies `spotless:apply` to staged `*.java`, so committing normally is enough.
- **RestAssured cannot execute JavaScript.** Assert on stable marker comments and on served HTML attributes, never by running scripts.
- **Track work in beans, not TODO lists.** Update `calit-0hyn` as tasks land; commit bean files with code.
- **Stored hour-cycle values are Intl's own vocabulary** — exactly `auto`, `h12`, `h23`. No mapping table anywhere.

## File Structure

**Phase 1**
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java` — `TZ_SCRIPT` only. Hour cycle from device; picker becomes optional.
- Create: `src/main/java/site/asm0dey/calit/web/OwnerInfo.java` — `@Named("owner") @RequestScoped` bean exposing the current owner's settings to Qute as `{inject:owner.*}`. Mirrors `SiteInfo` (`web/SiteInfo.java:18`). Phase 2 reuses it.
- Modify: `src/main/resources/templates/adminBase.html:22` — `data-tz` on `<body>`.
- Modify: `src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java`
- Create: `src/test/java/site/asm0dey/calit/web/AdminTimeRenderingTest.java`

**Phase 2**
- Create: `src/main/resources/db/migration/V25__owner_time_format.sql`
- Modify: `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java` — `timeFormat` field.
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:1123-1151` — accept and validate `timeFormat`.
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` + `messages/adm_{de,he}.properties` — settings labels.
- Modify: `src/main/resources/templates/AdminResource/settings.html` — the select.
- Modify: `src/main/java/site/asm0dey/calit/web/OwnerInfo.java` — expose `hourCycle`.
- Modify: `src/main/resources/templates/adminBase.html` — `data-hc` on `<body>`.
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` + `messages/msg_{de,he}.properties` — `email_datetime_pattern_h12`.
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` — renderer gains `hourCycle`.
- Create: `src/test/java/site/asm0dey/calit/web/OwnerTimeFormatSettingTest.java`
- Create: `src/test/java/site/asm0dey/calit/email/EmailHourCycleTest.java`

---

## Task 1: Hour cycle from the viewer's device

Words keep following the page language; only 12h-vs-24h moves to the device. This alone closes issue #116.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java:56-67`
- Test: `src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: the stable marker comment `CALIT_HOUR_CYCLE` inside `TZ_SCRIPT`, which later tasks and tests assert on.

- [ ] **Step 1: Write the failing test**

Add this method to `LayoutLocaleMarkerTest` (the class already has `seed()`, `@InjectMock CalendarPort calendarPort`, and the imports for `when`/`any`/`anyLong`/`List`):

```java
    /**
     * Issue #116: the time path must take its 12h/24h choice from the VIEWER's device, not from
     * the page's translation locale (bare "en" carries US defaults, hence AM/PM for everyone).
     * Words still follow documentElement.lang — only hourCycle moves.
     */
    @Test
    void timesResolveHourCycleFromTheViewersDevice() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().when()
                .get("/layouttest/lt-intro")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_HOUR_CYCLE"))
                // probes the device with an hour field — resolvedOptions() omits hourCycle without one
                .body(containsString("{hour:'numeric'}"))
                .body(containsString("resolvedOptions().hourCycle"))
                // the formatting call must carry the resolved cycle
                .body(containsString("hourCycle: HC"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=LayoutLocaleMarkerTest#timesResolveHourCycleFromTheViewersDevice
```

Expected: FAIL — assertion error, response body does not contain `CALIT_HOUR_CYCLE`.

- [ ] **Step 3: Write minimal implementation**

In `Layout.java`, replace this block (currently lines 56-67):

```java
              var LANG = document.documentElement.lang || undefined;
              function render() {
                var tz = picker.value;
                if (label) { label.textContent = tz; }
                document.querySelectorAll('[data-utc]').forEach(function (el) {
                  var d = new Date(el.dataset.utc);
                  var opts = (el.dataset.timeOnly === '1')
                    ? { timeStyle: 'short', timeZone: tz }
                    : { dateStyle: 'full', timeStyle: 'short', timeZone: tz };
                  el.textContent = d.toLocaleString(LANG, opts);
                });
              }
```

with:

```java
              /* Words (weekday, month, connector) follow the PAGE language... */
              var LANG = document.documentElement.lang || undefined;
              /* CALIT_HOUR_CYCLE — ...but 12h-vs-24h follows the VIEWER's device (issue #116).
                 documentElement.lang is region-less ('en' = US defaults = AM/PM for everyone).
                 resolvedOptions() only reports hourCycle when an hour field is requested. */
              var HC = new Intl.DateTimeFormat(undefined, {hour:'numeric'}).resolvedOptions().hourCycle;
              function render() {
                var tz = picker.value;
                if (label) { label.textContent = tz; }
                document.querySelectorAll('[data-utc]').forEach(function (el) {
                  var d = new Date(el.dataset.utc);
                  var opts = (el.dataset.timeOnly === '1')
                    ? { timeStyle: 'short', timeZone: tz, hourCycle: HC }
                    : { dateStyle: 'full', timeStyle: 'short', timeZone: tz, hourCycle: HC };
                  el.textContent = d.toLocaleString(LANG, opts);
                });
              }
```

Notes for the implementer:
- `hourCycle` combined with `timeStyle` is legal. The illegal combination is `dateStyle`/`timeStyle` together with individual component options (`hour`, `minute`, …).
- On a browser too old to report `hourCycle`, `HC` is `undefined`, and Intl ignores an option whose value is `undefined` — the result is exactly today's behaviour. No guard needed.
- Do **not** touch `CALENDAR_SCRIPT`. Its month and weekday names are content and must keep following `LANG`; its `firstDay` already reads `navigator.language` (`Layout.java:113`) and is correct.

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=LayoutLocaleMarkerTest
```

Expected: PASS, 3 tests (`bookingPagePassesLangToScripts`, `tzBarIsLocalized`, `timesResolveHourCycleFromTheViewersDevice`). The first still passes because `CALENDAR_SCRIPT` keeps `documentElement.lang`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/Layout.java \
        src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java
git commit -m "fix(i18n): take 12h/24h from the viewer's device, not the page locale

documentElement.lang is the region-less UI translation locale, so bare 'en'
carried US defaults and every viewer of an English page saw AM/PM. Words keep
following the page language; only hourCycle now comes from the device.

Closes #116"
```

---

## Task 2: Admin pages format times in the host's stored timezone

`Layout.java:48` reads `if (!picker) { return; }`, and `adminBase.html` has **zero** `tz-picker` elements — so on `/me` and `/me/pending` the script exits before `render()` and the raw placeholder survives: `<time data-utc="2026-08-20T13:00:00Z">2026-08-20T13:00:00Z UTC</time>` (`dashboard.html:28`, `pending.html:14`). Un-bailing it needs a zone, and the browser-detected one would silently show a travelling host their bookings in the trip's zone. The server knows better.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/OwnerInfo.java`
- Modify: `src/main/resources/templates/adminBase.html:22`
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java:46-69`
- Test: `src/test/java/site/asm0dey/calit/web/AdminTimeRenderingTest.java`

**Interfaces:**
- Consumes: `CurrentOwner` (`user/CurrentOwner.java` — `isSet()`, `id()`), `OwnerSettings.forOwner(Long)`.
- Produces: `OwnerInfo.getTimezone() -> String` (never null; `""` when no settings row), reachable from any Qute template as `{inject:owner.timezone}`. Task 5 adds `getHourCycle()` to the same bean.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminTimeRenderingTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * /me pages have no #tz-picker, so TZ_SCRIPT used to bail at "if (!picker) return" and leave the
 * raw ISO instant on screen. The script must now format without a picker, using the owner's
 * STORED timezone (not the browser-detected one — a travelling host must not silently read their
 * bookings in the trip's zone).
 *
 * <p>RestAssured cannot execute JS, so these assert on the served HTML and the script text.</p>
 */
@QuarkusTest
class AdminTimeRenderingTest {

    /** Saves a known timezone so the assertion below is deterministic. */
    private void saveTimezone(String zone) {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", zone)
                .formParam("locale", "en")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardCarriesTheOwnersStoredTimezone() {
        saveTimezone("Europe/Amsterdam");

        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-tz=\"Europe/Amsterdam\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void pendingCarriesTheOwnersStoredTimezone() {
        saveTimezone("Asia/Tokyo");

        given().when()
                .get("/me/pending")
                .then()
                .statusCode(200)
                .body(containsString("data-tz=\"Asia/Tokyo\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void scriptNoLongerBailsWhenThereIsNoPicker() {
        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_TZ_REFORMAT"))
                // the early return is gone
                .body(not(containsString("if (!picker) { return; }")))
                // and the no-picker path reads the server-supplied zone
                .body(containsString("document.body.dataset.tz"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminTimeRenderingTest
```

Expected: FAIL — all three. The first two find no `data-tz`; the third still finds `if (!picker) { return; }`.

- [ ] **Step 3: Create the OwnerInfo bean**

Create `src/main/java/site/asm0dey/calit/web/OwnerInfo.java`:

```java
package site.asm0dey.calit.web;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * The current owner's display preferences, exposed to Qute as {@code {inject:owner.*}} for the
 * /me templates. Request-scoped because it reads {@link CurrentOwner}, which {@code MeOwnerFilter}
 * populates per request (contrast {@link SiteInfo}, which is application-scoped config).
 *
 * <p>Exists so {@code adminBase.html} can carry the owner's timezone without adding a parameter to
 * every admin template's signature. Accessors never return null — Qute would render the literal
 * "null" — and the client scripts treat an empty string as "not supplied".</p>
 */
@Named("owner")
@RequestScoped
public class OwnerInfo {

    final CurrentOwner currentOwner;

    private OwnerSettings cached;

    private boolean loaded;

    @Inject
    public OwnerInfo(CurrentOwner currentOwner) {
        this.currentOwner = currentOwner;
    }

    /** Memoized so a template reading several accessors costs one query per request. */
    private OwnerSettings settings() {
        if (!loaded) {
            cached = currentOwner.isSet() ? OwnerSettings.forOwner(currentOwner.id()) : null;
            loaded = true;
        }
        return cached;
    }

    /**
     * The owner's configured IANA zone, or "" when no owner/settings row is in scope. The /me
     * pages have no timezone picker, so this is what their times are rendered in — a host who
     * travels must still read their bookings in the zone their availability is defined in.
     */
    public String getTimezone() {
        OwnerSettings s = settings();
        return (s == null || s.timezone == null) ? "" : s.timezone;
    }
}
```

- [ ] **Step 4: Put the zone on the admin body**

In `src/main/resources/templates/adminBase.html`, change line 22 from:

```html
<body class="admin-canvas">
```

to:

```html
<body class="admin-canvas" data-tz="{inject:owner.timezone}">
```

- [ ] **Step 5: Make the picker optional in TZ_SCRIPT**

In `Layout.java`, replace this block (currently lines 46-54):

```java
              var picker = document.getElementById('tz-picker');
              var label  = document.getElementById('tz-label');
              if (!picker) { return; }
              ZONES.forEach(function (z) {
                var o = document.createElement('option');
                o.value = z; o.textContent = z;
                if (z === detected) { o.selected = true; }
                picker.appendChild(o);
              });
```

with:

```java
              var picker = document.getElementById('tz-picker');
              var label  = document.getElementById('tz-label');
              /* The picker is invitee-only. The /me pages have none and must still format, so
                 fall back to the owner's stored zone (body[data-tz]) and only then to detection. */
              if (picker) {
                ZONES.forEach(function (z) {
                  var o = document.createElement('option');
                  o.value = z; o.textContent = z;
                  if (z === detected) { o.selected = true; }
                  picker.appendChild(o);
                });
              }
```

Then change the first line of `render()` from:

```java
                var tz = picker.value;
```

to:

```java
                var tz = picker ? picker.value : (document.body.dataset.tz || detected);
```

And change the listener line (currently line 68) from:

```java
              picker.addEventListener('change', render);
```

to:

```java
              if (picker) { picker.addEventListener('change', render); }
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminTimeRenderingTest,LayoutLocaleMarkerTest,AdminPendingTest,BookPageTest
```

Expected: PASS. `BookPageTest` and `AdminPendingTest` are the regression guard — the invitee picker path and the pending page must both still render.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/OwnerInfo.java \
        src/main/java/site/asm0dey/calit/web/Layout.java \
        src/main/resources/templates/adminBase.html \
        src/test/java/site/asm0dey/calit/web/AdminTimeRenderingTest.java
git commit -m "fix(admin): format times on /me instead of printing raw ISO instants

TZ_SCRIPT bailed at 'if (!picker) return' and adminBase has no tz-picker, so
/me and /me/pending showed 2026-08-20T13:00:00Z UTC verbatim. The picker is now
optional and the no-picker path renders in the owner's STORED zone, so a
travelling host does not silently read bookings in the trip's zone."
```

- [ ] **Step 8: Run the full suite before leaving phase 1**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test
```

Expected: PASS, no new failures. Phase 1 is now complete and issue #116 is closed. If you are shipping incrementally, this is a good place to open the PR.

---

## Task 3: Persist the host's time-format preference

**Files:**
- Create: `src/main/resources/db/migration/V25__owner_time_format.sql`
- Modify: `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java`
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:1123-1151`
- Test: `src/test/java/site/asm0dey/calit/web/OwnerTimeFormatSettingTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `OwnerSettings.timeFormat` (public `String` field, one of `auto` / `h12` / `h23`, defaults to `"auto"`) and `OwnerSettings.HOUR_CYCLES` (a `Set<String>` of the three legal values). Tasks 4, 5 and 6 read both.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/OwnerTimeFormatSettingTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * The host's 12h/24h preference persists, and an unknown submitted value falls back to "auto"
 * rather than reaching the database (same guard shape as the locale field).
 */
@QuarkusTest
class OwnerTimeFormatSettingTest {

    /** DatabaseResetCallback reseeds per test and the admin user is always id 1. */
    private static final long ADMIN_ID = 1L;

    private void post(String timeFormat) {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("timeFormat", timeFormat)
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);
    }

    private String stored() {
        return QuarkusTransaction.requiringNew().call(() -> OwnerSettings.forOwner(ADMIN_ID).timeFormat);
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void savesAnExplicitTwelveHourPreference() {
        post("h12");
        assertEquals("h12", stored());
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void savesAnExplicitTwentyFourHourPreference() {
        post("h23");
        assertEquals("h23", stored());
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void rejectsAnUnknownValueAndFallsBackToAuto() {
        post("h11-and-a-half");
        assertEquals("auto", stored());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=OwnerTimeFormatSettingTest
```

Expected: FAIL — compilation error, `timeFormat` is not a member of `OwnerSettings`.

- [ ] **Step 3: Add the migration**

Create `src/main/resources/db/migration/V25__owner_time_format.sql`:

```sql
-- Issue #116 follow-up: the host's own 12h/24h preference for /me pages and their own emails.
-- Values are Intl's vocabulary so the client needs no mapping table: auto | h12 | h23.
-- 'auto' means "the viewer's device decides" on /me, and "leave the translated pattern alone"
-- in email (a server has no device to read), so this default is a no-op for existing rows.
ALTER TABLE owner_settings ADD COLUMN time_format varchar(8) NOT NULL DEFAULT 'auto';
```

- [ ] **Step 4: Add the entity field**

In `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java`, add the import and the field. Put the field directly after the existing `locale` field (around line 27):

```java
    /**
     * This owner's clock preference for their OWN surfaces: {@code auto} (the viewer's device on
     * /me, the translated pattern in email), {@code h12}, or {@code h23}. Never applied to
     * invitee-facing pages or invitee emails — a public booking page must not carry one person's
     * preference. Values match Intl's {@code hourCycle} vocabulary.
     */
    @Column(name = "time_format", nullable = false, length = 8)
    public String timeFormat = "auto";

    /** The legal {@link #timeFormat} values; anything else is coerced to {@code auto} on save. */
    public static final java.util.Set<String> HOUR_CYCLES = java.util.Set.of("auto", "h12", "h23");
```

- [ ] **Step 5: Accept and validate the form field**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, add a parameter to `updateSettings` (currently ending at line 1128):

```java
            @RestForm String ownerNotificationsEnabled,
            @RestForm String timeFormat) {
```

and inside the transaction, directly after the `row.locale = ...` line (currently 1140):

```java
            row.timeFormat =
                    timeFormat != null && OwnerSettings.HOUR_CYCLES.contains(timeFormat) ? timeFormat : "auto";
```

A form posted without the field (an older cached page) sends `null`, which must land on `auto`. The
explicit `timeFormat != null` guard is required, not decorative: `Set.of(...)` is an immutable set
and `contains(null)` **throws NullPointerException** rather than returning false, so omitting it
turns every settings POST from a page without the field into a 500.

- [ ] **Step 6: Run tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=OwnerTimeFormatSettingTest,AdminSettingsTest,OwnerLocaleSettingTest
```

Expected: PASS. Hibernate runs in validate-only mode, so a mismatch between the entity and V25 fails at boot — a green run also proves the migration and the entity agree.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V25__owner_time_format.sql \
        src/main/java/site/asm0dey/calit/domain/OwnerSettings.java \
        src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/test/java/site/asm0dey/calit/web/OwnerTimeFormatSettingTest.java
git commit -m "feat(settings): persist the host's 12h/24h preference

owner_settings.time_format holds auto|h12|h23, Intl's own vocabulary so no
mapping table is needed client-side. Defaults to auto, which reproduces
today's output on every surface."
```

---

## Task 4: Settings UI for the preference

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (Settings section, after line 720)
- Modify: `src/main/resources/messages/adm_de.properties` (Settings section, after `adm_settings_label_timezone`)
- Modify: `src/main/resources/messages/adm_he.properties` (same place)
- Modify: `src/main/resources/templates/AdminResource/settings.html:20`
- Test: `src/test/java/site/asm0dey/calit/web/OwnerTimeFormatSettingTest.java` (extend)

**Interfaces:**
- Consumes: `OwnerSettings.timeFormat` from Task 3.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Append to `OwnerTimeFormatSettingTest`:

```java
Add this static import to the top of the file:

```java
import static org.hamcrest.Matchers.containsString;
```

and this method to the class:

```java
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void settingsPageOffersAllThreeOptionsAndMarksTheSavedOne() {
        post("h12");

        given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .body(containsString("name=\"timeFormat\""))
                .body(containsString("value=\"auto\""))
                .body(containsString("value=\"h23\""))
                .body(containsString("value=\"h12\" selected"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=OwnerTimeFormatSettingTest#settingsPageOffersAllThreeOptionsAndMarksTheSavedOne
```

Expected: FAIL — no `name="timeFormat"` in the rendered settings page.

- [ ] **Step 3: Add the message keys**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, insert after `adm_settings_label_timezone()` (line 720):

```java
    @Message("Time format")
    String adm_settings_label_time_format();

    @Message("Automatic (from your device)")
    String adm_settings_time_format_auto();

    @Message("24-hour (14:30)")
    String adm_settings_time_format_h23();

    @Message("12-hour (2:30 PM)")
    String adm_settings_time_format_h12();
```

In `src/main/resources/messages/adm_de.properties`, after the `adm_settings_label_timezone=Zeitzone` line:

```properties
adm_settings_label_time_format=Zeitformat
adm_settings_time_format_auto=Automatisch (von deinem Gerät)
adm_settings_time_format_h23=24-Stunden (14:30)
adm_settings_time_format_h12=12-Stunden (2:30 PM)
```

In `src/main/resources/messages/adm_he.properties`, after the `adm_settings_label_timezone=אזור זמן` line:

```properties
adm_settings_label_time_format=תבנית שעה
adm_settings_time_format_auto=אוטומטי (לפי המכשיר שלך)
adm_settings_time_format_h23=24 שעות (14:30)
adm_settings_time_format_h12=12 שעות (2:30 PM)
```

- [ ] **Step 4: Add the select**

In `src/main/resources/templates/AdminResource/settings.html`, insert directly after the closing `</select>` of the language field (line 20):

```html
    <label class="label" for="set-time-format">{adm:adm_settings_label_time_format}</label>
    <select id="set-time-format" name="timeFormat" class="select w-full">
      <option value="auto" {#if settings && settings.timeFormat == 'auto'}selected{/if}>{adm:adm_settings_time_format_auto}</option>
      <option value="h23" {#if settings && settings.timeFormat == 'h23'}selected{/if}>{adm:adm_settings_time_format_h23}</option>
      <option value="h12" {#if settings && settings.timeFormat == 'h12'}selected{/if}>{adm:adm_settings_time_format_h12}</option>
    </select>
```

The `{#if settings && …}` guard copies the shape already used at `settings.html:22`. `{#let}` and
elvis-inside-a-comparison are deliberately avoided — `{#let}` appears nowhere in this codebase, and
the null case needs no special branch: with no settings row **no** option is marked selected, so the
browser selects the first one, which is `auto`. That is the wanted default.

- [ ] **Step 5: Run tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=OwnerTimeFormatSettingTest,AdminI18nTest,SettingsLocaleTest,CsrfFormCoverageTest
```

Expected: PASS. `CsrfFormCoverageTest` is the guard that the settings form still carries its CSRF token — the select is added inside the existing form, so nothing should move.

- [ ] **Step 6: Verify translation key parity**

```bash
for k in adm_settings_label_time_format adm_settings_time_format_auto \
         adm_settings_time_format_h23 adm_settings_time_format_h12; do
  for f in de he; do
    grep -q "^$k=" src/main/resources/messages/adm_$f.properties \
      && echo "ok   $f $k" || echo "MISS $f $k"
  done
done
```

Expected: eight `ok` lines, no `MISS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/main/resources/templates/AdminResource/settings.html \
        src/test/java/site/asm0dey/calit/web/OwnerTimeFormatSettingTest.java
git commit -m "feat(settings): time-format select on the owner settings page

Three options (auto/24h/12h) with de + he translations."
```

---

## Task 5: /me pages honour the preference

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/OwnerInfo.java`
- Modify: `src/main/resources/templates/adminBase.html:22`
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java` (the `HC` line from Task 1)
- Test: `src/test/java/site/asm0dey/calit/web/AdminTimeRenderingTest.java` (extend)

**Interfaces:**
- Consumes: `OwnerInfo` from Task 2, `OwnerSettings.timeFormat` from Task 3.
- Produces: `OwnerInfo.getHourCycle() -> String` (`""` for `auto`/absent, else `h12`/`h23`), rendered as `body[data-hc]` on admin pages only.

- [ ] **Step 1: Write the failing test**

Append to `AdminTimeRenderingTest`:

```java
    /** An explicit host preference reaches the /me pages... */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardCarriesAnExplicitHourCycle() {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("timeFormat", "h23")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-hc=\"h23\""))
                // the script prefers the server value over the device probe
                .body(containsString("document.body.dataset.hc"));
    }

    /** ...and "auto" leaves the device in charge, so no cycle is forced. */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void autoEmitsNoForcedHourCycle() {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("timeFormat", "auto")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-hc=\"\""));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminTimeRenderingTest
```

Expected: FAIL on the two new methods — no `data-hc` attribute is served.

- [ ] **Step 3: Expose the cycle on the bean**

In `src/main/java/site/asm0dey/calit/web/OwnerInfo.java`, add after `getTimezone()`:

```java
    /**
     * The owner's forced Intl {@code hourCycle} ({@code h12}/{@code h23}), or "" when they chose
     * {@code auto} — the client treats "" as "let the device decide", which is what auto means.
     */
    public String getHourCycle() {
        OwnerSettings s = settings();
        if (s == null || s.timeFormat == null || "auto".equals(s.timeFormat)) {
            return "";
        }
        return s.timeFormat;
    }
```

- [ ] **Step 4: Put the cycle on the admin body**

In `src/main/resources/templates/adminBase.html`, change line 22 from:

```html
<body class="admin-canvas" data-tz="{inject:owner.timezone}">
```

to:

```html
<body class="admin-canvas" data-tz="{inject:owner.timezone}" data-hc="{inject:owner.hourCycle}">
```

- [ ] **Step 5: Let the server value win in TZ_SCRIPT**

In `Layout.java`, replace the `HC` line added in Task 1:

```java
              var HC = new Intl.DateTimeFormat(undefined, {hour:'numeric'}).resolvedOptions().hourCycle;
```

with:

```java
              var HC = new Intl.DateTimeFormat(undefined, {hour:'numeric'}).resolvedOptions().hourCycle;
              /* On /me the host may force a cycle; invitee pages never emit data-hc, so the
                 device keeps deciding there. Empty string means "auto". */
              var forcedHC = document.body.dataset.hc;
              if (forcedHC) { HC = forcedHC; }
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminTimeRenderingTest,LayoutLocaleMarkerTest,BookPageTest
```

Expected: PASS. `BookPageTest` proves the public page is unaffected — `base.html` emits no `data-hc`, so `forcedHC` is `undefined` there and the device still decides.

- [ ] **Step 7: Assert the preference does not leak to invitees**

Append to `LayoutLocaleMarkerTest`:

```java
    /**
     * The host's clock preference must never reach a public booking page.
     *
     * <p>Regression guard, green by construction — NOT a red-first TDD test. Public pages render
     * from base.html, which has never emitted data-hc, so this passes before and after this task.
     * It fails the day someone copies the attribute over from adminBase.html and starts leaking
     * one host's clock convention to every invitee.</p>
     */
    @Test
    void publicBookingPageCarriesNoHostHourCycle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().when()
                .get("/layouttest/lt-intro")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.not(containsString("data-hc=")));
    }
```

Run it:

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=LayoutLocaleMarkerTest#publicBookingPageCarriesNoHostHourCycle
```

Expected: PASS immediately — `base.html` was never touched. This test is the standing guard against a future change adding it.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/OwnerInfo.java \
        src/main/java/site/asm0dey/calit/web/Layout.java \
        src/main/resources/templates/adminBase.html \
        src/test/java/site/asm0dey/calit/web/AdminTimeRenderingTest.java \
        src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java
git commit -m "feat(admin): honour the host's time-format preference on /me

body[data-hc] overrides the device probe on admin pages only; public booking
pages emit no data-hc, so an invitee always sees their own convention."
```

---

## Task 6: Host emails honour the preference

`EmailService.format()` builds from `email_datetime_pattern`, one fixed string per locale, all currently 24h. Rewriting `HH:mm` inside a translated pattern would mean string surgery on `'בשעה' HH:mm`, so translators get a second key instead. Server-side `auto` keeps the existing pattern — a server has no device to probe, and deriving from the locale would silently flip every English host's mail to AM/PM on upgrade.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java:531-533`
- Modify: `src/main/resources/messages/msg_de.properties:169`
- Modify: `src/main/resources/messages/msg_he.properties:169`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` (lines 336, 367, 399, 439, 469, 502, 535, 564 and the `format` calls at 342, 373, 405, 445, 477, 478, 511, 543, 570; plus `format` at 883)
- Test: `src/test/java/site/asm0dey/calit/email/EmailHourCycleTest.java`

**Interfaces:**
- Consumes: `OwnerSettings.timeFormat` from Task 3.
- Produces: nothing later tasks depend on. `RecipientBodyRenderer.render` gains a sixth parameter, `String hourCycle`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/EmailHourCycleTest.java`:

```java
package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

/**
 * A host who picks h12 gets AM/PM in their OWN copy; the invitee's copy is unaffected by the
 * host's preference (a booking page must not carry one person's clock convention), and "auto"
 * leaves the translated pattern alone so upgrading changes nothing.
 */
@QuarkusTest
class EmailHourCycleTest {

    private static final String OWNER_EMAIL = "owner-hc@example.com";
    private static final String INVITEE_EMAIL = "invitee-hc@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        QuarkusTransaction.requiringNew().run(() -> Booking.deleteAll());
    }

    /** 13:00 UTC is 13:00 in UTC — 24h renders "13:00", 12h renders "1:00 PM". */
    private long seed(String hostTimeFormat) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "UTC";
            s.locale = "en";
            s.ownerNotificationsEnabled = true;
            s.timeFormat = hostTimeFormat;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "HC Call";
            t.slug = "hc-call-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.persist();

            var start = Instant.parse("2026-06-08T13:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.answers = Map.of();
            b.locale = "en";
            b.persist();
            return b.id;
        });
    }

    @Test
    void hostCopyUsesTwelveHourWhenTheHostChoseIt() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long id = seed("h12");

        emailService.handleConfirmed(new BookingConfirmed(id));

        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toOwner.size(), "host must receive their copy");
        String html = toOwner.getFirst().getHtml();
        assertTrue(html.contains("1:00 PM"), "host copy must be 12-hour; got: " + html);
    }

    @Test
    void inviteeCopyIgnoresTheHostPreference() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long id = seed("h12");

        emailService.handleConfirmed(new BookingConfirmed(id));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive confirmation");
        String html = toInvitee.getFirst().getHtml();
        assertTrue(html.contains("13:00"), "invitee copy must keep the translated 24h pattern; got: " + html);
        assertFalse(html.contains("1:00 PM"), "host preference must not leak to the invitee");
    }

    @Test
    void autoLeavesTheTranslatedPatternAlone() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long id = seed("auto");

        emailService.handleConfirmed(new BookingConfirmed(id));

        String html = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getHtml();
        assertTrue(html.contains("13:00"), "auto must reproduce today's 24h output; got: " + html);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailHourCycleTest
```

Expected: FAIL — `hostCopyUsesTwelveHourWhenTheHostChoseIt` finds `13:00` where it wants `1:00 PM`. The other two should already pass; that is fine and expected (they are the regression guards).

- [ ] **Step 3: Add the 12-hour pattern keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, directly after `email_datetime_pattern()` (line 533):

```java
    /** 12-hour variant of {@link #email_datetime_pattern()}, used when a host chose {@code h12}. */
    @Message("EEEE, d MMMM yyyy 'at' h:mm a")
    String email_datetime_pattern_h12();
```

In `src/main/resources/messages/msg_de.properties`, after line 169:

```properties
email_datetime_pattern_h12=EEEE, d. MMMM yyyy 'um' h:mm a
```

In `src/main/resources/messages/msg_he.properties`, after line 169:

```properties
email_datetime_pattern_h12=EEEE, d 'ב'MMMM yyyy 'בשעה' h:mm a
```

- [ ] **Step 4: Add the format overload**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`, replace the existing `format` method (lines 883-886):

```java
    private String format(Instant instant, ZoneId zone, Locale locale) {
        String pattern = messages.forLocale(locale).email_datetime_pattern();
        return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone));
    }
```

with:

```java
    /** Invitee-facing paths: always the locale's own translated pattern. */
    private String format(Instant instant, ZoneId zone, Locale locale) {
        return format(instant, zone, locale, "auto");
    }

    /**
     * Recipient-aware variant. {@code hourCycle} is that recipient's {@code OwnerSettings.timeFormat}
     * for a host copy and always {@code auto} for an invitee copy. Only an explicit {@code h12}
     * switches patterns: a server has no device to probe, so {@code auto} means "leave the
     * translated pattern alone" — which keeps every existing host's mail byte-identical.
     */
    private String format(Instant instant, ZoneId zone, Locale locale, String hourCycle) {
        var m = messages.forLocale(locale);
        String pattern = "h12".equals(hourCycle) ? m.email_datetime_pattern_h12() : m.email_datetime_pattern();
        return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone));
    }
```

- [ ] **Step 5: Thread the cycle through the recipient renderer**

Change the functional interface (line 752) from:

```java
        String render(String role, Locale locale, ZoneId zone, String greetingName, Booking linkBooking);
```

to:

```java
        String render(
                String role, Locale locale, ZoneId zone, String greetingName, Booking linkBooking, String hourCycle);
```

Update its javadoc (lines 744-749) by appending one sentence:

```java
     * {@code hourCycle} is that recipient's own clock preference — the host's
     * {@code OwnerSettings.timeFormat} for an owner copy, always {@code "auto"} for the invitee.
```

Then update the three `bodyForRecipient.render(...)` call sites in `sendForKindLocaleAware`:

- line 808 (invitee) — append `, "auto"`:
  ```java
                bodyForRecipient.render(INVITEE_ROLE, inviteeLocale, l.zone, l.booking.inviteeName, l.booking, "auto"),
  ```
- line 820 (group host) — append `, hd.settings.timeFormat`:
  ```java
                        bodyForRecipient.render(
                                OWNER_ROLE, hostLocale, hostZone, hd.settings.ownerName, hd.booking,
                                hd.settings.timeFormat),
  ```
- line 829 (single owner) — append `, l.owner.timeFormat`:
  ```java
                    bodyForRecipient.render(
                            OWNER_ROLE, ownerLocale, l.zone, l.owner.ownerName, l.booking, l.owner.timeFormat),
  ```

- [ ] **Step 6: Update all eight renderer lambdas**

Each lambda's parameter list gains `hourCycle`, and each `format(...)` call inside it gains the argument. The lambdas are at lines **336, 367, 399, 439, 469, 502, 535, 564**. For every one of them:

```java
                (role, locale, zone, greetingName, linkBooking) -> Templates.someName(
```

becomes

```java
                (role, locale, zone, greetingName, linkBooking, hourCycle) -> Templates.someName(
```

and inside each, every occurrence of

```java
                                format(l.booking.startUtc, zone, locale),
```

becomes

```java
                                format(l.booking.startUtc, zone, locale, hourCycle),
```

There are nine such calls: lines 342, 373, 405, 445, 477, 511, 543, 570, plus **line 478** which formats a different instant and follows the same shape:

```java
                                format(e.oldStartUtc(), zone, locale, hourCycle),
```

Leave the three calls **outside** the renderers untouched — lines 600, 655 and 710 are guest-facing (`sendGuestInvites`, `handleGuestDeclined`, `guestCancelBody`) and correctly keep the three-argument overload, which means `auto`.

The compiler is the checklist here: the interface change makes every missed lambda a compile error.

- [ ] **Step 7: Run tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailHourCycleTest,EmailLocaleTest,EmailLocaleHebrewTest,EmailRoleCopyTest,MultiHostEmailFanoutTest,EmailServiceGuestTest
```

Expected: PASS. `MultiHostEmailFanoutTest` covers the per-host branch at line 820 and `EmailServiceGuestTest` the untouched guest paths.

- [ ] **Step 8: Verify translation key parity**

```bash
for f in de he; do
  grep -q "^email_datetime_pattern_h12=" src/main/resources/messages/msg_$f.properties \
    && echo "ok   $f" || echo "MISS $f"
done
```

Expected: two `ok` lines.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/test/java/site/asm0dey/calit/email/EmailHourCycleTest.java
git commit -m "feat(email): host copies follow the host's time-format preference

Translators own both clock forms via email_datetime_pattern_h12 rather than
rewriting HH:mm inside a translated pattern. Only an explicit h12 switches:
auto keeps the existing pattern, so upgrading changes no existing mail.
Invitee copies always pass auto."
```

---

## Task 7: Full verification and docs

**Files:**
- Modify: `CLAUDE.md` (the stale migration range)
- Modify (on the `docs-site` branch): `docs-site/src/content/docs/` configuration/usage page

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Run the whole suite**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn test
```

Expected: PASS, zero failures. Do not proceed on a red suite; do not claim completion without this output.

- [ ] **Step 2: Check formatting**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
mvn spotless:check
```

Expected: BUILD SUCCESS. On failure run `mvn spotless:apply` and amend.

- [ ] **Step 3: Fix the stale migration range in CLAUDE.md**

In `CLAUDE.md`, under "Database / migrations", change:

```
Flyway migrations `V1…V10` in `src/main/resources/db/migration/`
```

to:

```
Flyway migrations `V1…V25` in `src/main/resources/db/migration/`
```

- [ ] **Step 4: Close out the tracking bean**

```bash
beans update calit-0hyn -s completed --body-append "## Summary of Changes

Phase 1 (closes #116): TZ_SCRIPT takes hourCycle from the viewer's device while words
keep following documentElement.lang; the tz-picker became optional so /me and
/me/pending format at all, using the owner's stored timezone via the new
@Named(\"owner\") OwnerInfo bean.

Phase 2: owner_settings.time_format (V25) holds auto|h12|h23, editable on /me/settings
in all three locales, applied to /me via body[data-hc] and to host email copies via the
new email_datetime_pattern_h12 key. Invitee-facing surfaces never read it."
```

- [ ] **Step 5: Commit the code side**

```bash
git add CLAUDE.md .beans
git commit -m "docs: record the actual Flyway migration range and close calit-0hyn"
```

- [ ] **Step 6: Document on the docs-site branch**

Per CLAUDE.md, user-facing changes land on `docs-site` in the same effort. Switch to that branch and add, to the settings/configuration page:

- a **Time format** row describing `Automatic` / `24-hour` / `12-hour`, noting that `Automatic` follows the device on `/me` and leaves email in the language's own convention;
- a sentence on the booking page docs stating that invitees always see times in their own device's 12h/24h convention, and that a host's setting never changes what an invitee sees.

Do not merge docs into `main` — they live on `docs-site`, which deploys via `.github/workflows/docs.yml`.

---

## Notes for the implementer

- **`hourCycle` needs an hour field.** `Intl.DateTimeFormat(undefined, {}).resolvedOptions().hourCycle` is `undefined`; only `{hour:'numeric'}` populates it. This is why the probe looks redundant but is not.
- **`hourCycle` with `timeStyle` is legal.** The illegal mix is `dateStyle`/`timeStyle` with individual component options.
- **A forced 12h cycle on a 24h-authored pattern zero-pads** — `de` renders `um 02:30 PM`. Known and accepted; it only occurs when a host explicitly asks for 12h on a German or Hebrew page.
- **CSRF is off in `%test`** but on in prod. The settings form already carries `{inject:csrf.token}`; the new select goes inside that same form, so nothing to add.
- **Behavioural JS output is not covered by CI.** RestAssured cannot run scripts, so the assertions here are structural. The formatting evidence lives in the spec's Evidence section.
