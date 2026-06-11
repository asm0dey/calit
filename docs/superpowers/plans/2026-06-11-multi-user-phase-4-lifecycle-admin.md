# Multi-User Support — Phase 4: Lifecycle & Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the multi-user lifecycle layer — site-admin user management, env-gated opt-in signup, and the first-login settings wizard — on top of the Phases 1–3 foundation, ending with the README update.

**Architecture:** Three new JAX-RS resources render Qute templates under `/me/*` (admin-only `UsersResource`, wizard `MeSetupResource`) and a public `/signup`. The existing `MeOwnerFilter` is extended to force any incompletely-onboarded user onto `/me/setup` before reaching other `/me/*` pages. All user state lives on the Phase-1 `AppUser` entity; per-user settings live on `OwnerSettings.forOwner(ownerId)`. Signup is gated purely by config (`calit.signup.enabled` ← env `SIGNUP_ENABLED`); when off, the resource returns 404.

**Tech Stack:** Quarkus 3.36.1, Hibernate Panache, Qute, Postgres, quarkus-security-jpa.

---

## File Structure

**New production files:**
- `src/main/java/com/calit/web/UsersResource.java` — `@Path("/me/users") @RolesAllowed("admin")`: list/create users, grant/revoke admin, lock/unlock.
- `src/main/java/com/calit/web/SignupResource.java` — `@Path("/signup")`: env-gated self-service registration.
- `src/main/java/com/calit/web/MeSetupResource.java` — `@Path("/me/setup")`: first-login wizard (password reset + settings).
- `src/main/resources/templates/UsersResource/users.html` — admin user-management table + create form.
- `src/main/resources/templates/SignupResource/signup.html` — public signup form.
- `src/main/resources/templates/MeSetupResource/meSetup.html` — wizard form (conditional password step + settings step).

**New test files:**
- `src/test/java/com/calit/web/UsersResourceTest.java`
- `src/test/java/com/calit/web/SignupResourceTest.java`
- `src/test/java/com/calit/web/SignupEnabledTest.java`
- `src/test/java/com/calit/web/SignupEnabledProfile.java` — `@TestProfile` turning `calit.signup.enabled` on.
- `src/test/java/com/calit/web/MeSetupResourceTest.java`
- `src/test/java/com/calit/web/MeOwnerFilterWizardTest.java`

**Modified production files:**
- `src/main/java/com/calit/web/MeOwnerFilter.java` — add wizard-redirect after setting `CurrentOwner` (Phase 2 created this; full updated body shown in Task 7).
- `src/main/resources/templates/adminBase.html` — add admin-only Users nav item; threads a `boolean isAdmin` param.
- `src/main/java/com/calit/web/AdminResource.java` (now serving `/me/*` after Phase 2/3) — add `isAdmin` argument to its `CheckedTemplate` calls so `adminBase.html` can show/hide the Users link. (Minimal: only the signatures that render the nav.)
- `src/main/resources/application.properties` — add `calit.signup.enabled=${SIGNUP_ENABLED:false}`.
- `README.md` — final task: document the multi-user model.

**Assumed present from Phases 1–3 (reference only, do NOT redefine):**
- `com.calit.user.AppUser` (fields `id, username, passwordHash, roles, isAdmin, enabled, mustChangePassword, settingsComplete, createdAt`; statics `findByUsername(String)`, `usernameTaken(String)`, `create(username,passwordHash,admin)`; `roles` synced to `isAdmin`).
  > **⚠ Phase-1 carry-forward:** `roles` (comma-string used to build the `SecurityIdentity`) and `isAdmin` (boolean) are a DUAL source of truth, synced ONLY inside `AppUser.create()`. The grant/revoke-admin tasks below flip `isAdmin` on an existing row, so they MUST also rewrite `roles`. Add an `AppUser.setAdmin(boolean)` helper (`this.isAdmin = admin; this.roles = admin ? "user,admin" : "user";`) and use it everywhere admin is toggled — never set `isAdmin` alone, or the augmentor/identity roles go stale.
- `com.calit.user.PasswordHasher` (`hash(String)→String`, `verify(String plain, String hash)→boolean`).
- `com.calit.user.Usernames` (`normalize`, `isValid`, `isReserved`, `validateNew`).
- `com.calit.user.CurrentOwner` (`@RequestScoped`: `set`, `get`, `id`, `require`).
- `OwnerSettings.forOwner(Long ownerId)` (Phase-1 per-owner lookup, replaces the old `SINGLETON_ID` `get()`).
- `MeOwnerFilter` (`@Provider ContainerRequestFilter` on `/me/*`) already sets `CurrentOwner` from the logged-in identity.
- `EnabledUserAugmentor` already rejects disabled users at auth time (so locking invalidates an existing cookie).

**Conventions to follow** (observed in `AdminResource` + existing templates):
- POST handlers re-render a `TemplateInstance` (HTMX-style full-page replacement) OR redirect with `Response.seeOther`; new lifecycle POSTs that change navigation state use 303 redirects.
- Checkbox semantics: `"on".equals(value)` (unchecked sends nothing).
- Templates declare typed params at the top with `{@type name}` and wrap content in `{#include adminBase title=... pendingCount=... active=... isAdmin=...}`.
- Test build command is `./mvnw` (the Maven wrapper is present at repo root).

---

## Task 1: UsersResource — list users (admin only)

**Files:**
- Create: `src/main/java/com/calit/web/UsersResource.java`
- Create: `src/main/resources/templates/UsersResource/users.html`
- Test: `src/test/java/com/calit/web/UsersResourceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class UsersResourceTest {

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void listShowsExistingUsers() {
        given()
            .when().get("/me/users")
            .then().statusCode(200)
            .body(containsString("Users"));
    }

    @Test
    @TestSecurity(user = "alice", roles = {"user"})
    void nonAdminIsForbidden() {
        given()
            .when().get("/me/users")
            .then().statusCode(403);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=UsersResourceTest`
Expected: FAIL — `/me/users` returns 404 (resource not created yet).

- [ ] **Step 3: Create the resource (list handler only)**

```java
package com.calit.web;

import com.calit.user.AppUser;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/me/users")
@RolesAllowed("admin")
public class UsersResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance users(List<AppUser> users, String error, boolean isAdmin);
    }

    /** All users, oldest first — admin management table. */
    private TemplateInstance render(String error) {
        List<AppUser> users = AppUser.list("order by createdAt asc");
        return Templates.users(users, error, true); // page is admin-only, so isAdmin is always true here
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        return render(null);
    }
}
```

- [ ] **Step 4: Create the template `users.html`**

```html
{@java.util.List<com.calit.user.AppUser> users}
{@java.lang.String error}
{@java.lang.Boolean isAdmin}
{#include adminBase title="Admin — Users" pendingCount=0 active="users" isAdmin=isAdmin}
  <h1 class="text-2xl font-bold mb-4">Users</h1>

  {#if error}
  <div class="alert alert-error mb-4 max-w-2xl"><span>{error}</span></div>
  {/if}

  <form method="post" action="/me/users" class="fieldset bg-base-100 border border-base-300 rounded-box p-4 max-w-md mb-6">
    <h2 class="font-semibold">Create user</h2>
    <label class="label">Username</label>
    <input class="input w-full" type="text" name="username" required>
    <label class="label">Temporary password</label>
    <input class="input w-full" type="text" name="tempPassword" required>
    <button type="submit" class="btn btn-primary mt-2">Create user</button>
  </form>

  <table class="table bg-base-100 border border-base-300 rounded-box max-w-3xl">
    <thead>
      <tr><th>Username</th><th>Admin</th><th>Status</th><th>Actions</th></tr>
    </thead>
    <tbody>
      {#for u in users}
      <tr>
        <td>{u.username}</td>
        <td>{#if u.isAdmin}Yes{#else}No{/if}</td>
        <td>{#if u.enabled}Active{#else}Locked{/if}</td>
        <td class="flex flex-wrap gap-1">
          {#if u.isAdmin}
          <form method="post" action="/me/users/{u.id}/revoke-admin"><button class="btn btn-xs">Revoke admin</button></form>
          {#else}
          <form method="post" action="/me/users/{u.id}/grant-admin"><button class="btn btn-xs">Grant admin</button></form>
          {/if}
          {#if u.enabled}
          <form method="post" action="/me/users/{u.id}/lock"><button class="btn btn-xs btn-warning">Lock</button></form>
          {#else}
          <form method="post" action="/me/users/{u.id}/unlock"><button class="btn btn-xs btn-success">Unlock</button></form>
          {/if}
        </td>
      </tr>
      {/for}
    </tbody>
  </table>
{/include}
```

> Note: `adminBase.html` gains an `isAdmin` param in Task 6. To keep this template compiling before Task 6, Task 6 is ordered immediately after the resource tasks that need it. If running strictly in order, the `{#include adminBase ... isAdmin=isAdmin}` line will only resolve once Task 6 adds the `{@java.lang.Boolean isAdmin}` param to `adminBase.html`. Apply Task 6's `adminBase.html` param addition here too if your runner compiles templates eagerly. (Task 6 also covers the nav link.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=UsersResourceTest`
Expected: PASS — admin sees the page (200), non-admin gets 403.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/UsersResource.java \
        src/main/resources/templates/UsersResource/users.html \
        src/test/java/com/calit/web/UsersResourceTest.java
git commit -m "feat: admin user-management list page at /me/users

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: UsersResource — create user

**Files:**
- Modify: `src/main/java/com/calit/web/UsersResource.java`
- Test: `src/test/java/com/calit/web/UsersResourceTest.java`

- [ ] **Step 1: Add failing tests**

Add to `UsersResourceTest`:

```java
    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void createUserPersistsTempUser() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "bob")
            .formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users")
            .then().statusCode(200)
            .body(containsString("bob"));

        // The created user must be onboarding-incomplete and non-admin.
        AppUser bob = AppUser.findByUsername("bob");
        org.junit.jupiter.api.Assertions.assertNotNull(bob);
        org.junit.jupiter.api.Assertions.assertTrue(bob.mustChangePassword);
        org.junit.jupiter.api.Assertions.assertFalse(bob.settingsComplete);
        org.junit.jupiter.api.Assertions.assertTrue(bob.enabled);
        org.junit.jupiter.api.Assertions.assertFalse(bob.isAdmin);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void createUserRejectsInvalidUsername() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "Me")   // reserved + uppercase
            .formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users")
            .then().statusCode(200)
            .body(containsString("error"));
    }
```

Add the import at the top of the test file:

```java
import com.calit.user.AppUser;
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=UsersResourceTest#createUserPersistsTempUser`
Expected: FAIL — no POST handler, returns 405/404.

- [ ] **Step 3: Add the create handler**

Add imports and the method to `UsersResource`:

```java
import com.calit.user.PasswordHasher;
import com.calit.user.Usernames;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import org.jboss.resteasy.reactive.RestForm;
```

```java
    @Inject
    PasswordHasher passwordHasher;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance create(@RestForm String username, @RestForm String tempPassword) {
        String normalized = Usernames.normalize(username);
        String validationError = Usernames.validateNew(normalized); // null when OK
        if (validationError != null) {
            return render(validationError);
        }
        AppUser u = new AppUser();
        u.username = normalized;
        u.passwordHash = passwordHasher.hash(tempPassword);
        u.isAdmin = false;
        u.enabled = true;
        u.mustChangePassword = true;
        u.settingsComplete = false;
        u.roles = "user"; // kept in sync with isAdmin (non-admin → just "user")
        u.createdAt = java.time.Instant.now();
        u.persist();
        return render(null);
    }
```

> `Usernames.validateNew` is assumed (Phase 1) to return a human-readable error string when the username is invalid/reserved/taken, and `null` when acceptable. If the Phase-1 signature instead throws, wrap the call in try/catch and pass `e.getMessage()` to `render`. Confirm the actual Phase-1 signature before editing; do not change `Usernames`.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw test -Dtest=UsersResourceTest`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/UsersResource.java \
        src/test/java/com/calit/web/UsersResourceTest.java
git commit -m "feat: admin create-user with temp password and onboarding flags

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: UsersResource — grant/revoke admin

**Files:**
- Modify: `src/main/java/com/calit/web/UsersResource.java`
- Test: `src/test/java/com/calit/web/UsersResourceTest.java`

- [ ] **Step 1: Add failing tests**

```java
    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void grantAndRevokeAdminSyncRoles() {
        // Arrange: create a plain user.
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "carol").formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users").then().statusCode(200);
        AppUser carol = AppUser.findByUsername("carol");

        given().when().post("/me/users/" + carol.id + "/grant-admin")
            .then().statusCode(200);
        AppUser afterGrant = AppUser.findById(carol.id);
        org.junit.jupiter.api.Assertions.assertTrue(afterGrant.isAdmin);
        org.junit.jupiter.api.Assertions.assertEquals("user,admin", afterGrant.roles);

        given().when().post("/me/users/" + carol.id + "/revoke-admin")
            .then().statusCode(200);
        AppUser afterRevoke = AppUser.findById(carol.id);
        org.junit.jupiter.api.Assertions.assertFalse(afterRevoke.isAdmin);
        org.junit.jupiter.api.Assertions.assertEquals("user", afterRevoke.roles);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=UsersResourceTest#grantAndRevokeAdminSyncRoles`
Expected: FAIL — grant/revoke endpoints return 404.

- [ ] **Step 3: Add the handlers**

Add imports and methods to `UsersResource`:

```java
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
```

```java
    /** Load a user or 404 — shared guard for id-scoped POST handlers. */
    private AppUser requireUser(Long id) {
        AppUser u = AppUser.findById(id);
        if (u == null) {
            throw new NotFoundException("No user " + id);
        }
        return u;
    }

    @POST
    @Path("/{id}/grant-admin")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance grantAdmin(@PathParam("id") Long id) {
        AppUser u = requireUser(id);
        u.isAdmin = true;
        u.roles = "user,admin"; // roles kept in sync with isAdmin
        return render(null);
    }

    @POST
    @Path("/{id}/revoke-admin")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance revokeAdmin(@PathParam("id") Long id) {
        AppUser u = requireUser(id);
        u.isAdmin = false;
        u.roles = "user";
        return render(null);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw test -Dtest=UsersResourceTest#grantAndRevokeAdminSyncRoles`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/UsersResource.java \
        src/test/java/com/calit/web/UsersResourceTest.java
git commit -m "feat: grant/revoke admin syncs AppUser roles

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: UsersResource — lock/unlock (+ disabled-user auth rejection)

**Files:**
- Modify: `src/main/java/com/calit/web/UsersResource.java`
- Test: `src/test/java/com/calit/web/UsersResourceTest.java`

- [ ] **Step 1: Add failing tests**

```java
    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void lockAndUnlockTogglesEnabled() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "dave").formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users").then().statusCode(200);
        AppUser dave = AppUser.findByUsername("dave");

        given().when().post("/me/users/" + dave.id + "/lock").then().statusCode(200);
        org.junit.jupiter.api.Assertions.assertFalse(((AppUser) AppUser.findById(dave.id)).enabled);

        given().when().post("/me/users/" + dave.id + "/unlock").then().statusCode(200);
        org.junit.jupiter.api.Assertions.assertTrue(((AppUser) AppUser.findById(dave.id)).enabled);
    }
```

Add a second test asserting a locked user can no longer authenticate. This relies on the Phase-2 form-login + `EnabledUserAugmentor`. Add a real-credential login helper that posts to `/j_security_check`:

```java
    @Test
    void lockedUserCannotLogIn() {
        // Seed a real DB user we can log in as (bypassing @TestSecurity to exercise the augmentor).
        Long id = io.quarkus.test.TestTransaction.isActive() ? null : seedEnabledUser("erin", "Erin-pw-12345");

        // 1) Enabled user logs in successfully (302 to landing, sets the credential cookie).
        io.restassured.response.Response ok = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "erin")
            .formParam("j_password", "Erin-pw-12345")
            .redirects().follow(false)
            .when().post("/j_security_check");
        org.junit.jupiter.api.Assertions.assertEquals(302, ok.statusCode());

        // 2) Lock the user directly in the DB.
        lockUser(id);

        // 3) Re-login now fails: the JPA identity provider rejects the disabled user.
        io.restassured.response.Response denied = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "erin")
            .formParam("j_password", "Erin-pw-12345")
            .redirects().follow(false)
            .when().post("/j_security_check");
        // Form-auth error redirects to the error page (not the landing page).
        org.junit.jupiter.api.Assertions.assertTrue(
            denied.statusCode() == 302 && denied.getHeader("Location").contains("error"),
            "locked user login should redirect to the error page, got "
                + denied.statusCode() + " -> " + denied.getHeader("Location"));
    }

    @jakarta.transaction.Transactional
    Long seedEnabledUser(String username, String plainPassword) {
        AppUser u = new AppUser();
        u.username = username;
        u.passwordHash = passwordHasher.hash(plainPassword);
        u.isAdmin = false;
        u.enabled = true;
        u.mustChangePassword = false;
        u.settingsComplete = true;
        u.roles = "user";
        u.createdAt = java.time.Instant.now();
        u.persist();
        return u.id;
    }

    @jakarta.transaction.Transactional
    void lockUser(Long id) {
        AppUser u = AppUser.findById(id);
        u.enabled = false;
    }

    @jakarta.inject.Inject
    com.calit.user.PasswordHasher passwordHasher;
```

> If injecting a bean into a `@QuarkusTest` for the seed helpers is awkward in this codebase's test style, prefer the existing Phase-1/2 test helper that seeds an `AppUser` (the spec mentions `FormAuth` seeds a DB `AppUser`). Reuse that helper instead of duplicating `seedEnabledUser`. Confirm its name in `src/test/java` before writing; do not invent a new helper if one exists.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=UsersResourceTest#lockAndUnlockTogglesEnabled`
Expected: FAIL — lock/unlock endpoints return 404.

- [ ] **Step 3: Add the handlers**

Add to `UsersResource`:

```java
    @POST
    @Path("/{id}/lock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance lock(@PathParam("id") Long id) {
        AppUser u = requireUser(id);
        u.enabled = false;
        return render(null);
    }

    @POST
    @Path("/{id}/unlock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance unlock(@PathParam("id") Long id) {
        AppUser u = requireUser(id);
        u.enabled = true;
        return render(null);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw test -Dtest=UsersResourceTest`
Expected: PASS — lock/unlock toggle `enabled`; locked user's login is rejected at the error page.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/UsersResource.java \
        src/test/java/com/calit/web/UsersResourceTest.java
git commit -m "feat: admin lock/unlock users; locked user cannot authenticate

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: SignupResource — env-gated self-service signup

**Files:**
- Create: `src/main/java/com/calit/web/SignupResource.java`
- Create: `src/main/resources/templates/SignupResource/signup.html`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/calit/web/SignupResourceTest.java`
- Test: `src/test/java/com/calit/web/SignupEnabledTest.java`
- Test: `src/test/java/com/calit/web/SignupEnabledProfile.java`

- [ ] **Step 1: Add the config property**

Edit `src/main/resources/application.properties`. After the existing Turnstile/abuse block (a good home is right below the `%test.quarkus.scheduler.enabled=false` line at the end, or grouped with the auth block), add:

```properties
# --- Opt-in self-service sign-up (Phase 4) ---
# Public /signup is gated by this flag. When false (default), GET and POST /signup return 404.
# Toggling requires changing the env and restarting; there is no runtime UI toggle.
calit.signup.enabled=${SIGNUP_ENABLED:false}
```

- [ ] **Step 2: Write the failing "disabled" test**

`src/test/java/com/calit/web/SignupResourceTest.java`:

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class SignupResourceTest {

    // Default profile: calit.signup.enabled=false → both verbs 404.

    @Test
    void getReturns404WhenDisabled() {
        given().when().get("/signup").then().statusCode(404);
    }

    @Test
    void postReturns404WhenDisabled() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "frank")
            .formParam("password", "Frank-pw-12345")
            .when().post("/signup")
            .then().statusCode(404);
    }
}
```

- [ ] **Step 3: Note the false-green risk, defer the red to the enabled test**

With no resource yet, JAX-RS already returns 404 for `/signup`, so `SignupResourceTest` (the disabled case) would pass for the wrong reason. The genuine red bar for this task is therefore the **enabled** test in Step 4 (`SignupEnabledTest.getRendersForm` expects 200 and will FAIL until the resource exists). Do not run `SignupResourceTest` in isolation here expecting red — its value is as a regression guard that gating stays correct once the resource is built. Write Steps 4–6, then run everything together in Step 7.

- [ ] **Step 4: Write the test profile + enabled test**

`src/test/java/com/calit/web/SignupEnabledProfile.java`:

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Turns on opt-in signup so /signup is reachable in SignupEnabledTest. */
public class SignupEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("calit.signup.enabled", "true");
    }
}
```

`src/test/java/com/calit/web/SignupEnabledTest.java`:

```java
package com.calit.web;

import com.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(SignupEnabledProfile.class)
class SignupEnabledTest {

    @Test
    void getRendersForm() {
        given().when().get("/signup")
            .then().statusCode(200)
            .body(containsString("Sign up"));
    }

    @Test
    void postCreatesSelfServiceUser() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "grace")
            .formParam("password", "Grace-pw-12345")
            .redirects().follow(false)
            .when().post("/signup")
            .then().statusCode(303); // redirect to /login after registration

        AppUser grace = AppUser.findByUsername("grace");
        org.junit.jupiter.api.Assertions.assertNotNull(grace);
        // Self-service users pick their own password → no forced reset, but still must do the settings wizard.
        org.junit.jupiter.api.Assertions.assertFalse(grace.mustChangePassword);
        org.junit.jupiter.api.Assertions.assertFalse(grace.settingsComplete);
        org.junit.jupiter.api.Assertions.assertTrue(grace.enabled);
        org.junit.jupiter.api.Assertions.assertFalse(grace.isAdmin);
    }
}
```

- [ ] **Step 5: Create the resource**

`src/main/java/com/calit/web/SignupResource.java`:

```java
package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.PasswordHasher;
import com.calit.user.Usernames;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

@Path("/signup")
public class SignupResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance signup(String error);
    }

    @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false")
    boolean signupEnabled;

    @Inject
    PasswordHasher passwordHasher;

    /** When signup is disabled the whole resource is invisible: behave exactly like no route. */
    private void requireEnabled() {
        if (!signupEnabled) {
            throw new NotFoundException();
        }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance form() {
        requireEnabled();
        return Templates.signup(null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response register(@RestForm String username, @RestForm String password) {
        requireEnabled();
        String normalized = Usernames.normalize(username);
        String validationError = Usernames.validateNew(normalized);
        if (validationError != null) {
            return Response.ok(Templates.signup(validationError)).build();
        }
        AppUser u = new AppUser();
        u.username = normalized;
        u.passwordHash = passwordHasher.hash(password);
        u.isAdmin = false;
        u.enabled = true;
        u.mustChangePassword = false; // self-chosen password → no forced reset
        u.settingsComplete = false;   // still needs the first-login settings wizard
        u.roles = "user";
        u.createdAt = java.time.Instant.now();
        u.persist();
        // Registered — send them to log in, then the wizard kicks in at /me.
        return Response.seeOther(UriBuilder.fromUri("/login").build()).build();
    }
}
```

- [ ] **Step 6: Create the template `signup.html`**

`src/main/resources/templates/SignupResource/signup.html`:

```html
{@java.lang.String error}
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Sign up — calit</title>
  <link rel="stylesheet" href="/calit.css">
</head>
<body class="min-h-screen flex items-center justify-center bg-base-200">
  <form method="post" action="/signup" class="fieldset bg-base-100 border border-base-300 rounded-box p-6 w-full max-w-sm">
    <h1 class="text-xl font-bold mb-2">Sign up</h1>
    {#if error}
    <div class="alert alert-error mb-2"><span>{error}</span></div>
    {/if}
    <label class="label">Username</label>
    <input class="input w-full" type="text" name="username" required autofocus>
    <label class="label">Password</label>
    <input class="input w-full" type="password" name="password" required>
    <button type="submit" class="btn btn-primary mt-3">Create account</button>
    <a href="/login" class="link link-hover text-sm mt-2">Already have an account? Log in</a>
  </form>
</body>
</html>
```

- [ ] **Step 7: Run both test classes**

Run: `./mvnw test -Dtest=SignupResourceTest,SignupEnabledTest`
Expected: PASS — disabled profile 404s both verbs; enabled profile renders the form and creates `grace` with the right flags, then 303 to `/login`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/calit/web/SignupResource.java \
        src/main/resources/templates/SignupResource/signup.html \
        src/main/resources/application.properties \
        src/test/java/com/calit/web/SignupResourceTest.java \
        src/test/java/com/calit/web/SignupEnabledTest.java \
        src/test/java/com/calit/web/SignupEnabledProfile.java
git commit -m "feat: env-gated /signup (404 when off, creates self-service user when on)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: adminBase nav — admin-only Users link (thread isAdmin)

**Files:**
- Modify: `src/main/resources/templates/adminBase.html`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (add `isAdmin` arg to nav-rendering template calls)
- Test: `src/test/java/com/calit/web/UsersResourceTest.java` (assert the link shows on an admin page)

> The wizard's `/me/setup` is its own page (Task 7) and does not use `adminBase`, so the wizard is unaffected by this task.

- [ ] **Step 1: Add the `isAdmin` param + Users nav item to `adminBase.html`**

At the top of `adminBase.html`, after the existing param declarations, add:

```html
{@java.lang.Boolean isAdmin}
```

Inside the `<ul class="menu ...">`, after the Google `<li>` (the last current item), add the admin-only Users link:

```html
        {#if isAdmin}
        <li><a href="/me/users" class="{#if active == 'users'}menu-active{/if}">
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/></svg>Users</a></li>
        {/if}
```

- [ ] **Step 2: Make existing template calls compile with the new param**

`adminBase.html` now requires an `isAdmin` arg on every `{#include adminBase ...}`. Every template that includes `adminBase` must pass it, and every `Templates.*` method whose template renders the nav must accept and forward it.

For this phase, the minimal change is on `AdminResource` (the `/me/*` management resource after Phases 2/3). Update each nav-rendering `CheckedTemplate` native method to accept a trailing `boolean isAdmin`, pass `currentOwner`-derived admin status, and update each `{#include adminBase ...}` to add `isAdmin=isAdmin`.

Concretely, AdminResource already injects nothing that knows admin status; inject the current user. Add:

```java
import io.quarkus.security.identity.SecurityIdentity;
```

```java
    @Inject
    SecurityIdentity identity;

    /** True when the logged-in user holds the site-admin role (drives the Users nav link). */
    private boolean isAdmin() {
        return identity.hasRole("admin");
    }
```

Then, for **each** `Templates.*(...)` native method that backs a page using `adminBase` (dashboard, meetingTypes, meetingTypeDetail, availability, settings, google, bookingFields, dateOverrides, pending), add a trailing `boolean isAdmin` parameter, and pass `isAdmin()` at every call site. Example for `settings`:

Native method:
```java
        public static native TemplateInstance settings(
                OwnerSettings settings, int reminderLeadMinutes, Long pendingCount,
                java.util.List<String> zones, boolean isAdmin);
```

Call sites (both in `settings()` and `updateSettings()`):
```java
        return Templates.settings(OwnerSettings.forOwner(currentOwner.id()), reminderLeadMinutes,
                pendingCount(), zoneIds(), isAdmin());
```

And in each corresponding template (`AdminResource/settings.html`, etc.) add `{@java.lang.Boolean isAdmin}` at the top and `isAdmin=isAdmin` on the `{#include adminBase ...}` line.

> This is mechanical but touches every admin page/template. Keep it minimal: add one param, one call argument, one include attribute per page. Do NOT refactor unrelated code. The `pending.html` and `dashboard.html` templates that use their own layout (`Layout.TZ_SCRIPT`, not `adminBase`) do not need the param — only templates that actually `{#include adminBase}` do. Grep first: `grep -rl 'include adminBase' src/main/resources/templates`.

- [ ] **Step 3: Add the nav-link assertion test**

Add to `UsersResourceTest`:

```java
    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void adminSeesUsersNavLink() {
        given().when().get("/me/users")
            .then().statusCode(200)
            .body(containsString("/me/users")); // the nav link href is present for admins
    }
```

> A stronger assertion (link hidden for non-admins) requires a non-admin-reachable `adminBase` page. Since all `/me/*` management pages require the `admin` role in this codebase (carried over from `/admin/*`), there is no non-admin `adminBase` page to assert the negative against; the `{#if isAdmin}` guard is still correct and future-proofs the nav if a non-admin `/me` page is added later. Do not add a page just to test the negative.

- [ ] **Step 4: Run**

Run: `./mvnw test -Dtest=UsersResourceTest`
Expected: PASS — page renders with the Users link; the whole `/me/*` admin UI still compiles with the new param.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/adminBase.html \
        src/main/resources/templates/AdminResource \
        src/main/java/com/calit/web/AdminResource.java \
        src/test/java/com/calit/web/UsersResourceTest.java
git commit -m "feat: admin-only Users nav link via threaded isAdmin param

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: MeSetupResource — first-login wizard

**Files:**
- Create: `src/main/java/com/calit/web/MeSetupResource.java`
- Create: `src/main/resources/templates/MeSetupResource/meSetup.html`
- Test: `src/test/java/com/calit/web/MeSetupResourceTest.java`

The wizard has two steps in one page: (1) a password-reset block shown only when `mustChangePassword`; (2) the settings block (display name, email, timezone). One POST handles both: if the user still `mustChangePassword`, the password fields are required and the flag is cleared; the settings fields create/update `OwnerSettings.forOwner(...)` and set `settingsComplete=true`; then redirect `/me`.

- [ ] **Step 1: Write the failing tests**

```java
package com.calit.web;

import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import com.calit.user.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class MeSetupResourceTest {

    @Inject
    PasswordHasher passwordHasher;

    @Transactional
    Long seed(String username, boolean mustChange) {
        AppUser u = new AppUser();
        u.username = username;
        u.passwordHash = passwordHasher.hash("Initial-pw-12345");
        u.isAdmin = false;
        u.enabled = true;
        u.mustChangePassword = mustChange;
        u.settingsComplete = false;
        u.roles = "user";
        u.createdAt = java.time.Instant.now();
        u.persist();
        return u.id;
    }

    @Test
    @TestSecurity(user = "wiz1", roles = {"user"})
    void getRendersWizardWithPasswordStepWhenForced() {
        seed("wiz1", true);
        given().when().get("/me/setup")
            .then().statusCode(200)
            .body(containsString("New password"));
    }

    @Test
    @TestSecurity(user = "wiz2", roles = {"user"})
    void postCompletesPasswordAndSettings() {
        Long id = seed("wiz2", true);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("newPassword", "Brand-new-pw-12345")
            .formParam("ownerName", "Wiz Two")
            .formParam("ownerEmail", "wiz2@example.com")
            .formParam("timezone", "Europe/Amsterdam")
            .redirects().follow(false)
            .when().post("/me/setup")
            .then().statusCode(303); // redirect to /me

        AppUser after = AppUser.findById(id);
        org.junit.jupiter.api.Assertions.assertFalse(after.mustChangePassword);
        org.junit.jupiter.api.Assertions.assertTrue(after.settingsComplete);
        org.junit.jupiter.api.Assertions.assertTrue(
            passwordHasher.verify("Brand-new-pw-12345", after.passwordHash),
            "password should have been updated to the new value");

        OwnerSettings s = OwnerSettings.forOwner(id);
        org.junit.jupiter.api.Assertions.assertNotNull(s);
        org.junit.jupiter.api.Assertions.assertEquals("Wiz Two", s.ownerName);
        org.junit.jupiter.api.Assertions.assertEquals("wiz2@example.com", s.ownerEmail);
        org.junit.jupiter.api.Assertions.assertEquals("Europe/Amsterdam", s.timezone);
    }

    @Test
    @TestSecurity(user = "wiz3", roles = {"user"})
    void postSkipsPasswordWhenNotForced() {
        Long id = seed("wiz3", false); // self-service user: no forced reset

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("ownerName", "Wiz Three")
            .formParam("ownerEmail", "wiz3@example.com")
            .formParam("timezone", "Europe/Amsterdam")
            .redirects().follow(false)
            .when().post("/me/setup")
            .then().statusCode(303);

        AppUser after = AppUser.findById(id);
        org.junit.jupiter.api.Assertions.assertTrue(after.settingsComplete);
        // Password unchanged.
        org.junit.jupiter.api.Assertions.assertTrue(
            passwordHasher.verify("Initial-pw-12345", after.passwordHash));
    }
}
```

> The `@TestSecurity` username must match the seeded username so `CurrentOwner`/`MeOwnerFilter` resolves the same `AppUser`. If the Phase-2 `CurrentOwner.id()` resolves the owner from the augmented identity (not a path), seeding a row with the matching `username` is sufficient. Verify how `CurrentOwner.id()` is wired (augmentor attribute) before relying on it; do not modify `CurrentOwner`.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=MeSetupResourceTest`
Expected: FAIL — `/me/setup` returns 404 (resource not created). (Note: until Task 8 extends `MeOwnerFilter`, a GET to `/me/setup` is not yet force-redirected — that's fine; this task only builds the page itself.)

- [ ] **Step 3: Create the resource**

`src/main/java/com/calit/web/MeSetupResource.java`:

```java
package com.calit.web;

import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import com.calit.user.CurrentOwner;
import com.calit.user.PasswordHasher;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;

/** First-login wizard, distinct from the first-run /setup bootstrap. */
@Path("/me/setup")
@RolesAllowed("user")
public class MeSetupResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance meSetup(
                boolean mustChangePassword, OwnerSettings settings, List<String> zones, String error);
    }

    @Inject
    CurrentOwner currentOwner;

    @Inject
    PasswordHasher passwordHasher;

    /** All IANA zone ids, sorted — for the timezone combobox (matches AdminResource.settings). */
    private static List<String> zoneIds() {
        return java.time.ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance wizard() {
        AppUser me = AppUser.findById(currentOwner.id());
        OwnerSettings existing = OwnerSettings.forOwner(currentOwner.id()); // may be null on first visit
        return Templates.meSetup(me.mustChangePassword, existing, zoneIds(), null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response submit(@RestForm String newPassword,
                           @RestForm String ownerName,
                           @RestForm String ownerEmail,
                           @RestForm String timezone) {
        Long ownerId = currentOwner.id();
        AppUser me = AppUser.findById(ownerId);

        // Step 1: only when a forced reset is pending.
        if (me.mustChangePassword) {
            if (newPassword == null || newPassword.isBlank()) {
                return Response.ok(Templates.meSetup(true,
                        OwnerSettings.forOwner(ownerId), zoneIds(),
                        "Please choose a new password.")).build();
            }
            me.passwordHash = passwordHasher.hash(newPassword);
            me.mustChangePassword = false;
        }

        // Step 2: create/update this owner's settings row.
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId; // per-owner row (Phase 1 dropped SINGLETON_ID)
        }
        s.ownerName = ownerName;
        s.ownerEmail = ownerEmail;
        s.timezone = timezone;
        s.persist();

        me.settingsComplete = true;
        return Response.seeOther(UriBuilder.fromUri("/me").build()).build();
    }
}
```

> `OwnerSettings.ownerId` is the Phase-1 per-owner FK field. If Phase 1 named the association differently (e.g. an `AppUser owner` reference rather than a `Long ownerId`), set it accordingly — read `OwnerSettings.java` as it exists post-Phase-1 before editing. The Phase-1 `OwnerSettings.forOwner(Long)` is the canonical lookup. Do not reintroduce `SINGLETON_ID`.

- [ ] **Step 4: Create the template `meSetup.html`**

`src/main/resources/templates/MeSetupResource/meSetup.html` (reuses the settings.html field style + the same timezone `{#for}` loop):

```html
{@java.lang.Boolean mustChangePassword}
{@com.calit.domain.OwnerSettings settings}
{@java.util.List<java.lang.String> zones}
{@java.lang.String error}
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Welcome — set up your account</title>
  <link rel="stylesheet" href="/calit.css">
</head>
<body class="min-h-screen flex items-center justify-center bg-base-200 py-8">
  <form method="post" action="/me/setup" class="fieldset bg-base-100 border border-base-300 rounded-box p-6 w-full max-w-md">
    <h1 class="text-2xl font-bold mb-2">Finish setting up</h1>
    <p class="text-sm opacity-70 mb-2">A couple of details before you start.</p>

    {#if error}
    <div class="alert alert-error mb-2"><span>{error}</span></div>
    {/if}

    {#if mustChangePassword}
    <h2 class="font-semibold mt-2">Choose a password</h2>
    <label class="label">New password</label>
    <input class="input w-full" type="password" name="newPassword" required>
    {/if}

    <h2 class="font-semibold mt-3">Your details</h2>
    <label class="label">Name</label>
    <input class="input w-full" type="text" name="ownerName" required value="{#if settings}{settings.ownerName}{/if}">
    <label class="label">Email</label>
    <input class="input w-full" type="email" name="ownerEmail" required value="{#if settings}{settings.ownerEmail}{/if}">
    <label class="label">Timezone</label>
    <select class="select w-full" name="timezone" required>
      {#for z in zones}
        {#if settings}
        <option value="{z}"{#if settings.timezone == z} selected{/if}>{z}</option>
        {#else}
        <option value="{z}"{#if z == 'Europe/Amsterdam'} selected{/if}>{z}</option>
        {/if}
      {/for}
    </select>

    <button type="submit" class="btn btn-primary mt-4">Finish</button>
  </form>
</body>
</html>
```

- [ ] **Step 5: Run to verify pass**

Run: `./mvnw test -Dtest=MeSetupResourceTest`
Expected: PASS — wizard renders the password step when forced, completes both steps (password updated, settings written, `settingsComplete=true`, 303 to `/me`), and skips the password update when not forced.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/MeSetupResource.java \
        src/main/resources/templates/MeSetupResource/meSetup.html \
        src/test/java/com/calit/web/MeSetupResourceTest.java
git commit -m "feat: first-login wizard at /me/setup (password reset + settings)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: MeOwnerFilter — wizard-redirect extension

**Files:**
- Modify: `src/main/java/com/calit/web/MeOwnerFilter.java`
- Test: `src/test/java/com/calit/web/MeOwnerFilterWizardTest.java`

The Phase-2 filter already sets `CurrentOwner` from the logged-in identity for `/me/*`. Extend it: after setting the owner, if the user is not fully onboarded (`mustChangePassword || !settingsComplete`) AND the request path is not `/me/setup` itself (and not a static asset), abort with a 302 to `/me/setup`.

- [ ] **Step 1: Write the failing tests**

```java
package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class MeOwnerFilterWizardTest {

    @Inject
    PasswordHasher passwordHasher;

    @Transactional
    void seed(String username, boolean settingsComplete) {
        AppUser u = new AppUser();
        u.username = username;
        u.passwordHash = passwordHasher.hash("Initial-pw-12345");
        u.isAdmin = false;
        u.enabled = true;
        u.mustChangePassword = false;
        u.settingsComplete = settingsComplete;
        u.roles = "user";
        u.createdAt = java.time.Instant.now();
        u.persist();
    }

    @Test
    @TestSecurity(user = "incomplete", roles = {"user"})
    void meDashboardRedirectsToSetupWhenIncomplete() {
        seed("incomplete", false);
        given().redirects().follow(false)
            .when().get("/me")
            .then().statusCode(302)
            .header("Location", org.hamcrest.Matchers.containsString("/me/setup"));
    }

    @Test
    @TestSecurity(user = "incomplete2", roles = {"user"})
    void setupPageItselfIsReachableWhileIncomplete() {
        seed("incomplete2", false);
        given().redirects().follow(false)
            .when().get("/me/setup")
            .then().statusCode(200); // must NOT redirect to itself
    }

    @Test
    @TestSecurity(user = "complete", roles = {"user", "admin"})
    void completedUserReachesMeNormally() {
        seed("complete", true);
        given().redirects().follow(false)
            .when().get("/me")
            .then().statusCode(200); // dashboard, no redirect
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=MeOwnerFilterWizardTest#meDashboardRedirectsToSetupWhenIncomplete`
Expected: FAIL — `/me` returns 200 (no redirect yet) for the incomplete user.

- [ ] **Step 3: Update the filter (full body)**

Replace the body of `src/main/java/com/calit/web/MeOwnerFilter.java` with the version below. This shows the complete filter including the Phase-2 owner-resolution it already does, plus the new wizard redirect. Keep the Phase-2 owner-resolution exactly as the existing file has it; only the marked block is added.

```java
package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.CurrentOwner;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

/**
 * For every authenticated /me/* request: resolve and set CurrentOwner from the logged-in
 * identity (Phase 2), then force not-yet-onboarded users through the first-login wizard (Phase 4).
 */
@Provider
public class MeOwnerFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    CurrentOwner currentOwner;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath(); // e.g. "me", "me/setup", "me/users"
        if (!isMePath(path)) {
            return; // filter only governs /me/*
        }
        if (identity == null || identity.isAnonymous()) {
            return; // unauthenticated requests are handled by the security layer, not here
        }

        // --- Phase 2: resolve owner from the logged-in username and publish it for this request. ---
        String username = identity.getPrincipal().getName();
        AppUser me = AppUser.findByUsername(username);
        if (me == null) {
            return; // augmentor should have rejected this; nothing to scope
        }
        currentOwner.set(me); // (Phase 2 CurrentOwner.set signature; adjust if it takes an id)

        // --- Phase 4: force the first-login wizard until onboarding is complete. ---
        boolean onboardingComplete = !me.mustChangePassword && me.settingsComplete;
        if (!onboardingComplete && !isSetupPath(path)) {
            ctx.abortWith(
                Response.status(Response.Status.FOUND) // 302
                    .location(UriBuilder.fromUri("/me/setup").build())
                    .build());
        }
    }

    /** True for the /me root and any /me/... sub-path (path is reported without a leading slash). */
    private static boolean isMePath(String path) {
        return path.equals("me") || path.startsWith("me/");
    }

    /** The wizard page itself must stay reachable while onboarding is incomplete. */
    private static boolean isSetupPath(String path) {
        return path.equals("me/setup");
    }
}
```

> Match the existing Phase-2 file's exact `CurrentOwner` API (`set(AppUser)` vs `set(Long id)`), its `@PreMatching`/path-matching style, and any static-asset handling it already had. If the Phase-2 filter used `@PreMatching` (so `getPath()` is matched before resolution), keep that annotation — add `@PreMatching` above `@Provider` here too. Do not regress the Phase-2 owner resolution; only the marked Phase-4 block is new. Static assets are served from non-`/me/*` paths (`/calit.css`, etc.), so the `isMePath` guard already excludes them — no extra asset check is needed.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw test -Dtest=MeOwnerFilterWizardTest`
Expected: PASS — `/me` 302s to `/me/setup` while incomplete; `/me/setup` itself returns 200; a completed user reaches `/me` (200).

- [ ] **Step 5: Full regression run**

Run: `./mvnw test`
Expected: PASS — the new redirect must not break existing `/me/*` admin tests (those seed completed users; if any seed left `settingsComplete=false`, update that seed helper to set it true — the management tests assume a fully-onboarded admin). If an existing test now 302s unexpectedly, fix its seed to `settingsComplete=true, mustChangePassword=false`, not the filter.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/MeOwnerFilter.java \
        src/test/java/com/calit/web/MeOwnerFilterWizardTest.java
git commit -m "feat: MeOwnerFilter forces /me/setup until onboarding completes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: README update

**Files:**
- Modify: `README.md`

This is documentation only — no test. After editing, run the suite once to confirm nothing else regressed during the phase.

- [ ] **Step 1: Replace the intro paragraph**

Replace the first paragraph (the "single-owner scheduling app" sentence through the abuse-protection list) with:

```markdown
A **multi-user** scheduling app built on Quarkus — each user runs their own independent
"Calendly": isolated meeting types, availability, bookings, settings, and Google account, served
from a personal public URL `/<username>/<slug>`. You publish bookable meeting types; invitees pick
a slot and book. Bookings sync to Google Calendar (optional), auto-create a Google Meet link, and
email both parties. Includes per-type buffers, min-notice/booking-horizon, date-specific availability
overrides, an approval workflow, custom booking-form fields, reminders, and public-form abuse
protection (Cloudflare Turnstile + honeypot + per-email daily cap).

**Users & isolation.** Every user is an `app_user` row (passwords hashed with **argon2id**). All
tenant data carries an `owner_id`, and every query is owner-scoped — one user can never see or edit
another's meeting types, bookings, settings, or calendar. Site admins (`is_admin`) manage users at
`/me/users`: create users (with a one-time temporary password), grant/revoke admin, and lock/unlock
accounts (a locked account can no longer log in, and its existing session cookie stops working).
```

- [ ] **Step 2: Add a "User accounts & onboarding" section**

Insert a new section after the intro (before "## Requirements"):

```markdown
## User accounts & onboarding

- **First run (bootstrap).** When the database has no users, every request redirects to `/setup`,
  which creates the first user as a **site admin**. Once any user exists, `/setup` returns 404.
- **Admin-created users.** A site admin creates accounts at `/me/users` with a username and a
  temporary password. The new user must change that password and complete the settings wizard on
  first login.
- **Opt-in self-service sign-up.** Public `/signup` is **off by default**. Set `SIGNUP_ENABLED=true`
  to let anyone register (username + their own password); when off, `/signup` returns 404. Changing
  the flag requires a restart — there is no runtime toggle.
- **First-login wizard (`/me/setup`).** On first login a user is sent to `/me/setup` and kept there
  until onboarding is done: set a new password (only for admin-created temp-password accounts) and
  fill in display name, email, and timezone. After that they land on `/me`.

### URL scheme

| Path | Audience |
|---|---|
| `/me`, `/me/meeting-types`, `/me/availability`, `/me/settings`, … | The logged-in user's own management UI. |
| `/me/users` | Site admins only — user management. |
| `/me/setup` | First-login onboarding wizard. |
| `/{username}` | A user's public landing page (their active meeting types). |
| `/{username}/{slug}` | Public booking page for one meeting type. |
| `/setup` | First-run bootstrap (404 once a user exists). |
| `/signup` | Self-service registration (404 unless `SIGNUP_ENABLED=true`). |
```

- [ ] **Step 3: Fix the Owner-admin quick-start line**

In "## Quick start (local dev)", replace the `Owner admin:` bullet with:

```markdown
- Management UI: <http://localhost:8080/me> (form login at `/login`). On a fresh database, visit any
  page and you'll be redirected to `/setup` to create the first (admin) user — there is **no** default
  password.
```

- [ ] **Step 4: Remove `ADMIN_PASSWORD` from the env tables and Docker steps**

In "## Configuration (environment variables) → Required", delete the `ADMIN_PASSWORD` row and replace it with nothing (the first admin is created via `/setup`, not an env var). Update the `SESSION_ENCRYPTION_KEY` row's purpose text to "Encrypts the login cookie" (drop the word "admin"). Add a new row to the **Common / defaulted** table:

```markdown
| `SIGNUP_ENABLED` | `false` | Allow public self-service sign-up at `/signup`. When `false`, `/signup` returns 404. |
```

In "## Run with Docker Compose", change the `cp .env.example .env` comment from
`set DB_PASSWORD, ADMIN_PASSWORD, APP_BASE_URL` to `set DB_PASSWORD, SESSION_ENCRYPTION_KEY, APP_BASE_URL`.

- [ ] **Step 5: Update auth wording and migration version references**

- In "## Build & run for production" and "## Notes & operational details", change every `V1…V6`
  reference to `V1…V7` (the multi-user migration added in Phase 1).
- In "## First-run checklist", replace step 1–2:

```markdown
1. Deploy with at least the **required** env vars set (DB, SMTP, `APP_BASE_URL`,
   `SESSION_ENCRYPTION_KEY`). There is no `ADMIN_PASSWORD` — the first admin is created on first run.
2. Visit the site and complete `/setup` to create the first (admin) user, then finish the
   first-login wizard (password is already set here; fill in name, email, timezone).
```

- In "## First-run checklist" step 8, change the secret-link path from `/book/{slug}` to
  `/{username}/{slug}`.

- [ ] **Step 6: Add an auth note to operational details**

Append to "## Notes & operational details":

```markdown
- **Authentication:** users are stored in the `app_user` table with **argon2id** password hashes
  (no embedded/env-configured user; the old `ADMIN_PASSWORD` is gone). Form login at `/login` issues
  an encrypted, stateless cookie shared across replicas. Disabling a user (admin lock) is enforced at
  authentication time, so a locked user's existing cookie immediately stops working.
- **Per-user isolation:** all tenant tables carry `owner_id` and every query is owner-scoped; there is
  no cross-user visibility. `meeting_type.slug` is unique **per user**, so two users can both use
  `intro-call`.
```

- [ ] **Step 7: Verify the full suite still passes**

Run: `./mvnw test`
Expected: PASS — README changes are doc-only; this run confirms the whole phase is green end to end.

- [ ] **Step 8: Commit**

```bash
git add README.md
git commit -m "docs: document multi-user model, /setup, SIGNUP_ENABLED, /me URL scheme

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Done

The phase is complete when `./mvnw test` is green and the README reflects: the multi-user model +
per-user isolation, first-run `/setup`, opt-in `SIGNUP_ENABLED`, the `/me/*` + `/{user}/{slug}` URL
scheme, removal of `ADMIN_PASSWORD`/embedded user in favour of DB users + argon2id, and the new
`SIGNUP_ENABLED` env var. Docker must be running for Dev Services (Postgres) during every `./mvnw test`.
