# Localization (German) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multi-language support to calit with German as the first non-default language across public booking pages, owner admin UI, auth/bootstrap pages, and all emails; English stays default + per-key fallback.

**Architecture:** Native Quarkus Qute message bundles (`@MessageBundle` / `{msg:key}`) hold all UI strings. A single `LocaleResolver` CDI bean computes the active locale per request — owner's stored locale for `/me*`, else cookie → `Accept-Language` → English for everyone else. A `@TemplateGlobal` exposes the active language to templates for `<html lang>`. Invitee + owner locales are persisted (`Booking.locale`, `OwnerSettings.locale`) so async/scheduled emails render in the right language. Date/time localized both server-side (`DateTimeFormatter` with `Locale`) and client-side (the two inline scripts in `Layout` read `<html lang>`).

**Tech Stack:** Quarkus 3.36, Java 25, Qute, Qute i18n (`io.quarkus.qute.i18n`), Panache, Flyway, RestAssured + JUnit5 (`@QuarkusTest`).

## Global Constraints

- **No new runtime dependency.** Qute i18n ships with `quarkus-qute` (already present). Do not add libraries.
- **No runtime JS beyond the existing inline scripts.** Pages are server-rendered; the language switch is a cookie + GET redirect, never `localStorage`.
- **Owner scoping invariant.** Every tenant query filters by `currentOwner.id()`. New columns (`locale`) are per-row; do not leak across owners.
- **Never edit an applied Flyway migration.** New `V16__*.sql` only. Latest applied is `V15`.
- **CSRF.** `quarkus-rest-csrf` is ON in prod, OFF in `%test`. Every new `POST` form carries `{inject:csrf.token}`. The language switch is `GET` (no token).
- **Supported locales:** `en` (default + fallback), `de`. Single source of truth: config `app.supported-locales=en,de`.
- **Qute params:** `maven.compiler.parameters=true` is already set — keep template param names intact.
- **Tests assert on rendered strings/markers**, never by executing JS (RestAssured can't).
- **German strings authored by the implementer** (Claude), reviewed in PR. English is the `@Message` default value in the bundle interface; German lives in `msg_de.properties`.
- **Docs are part of done:** user-facing changes land on the `docs-site` branch in the same effort (final task).

---

## File Structure

**New files:**
- `src/main/java/com/calit/i18n/AppMessages.java` — `@MessageBundle` interface; every UI string is a `@Message` method (English default values).
- `src/main/java/com/calit/i18n/AppLocales.java` — supported-locale list + parsing/negotiation helpers (pure, unit-testable).
- `src/main/java/com/calit/i18n/CurrentLocaleResolver.java` — Qute `LocaleResolver` bean: owner locale for `/me*`, else cookie → `Accept-Language` → default.
- `src/main/java/com/calit/i18n/LangGlobal.java` — `@TemplateGlobal` exposing `lang` (BCP-47 string) to all templates.
- `src/main/java/com/calit/i18n/Messages.java` — thin helper to fetch a locale-specific `AppMessages` for Java code (email subjects), backed by `@Localized` injections.
- `src/main/java/com/calit/web/LangResource.java` — `GET /lang/{code}` switch endpoint.
- `src/main/resources/messages/msg_de.properties` — German translations.
- `src/main/resources/db/migration/V16__locale_columns.sql` — adds `locale` to `owner_settings` + `booking`.
- Test files mirrored under `src/test/java/com/calit/...` per task.

**Modified files:**
- `src/main/resources/application.properties` — `quarkus.default-locale=en`, `app.supported-locales=en,de`.
- `src/main/java/com/calit/domain/OwnerSettings.java` — `public String locale = "en";`.
- `src/main/java/com/calit/booking/Booking.java` — `public String locale = "en";`.
- `src/main/java/com/calit/booking/BookingService.java` — `book(...)` accepts + stores invitee locale.
- `src/main/java/com/calit/booking/BookingResource.java` + `PublicResource` (web form) — pass resolved invitee locale to `book(...)`.
- `src/main/java/com/calit/email/EmailService.java` — locale-aware date format + subjects; render with explicit `.setLocale(...)`.
- `src/main/java/com/calit/web/Layout.java` — `TZ_SCRIPT` / `CALENDAR_SCRIPT` read `<html lang>`.
- `src/main/resources/templates/base.html`, `adminBase.html` — `<html lang="{lang}">`.
- All templates under `src/main/resources/templates/**` — hardcoded strings → `{msg:key}`.
- `src/main/java/com/calit/web/AdminResource.java` — settings: language `<select>` persisted to `OwnerSettings.locale`.
- `src/main/resources/templates/AdminResource/settings.html` — language select.

---

## Key Conventions (read once, apply everywhere)

**Message key naming:** `<area>_<screen>_<what>`, lowercase snake_case. Areas: `pub` (public/invitee), `adm` (admin UI), `auth` (login/signup/reset/setup), `email`, `common` (shared: buttons, nav). Examples: `pub_book_title`, `adm_settings_save`, `auth_login_submit`, `email_confirmation_subject`, `common_cancel`.

**Bundle method signature:** message with no params → `String key();`; with params → `String key(String name)` and `@Message("... {name} ...")`. Parameter names must match placeholders (compiler `-parameters` makes Qute see them).

**Template usage:** `{msg:pub_book_title}` / `{msg:email_confirmation_subject}` / with args `{msg:pub_greeting(inviteeName)}`.

**English vs German:** English is the `@Message(...)` default on the interface method. German is a line in `msg_de.properties` keyed by method name: `pub_book_title=Termin buchen`. A key missing from `msg_de.properties` falls back to the English default automatically.

---

## Task 1: Supported-locale negotiation helper (pure logic, TDD)

**Files:**
- Create: `src/main/java/com/calit/i18n/AppLocales.java`
- Test: `src/test/java/com/calit/i18n/AppLocalesTest.java`

**Interfaces:**
- Produces:
  - `AppLocales.DEFAULT` → `Locale` (`Locale.ENGLISH`)
  - `AppLocales.supported()` → `List<Locale>` (`[en, de]`, read from config)
  - `AppLocales.isSupported(String tag)` → `boolean`
  - `AppLocales.pick(String tag)` → `Locale` — exact-or-language match against supported, else `DEFAULT`. Null/blank → `DEFAULT`.
  - `AppLocales.fromAcceptLanguage(String header)` → `Locale` — parse `Accept-Language`, return best supported match or `DEFAULT`.

- [ ] **Step 1: Write failing test**

```java
package com.calit.i18n;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class AppLocalesTest {
    @Test void picksExactSupported() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de"));
    }
    @Test void picksByLanguageIgnoringRegion() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de-AT"));
    }
    @Test void unsupportedFallsBackToDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.pick("fr"));
    }
    @Test void nullOrBlankIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.pick(null));
        assertEquals(AppLocales.DEFAULT, AppLocales.pick("  "));
    }
    @Test void acceptLanguagePicksBestSupported() {
        assertEquals(Locale.GERMAN, AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9,de;q=0.8,en;q=0.7"));
    }
    @Test void acceptLanguageNoneSupportedIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.fromAcceptLanguage("fr-FR,fr;q=0.9"));
    }
    @Test void acceptLanguageNullIsDefault() {
        assertEquals(AppLocales.DEFAULT, AppLocales.fromAcceptLanguage(null));
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -Dtest=AppLocalesTest`
Expected: FAIL — `AppLocales` does not exist (compile error).

- [ ] **Step 3: Implement**

```java
package com.calit.i18n;

import org.eclipse.microprofile.config.ConfigProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Supported-locale list + negotiation. Pure; no CDI so it stays unit-testable. */
public final class AppLocales {
    private AppLocales() {}

    public static final Locale DEFAULT = Locale.ENGLISH;

    public static List<Locale> supported() {
        String csv = ConfigProvider.getConfig()
                .getOptionalValue("app.supported-locales", String.class).orElse("en,de");
        List<Locale> out = new ArrayList<>();
        for (String tag : csv.split(",")) {
            String t = tag.trim();
            if (!t.isEmpty()) out.add(Locale.forLanguageTag(t));
        }
        if (out.isEmpty()) out.add(DEFAULT);
        return out;
    }

    public static boolean isSupported(String tag) {
        if (tag == null || tag.isBlank()) return false;
        Locale want = Locale.forLanguageTag(tag.trim());
        return supported().stream().anyMatch(l -> l.getLanguage().equals(want.getLanguage()));
    }

    /** Exact-or-language match against supported; null/blank/unsupported → DEFAULT. */
    public static Locale pick(String tag) {
        if (tag == null || tag.isBlank()) return DEFAULT;
        Locale want = Locale.forLanguageTag(tag.trim());
        return supported().stream()
                .filter(l -> l.getLanguage().equals(want.getLanguage()))
                .findFirst().orElse(DEFAULT);
    }

    /** Best supported match from an Accept-Language header, honoring q-order; else DEFAULT. */
    public static Locale fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return DEFAULT;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            Locale best = Locale.lookup(ranges, supported());
            return best != null ? best : DEFAULT;
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `mvn test -Dtest=AppLocalesTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/i18n/AppLocales.java src/test/java/com/calit/i18n/AppLocalesTest.java
git commit -m "feat(i18n): supported-locale negotiation helper"
```

---

## Task 2: Message bundle skeleton + config

**Files:**
- Create: `src/main/java/com/calit/i18n/AppMessages.java`
- Create: `src/main/resources/messages/msg_de.properties`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/calit/i18n/AppMessagesTest.java`

**Interfaces:**
- Produces: `AppMessages` `@MessageBundle` with a starter set of `@Message` methods. Later string-extraction tasks ADD methods here. Namespace in templates: `msg`.

- [ ] **Step 1: Add config**

Append to `src/main/resources/application.properties`:

```properties
# i18n
quarkus.default-locale=en
app.supported-locales=en,de
```

- [ ] **Step 2: Write failing test**

```java
package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AppMessagesTest {
    @Inject AppMessages en;
    @Inject @Localized("de") AppMessages de;

    @Test void englishDefault() { assertEquals("Cancel", en.common_cancel()); }
    @Test void germanOverride() { assertEquals("Abbrechen", de.common_cancel()); }
}
```

- [ ] **Step 3: Run, verify it fails**

Run: `mvn test -Dtest=AppMessagesTest`
Expected: FAIL — `AppMessages` missing.

- [ ] **Step 4: Implement bundle + German file**

`AppMessages.java`:

```java
package com.calit.i18n;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * Type-safe UI string bundle. English is the default value here; German lives in
 * src/main/resources/messages/msg_de.properties keyed by method name. Missing German
 * key falls back to the English default automatically. Template namespace: {msg:key}.
 *
 * Keys follow <area>_<screen>_<what>: pub_*, adm_*, auth_*, email_*, common_*.
 */
@MessageBundle // default namespace "msg"
public interface AppMessages {

    @Message("Cancel")
    String common_cancel();
}
```

`msg_de.properties`:

```properties
common_cancel=Abbrechen
```

- [ ] **Step 5: Run, verify pass**

Run: `mvn test -Dtest=AppMessagesTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/i18n/AppMessages.java src/main/resources/messages/msg_de.properties src/main/resources/application.properties src/test/java/com/calit/i18n/AppMessagesTest.java
git commit -m "feat(i18n): message bundle skeleton + locale config"
```

---

## Task 3: DB migration + entity columns

**Files:**
- Create: `src/main/resources/db/migration/V16__locale_columns.sql`
- Modify: `src/main/java/com/calit/domain/OwnerSettings.java`, `src/main/java/com/calit/booking/Booking.java`
- Test: `src/test/java/com/calit/i18n/LocaleColumnsTest.java`

**Interfaces:**
- Produces: `OwnerSettings.locale` (`String`, default `"en"`), `Booking.locale` (`String`, default `"en"`).

- [ ] **Step 1: Write the migration**

`V16__locale_columns.sql`:

```sql
ALTER TABLE owner_settings ADD COLUMN locale varchar(8) NOT NULL DEFAULT 'en';
ALTER TABLE booking        ADD COLUMN locale varchar(8) NOT NULL DEFAULT 'en';
```

- [ ] **Step 2: Add entity fields**

In `OwnerSettings.java`, after `timezone`:

```java
    /** BCP-47 language tag for this owner's admin UI + owner-copy emails. */
    @Column(nullable = false)
    public String locale = "en";
```

In `Booking.java`, after `manageToken` (or near other scalar fields):

```java
    /** BCP-47 language tag captured from the invitee at booking time; drives invitee emails. */
    @Column(nullable = false)
    public String locale = "en";
```

(Use the same `jakarta.persistence.Column` import style already in each file; if `@Column` isn't imported, add it.)

- [ ] **Step 3: Write failing test**

```java
package com.calit.i18n;

import com.calit.domain.OwnerSettings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class LocaleColumnsTest {
    @Test @Transactional
    void ownerSettingsHasLocaleDefaultingToEn() {
        OwnerSettings s = OwnerSettings.forOwner(1L); // admin always id 1
        assertEquals("en", s.locale);
    }
}
```

- [ ] **Step 4: Run, verify pass** (Hibernate validate-only must accept the new columns; Flyway applies V16 at boot)

Run: `mvn test -Dtest=LocaleColumnsTest`
Expected: PASS. (A failure here usually means a schema/entity mismatch — fix the column type/nullability, not the migration if already applied.)

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V16__locale_columns.sql src/main/java/com/calit/domain/OwnerSettings.java src/main/java/com/calit/booking/Booking.java src/test/java/com/calit/i18n/LocaleColumnsTest.java
git commit -m "feat(i18n): add locale columns to owner_settings and booking"
```

---

## Task 4: Locale resolver + `<html lang>` global

**Files:**
- Create: `src/main/java/com/calit/i18n/CurrentLocaleResolver.java`, `src/main/java/com/calit/i18n/LangGlobal.java`
- Test: `src/test/java/com/calit/i18n/LocaleResolutionTest.java` (uses a tiny test-only template)

**Interfaces:**
- Consumes: `CurrentOwner` (from `com.calit.user`), `AppLocales` (Task 1), `OwnerSettings.locale` (Task 3).
- Produces: per-request locale for Qute message rendering; `{lang}` template global (BCP-47 string of the active locale).

**Resolution order (single bean):**
1. If `CurrentOwner` is set (i.e. an owner-scoped `/me*` request) → `AppLocales.pick(owner's OwnerSettings.locale)`.
2. Else cookie `calit_lang` → `AppLocales.pick(cookie)` if supported.
3. Else `Accept-Language` → `AppLocales.fromAcceptLanguage(header)`.
4. Else `AppLocales.DEFAULT`.
Return `null` when there is no active HTTP request (e.g. scheduler thread) so emails fall back to explicit `.setLocale(...)`.

- [ ] **Step 1: Implement the resolver**

```java
package com.calit.i18n;

import com.calit.domain.OwnerSettings;
import com.calit.user.CurrentOwner;
import io.quarkus.qute.i18n.LocaleResolver;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Locale;

/**
 * Computes the active locale for every Qute render. Owner UI ({@code /me*}) uses the owner's
 * stored locale; everyone else uses cookie -> Accept-Language -> default. Returns null when
 * there is no live HTTP request (scheduler/outbox threads), so email rendering must set the
 * locale explicitly via TemplateInstance#setLocale.
 */
@ApplicationScoped
public class CurrentLocaleResolver implements LocaleResolver {

    @Inject Instance<CurrentOwner> currentOwner;   // request-scoped; Instance guards no-request threads
    @Inject Instance<HttpServerRequest> request;

    @Override
    public Locale getLocale() {
        // Owner-scoped request: owner's chosen language wins.
        if (currentOwner.isResolvable()) {
            CurrentOwner co = currentOwner.get();
            if (co.isSet()) {
                OwnerSettings s = OwnerSettings.forOwner(co.id());
                if (s != null && s.locale != null) return AppLocales.pick(s.locale);
            }
        }
        if (!request.isResolvable()) return null; // no HTTP request (scheduler) -> defer
        HttpServerRequest req = request.get();
        if (req == null) return null;

        String cookie = req.getCookie("calit_lang") != null ? req.getCookie("calit_lang").getValue() : null;
        if (cookie != null && AppLocales.isSupported(cookie)) return AppLocales.pick(cookie);

        return AppLocales.fromAcceptLanguage(req.getHeader("Accept-Language"));
    }
}
```

- [ ] **Step 2: Implement the template global**

```java
package com.calit.i18n;

import io.quarkus.qute.TemplateGlobal;
import io.quarkus.qute.i18n.MessageBundles;

/** Exposes {lang} (active BCP-47 tag) to every template for <html lang="{lang}">. */
@TemplateGlobal
public class LangGlobal {
    static String lang() {
        java.util.Locale l = MessageBundles.getCurrentLocale();
        return (l != null ? l : AppLocales.DEFAULT).toLanguageTag();
    }
}
```

> NOTE: if `MessageBundles.getCurrentLocale()` is not available in this Qute version, inject `CurrentLocaleResolver` and call `getLocale()` (null → DEFAULT) instead. Verify against the `io.quarkus.qute.i18n` API at implementation time via the javadocs MCP.

- [ ] **Step 3: Write failing test** (test-only template proves both resolution paths)

Create `src/test/resources/templates/i18nProbe.html`:

```html
lang={lang}|cancel={msg:common_cancel}
```

Create the test:

```java
package com.calit.i18n;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LocaleResolutionTest {
    @Test void publicCookieDeRendersGerman() {
        given().cookie("calit_lang", "de").when().get("/__i18n_probe")
            .then().statusCode(200)
            .body(containsString("lang=de")).body(containsString("cancel=Abbrechen"));
    }
    @Test void publicAcceptLanguageDe() {
        given().header("Accept-Language", "de").when().get("/__i18n_probe")
            .then().statusCode(200).body(containsString("cancel=Abbrechen"));
    }
    @Test void unsupportedFallsToEnglish() {
        given().header("Accept-Language", "fr").when().get("/__i18n_probe")
            .then().statusCode(200).body(containsString("cancel=Cancel"));
    }
}
```

Add a test-only probe resource `src/test/java/com/calit/i18n/I18nProbeResource.java`:

```java
package com.calit.i18n;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/__i18n_probe")
public class I18nProbeResource {
    @CheckedTemplate(basePath = "")
    static class Templates { static native TemplateInstance i18nProbe(); }

    @GET @Produces(MediaType.TEXT_PLAIN)
    public TemplateInstance probe() { return Templates.i18nProbe(); }
}
```

- [ ] **Step 4: Run, verify fail then pass**

Run: `mvn test -Dtest=LocaleResolutionTest`
First expected: FAIL (resolver/global/probe missing or German not resolving). Implement until: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/i18n/CurrentLocaleResolver.java src/main/java/com/calit/i18n/LangGlobal.java src/test/java/com/calit/i18n/ src/test/resources/templates/i18nProbe.html
git commit -m "feat(i18n): per-request locale resolver + {lang} template global"
```

---

## Task 5: Invitee language switch endpoint

**Files:**
- Create: `src/main/java/com/calit/web/LangResource.java`
- Test: `src/test/java/com/calit/web/LangResourceTest.java`

**Interfaces:**
- Consumes: `AppLocales.isSupported` (Task 1).
- Produces: `GET /lang/{code}?return=<localPath>` → sets `calit_lang` cookie, 303 redirect to `return` (validated local path; default `/`).

- [ ] **Step 1: Write failing test**

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class LangResourceTest {
    @Test void setsCookieAndRedirectsToReturn() {
        given().redirects().follow(false)
            .when().get("/lang/de?return=/alice/intro")
            .then().statusCode(303)
            .header("Location", endsWith("/alice/intro"))
            .cookie("calit_lang", "de");
    }
    @Test void unsupportedCodeIgnoredCookieNotSet() {
        given().redirects().follow(false)
            .when().get("/lang/fr?return=/x")
            .then().statusCode(303).header("Set-Cookie", anyOf(nullValue(), not(containsString("calit_lang=fr"))));
    }
    @Test void rejectsNonLocalReturn() {
        given().redirects().follow(false)
            .when().get("/lang/de?return=https://evil.test/x")
            .then().statusCode(303).header("Location", endsWith("/"));
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -Dtest=LangResourceTest`
Expected: FAIL — 404, `LangResource` missing.

- [ ] **Step 3: Implement**

```java
package com.calit.web;

import com.calit.i18n.AppLocales;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/** Invitee language switch. GET (no state mutation beyond a preference cookie) -> no CSRF token. */
@Path("/lang")
public class LangResource {

    @GET
    @Path("/{code}")
    public Response set(@PathParam("code") String code, @QueryParam("return") String ret) {
        String target = safeLocal(ret);
        Response.ResponseBuilder rb = Response.seeOther(URI.create(target)); // 303
        if (AppLocales.isSupported(code)) {
            rb.cookie(new NewCookie.Builder("calit_lang")
                    .value(code).path("/").maxAge(60 * 60 * 24 * 365)
                    .sameSite(NewCookie.SameSite.LAX).build());
        }
        return rb.build();
    }

    /** Only same-site absolute paths; anything else -> "/". Blocks open redirects. */
    private static String safeLocal(String ret) {
        if (ret == null || !ret.startsWith("/") || ret.startsWith("//")) return "/";
        return ret;
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `mvn test -Dtest=LangResourceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/LangResource.java src/test/java/com/calit/web/LangResourceTest.java
git commit -m "feat(i18n): /lang/{code} invitee language switch"
```

---

## Task 6: Owner language setting (UI + persistence)

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java:595-612` (the `updateSettings` handler + `settings()` getter), and `settings()` Templates signature if needed
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Test: `src/test/java/com/calit/web/OwnerLocaleSettingTest.java`

**Interfaces:**
- Consumes: `OwnerSettings.locale` (Task 3), `AppLocales` (Task 1).
- Produces: owner can set their locale; `/me*` renders in it (proves Task 4 owner path end-to-end).

- [ ] **Step 1: Write failing test**

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class OwnerLocaleSettingTest {

    @Test @TestSecurity(user = "admin", roles = "user")
    void savingGermanThenRenderingMeShowsGerman() {
        // CSRF is OFF in %test, so a bare form POST is accepted.
        given().formParam("ownerName", "Admin").formParam("timezone", "UTC")
               .formParam("locale", "de").formParam("reminderLeadMinutes", "60")
               .when().post("/me/settings").then().statusCode(anyOf(is(200), is(303)));

        given().when().get("/me/settings").then().statusCode(200)
               .body(containsString("Abbrechen")); // a known translated string on the page
    }
}
```

(Adjust the asserted German string to one that actually appears on the settings page after Task 9 translates it; before that, assert the `<option value="de" selected>` is present instead.)

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -Dtest=OwnerLocaleSettingTest`
Expected: FAIL — `locale` form param not handled / select absent.

- [ ] **Step 3: Implement handler change**

In `AdminResource.updateSettings`, add `@RestForm String locale` param and persist:

```java
    public TemplateInstance updateSettings(@RestForm String ownerName,
                                           @RestForm String timezone,
                                           @RestForm String locale,
                                           /* ...existing params... */) {
        OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
        if (s == null) { s = new OwnerSettings(); s.ownerId = currentOwner.id(); }
        s.ownerName = ownerName;
        s.timezone = timezone;
        s.locale = com.calit.i18n.AppLocales.isSupported(locale) ? locale : "en";
        // ...existing persistence...
    }
```

- [ ] **Step 4: Add the select to `settings.html`**

Inside the settings form (follow the existing field markup; carry the CSRF token that the form already injects):

```html
<label class="form-control w-full">
  <span class="label-text">{msg:adm_settings_language}</span>
  <select name="locale" class="select select-bordered">
    <option value="en" {#if settings.locale == 'en'}selected{/if}>English</option>
    <option value="de" {#if settings.locale == 'de'}selected{/if}>Deutsch</option>
  </select>
</label>
```

Add to `AppMessages`: `@Message("Language") String adm_settings_language();` and to `msg_de.properties`: `adm_settings_language=Sprache`.

- [ ] **Step 5: Run, verify pass**

Run: `mvn test -Dtest=OwnerLocaleSettingTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java src/main/resources/templates/AdminResource/settings.html src/main/java/com/calit/i18n/AppMessages.java src/main/resources/messages/msg_de.properties src/test/java/com/calit/web/OwnerLocaleSettingTest.java
git commit -m "feat(i18n): owner language setting"
```

---

## Task 7: Persist invitee locale on booking

**Files:**
- Modify: `src/main/java/com/calit/booking/BookingService.java` (the `book(...)` method), `src/main/java/com/calit/booking/BookingResource.java`, the web booking form handler in `PublicResource`
- Test: `src/test/java/com/calit/booking/BookingLocaleTest.java`

**Interfaces:**
- Consumes: `CurrentLocaleResolver`/`MessageBundles.getCurrentLocale()` (Task 4) to read the active invitee locale at booking time.
- Produces: `Booking.locale` is set from the request locale; `BookingService.book(...)` gains a trailing `String locale` parameter.

> Note the existing guard in `BookingResource`: all booking creation MUST go through `BookingService.book`. Add the param there; do not branch logic.

- [ ] **Step 1: Write failing test**

```java
package com.calit.booking;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BookingLocaleTest {
    @Inject BookingService bookingService;

    @Test
    void bookStoresProvidedLocale() {
        // Seeded admin (id 1) + a seeded meeting type slug; reuse whatever existing booking tests use.
        Booking b = bookingService.book(1L, "intro", nextAvailableInstant(),
                "Erika", "erika@example.de", java.util.Map.of(), null, null, "de");
        assertEquals("de", b.locale);
    }
    // nextAvailableInstant(): copy the slot-picking helper pattern from BookingServiceTest.
}
```

(Mirror the exact `book(...)` argument list used by existing `BookingServiceTest`; append `"de"` as the new last arg.)

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -Dtest=BookingLocaleTest`
Expected: FAIL — `book` has no locale parameter (compile error).

- [ ] **Step 3: Implement**

- Add `String locale` as the last parameter of `BookingService.book(...)`; inside, set `booking.locale = AppLocales.isSupported(locale) ? locale : "en";` before persist.
- In `BookingResource.create`, resolve the request locale and pass it: read `MessageBundles.getCurrentLocale()` (null → "en") → `.toLanguageTag()`, or accept it from the JSON `BookRequest` if the web form already sends it. Simplest: resolve server-side from the request, ignore client input.
- In the `PublicResource` web booking form handler, pass the active locale the same way.

```java
// in both call sites:
java.util.Locale cur = io.quarkus.qute.i18n.MessageBundles.getCurrentLocale();
String locale = (cur != null ? cur : com.calit.i18n.AppLocales.DEFAULT).getLanguage();
Booking b = bookingService.book(owner.id, slug, start, name, email, answers, /*...*/, locale);
```

- [ ] **Step 4: Run, verify pass** (also run the existing booking suite to catch the signature change)

Run: `mvn test -Dtest=BookingLocaleTest,BookingServiceTest`
Expected: PASS. Fix any existing `book(...)` callers that now miss the argument.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/ src/main/java/com/calit/web/PublicResource.java src/test/java/com/calit/booking/BookingLocaleTest.java
git commit -m "feat(i18n): capture invitee locale on booking"
```

---

## Task 8: Locale-aware emails (subjects + dates + render locale)

**Files:**
- Modify: `src/main/java/com/calit/email/EmailService.java`
- Create: `src/main/java/com/calit/i18n/Messages.java`
- Modify: `src/main/resources/templates/email/*.html`
- Test: `src/test/java/com/calit/email/EmailLocaleTest.java`

**Interfaces:**
- Consumes: `AppMessages` + `@Localized("de")` (Task 2), `Booking.locale` / `OwnerSettings.locale` (Task 3).
- Produces: invitee emails render from `booking.locale`; owner-copy emails from `OwnerSettings.locale`; dates formatted in that locale; subjects from the bundle.

- [ ] **Step 1: Implement the Java-side message accessor** (`Messages.java`)

```java
package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/** Locale-specific AppMessages for Java code (e.g. email subjects). Two-locale switch. */
@ApplicationScoped
public class Messages {
    @Inject AppMessages en;
    @Inject @Localized("de") AppMessages de;

    public AppMessages forLocale(Locale l) {
        // ponytail: two-locale switch; replace with a Map<lang,AppMessages> when a 3rd lands.
        return (l != null && "de".equals(l.getLanguage())) ? de : en;
    }
    public AppMessages forTag(String tag) { return forLocale(AppLocales.pick(tag)); }
}
```

- [ ] **Step 2: Write failing test**

```java
package com.calit.email;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EmailLocaleTest {
    @Inject com.calit.i18n.Messages messages;

    @Test void germanSubjectResolves() {
        String subj = messages.forTag("de").email_confirmation_subject();
        assertNotEquals(messages.forTag("en").email_confirmation_subject(), subj);
        assertFalse(subj.isBlank());
    }
}
```

(Stronger end-to-end: if existing email tests capture rendered bodies via the mock mailer, add one asserting a `de` booking yields a German date string like a German weekday name. Mirror the existing email test harness.)

- [ ] **Step 3: Run, verify it fails**

Run: `mvn test -Dtest=EmailLocaleTest`
Expected: FAIL — `email_confirmation_subject` / `Messages` missing.

- [ ] **Step 4: Implement**

- Add the email `@Message` methods to `AppMessages` (subjects + body strings) with English defaults; add German lines to `msg_de.properties`.
- In `EmailService`: replace the static `TIME_FORMAT` (hardcoded `Locale.ENGLISH`) with a per-call formatter built from the target locale:

```java
private static String format(Instant instant, ZoneId zone, Locale locale) {
    return DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", locale)
            .format(instant.atZone(zone));
}
```

  (The literal `'at'` stays for English; for German, switch the whole pattern via a `@Message` key — e.g. `email_datetime_pattern` returning `EEEE, d. MMMM yyyy 'um' HH:mm` for `de` — and build the formatter from that key per locale.)
- Resolve the locale per recipient: invitee sends use `AppLocales.pick(booking.locale)`; owner sends use `AppLocales.pick(ownerSettings.locale)`. Thread it into `format(...)` and into subject lookups via `messages.forLocale(locale)`.
- Render email templates with explicit locale: `template.instance().setLocale(locale.toLanguageTag()).data(...).render()` so `{msg:...}` inside email templates matches (the request-scoped resolver returns null on the outbox/scheduler thread).

- [ ] **Step 5: Run, verify pass**

Run: `mvn test -Dtest=EmailLocaleTest`
Expected: PASS. Then run the full email suite: `mvn test -Dtest='*Email*,*Reminder*'`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/email/EmailService.java src/main/java/com/calit/i18n/Messages.java src/main/java/com/calit/i18n/AppMessages.java src/main/resources/messages/msg_de.properties src/main/resources/templates/email/ src/test/java/com/calit/email/EmailLocaleTest.java
git commit -m "feat(i18n): locale-aware email subjects, dates, and rendering"
```

---

## Task 9: Translate template surface — repeatable procedure (one task PER group below)

**This is mechanical and repeated.** Tasks 9a–9d each cover one template group. The procedure is identical; only the file list and strings change. Do them one group at a time, each its own commit, so a reviewer can gate each.

**Procedure (per group):**

1. For each template in the group, find every human-visible literal (page text, button labels, headings, placeholders, `aria-label`s, `title`s). Skip: CSS classes, data attributes, URLs, code, owner-entered content.
2. For each literal, add a `@Message("<English>")` method to `AppMessages` using the key convention (`<area>_<screen>_<what>`), and replace the literal in the template with `{msg:key}`. Strings with dynamic values become parameterized messages (`@Message("Booked with {name}") String pub_conf_with(String name);` → `{msg:pub_conf_with(ownerName)}`).
3. Add the German translation line to `msg_de.properties` for each new key.
4. Set `<html lang="{lang}">` in the group's base layout if not already done (Task covers base templates in 9a).
5. **Use logical Tailwind classes** in any markup you touch (`ms-`/`me-`/`ps-`/`pe-`/`text-start`/`text-end`/`start-`/`end-`) instead of physical (`ml-`/`pr-`/`text-left`/`left-`) — free RTL hedge.
6. Build CSS only if you changed classes: `bun run css:build`.
7. Run that group's page tests + a focused locale assertion.
8. Commit.

**Per-group test pattern** (add one such test per group, e.g. `PublicI18nTest`):

```java
@Test void publicPageGermanViaCookie() {
    given().cookie("calit_lang", "de").when().get("/alice/intro")
        .then().statusCode(200).body(containsString("<html lang=\"de\"")).body(containsString("Termin buchen"));
}
@Test void publicPageEnglishDefault() {
    given().when().get("/alice/intro")
        .then().statusCode(200).body(containsString("Book")); // English default
}
```

(Use seeded fixtures the existing public-page tests already rely on; assert on a string you actually translated.)

### Task 9a: base layouts + public/invitee flow

**Files (modify):** `base.html`, `adminBase.html`, `templates/PublicResource/*.html` (landing, index, book, confirmation, cancelled, manage, unavailable, notReady), plus `AppMessages` + `msg_de.properties`.
**Test:** `src/test/java/com/calit/web/PublicI18nTest.java`.
First set `<html lang="{lang}">` in `base.html`/`adminBase.html` (replace `lang="en"`). Then apply the procedure to the public templates.
Commit: `feat(i18n): translate public booking flow + base layouts`.

### Task 9b: owner admin UI

**Files (modify):** `templates/AdminResource/*.html` (dashboard, meetingTypes, meetingTypeDetail, availability, dateOverrides, bookingFields, pending, settings), `templates/MeSetupResource/meSetup.html`, `templates/GooglePageResource/google.html`, `templates/UsersResource/users.html`.
**Test:** `src/test/java/com/calit/web/AdminI18nTest.java` — set owner locale to `de` (via Task 6 flow or direct `OwnerSettings` write in a `@Transactional` setup), assert `/me/...` renders German regardless of cookie.
Commit: `feat(i18n): translate owner admin UI`.

### Task 9c: auth / bootstrap

**Files (modify):** `templates/LoginResource/login.html`, `templates/SignupResource/signup.html`, `templates/PasswordResetResource/forgot.html`, `templates/PasswordResetResource/reset.html`, `templates/SetupResource/setup.html`, `templates/GoogleLoginResource/bridge.html`.
**Test:** `src/test/java/com/calit/web/AuthI18nTest.java` — login page German via cookie/Accept-Language.
Commit: `feat(i18n): translate auth and bootstrap pages`.

### Task 9d: email templates

**Files (modify):** `templates/email/*.html` (confirmation, requested, reminder, cancellation, reschedule, declined, password-reset, google-disconnected).
(Subjects + render-locale wiring already done in Task 8; this is the in-template body strings.)
**Test:** extend `EmailLocaleTest` to assert a rendered German body contains a known German string.
Commit: `feat(i18n): translate email bodies`.

---

## Task 10: Localize the two client-side scripts

**Files:**
- Modify: `src/main/java/com/calit/web/Layout.java` (`TZ_SCRIPT`, `CALENDAR_SCRIPT`)
- Test: `src/test/java/com/calit/web/LayoutLocaleMarkerTest.java`

**Interfaces:**
- Consumes: `<html lang>` (set in Task 9a). The scripts read `document.documentElement.lang`.

> RestAssured can't run JS, so the test asserts the markers/locale-reading code is present in the served HTML, per the existing `CALIT_TZ_REFORMAT` marker convention.

- [ ] **Step 1: Write failing test**

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LayoutLocaleMarkerTest {
    @Test void bookingPagePassesLangToScripts() {
        given().when().get("/alice/intro").then().statusCode(200)
            .body(containsString("CALIT_TZ_REFORMAT"))
            .body(containsString("documentElement.lang")); // scripts now read page language
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -Dtest=LayoutLocaleMarkerTest`
Expected: FAIL — scripts don't reference `documentElement.lang` yet.

- [ ] **Step 3: Implement**

- `TZ_SCRIPT`: change `var opts = ...` usage so `el.textContent = d.toLocaleString(LANG, opts);` where near the top `var LANG = document.documentElement.lang || [];`.
- `CALENDAR_SCRIPT`: delete the hardcoded `MONTHS`/`DOW` arrays; derive them:

```javascript
var LANG = document.documentElement.lang || undefined;
var MONTHS = [], DOW = [];
for (var mi = 0; mi < 12; mi++) {
  MONTHS.push(new Intl.DateTimeFormat(LANG, {month:'long'}).format(new Date(2021, mi, 1)));
}
// week starts Monday: 2021-03-01 is a Monday
for (var di = 0; di < 7; di++) {
  DOW.push(new Intl.DateTimeFormat(LANG, {weekday:'short'}).format(new Date(2021, 2, 1 + di)));
}
```

- [ ] **Step 4: Run, verify pass**

Run: `mvn test -Dtest=LayoutLocaleMarkerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/Layout.java src/test/java/com/calit/web/LayoutLocaleMarkerTest.java
git commit -m "feat(i18n): client scripts read page language for time/calendar names"
```

---

## Task 11: Full suite + CSS build verification

- [ ] **Step 1: Build CSS** (templates changed)

Run: `bun run css:build`
Expected: exits 0, `/calit.css` regenerated.

- [ ] **Step 2: Full test suite**

Run: `mvn test`
Expected: all green (Docker running). Fix any `book(...)` callers, email harness, or page tests broken by the signature/string changes.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A && git commit -m "test(i18n): fix up callers and assertions after i18n"
```

---

## Task 12: Documentation (`docs-site` branch)

> Per CLAUDE.md, docs are part of done. This is on the **`docs-site`** branch (Astro Starlight), not `main`.

- [ ] **Step 1: Add a Localization page** under `docs-site/src/content/docs/` covering: supported languages (English, German), how invitee language is detected (browser `Accept-Language`) and switched (the footer switch + `calit_lang` cookie), and the owner language setting driving admin UI + owner emails.
- [ ] **Step 2: Config reference:** document `app.supported-locales` (default `en,de`) in the configuration page.
- [ ] **Step 3: Usage/settings page:** note the new "Language" field in owner Settings.
- [ ] **Step 4: Commit on `docs-site`** and push.

---

## Self-Review (completed during planning)

- **Spec coverage:** engine (T2,T4) · two locale axes (T4 owner path, T4 cookie/header path) · cookie switch (T5) · persisted booking locale (T7) + owner locale (T6) · server date formatting (T8) · client-side time/calendar localization (T10) · `<html lang>` (T4 global + T9a) · V16 columns (T3) · owner settings UI (T6) · tests (each task) · docs (T12) · RTL hedge (T9 procedure step 5) — all mapped.
- **Placeholders:** translation tasks (T9) are intentionally procedural (enumerating hundreds of literals verbatim would duplicate the templates); the procedure, key convention, worked examples, and per-group test pattern are concrete. All logic/infra tasks carry full code.
- **Type consistency:** `AppLocales.pick/isSupported/fromAcceptLanguage`, `Messages.forLocale/forTag`, `CurrentLocaleResolver.getLocale`, `Booking.locale`/`OwnerSettings.locale`, the added `book(...)` trailing `String locale`, `{lang}` global — names used consistently across tasks.
- **Open verification at impl time:** `MessageBundles.getCurrentLocale()` availability in this Qute version (Task 4 note) — confirm via javadocs MCP; fallback path given.
