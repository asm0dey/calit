# Footer & First-Run Setup Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the footer legible and shared across public + admin pages with a scalable no-JS language switcher; make `/privacy` and `/terms` reachable before first-user bootstrap; auto-detect the first-run timezone; and replace the app's privacy/terms pages with the canonical docs policy in the marketing-landing visual style.

**Architecture:** A single Qute fragment `templates/footer.html` is `{#include}`'d by both base templates, eliminating duplication and the admin-page gaps. The language switcher becomes a no-JS daisyUI `<details>` dropdown of the existing `/lang/{code}?return=…` links. `FirstRunRedirectFilter` gains two allowlist entries. The first-run timezone `<select>` defaults to `UTC` and carries a `data-tz-autodetect` hook that a tiny inline script upgrades to the browser zone. Privacy/terms templates are rewritten with the canonical policy text in landing-page styling.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute (`@CheckedTemplate`, `{#include}`, `{inject:…}`), Tailwind v4 + daisyUI 5 (compiled to `/calit.css`), RestAssured `@QuarkusTest`.

## Global Constraints

- Server-rendered Qute only. **No new runtime JavaScript** except the single timezone-autodetect snippet (Task 3), which follows the existing inline-vanilla-JS idiom and degrades gracefully with JS off.
- **No new dependency.** UI uses Tailwind v4 + daisyUI 5 already compiled to `/calit.css`. Do NOT add `@tailwindcss/typography` (no `prose`).
- Root package is `site.asm0dey.calit`. Repo root: `/home/finkel/work_self/calit`. Run tests with `./mvnw -o test -Dtest=…` (Docker required; Dev Services Postgres).
- RestAssured cannot run JS — assert on stable marker comments, never script behavior. Markers used here: `CALIT_BUILD_FOOTER`, `CALIT_LEGAL_PRIVACY`, `CALIT_LEGAL_TERMS`, `CALIT_TZ_AUTODETECT`.
- Default test profile seeds admin as id 1; the no-user case uses `AppUser.deleteAll()` in its own transaction with an `@AfterEach` that restores the baseline admin (mirror `SetupFlowTest`), so the shared DB never ends at zero users.
- Language switcher stays **link-based** (`<a href="/lang/{code}?return=…">`) — no `<select>`, no JS to navigate. The active locale renders as a non-link with `aria-current`.
- Legal copy is English-only by design (mark templates with a `{! ponytail: … !}` comment). The privacy text is the canonical copy from `docs-site/src/content/docs/privacy.md`; keep the two in sync.
- daisyUI dropdown is the `<details>`/`<summary>` form: `<details class="dropdown"><summary>…</summary><ul class="dropdown-content menu">…</ul></details>`. Active menu item uses `menu-active`.

---

### Task 1: Shared, legible footer with no-JS language dropdown

**Files:**
- Create: `src/main/resources/templates/footer.html`
- Modify: `src/main/resources/templates/base.html` (replace the footer block, currently `<!-- CALIT_BUILD_FOOTER -->` … `</footer>` starting at line 35)
- Modify: `src/main/resources/templates/adminBase.html` (replace the footer block at lines 65–69)
- Test: `src/test/java/site/asm0dey/calit/web/FooterPolishTest.java`

**Interfaces:**
- Consumes: `localeOptions` (global `List<LocaleOption>` with `.code/.label/.active/.href`, set by `LocaleTemplateInitializer` on every `TemplateInstance`), `{inject:build.version}`, `{inject:build.commit}`, `{msg:common_language}`.
- Produces: a reusable `footer.html` template included via `{#include footer /}`.

- [ ] **Step 1: Write the failing test**

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class FooterPolishTest {

    // The shared footer must appear on a PUBLIC page with build info, legal links,
    // and the no-JS language dropdown (a /lang link + the active endonym).
    @Test
    void publicFooterHasSharedContent() {
        given().when().get("/login").then().statusCode(200)
            .body(containsString("CALIT_BUILD_FOOTER"))
            .body(containsString("href=\"/privacy\""))
            .body(containsString("href=\"/terms\""))
            .body(containsString("class=\"dropdown"))
            .body(containsString("/lang/"))
            .body(containsString("English")); // active endonym in default (en) test locale
    }

    // The SAME footer must now also appear on an ADMIN page (was missing before).
    @Test
    void adminFooterHasSharedContent() {
        given().cookie("quarkus-credential", FormAuth.login())
            .when().get("/me").then().statusCode(200)
            .body(containsString("CALIT_BUILD_FOOTER"))
            .body(containsString("href=\"/privacy\""))
            .body(containsString("href=\"/terms\""))
            .body(containsString("class=\"dropdown"))
            .body(containsString("/lang/"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=FooterPolishTest`
Expected: FAIL — `adminFooterHasSharedContent` fails (admin footer has no `/privacy`, `/lang/`, or `dropdown`); `publicFooterHasSharedContent` fails on `class="dropdown"` (still inline links).

- [ ] **Step 3: Create `footer.html`**

`src/main/resources/templates/footer.html`:

```html
{! Shared footer: build info, legal links, no-JS language switcher. Included by base.html and adminBase.html. !}
<!-- CALIT_BUILD_FOOTER -->
<footer class="container py-6 text-center text-xs text-base-content/70">
  <a href="https://github.com/asm0dey/calit" class="link link-hover text-base-content">calit</a>
  <span class="mx-1">{inject:build.version} &middot; {inject:build.commit}</span>
  <div class="mt-1">
    <a href="/privacy" class="link link-hover text-base-content">Privacy</a>
    <span class="mx-1">&middot;</span>
    <a href="/terms" class="link link-hover text-base-content">Terms</a>
  </div>
  <details class="dropdown dropdown-top dropdown-end mt-2">
    <summary class="btn btn-ghost btn-xs" aria-label="{msg:common_language}">
      {#for o in localeOptions}{#if o.active}{o.label}{/if}{/for} &#9662;
    </summary>
    <ul class="menu dropdown-content bg-base-100 rounded-box z-10 w-44 p-2 shadow-sm max-h-64 overflow-y-auto text-left text-base-content">
      {#for o in localeOptions}
        <li>
          {#if o.active}
          <span class="menu-active" aria-current="true">{o.label}</span>
          {#else}
          <a href="{o.href}">{o.label}</a>
          {/if}
        </li>
      {/for}
    </ul>
  </details>
</footer>
```

- [ ] **Step 4: Replace the footer in `base.html`**

In `src/main/resources/templates/base.html`, delete the entire existing footer block — from the line `<!-- CALIT_BUILD_FOOTER -->` through its closing `</footer>` (the block containing the `calit` link, build info, the Privacy/Terms `<div>`, and the `<nav>…</nav>` locale loop) — and replace all of it with a single line:

```html
  {#include footer /}
```

(Leave the surrounding `</main>` above and the `<script>` theme block below untouched.)

- [ ] **Step 5: Replace the footer in `adminBase.html`**

In `src/main/resources/templates/adminBase.html`, replace the footer block (lines 65–69):

```html
      <!-- CALIT_BUILD_FOOTER -->
      <footer class="container pb-6 text-center text-xs text-base-content/50">
        <a href="https://github.com/asm0dey/calit" class="link link-hover">calit</a>
        {inject:build.version} &middot; {inject:build.commit}
      </footer>
```

with:

```html
      {#include footer /}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -o test -Dtest=FooterPolishTest,FooterBuildInfoTest`
Expected: PASS — both new tests green, and the existing `FooterBuildInfoTest` (asserts `CALIT_BUILD_FOOTER` + `calit` on `/login`) still passes against the shared fragment.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/footer.html src/main/resources/templates/base.html src/main/resources/templates/adminBase.html src/test/java/site/asm0dey/calit/web/FooterPolishTest.java
git commit -m "feat(web): shared footer fragment with legible no-JS language dropdown"
```

---

### Task 2: Reach `/privacy` and `/terms` before first-user bootstrap

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java` (the `isAllowedWhileUnbootstrapped` allowlist, around lines 43–49)
- Test: `src/test/java/site/asm0dey/calit/user/FirstRunLegalPagesTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `/privacy` and `/terms` return 200 (not a 302 to `/setup`) when zero users exist.

- [ ] **Step 1: Write the failing test**

```java
package site.asm0dey.calit.user;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class FirstRunLegalPagesTest {

    private void deleteAllUsers() {
        QuarkusTransaction.requiringNew().run(AppUser::deleteAll);
    }

    // Never leave the shared DB at zero users (mirrors SetupFlowTest).
    @AfterEach
    void restoreBaseline() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.count() == 0) {
                AppUser.create("admin", new PasswordHasher().hash("testpass"), true).persist();
            }
        });
    }

    @Test
    void privacyReachableWithNoUsers() {
        deleteAllUsers();
        given().redirects().follow(false)
            .when().get("/privacy").then().statusCode(200)
            .body(containsString("CALIT_LEGAL_PRIVACY"));
    }

    @Test
    void termsReachableWithNoUsers() {
        deleteAllUsers();
        given().redirects().follow(false)
            .when().get("/terms").then().statusCode(200)
            .body(containsString("CALIT_LEGAL_TERMS"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=FirstRunLegalPagesTest`
Expected: FAIL — both return 302 to `/setup` (status 200 assertion fails).

- [ ] **Step 3: Add the two paths to the allowlist**

In `src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java`, in `isAllowedWhileUnbootstrapped`, add `/privacy` and `/terms` alongside `/setup`. Change:

```java
                || path.equals("/setup")
                || path.equals("/j_security_check")
```

to:

```java
                || path.equals("/setup")
                || path.equals("/privacy")   // public legal pages must be reachable pre-bootstrap
                || path.equals("/terms")     // (e.g. Google's verification crawler on a fresh instance)
                || path.equals("/j_security_check")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -o test -Dtest=FirstRunLegalPagesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/user/FirstRunRedirectFilter.java src/test/java/site/asm0dey/calit/user/FirstRunLegalPagesTest.java
git commit -m "fix(setup): allow /privacy and /terms before first-user bootstrap"
```

---

### Task 3: First-run timezone auto-detect (UTC fallback)

**Files:**
- Modify: `src/main/resources/templates/MeSetupResource/meSetup.html` (timezone `<select>`, lines 37–45)
- Modify: `src/main/resources/templates/AdminResource/settings.html` (timezone `<select>`, lines 17–24)
- Test: `src/test/java/site/asm0dey/calit/web/TimezoneAutodetectTest.java`

**Interfaces:**
- Consumes: existing `zones` list and optional `settings` (with `settings.timezone`) already passed to both templates.
- Produces: when no saved settings, the `<select>` defaults to `UTC` (not `Europe/Amsterdam`) and carries `data-tz-autodetect`; a `CALIT_TZ_AUTODETECT` inline script preselects the browser zone if present.

- [ ] **Step 1: Write the failing test**

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class TimezoneAutodetectTest {

    @Transactional
    void seedNotOnboarded(String username) {
        if (AppUser.findByUsername(username) == null) {
            AppUser u = AppUser.create(username, new PasswordHasher().hash("Initial-pw-12345"), false);
            u.mustChangePassword = false;
            u.settingsComplete = false; // -> first-login wizard renders the no-settings timezone select
            u.persist();
        }
    }

    @Test
    @TestSecurity(user = "tzwiz", roles = {"user"})
    void firstRunWizardDefaultsUtcAndAutodetects() {
        seedNotOnboarded("tzwiz");
        given().when().get("/me/setup").then().statusCode(200)
            .body(containsString("data-tz-autodetect"))
            .body(containsString("value=\"UTC\" selected"))
            .body(containsString("CALIT_TZ_AUTODETECT"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=TimezoneAutodetectTest`
Expected: FAIL — select has no `data-tz-autodetect`, defaults to `Europe/Amsterdam`, no autodetect script.

- [ ] **Step 3: Update the timezone select in `meSetup.html`**

In `src/main/resources/templates/MeSetupResource/meSetup.html`, replace the timezone block:

```html
    <select id="timezone" class="select w-full" name="timezone" required>
      {#for z in zones}
        {#if settings}
        <option value="{z}"{#if settings.timezone == z} selected{/if}>{z}</option>
        {#else}
        <option value="{z}"{#if z == 'Europe/Amsterdam'} selected{/if}>{z}</option>
        {/if}
      {/for}
    </select>
```

with (note `{#if !settings}data-tz-autodetect{/if}` on the select, `UTC` fallback, and the script):

```html
    <select id="timezone" class="select w-full" name="timezone" required{#if !settings} data-tz-autodetect{/if}>
      {#for z in zones}
        {#if settings}
        <option value="{z}"{#if settings.timezone == z} selected{/if}>{z}</option>
        {#else}
        <option value="{z}"{#if z == 'UTC'} selected{/if}>{z}</option>
        {/if}
      {/for}
    </select>
    <script>
    /* CALIT_TZ_AUTODETECT — on first run (no saved timezone), preselect the browser's zone. */
    (function () {
      var sel = document.querySelector('select[data-tz-autodetect]');
      if (!sel) { return; }
      try {
        var tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (tz && [].some.call(sel.options, function (o) { return o.value === tz; })) {
          sel.value = tz;
        }
      } catch (e) {}
    })();
    </script>
```

- [ ] **Step 4: Update the timezone select in `settings.html`**

In `src/main/resources/templates/AdminResource/settings.html`, apply the same change to its block (id `set-timezone`):

```html
    <select id="set-timezone" class="select w-full" name="timezone" required>
      {#for z in zones}
        {#if settings}
        <option value="{z}"{#if settings.timezone == z} selected{/if}>{z}</option>
        {#else}
        <option value="{z}"{#if z == 'Europe/Amsterdam'} selected{/if}>{z}</option>
        {/if}
      {/for}
    </select>
```

becomes:

```html
    <select id="set-timezone" class="select w-full" name="timezone" required{#if !settings} data-tz-autodetect{/if}>
      {#for z in zones}
        {#if settings}
        <option value="{z}"{#if settings.timezone == z} selected{/if}>{z}</option>
        {#else}
        <option value="{z}"{#if z == 'UTC'} selected{/if}>{z}</option>
        {/if}
      {/for}
    </select>
    <script>
    /* CALIT_TZ_AUTODETECT — on first run (no saved timezone), preselect the browser's zone. */
    (function () {
      var sel = document.querySelector('select[data-tz-autodetect]');
      if (!sel) { return; }
      try {
        var tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (tz && [].some.call(sel.options, function (o) { return o.value === tz; })) {
          sel.value = tz;
        }
      } catch (e) {}
    })();
    </script>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -o test -Dtest=TimezoneAutodetectTest,MeSetupResourceTest,AdminSettingsTest`
Expected: PASS — new test green; existing wizard/settings tests unaffected (they post explicit timezones, and the saved-value branch is unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/MeSetupResource/meSetup.html src/main/resources/templates/AdminResource/settings.html src/test/java/site/asm0dey/calit/web/TimezoneAutodetectTest.java
git commit -m "feat(setup): auto-detect first-run timezone, UTC fallback"
```

---

### Task 4: Port canonical privacy policy + landing-style restyle of both legal pages

**Files:**
- Modify: `src/main/resources/templates/LegalResource/privacy.html` (full rewrite)
- Modify: `src/main/resources/templates/LegalResource/terms.html` (full rewrite — same content, new style)
- Test: `src/test/java/site/asm0dey/calit/web/LegalPagesTest.java` (extend existing)

**Interfaces:**
- Consumes: `{inject:site.operatorName}` (falls back to base-url), `{inject:site.contactEmail}` (null when unset), the `base` template.
- Produces: `/privacy` and `/terms` rendered in landing-page style; `LegalResource.java` unchanged (same `Templates.privacy(String)` / `terms(String)` methods).

- [ ] **Step 1: Write the failing test (extend `LegalPagesTest`)**

Add these methods to the existing `src/test/java/site/asm0dey/calit/web/LegalPagesTest.java` (keep the existing `privacyPageRendersWithGoogleDisclosure`, `termsPageRenders`, `publicFooterLinksToLegalPages` methods):

```java
    @org.junit.jupiter.api.Test
    void privacyHasCanonicalSectionsAndLandingStyle() {
        io.restassured.RestAssured.given()
            .when().get("/privacy").then().statusCode(200)
            .body(org.hamcrest.Matchers.containsString("Retention and deletion"))
            .body(org.hamcrest.Matchers.containsString("How Google user data is used"))
            .body(org.hamcrest.Matchers.containsString("max-w-3xl"))
            .body(org.hamcrest.Matchers.containsString("text-3xl font-bold"))
            .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("class=\"prose")));
    }

    @org.junit.jupiter.api.Test
    void termsUsesLandingStyle() {
        io.restassured.RestAssured.given()
            .when().get("/terms").then().statusCode(200)
            .body(org.hamcrest.Matchers.containsString("max-w-3xl"))
            .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("class=\"prose")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=LegalPagesTest`
Expected: FAIL — current pages use `class="prose"` and `max-w-2xl`, lack "Retention and deletion".

- [ ] **Step 3: Rewrite `privacy.html`**

Replace the entire contents of `src/main/resources/templates/LegalResource/privacy.html` with:

```html
{@java.lang.String title}
{#include base title=title}
{! ponytail: English-only legal copy, ported from docs-site/src/content/docs/privacy.md — keep the two in sync. !}
<div class="max-w-3xl mx-auto">
  <!-- CALIT_LEGAL_PRIVACY -->
  <header class="mb-6">
    <h1 class="text-3xl font-bold">Privacy Policy</h1>
    <p class="text-base-content/70">This calit instance is operated by <strong>{inject:site.operatorName}</strong>.</p>
  </header>

  <h2 class="text-xl font-semibold mt-6">What calit is</h2>
  <p class="mt-2">calit is open-source scheduling software the operator hosts themselves. It stores all data in a database the operator controls. The maintainers of the project do not operate a central service, do not receive your data, and have no access to any deployment's database.</p>

  <h2 class="text-xl font-semibold mt-6">Data calit stores</h2>
  <ul class="list-disc pl-6 mt-2">
    <li><strong>Account data</strong> — your username, email address, display name, timezone, and an argon2id hash of your password (never the password itself).</li>
    <li><strong>Scheduling data</strong> — your meeting types, availability rules, and the bookings made by invitees (invitee name, email, and any answers to custom booking questions you configure).</li>
    <li><strong>Google account data</strong> — when you connect Google Calendar (optional): the Google account's email and stable subject identifier, and the OAuth access and refresh tokens. Tokens are encrypted at rest.</li>
  </ul>

  <h2 class="text-xl font-semibold mt-6">How Google user data is used</h2>
  <p class="mt-2">When you connect a Google account, calit requests the Google Calendar scope and uses it only to provide scheduling: it reads free/busy information from the calendars you select to compute available slots, and creates, updates, and deletes events on the write-target calendar you choose when bookings are made, rescheduled, or cancelled. calit does not read the content of your calendar events beyond busy intervals, and does not use Google data for advertising, profiling, or any purpose other than the scheduling features you initiated.</p>

  <h3 class="text-lg font-semibold mt-4">Limited Use disclosure</h3>
  <p class="mt-2">calit's use and transfer of information received from Google APIs adheres to the <a class="link" href="https://developers.google.com/terms/api-services-user-data-policy" rel="noopener" target="_blank">Google API Services User Data Policy</a>, including the Limited Use requirements. Google user data is not sold, not transferred to third parties except as needed to provide the scheduling features, not used for advertising, and not read by humans except where required for security, to comply with law, or with your explicit consent.</p>

  <h2 class="text-xl font-semibold mt-6">Data sharing</h2>
  <p class="mt-2">calit does not sell or share your data. The only external parties a deployment communicates with for core functionality are Google (only for the calendar operations above) and the SMTP server the operator configures for sending booking and notification emails.</p>

  <h2 class="text-xl font-semibold mt-6">Retention and deletion</h2>
  <ul class="list-disc pl-6 mt-2">
    <li>Disconnecting a Google account in <a class="link" href="/me/google">Settings → Google</a> deletes that account's stored tokens and calendar selections.</li>
    <li>Deleting a user account removes that user's scheduling data.</li>
    <li>Booking records are retained until removed by the account owner or operator.</li>
  </ul>

  <h2 class="text-xl font-semibold mt-6">Security</h2>
  <p class="mt-2">Passwords are hashed with argon2id; Google OAuth tokens are encrypted at rest. Production deployments run behind TLS with secure cookies. The operator is responsible for securing the host and database.</p>

  <h2 class="text-xl font-semibold mt-6">Contact</h2>
  {#if inject:site.contactEmail}
  <p class="mt-2">For privacy questions and data requests about this deployment, contact the operator at <a class="link" href="mailto:{inject:site.contactEmail}">{inject:site.contactEmail}</a>. For questions about the calit software itself, open an issue at <a class="link" href="https://github.com/asm0dey/calit" rel="noopener" target="_blank">github.com/asm0dey/calit</a>.</p>
  {#else}
  <p class="mt-2">For questions about a specific deployment, contact that deployment's operator. For questions about the calit software itself, open an issue at <a class="link" href="https://github.com/asm0dey/calit" rel="noopener" target="_blank">github.com/asm0dey/calit</a>.</p>
  {/if}
</div>
{/include}
```

- [ ] **Step 4: Rewrite `terms.html`**

Replace the entire contents of `src/main/resources/templates/LegalResource/terms.html` with:

```html
{@java.lang.String title}
{#include base title=title}
{! ponytail: English-only legal copy. Add msg: keys only if bilingual terms are requested. !}
<div class="max-w-3xl mx-auto">
  <!-- CALIT_LEGAL_TERMS -->
  <header class="mb-6">
    <h1 class="text-3xl font-bold">Terms of Service</h1>
    <p class="text-base-content/70">This calit instance is operated by <strong>{inject:site.operatorName}</strong>.</p>
  </header>

  <h2 class="text-xl font-semibold mt-6">The service</h2>
  <p class="mt-2">calit is a scheduling service provided by the operator. By creating an account or booking a meeting you agree to these terms.</p>

  <h2 class="text-xl font-semibold mt-6">Acceptable use</h2>
  <p class="mt-2">Do not use this service to send spam, harass others, or violate any applicable law. The operator may suspend accounts that do.</p>

  <h2 class="text-xl font-semibold mt-6">No warranty</h2>
  <p class="mt-2">The service is provided "as is", without warranty of any kind. The operator is not liable for missed meetings, lost data, or any indirect damages arising from use of the service.</p>

  <h2 class="text-xl font-semibold mt-6">Changes</h2>
  <p class="mt-2">The operator may update these terms; continued use after a change constitutes acceptance.</p>

  {#if inject:site.contactEmail}
  <h2 class="text-xl font-semibold mt-6">Contact</h2>
  <p class="mt-2"><a class="link" href="mailto:{inject:site.contactEmail}">{inject:site.contactEmail}</a></p>
  {/if}
</div>
{/include}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -o test -Dtest=LegalPagesTest`
Expected: PASS — new and existing methods green (existing assertions for "Google Calendar" and "Limited Use" still hold; the ported copy contains both).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/LegalResource/privacy.html src/main/resources/templates/LegalResource/terms.html src/test/java/site/asm0dey/calit/web/LegalPagesTest.java
git commit -m "feat(web): canonical privacy policy + landing-style legal pages"
```

---

### Task 6: Pin the landing theme + de-duplicate its footer

**Files:**
- Modify: `src/main/resources/templates/base.html` (body tag: add `forceTheme` data-theme; footer include: make opt-out via `ownFooter`)
- Modify: `src/main/resources/templates/PublicResource/index.html` (pass `forceTheme`/`ownFooter` to the base include; add Privacy/Terms to `lp-foot`)
- Test: `src/test/java/site/asm0dey/calit/web/LandingFooterTest.java`

**Interfaces:**
- Consumes: the shared `footer.html` (Task 1), the `base` template, existing `lp-navlink` CSS class in `index.html`.
- Produces: the landing pins `data-theme="calit-light"` on `<body>` and renders only its own `lp-foot` (no appended shared footer); other pages keep the shared footer.

**Why:** The marketing landing hardcodes a cream palette but does not pin a theme, so the theme-aware shared footer goes invisible in dark-OS. Pinning the landing to its light theme keeps every daisyUI element on it consistent; opt-out of the shared footer removes the duplicate (the landing has its own).

- [ ] **Step 1: Write the failing test**

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class LandingFooterTest {

    @Test
    void landingPinsLightThemeAndOwnsItsFooter() {
        given().when().get("/").then().statusCode(200)
            // landing body is pinned to the light theme so its daisyUI bits match its cream palette
            .body(containsString("data-theme=\"calit-light\""))
            // legal links live in the landing's own footer
            .body(containsString("href=\"/privacy\""))
            .body(containsString("href=\"/terms\""))
            // the shared daisyUI footer (with the language dropdown) is NOT appended on the landing
            .body(not(containsString("class=\"dropdown")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=LandingFooterTest`
Expected: FAIL — landing currently has no `data-theme="calit-light"`, the `lp-foot` lacks `/privacy`/`/terms`, and the shared footer (with `class="dropdown"`) is appended.

- [ ] **Step 3: Make `base.html` theme-pinnable and footer opt-out**

In `src/main/resources/templates/base.html`:

(a) The `<body>` tag (line 24) currently:

```html
<body class="canvas {bodyClass ?: ''} bg-base-200 text-base-content min-h-screen">
```

becomes (add an optional `data-theme` when `forceTheme` is provided):

```html
<body class="canvas {bodyClass ?: ''} bg-base-200 text-base-content min-h-screen"{#if forceTheme} data-theme="{forceTheme}"{/if}>
```

(b) The shared-footer include (the `{#include footer /}` line added in Task 1) becomes opt-out:

```html
  {#if !ownFooter}{#include footer /}{/if}
```

(`forceTheme` and `ownFooter` are undeclared optional params — Qute treats them as null/false when a caller omits them, exactly like the existing `{bodyClass ?: ''}` usage, so all other callers are unaffected: they still get the shared footer and no forced theme.)

- [ ] **Step 4: Update the landing `index.html`**

In `src/main/resources/templates/PublicResource/index.html`:

(a) Change the include directive (line 4) from:

```html
{#include base title=title bodyClass="lp-body"}
```

to:

```html
{#include base title=title bodyClass="lp-body" forceTheme="calit-light" ownFooter=true}
```

(b) Add Privacy/Terms links to the landing's own footer. Change the footer (around line 316):

```html
  <footer class="lp-wrap lp-foot">
    <a class="lp-brand" href="/"><span class="chip">c</span> calit</a>
    <span class="meta">{msg:pub_landing_footer_meta}</span>
  </footer>
```

to:

```html
  <footer class="lp-wrap lp-foot">
    <a class="lp-brand" href="/"><span class="chip">c</span> calit</a>
    <nav class="lp-foot-links">
      <a class="lp-navlink" href="/privacy">Privacy</a>
      <a class="lp-navlink" href="/terms">Terms</a>
    </nav>
    <span class="meta">{msg:pub_landing_footer_meta}</span>
  </footer>
```

(`lp-navlink` is the landing's existing hex-colored link class — theme-independent, always visible. No new CSS needed; `lp-foot-links` is just a grouping `<nav>` and needs no style to function.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -o test -Dtest=LandingFooterTest,FooterPolishTest`
Expected: PASS — `LandingFooterTest` green, and `FooterPolishTest` (Task 1) still green because `/login` and `/me` do not pass `ownFooter`, so they keep the shared footer.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/base.html src/main/resources/templates/PublicResource/index.html src/test/java/site/asm0dey/calit/web/LandingFooterTest.java
git commit -m "fix(web): pin landing to light theme + de-duplicate its footer"
```

---

### Task 5: docs-site sync pointer (follow-up, separate branch)

This is a checklist item, not a `main`-branch code change. On the **`docs-site`** branch, add a short HTML comment near the top of `docs-site/src/content/docs/privacy.md` noting that the app serves the same policy at `${APP_BASE_URL}/privacy` and that edits should be mirrored into `src/main/resources/templates/LegalResource/privacy.html`. Commit + push docs-site (Pages redeploys). Do this after the feature branch merges; it does not block this plan.

---

## Self-Review

- **Spec coverage:** Item 1+2+3 (shared footer, contrast, no-JS dropdown) → Task 1; Item 4 (filter allowlist) → Task 2; Item 5 (TZ autodetect, UTC fallback) → Task 3; Item 6 (canonical privacy + landing restyle, terms restyle, sync note) → Task 4 + Task 5. All covered.
- **Placeholder scan:** every code/template/test step shows full content; no TBD/TODO.
- **Type/marker consistency:** markers `CALIT_BUILD_FOOTER`, `CALIT_LEGAL_PRIVACY`, `CALIT_LEGAL_TERMS`, `CALIT_TZ_AUTODETECT` and the `data-tz-autodetect` hook are used identically in templates and asserting tests; `{inject:site.operatorName|contactEmail}` match the shipped `SiteInfo` getters; `localeOptions` fields (`active`/`label`/`href`) match `LocaleOption`.
- **Contrast note:** the `/50`→`/70` + full-strength interactive change lives entirely in `footer.html` (Task 1); RestAssured can't assert color, so it's verified by the class values in review.
```
