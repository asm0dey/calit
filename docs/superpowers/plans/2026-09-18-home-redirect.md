# Signed-in Home Redirect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A signed-in visitor to `/` lands on their dashboard instead of the marketing page, with a per-user opt-out and the marketing page moved to a permanent `/calit`.

**Architecture:** `PublicResource.index()` grows a branch: signed in + preference on → `303 See Other` to `/me`; everything else renders the product-page template as today. A new literal route `/calit` renders that same template and never redirects, so it is the escape hatch. The preference is one boolean column on `OwnerSettings`, defaulting on, surfaced as a checkbox on `/me/settings`.

**Tech Stack:** Quarkus 3.38 / Java 25, Qute `@CheckedTemplate`, Panache entities with public fields, Flyway migrations, RestAssured + `@TestSecurity` for tests, Tailwind v4 + daisyUI.

**Spec:** `docs/superpowers/specs/2026-09-18-home-redirect-design.md`

**Bean:** `calit-q8m1` — keep its checklist current as you go, and include the bean file in each commit.

## Global Constraints

- **Docker must be running.** Dev Services provisions the test Postgres; there is no H2 fallback.
- **Build JDK must be 26.** Verify with `./mvnw -v` — it must report `Java version: 26.x`. If it reports 21, `export JAVA_HOME=~/.sdkman/candidates/java/26.0.2+1.1-librca`.
- **Never edit an applied migration.** Flyway checksum validation fails on any change, comments included. New `V*.sql` only.
- **Every user-facing string ships with `de` and `he` in the same change.** Add the value to `src/main/resources/messages/adm_{de,he}.properties` alongside the `@Message` default. No English fallback, no deferral to a native reviewer.
- **Formatting is enforced at `verify`.** Run `mvn spotless:apply` before each commit (lefthook also does it on staged `*.java`).
- **The whole suite must be green before a PR.** `mvn test` — 0 failures, 0 errors, `BUILD SUCCESS`. Not just the classes you touched.
- **Branch from `origin/main`.** Never push to `main`.
- **Owner scoping:** every tenant query filters by owner id. This change reads `OwnerSettings` for the signed-in user only.
- Commit messages end with: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `src/main/resources/db/migration/V36__home_redirect.sql` | Adds the column, records the deliberate V33 departure | 1 |
| `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java` | The `homeRedirectEnabled` field | 1 |
| `src/test/java/site/asm0dey/calit/domain/OwnerSettingsHomeRedirectTest.java` | Column exists and defaults on | 1 |
| `src/main/java/site/asm0dey/calit/web/PublicResource.java` | `/calit` route, shared render helper, cache headers, the redirect branch | 2, 3 |
| `src/main/resources/templates/PublicResource/index.html` | Canonical link | 2 |
| `src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java` | `/calit` exempt pre-bootstrap | 2 |
| `src/main/resources/templates/adminBase.html` | Brand becomes an anchor to `/calit` | 2 |
| `src/test/java/site/asm0dey/calit/web/ProductPageTest.java` | `/calit` behaviour + cache headers | 2 |
| `src/test/java/site/asm0dey/calit/web/HomeRedirectTest.java` | The `/` branch, both preference states, and `/{username}` untouched | 3 |
| `src/main/java/site/asm0dey/calit/web/AdminResource.java` | Settings form param + persist | 4 |
| `src/main/resources/templates/AdminResource/settings.html` | The checkbox | 4 |
| `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` + `messages/adm_{de,he}.properties` | Two new keys × three locales | 4 |
| `src/test/java/site/asm0dey/calit/web/HomeRedirectSettingTest.java` | Toggling the checkbox changes `/` | 4 |
| `CONTEXT.md`, `docs/adr/0011-*.md` | Glossary + decision record | 5 |

---

### Task 1: The column and the entity field

**Files:**
- Create: `src/main/resources/db/migration/V36__home_redirect.sql`
- Modify: `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java:44-45` (insert after `ownerNotificationsEnabled`)
- Test: `src/test/java/site/asm0dey/calit/domain/OwnerSettingsHomeRedirectTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public boolean OwnerSettings.homeRedirectEnabled` (default `true`), column `owner_settings.home_redirect_enabled BOOLEAN NOT NULL DEFAULT TRUE`.

**Background you need:** Hibernate runs in validate-only mode here (`schema-management.strategy=validate`) — it never creates schema, so the migration and the entity field must agree exactly or the app fails to boot. `V35__deleted_username.sql` is the current highest migration.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/domain/OwnerSettingsHomeRedirectTest.java`:

```java
package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

/**
 * The home-redirect opt-out defaults ON. DatabaseResetCallback reseeds before each test and the
 * admin user is always id 1, so the seeded row is the one under test.
 */
@QuarkusTest
class OwnerSettingsHomeRedirectTest {

    @Test
    @Transactional
    void seededOwnerHasHomeRedirectEnabled() {
        assertTrue(OwnerSettings.forOwner(1L).homeRedirectEnabled);
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

```bash
mvn test -Dtest=OwnerSettingsHomeRedirectTest
```

Expected: compilation failure — `cannot find symbol: variable homeRedirectEnabled`.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V36__home_redirect.sql`:

```sql
-- Bean calit-q8m1 / GH #193: a signed-in GET / now redirects to /me. Per-user opt-out.
--
-- Deliberate departure from the rule V33 states in its own comment ("TRUE for existing rows
-- preserves exactly the behaviour they have today"). Today's behaviour is NO redirect, so
-- preserving it would mean backfilling FALSE -- which ships an opt-in to every user who already
-- exists and leaves the problem in place on every current instance. The V33 rule guards against
-- silent surprises that are hard to notice and hard to undo; landing on your own dashboard is
-- noticed instantly and undone by one checkbox or by /calit. Existing rows get TRUE on purpose.
ALTER TABLE owner_settings
    ADD COLUMN home_redirect_enabled BOOLEAN NOT NULL DEFAULT TRUE;
```

- [ ] **Step 4: Add the entity field**

In `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java`, immediately after the
`ownerNotificationsEnabled` field (line 45), insert:

```java

    /**
     * Signed-in GET / 303s to /me. Opt-out, so it defaults on. The product page stays at /calit,
     * which never redirects. Named "home", not "landing": "landing" means /{username} here.
     */
    @Column(name = "home_redirect_enabled", nullable = false)
    public boolean homeRedirectEnabled = true;
```

- [ ] **Step 5: Run the test and verify it passes**

```bash
mvn test -Dtest=OwnerSettingsHomeRedirectTest
```

Expected: PASS. If boot fails with a `SchemaManagementException` naming `home_redirect_enabled`, the column name in the migration and the `@Column(name = ...)` disagree.

- [ ] **Step 6: Format and commit**

```bash
mvn spotless:apply
git add src/main/resources/db/migration/V36__home_redirect.sql \
        src/main/java/site/asm0dey/calit/domain/OwnerSettings.java \
        src/test/java/site/asm0dey/calit/domain/OwnerSettingsHomeRedirectTest.java \
        .beans/
git commit -m "$(cat <<'EOF'
feat(settings): add home_redirect_enabled, defaulting on

Existing rows get TRUE deliberately -- see the migration comment for why this
departs from the rule V33 states.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `/calit`, cache headers, and the way back from `/me`

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java:227-235` (`index()`)
- Modify: `src/main/resources/templates/PublicResource/index.html:6` (the `{#head}` block)
- Modify: `src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java:50` (the exempt list)
- Modify: `src/main/resources/templates/adminBase.html:25`
- Test: `src/test/java/site/asm0dey/calit/web/ProductPageTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `private Response PublicResource.productPageResponse()` — Task 3 calls it for the non-redirecting branch. `index()` changes return type from `TemplateInstance` to `Response`.

**Background you need:** `/` is exempt from `FirstRunRedirectFilter` so the marketing page renders before an instance is bootstrapped (`SetupFlowTest:85` asserts this). `/calit` renders the same content and needs the same exemption. `calit` is already in `Usernames.RESERVED`, so the literal `@Path("/calit")` can never shadow a real user's `/{username}` page — no `Usernames` change needed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/ProductPageTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * /calit is the product page's permanent home and the escape hatch for a signed-in user whose /
 * goes to their dashboard. It NEVER redirects, whoever asks for it.
 */
@QuarkusTest
class ProductPageTest {

    @Test
    void productPageServesTheMarketingContentToAnonymousVisitors() {
        given().redirects()
                .follow(false)
                .when()
                .get("/calit")
                .then()
                .statusCode(200)
                .body(containsString("Self-hosted scheduling"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void productPageNeverRedirectsASignedInVisitor() {
        given().redirects()
                .follow(false)
                .when()
                .get("/calit")
                .then()
                .statusCode(200)
                .body(containsString("Self-hosted scheduling"));
    }

    @Test
    void authVaryingHtmlIsNeverStoredByASharedCache() {
        given().when().get("/calit").then().header("Cache-Control", equalTo("private"));
        given().when().get("/").then().header("Cache-Control", equalTo("private"));
    }

    @Test
    void productPageDeclaresTheHomePageAsCanonical() {
        given().when()
                .get("/calit")
                .then()
                .statusCode(200)
                .body(containsString("rel=\"canonical\" href=\"/\""));
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

```bash
mvn test -Dtest=ProductPageTest
```

Expected: FAIL — `/calit` returns 404 (it falls through to `@Path("/{user}")` and no user is named `calit`).

- [ ] **Step 3: Extract the render helper and add the route**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`, replace the whole `index()` method
(lines 227-235, the one whose body starts `// Root is a generic product page`) with:

```java
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index() {
        // Root is the instance entrance -- NOT any owner's landing. Per-owner landings live at
        // /{user}, and the product page has its own permanent URL at /calit.
        return productPageResponse();
    }

    @GET
    @Path("/calit")
    @Produces(MediaType.TEXT_HTML)
    public Response productPage() {
        // The product page's permanent home, and the escape hatch for a signed-in user whose /
        // now goes to /me. Never redirects. "calit" is already in Usernames.RESERVED, so this
        // literal path can never shadow a real user's /{username} landing.
        return productPageResponse();
    }

    /**
     * The marketing/product page, served identically at / and /calit. Auth-aware: a logged-in
     * visitor sees Settings/Log out and their dashboard, not "Sign in" -- which is exactly why the
     * response must never be stored by a shared cache.
     */
    private Response productPageResponse() {
        var m = messages.forLocale(activeLocale.current());
        var authenticated = !identity.isAnonymous();
        String username = authenticated ? identity.getPrincipal().getName() : null;
        return Response.ok(Templates.index(m.pub_index_title(), authenticated, username, ogCards.product("/")))
                .header("Cache-Control", "private")
                .build();
    }
```

No change to `Templates` — both routes reuse `Templates.index`.

- [ ] **Step 4: Add the canonical link**

In `src/main/resources/templates/PublicResource/index.html`, inside the `{#head}` block that
begins at line 6, add as its first line:

```html
  <link rel="canonical" href="/">
```

`/` and `/calit` serve byte-identical HTML; the canonical names `/` as the URL to index and share.

- [ ] **Step 5: Exempt `/calit` from the first-run redirect**

In `src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java`, in
`isAllowedWhileUnbootstrapped`, immediately after the `path.equals("/")` line, add:

```java
                || path.equals("/calit") // same content as "/" -- same pre-bootstrap exemption
```

- [ ] **Step 6: Make the admin brand a link back**

In `src/main/resources/templates/adminBase.html`, replace line 25:

```html
      <div class="admin-brand"><span class="chip">c</span> calit</div>
```

with:

```html
      <a class="admin-brand" href="/calit"><span class="chip">c</span> calit</a>
```

It must point at `/calit`, not `/` — `/` bounces a signed-in user straight back to `/me` once
Task 3 lands. No CSS change is needed: `.admin-brand` (`input.css:348`) sets only layout, and
Tailwind preflight resets `a` to `color: inherit; text-decoration: inherit`. Note that
`.admin-brand` is `display: none` below the mobile breakpoint (`input.css:386`), so on phones the
escape hatch is the typed URL — same as the brand's visibility today.

- [ ] **Step 7: Run the test and verify it passes**

```bash
mvn test -Dtest=ProductPageTest
```

Expected: PASS.

If the body comes back empty or as `io.quarkus.qute.TemplateInstance@...`, the Qute writer did not
pick up the wrapped entity. Fall back to returning `TemplateInstance` from both routes and setting
the header in a `ContainerResponseFilter` matched on those two paths — but try the wrapped
`Response` first; it is the simpler shape and Quarkus supports it.

- [ ] **Step 8: Run the tests that already hit `/`**

```bash
mvn test -Dtest='PublicLandingTest+LandingFooterTest+SetupFlowTest+AdminMeetingTypesTest+StaticAssetsTest'
```

Expected: PASS. All five make anonymous requests, so the return-type change must not alter what
they see.

- [ ] **Step 9: Format and commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/resources/templates/PublicResource/index.html \
        src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java \
        src/main/resources/templates/adminBase.html \
        src/test/java/site/asm0dey/calit/web/ProductPageTest.java \
        .beans/
git commit -m "$(cat <<'EOF'
feat(web): serve the product page at /calit and link it from the admin shell

Both / and /calit render the same template with Cache-Control: private, and the
admin brand becomes an anchor so /me is no longer a one-way door.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Redirect a signed-in visitor from `/` to `/me`

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (the `index()` from Task 2)
- Test: `src/test/java/site/asm0dey/calit/web/HomeRedirectTest.java`

**Interfaces:**
- Consumes: `OwnerSettings.homeRedirectEnabled` (Task 1); `productPageResponse()` (Task 2).
- Produces: `private boolean PublicResource.homeRedirectEnabled(String username)`.

**Background you need:** `@TestSecurity(user = "admin", roles = "user")` installs an identity for the whole request, so `identity.isAnonymous()` is false even on a public route. The admin user is always id 1 after `DatabaseResetCallback` reseeds. RestAssured follows redirects by default — every test asserting a 303 must call `.redirects().follow(false)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/HomeRedirectTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * "/" is the instance entrance: a signed-in visitor goes to their dashboard. Pages that belong to
 * a person never redirect, however you are signed in -- checking your own public page as a guest
 * sees it is the main reason to visit it.
 */
@QuarkusTest
class HomeRedirectTest {

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void signedInVisitorIsSentToTheirDashboard() {
        given().redirects()
                .follow(false)
                .when()
                .get("/")
                .then()
                .statusCode(303)
                .header("Location", endsWith("/me"))
                // A shared cache must never replay this at an anonymous visitor.
                .header("Cache-Control", equalTo("no-store"));
    }

    @Test
    void anonymousVisitorStillGetsTheProductPage() {
        given().redirects()
                .follow(false)
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(containsString("Self-hosted scheduling"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void ownLandingPageIsNeverRedirectedAway() {
        // /{username} belongs to a person; being signed in must not take you off it.
        given().redirects().follow(false).when().get("/admin").then().statusCode(200);
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

```bash
mvn test -Dtest=HomeRedirectTest
```

Expected: `signedInVisitorIsSentToTheirDashboard` FAILS with `expected 303 but was 200`. The other
two pass already.

- [ ] **Step 3: Add the redirect branch**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`, replace the `index()` body written in
Task 2 with:

```java
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index() {
        // Root is the instance entrance -- NOT any owner's landing. A signed-in visitor goes to
        // their dashboard unless they opted out; the product page keeps its own URL at /calit.
        if (!identity.isAnonymous() && homeRedirectEnabled(identity.getPrincipal().getName())) {
            return Response.seeOther(URI.create("/me"))
                    .header("Cache-Control", "no-store") // per-identity: never cached and replayed
                    .build();
        }
        return productPageResponse();
    }

    /**
     * False when the user opted out -- and also when they have no settings row yet, which fails
     * toward today's behaviour rather than bouncing someone mid-bootstrap.
     */
    private boolean homeRedirectEnabled(String username) {
        AppUser u = AppUser.findByUsername(username);
        if (u == null) {
            return false;
        }
        OwnerSettings s = OwnerSettings.forOwner(u.id);
        return s != null && s.homeRedirectEnabled;
    }
```

Add `import java.net.URI;` to the import block. `AppUser` and `OwnerSettings` are already imported.

- [ ] **Step 4: Run the test and verify it passes**

```bash
mvn test -Dtest=HomeRedirectTest
```

Expected: PASS, all three.

- [ ] **Step 5: Run the whole suite**

```bash
mvn test
```

Expected: `BUILD SUCCESS`. This is the first task that changes behaviour for an authenticated
request, so any test that signs in and then hits `/` surfaces here.

- [ ] **Step 6: Format and commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/test/java/site/asm0dey/calit/web/HomeRedirectTest.java \
        .beans/
git commit -m "$(cat <<'EOF'
feat(web): send signed-in visitors from / to their dashboard

Closes #193. Honours the per-user opt-out; /{username} is untouched because it
belongs to a person.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: The opt-out checkbox on `/me/settings`

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:1535-1562` (`updateSettings`)
- Modify: `src/main/resources/templates/AdminResource/settings.html:31-33`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java:848-849` (insert after)
- Modify: `src/main/resources/messages/adm_de.properties:282` (insert after)
- Modify: `src/main/resources/messages/adm_he.properties:282` (insert after)
- Test: `src/test/java/site/asm0dey/calit/web/HomeRedirectSettingTest.java`

**Interfaces:**
- Consumes: `OwnerSettings.homeRedirectEnabled` (Task 1); the `/` branch (Task 3).
- Produces: form field `homeRedirectEnabled` on `POST /me/settings`.

**Background you need:** an unchecked HTML checkbox submits nothing, so the handler reads it as
`"on".equals(value)` — absent means off. That is exactly right for an opt-out. It also means **any
POST to `/me/settings` that omits the field turns the preference off**; `OwnerLocaleSettingTest`
posts only four fields and will now leave the redirect disabled for its own assertions. That is
harmless there (it asserts on locale), but any new test that posts settings and then expects a
redirect must include `homeRedirectEnabled=on`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/HomeRedirectSettingTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * The home redirect is an opt-out: on by default, and one checkbox away from off. An unchecked box
 * submits nothing, which is how the form expresses "off" (CSRF is disabled in %test, so a bare
 * form POST is accepted).
 */
@QuarkusTest
class HomeRedirectSettingTest {

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void settingsPageShowsTheOptOutCheckedByDefault() {
        given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .body(containsString("name=\"homeRedirectEnabled\""))
                .body(containsString("name=\"homeRedirectEnabled\" class=\"checkbox checkbox-sm\" checked"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void optingOutMakesHomeRenderTheProductPageAgain() {
        // Unchecked box => field absent from the POST.
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().redirects()
                .follow(false)
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(containsString("Self-hosted scheduling"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void optingBackInRestoresTheRedirect() {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("homeRedirectEnabled", "on")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().redirects().follow(false).when().get("/").then().statusCode(303);
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

```bash
mvn test -Dtest=HomeRedirectSettingTest
```

Expected: `settingsPageShowsTheOptOutCheckedByDefault` and `optingOutMakesHomeRenderTheProductPageAgain`
FAIL — the field does not exist yet, so the page has no checkbox and the POST cannot turn it off.

- [ ] **Step 3: Add the message keys**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, after
`String adm_settings_label_notifications();` (line 849), add:

```java

    @Message("Go straight to my dashboard from the home page")
    String adm_settings_label_home_redirect();

    @Message("The product page stays at /calit.")
    String adm_settings_home_redirect_hint();
```

In `src/main/resources/messages/adm_de.properties`, after line 282, add:

```properties
adm_settings_label_home_redirect=Von der Startseite direkt zum Dashboard
adm_settings_home_redirect_hint=Die Produktseite bleibt unter /calit erreichbar.
```

In `src/main/resources/messages/adm_he.properties`, after line 282, add:

```properties
adm_settings_label_home_redirect=מעבר ישיר ללוח הבקרה מדף הבית
adm_settings_home_redirect_hint=דף המוצר נשאר בכתובת /calit
```

`Dashboard` / `לוח בקרה` match `adm_nav_dashboard` in each file, so the wording is consistent with
the navigation the checkbox is talking about.

- [ ] **Step 4: Add the checkbox**

In `src/main/resources/templates/AdminResource/settings.html`, immediately after the
notifications `</label>` (line 33, the one ending `{adm:adm_settings_label_notifications}</label>`),
insert:

```html
    <label class="label cursor-pointer justify-start gap-2 mt-2">
      <input type="checkbox" name="homeRedirectEnabled" class="checkbox checkbox-sm"{#if settings && settings.homeRedirectEnabled} checked{/if}>
      {adm:adm_settings_label_home_redirect}</label>
    <p class="text-sm text-base-content/70">{adm:adm_settings_home_redirect_hint}</p>
```

- [ ] **Step 5: Persist the field**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, add a parameter to `updateSettings`
after `@RestForm String ownerNotificationsEnabled,`:

```java
            @RestForm String homeRedirectEnabled,
```

and inside the transaction, after the `row.ownerNotificationsEnabled = ...` line:

```java
            // Unchecked checkbox sends no value → the owner opted out of the / → /me redirect.
            row.homeRedirectEnabled = "on".equals(homeRedirectEnabled);
```

- [ ] **Step 6: Run the test and verify it passes**

```bash
mvn test -Dtest=HomeRedirectSettingTest
```

Expected: PASS, all three.

- [ ] **Step 7: Check locale parity**

Every `String key();` in `AdminMessages` must have a matching `key=` line in both locale files:

```bash
grep -c "adm_settings_label_home_redirect\|adm_settings_home_redirect_hint" \
  src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties
```

Expected: `2` for each file.

- [ ] **Step 8: Run the whole suite**

```bash
mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Format and commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/settings.html \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/test/java/site/asm0dey/calit/web/HomeRedirectSettingTest.java \
        .beans/
git commit -m "$(cat <<'EOF'
feat(settings): let an owner opt out of the home redirect

Checkbox on /me/settings, translated to de and he.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Glossary, decision record, and the changelog

**Files:**
- Modify: `CONTEXT.md`
- Create: `docs/adr/0011-home-is-the-instance-entrance.md`
- Separately, on the `docs-site` branch: `docs-site/src/content/docs/releases/changelog.md`

**Interfaces:**
- Consumes: the behaviour from Tasks 1-4.
- Produces: nothing code-facing.

**Background you need:** `docs/adr/` currently holds `0001`-`0010`; the format to follow is the one
in `0010-personal-data-is-a-hand-written-inventory-guarded-by-a-schema-test.md`. `CONTEXT.md` is a
glossary only — no implementation detail, no spec content. The changelog lives on a different
branch and its house style is one terse bullet, roughly 20-35 words, no bold lead-in, no
before/after narrative, closing with an upgrade note.

- [ ] **Step 1: Add the terms to `CONTEXT.md`**

Read `CONTEXT.md` first to match its existing heading structure, then add these three terms and the
principle. "landing" currently means two different things in the codebase, which is the collision
being resolved:

- **Home** — `/`. Belongs to the instance, not to any user. A signed-in visitor is sent to their dashboard from here unless they opted out.
- **Landing** — `/{username}`. That owner's public booking page. Never redirects: checking your own page as a guest sees it is the main reason to visit it.
- **Product page** — `/calit`. The marketing pitch at a permanent URL. Rendered identically at `/` for anonymous visitors, which is why `/` is the canonical of the pair.

Plus the principle: *being signed in never redirects you away from a page that belongs to someone.*

- [ ] **Step 2: Write the ADR**

Create `docs/adr/0011-home-is-the-instance-entrance.md`, following the format of ADR 0010. It must
record: the decision (`/` is the entrance, `/calit` is the product page, per-user opt-out defaulting
on); why `/calit` and not `/about` (`about` is not in `Usernames.RESERVED`, so a literal route would
silently shadow an existing user's page on upgrade); why existing rows are backfilled `TRUE` against
the rule V33 states; and the rejected alternatives — `/?home`, redirecting anonymous visitors too,
opt-in, a `LANDING_REDIRECT` env var, no preference at all, and redirecting `/{username}` for its
own owner.

- [ ] **Step 3: Mirror it into the precedent graph**

```bash
uv run ~/.claude/plugins/cache/precedent/precedent/0.4.1/scripts/precedent.py \
  check --topic "url-structure" --chose "signed-in / redirects to the dashboard"
```

If nothing comparable comes back, record it with `precedent record`, passing `--rejected` (the
alternatives listed in Step 2) and `--rationale` (the friction is universal and recurring; the
product page keeps a real URL). The rejected alternatives and the why are the half that gets quoted
back in the next project.

- [ ] **Step 4: Commit the docs**

```bash
git add CONTEXT.md docs/adr/0011-home-is-the-instance-entrance.md .beans/
git commit -m "$(cat <<'EOF'
docs: record home/landing/product-page terms and the entrance decision

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Add the changelog entry on `docs-site`**

Switch to the `docs-site` branch and add a bullet under `## Unreleased` in
`docs-site/src/content/docs/releases/changelog.md` (create the section if absent, with the standing
subtitle "Merged but not yet in a tagged release."). Terse, ~25 words, ending with the PR link:

> Signing in and visiting `/` now goes to your dashboard; the product page moved to `/calit`. Migration `V36` adds `home_redirect_enabled`, on for existing owners. ([#N](https://github.com/asm0dey/calit/pull/N))

Close the section with an upgrade note naming the caveat: existing owners get the redirect on
upgrade and turn it off on `/me/settings`.

- [ ] **Step 6: Final verification before the PR**

```bash
mvn verify
```

Expected: `BUILD SUCCESS` — this runs Spotless's check (which `mvn test` skips) plus the `*IT`
classes under Failsafe. Then run `show-me` and put the diagram in the PR body.
