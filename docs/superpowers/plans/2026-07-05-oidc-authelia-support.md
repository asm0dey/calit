# OIDC (Authelia) Single Sign-On Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users sign in to calit through an OIDC provider (e.g. Authelia), auto-provisioning/linking a local `AppUser` and optionally granting admin from an OIDC group.

**Architecture:** Reuse the existing **external-IdP bridge** already shipped for Google. `quarkus-oidc` (web-app code flow) does the OAuth/JWKS/PKCE work, but it is scoped to a **single login path** (`/api/oidc/login`) via a per-path `auth-mechanism=code` permission. The rest of the app stays on the existing form-auth cookie session — the whole tenancy layer (`MeOwnerFilter`, `CurrentOwner`, principal=username) is untouched. On the callback, our resource reads the verified id_token, resolves/provisions the `AppUser`, mints a single-use login ticket, and auto-POSTs it to `/j_security_check` (which mints the `quarkus-credential` cookie) — identical to `GoogleLoginResource`. The OIDC session cookie is expired immediately so each SSO click re-checks the provider (needed for admin revocation).

**Tech Stack:** Quarkus 3.36 `quarkus-oidc`, Java 25, Panache, Qute, Flyway, JUnit 5 + RestAssured.

## Global Constraints

- **Java 25 / Quarkus 3.36.** Build JDK must be 26 (`export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` before `./mvnw`, or `mvn` per the build-jdk memory).
- **Docker MUST be running** for `mvn test` / `quarkus:dev` (Dev Services Postgres).
- **Owner scoping:** every tenant row carries `owner_id`; never cross-read. This feature touches only `app_user` + `owner_settings` lookups already used by the Google flow, so no new owner-scoped queries — keep it that way.
- **i18n mandatory:** every new user-facing string gets an English `@Message` default **and** a `de` **and** `he` value in `src/main/resources/messages/msg_{de,he}.properties`, keyed by method name. No leaning on the English fallback.
- **Flyway:** never edit an applied migration. Latest is `V22`; the new one is `V23`.
- **Formatting:** Palantir via `mvn spotless:apply` (pre-commit hook does staged files). `mvn verify` fails on unformatted code.
- **Reuse over new:** the Google flow is the template. Reuse `LoginTicketService`, the `bridge.html` template, and the `OwnerSettings.findOwnerIdsByEmail` link logic verbatim in shape.
- **Degraded mode:** with `OIDC_ENABLED` unset the app boots normally, the SSO button is hidden, and `/api/oidc/login` is not an active auth path. quarkus-oidc must NOT attempt discovery at boot when disabled.

---

## File Structure

New package `site.asm0dey.calit.oidc/` (parallel to `google/`):

- `oidc/OidcIdentity.java` — record `(String sub, String email, boolean emailVerified, Set<String> groups)`.
- `oidc/OidcSignInException.java` — `RuntimeException` + `Reason { SIGNUP_DISABLED, AMBIGUOUS_EMAIL }`.
- `oidc/OidcSignInService.java` — resolve/link/provision + admin mapping (the money logic).
- `oidc/OidcLoginResource.java` — `@Path("/api/oidc/login")` GET: read id_token → bridge to form auth.

Modified:

- `user/AppUser.java` — add `oidcSub`, `oidcAdmin` fields, `createOidcUser` factory, `findByOidcSub`, `applyOidcAdmin`.
- `db/migration/V23__oidc_login.sql` — add the two columns.
- `application.properties` — quarkus-oidc config + scoped permission + `%test` off.
- `pom.xml` — `quarkus-oidc` (main), `quarkus-test-oidc-server` (test, only if Task 8 done).
- `web/LoginResource.java` + `templates/LoginResource/login.html` — `oidcEnabled` + SSO button + notices.
- `i18n/AppMessages.java` + `messages/msg_{de,he}.properties` — button label + 3 notices.

Reused unchanged: `user/LoginTicketService.java`, `templates/GoogleLoginResource/bridge.html`, `domain/OwnerSettings.java`.

---

### Task 1: `AppUser` OIDC columns + migration

**Files:**
- Create: `src/main/resources/db/migration/V23__oidc_login.sql`
- Modify: `src/main/java/site/asm0dey/calit/user/AppUser.java`
- Test: `src/test/java/site/asm0dey/calit/user/AppUserOidcTest.java`

**Interfaces:**
- Produces:
  - `AppUser.createOidcUser(String username, String oidcSub, boolean oidcAdmin) -> AppUser`
  - `AppUser.findByOidcSub(String oidcSub) -> AppUser` (null if arg null or no row)
  - `void AppUser.applyOidcAdmin(boolean oidcGrantsAdmin)` — sets `oidcAdmin`, recomputes `roles = rolesFor(isAdmin || oidcAdmin)`. Local `isAdmin` is never changed.
  - fields `public String oidcSub;` `public boolean oidcAdmin;`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/user/AppUserOidcTest.java`:

```java
package site.asm0dey.calit.user;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AppUserOidcTest {

    @Test
    @Transactional
    void createOidcUser_setsPasswordlessNonLocalAdmin_rolesTrackOidcAdmin() {
        AppUser u = AppUser.createOidcUser("alice", "sub-123", true);
        assertNull(u.passwordHash);
        assertEquals("sub-123", u.oidcSub);
        assertFalse(u.isAdmin, "OIDC never sets the local admin bit");
        assertTrue(u.oidcAdmin);
        assertEquals("user,admin", u.roles, "effective roles include admin when oidcAdmin");
    }

    @Test
    @Transactional
    void applyOidcAdmin_revokesOidcAdmin_butKeepsLocalAdmin() {
        AppUser local = AppUser.create("boss", "hash", true); // local site admin
        local.applyOidcAdmin(false); // OIDC groups say "not admin"
        assertTrue(local.isAdmin, "local admin is sticky");
        assertFalse(local.oidcAdmin);
        assertEquals("user,admin", local.roles, "local admin keeps admin role");

        AppUser granted = AppUser.createOidcUser("temp", "sub-9", true);
        granted.applyOidcAdmin(false); // removed from Authelia admin group
        assertFalse(granted.isAdmin);
        assertFalse(granted.oidcAdmin);
        assertEquals("user", granted.roles, "OIDC-granted admin is revoked");
    }

    @Test
    @Transactional
    void findByOidcSub_roundTrips_andNullSafe() {
        AppUser u = AppUser.createOidcUser("carol", "sub-round", false);
        u.persist();
        assertEquals(u.id, AppUser.findByOidcSub("sub-round").id);
        assertNull(AppUser.findByOidcSub(null));
        assertNull(AppUser.findByOidcSub("no-such-sub"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AppUserOidcTest`
Expected: FAIL — compile error, `createOidcUser` / `findByOidcSub` / `applyOidcAdmin` / `oidcSub` / `oidcAdmin` do not exist.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V23__oidc_login.sql`:

```sql
-- OIDC (e.g. Authelia) single sign-on.
-- oidc_sub: stable id_token "sub" linking this account to the OIDC identity (unique, nullable).
-- oidc_admin: admin granted by an OIDC group; effective admin = is_admin OR oidc_admin.
--             Kept separate from is_admin so a local admin is never demoted by the IdP.
ALTER TABLE app_user ADD COLUMN oidc_sub VARCHAR(255);
ALTER TABLE app_user ADD CONSTRAINT app_user_oidc_sub_key UNIQUE (oidc_sub);
ALTER TABLE app_user ADD COLUMN oidc_admin BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 4: Add the entity fields, factory, finder, and helper**

In `src/main/java/site/asm0dey/calit/user/AppUser.java`, add fields after `googleSub` (after line 31):

```java
    /** Stable OIDC id_token "sub" linking this account to an OIDC (e.g. Authelia) identity, or null. Unique. */
    @Column(name = "oidc_sub", unique = true)
    public String oidcSub;

    /**
     * Admin granted by an OIDC group, recomputed on every OIDC login. Effective admin is
     * {@code isAdmin || oidcAdmin}; kept separate so a local {@code isAdmin} is never demoted by the IdP.
     */
    @Column(name = "oidc_admin", nullable = false)
    public boolean oidcAdmin = false;
```

Add after `createGoogleUser` (after line 85):

```java
    /**
     * Factory for an OIDC-only user: no password, not a local admin, not yet onboarded. {@code oidcAdmin}
     * comes from the IdP groups; roles reflect the effective admin (isAdmin=false || oidcAdmin). The
     * username must already be uniquified by the caller (see Usernames.uniquify); it is normalized here.
     */
    public static AppUser createOidcUser(String username, String oidcSub, boolean oidcAdmin) {
        var u = new AppUser();
        u.username = Usernames.normalize(username);
        u.passwordHash = null;
        u.oidcSub = oidcSub;
        u.isAdmin = false;
        u.oidcAdmin = oidcAdmin;
        u.roles = rolesFor(oidcAdmin); // effective = isAdmin(false) || oidcAdmin
        u.mustChangePassword = false;
        u.settingsComplete = false;
        u.createdAt = Instant.now();
        return u;
    }
```

Add `findByOidcSub` after `findByGoogleSub` (after line 96):

```java
    public static AppUser findByOidcSub(String oidcSub) {
        if (oidcSub == null) {
            return null;
        }
        return find("oidcSub", oidcSub).firstResult();
    }
```

Add `applyOidcAdmin` after `setAdmin` (after line 106):

```java
    /**
     * Recompute effective roles after an OIDC login: the local {@code isAdmin} is sticky (never
     * touched here); {@code oidcAdmin} is (re)set from the IdP groups so admin granted via a group
     * is revoked on the next login once the user leaves that group.
     */
    public void applyOidcAdmin(boolean oidcGrantsAdmin) {
        this.oidcAdmin = oidcGrantsAdmin;
        this.roles = rolesFor(this.isAdmin || this.oidcAdmin);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=AppUserOidcTest`
Expected: PASS (3 tests). Flyway applies V23 against the throwaway Postgres; Hibernate validate-only matches the new columns.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -DspotlessFiles=.*AppUser.*
git add src/main/resources/db/migration/V23__oidc_login.sql \
        src/main/java/site/asm0dey/calit/user/AppUser.java \
        src/test/java/site/asm0dey/calit/user/AppUserOidcTest.java
git commit -m "feat(oidc): app_user oidc_sub/oidc_admin columns + entity helpers"
```

---

### Task 2: `OidcSignInService` — resolve, link, provision, admin mapping

**Files:**
- Create: `src/main/java/site/asm0dey/calit/oidc/OidcIdentity.java`
- Create: `src/main/java/site/asm0dey/calit/oidc/OidcSignInException.java`
- Create: `src/main/java/site/asm0dey/calit/oidc/OidcSignInService.java`
- Test: `src/test/java/site/asm0dey/calit/oidc/OidcSignInServiceTest.java`

**Interfaces:**
- Consumes: `AppUser.findByOidcSub`, `AppUser.createOidcUser`, `AppUser.applyOidcAdmin` (Task 1); `OwnerSettings.findOwnerIdsByEmail(String) -> List<Long>`; `Usernames.fromEmail`, `Usernames.uniquify`.
- Produces:
  - `record OidcIdentity(String sub, String email, boolean emailVerified, Set<String> groups)`
  - `OidcSignInException` with `enum Reason { SIGNUP_DISABLED, AMBIGUOUS_EMAIL }` and `public final Reason reason`
  - `OidcSignInService.resolveOrProvision(OidcIdentity) -> AppUser` (`@Transactional`). Reads config `calit.signup.enabled` and `calit.oidc.admin-group`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/oidc/OidcSignInServiceTest.java`:

```java
package site.asm0dey.calit.oidc;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
@TestProfile(OidcSignInServiceTest.SignupOnAdminGroup.class)
class OidcSignInServiceTest {

    /** Enable signup + define the admin group, so provisioning and admin mapping are exercised. */
    public static class SignupOnAdminGroup implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.signup.enabled", "true", "calit.oidc.admin-group", "calit-admins");
        }
    }

    @Inject
    OidcSignInService service;

    private static OidcIdentity id(String sub, String email, boolean verified, String... groups) {
        return new OidcIdentity(sub, email, verified, Set.of(groups));
    }

    @Test
    @Transactional
    void provisionsNewUser_andGrantsAdminFromGroup() {
        AppUser u = service.resolveOrProvision(id("sub-new", "new@example.com", true, "calit-admins"));
        assertNotNull(u.id);
        assertEquals("sub-new", u.oidcSub);
        assertNull(u.passwordHash);
        assertFalse(u.isAdmin);
        assertTrue(u.oidcAdmin);
        assertEquals("user,admin", u.roles);
        assertEquals("new@example.com", OwnerSettings.forOwner(u.id).ownerEmail);
    }

    @Test
    @Transactional
    void provisionsNewUser_withoutAdminGroup_isPlainUser() {
        AppUser u = service.resolveOrProvision(id("sub-plain", "plain@example.com", true, "some-other-group"));
        assertFalse(u.oidcAdmin);
        assertEquals("user", u.roles);
    }

    @Test
    @Transactional
    void secondLoginRevokesOidcAdmin_whenGroupRemoved() {
        service.resolveOrProvision(id("sub-rev", "rev@example.com", true, "calit-admins"));
        AppUser after = service.resolveOrProvision(id("sub-rev", "rev@example.com", true)); // no groups now
        assertFalse(after.oidcAdmin);
        assertEquals("user", after.roles);
    }

    @Test
    @Transactional
    void linksByVerifiedEmail_toExistingLocalAdmin_withoutDemotingIt() {
        // Admin user id 1 always exists (DatabaseResetCallback). Give it a settings email to match on.
        AppUser admin = AppUser.findById(1L);
        OwnerSettings s = OwnerSettings.forOwner(1L);
        s.ownerEmail = "root@example.com";
        // OIDC login with matching verified email, but NOT in the admin group:
        AppUser linked = service.resolveOrProvision(id("sub-root", "root@example.com", true));
        assertEquals(admin.id, linked.id, "linked to the existing account by email");
        assertEquals("sub-root", linked.oidcSub);
        assertTrue(linked.isAdmin, "local admin is not demoted by OIDC");
        assertEquals("user,admin", linked.roles);
    }

    @Test
    @Transactional
    void unverifiedEmail_doesNotLink_provisionsFresh() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        s.ownerEmail = "same@example.com";
        AppUser u = service.resolveOrProvision(id("sub-unv", "same@example.com", false));
        assertNotEquals(1L, u.id, "unverified email must not auto-link");
    }

    @Test
    @Transactional
    void ambiguousEmail_isRejected() {
        AppUser a = AppUser.create("dup-a", "h", false);
        a.persist();
        AppUser b = AppUser.create("dup-b", "h", false);
        b.persist();
        for (AppUser x : new AppUser[] {a, b}) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = x.id;
            s.ownerName = "";
            s.ownerEmail = "dup@example.com";
            s.timezone = "UTC";
            s.persist();
        }
        var ex = assertThrows(
                OidcSignInException.class, () -> service.resolveOrProvision(id("sub-dup", "dup@example.com", true)));
        assertEquals(OidcSignInException.Reason.AMBIGUOUS_EMAIL, ex.reason);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OidcSignInServiceTest`
Expected: FAIL — compile error, `OidcIdentity` / `OidcSignInException` / `OidcSignInService` do not exist.

- [ ] **Step 3: Write `OidcIdentity`**

Create `src/main/java/site/asm0dey/calit/oidc/OidcIdentity.java`:

```java
package site.asm0dey.calit.oidc;

import java.util.Set;

/**
 * The claims we read from a verified OIDC id_token. {@code groups} is the id_token "groups" claim
 * (empty if the provider sends none). Signature/issuer/audience/nonce were already verified by
 * quarkus-oidc before this record is built.
 */
public record OidcIdentity(String sub, String email, boolean emailVerified, Set<String> groups) {}
```

- [ ] **Step 4: Write `OidcSignInException`**

Create `src/main/java/site/asm0dey/calit/oidc/OidcSignInException.java`:

```java
package site.asm0dey.calit.oidc;

/** An SSO login that cannot complete for a non-technical reason the user must be told about. */
public class OidcSignInException extends RuntimeException {

    public enum Reason {
        SIGNUP_DISABLED,
        AMBIGUOUS_EMAIL
    }

    public final Reason reason;

    public OidcSignInException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
```

- [ ] **Step 5: Write `OidcSignInService`**

Create `src/main/java/site/asm0dey/calit/oidc/OidcSignInService.java`:

```java
package site.asm0dey.calit.oidc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.Usernames;

/**
 * Maps a verified OIDC (e.g. Authelia) identity to the {@link AppUser} that should be logged in:
 *   1. known oidc_sub          -> that user;
 *   2. unknown sub but the id_token's VERIFIED email matches exactly one existing account
 *      -> link the sub to that account;
 *   3. otherwise provision a new passwordless, not-yet-onboarded account, but only when
 *      SIGNUP_ENABLED=true; else reject with SIGNUP_DISABLED.
 * On every path, {@link AppUser#applyOidcAdmin(boolean)} recomputes admin from the IdP groups
 * (grant AND revoke), while the local {@code isAdmin} bit is left untouched (never demoted).
 * Ambiguous email (more than one same-email account) is rejected rather than guessed.
 *
 * @implNote Same caveat as the Google flow: auto-link trusts the IdP's verified email against the
 *           account's OwnerSettings email, which the app itself does not verify (free-text from the
 *           settings wizard). Acceptable pre-public.
 */
@ApplicationScoped
public class OidcSignInService {

    @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false")
    boolean signupEnabled;

    /** OIDC group whose members get admin, or blank to disable OIDC-driven admin entirely. */
    @ConfigProperty(name = "calit.oidc.admin-group", defaultValue = "")
    String adminGroup;

    @Transactional
    public AppUser resolveOrProvision(OidcIdentity identity) {
        boolean grantsAdmin = grantsAdmin(identity.groups());

        AppUser bySub = AppUser.findByOidcSub(identity.sub());
        if (bySub != null) {
            bySub.applyOidcAdmin(grantsAdmin); // managed entity -> dirty-checked in this tx
            return bySub;
        }

        if (identity.emailVerified() && identity.email() != null) {
            List<Long> owners = OwnerSettings.findOwnerIdsByEmail(identity.email());
            if (owners.size() == 1) {
                AppUser linked = AppUser.findById(owners.getFirst());
                linked.oidcSub = identity.sub();
                linked.applyOidcAdmin(grantsAdmin);
                return linked;
            }
            if (owners.size() > 1) {
                throw new OidcSignInException(OidcSignInException.Reason.AMBIGUOUS_EMAIL);
            }
        }

        if (!signupEnabled) {
            throw new OidcSignInException(OidcSignInException.Reason.SIGNUP_DISABLED);
        }
        return provision(identity, grantsAdmin);
    }

    private boolean grantsAdmin(Set<String> groups) {
        return adminGroup != null && !adminGroup.isBlank() && groups != null && groups.contains(adminGroup);
    }

    private AppUser provision(OidcIdentity identity, boolean grantsAdmin) {
        String username = Usernames.uniquify(Usernames.fromEmail(identity.email()), AppUser::usernameTaken);
        AppUser u = AppUser.createOidcUser(username, identity.sub(), grantsAdmin);
        u.persist();

        // Pre-create the settings row so the first-login wizard (/me/setup) can pre-fill the email.
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = "";
        s.ownerEmail = identity.email() == null ? "" : identity.email();
        s.timezone = "UTC";
        s.persist();
        return u;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=OidcSignInServiceTest`
Expected: PASS (6 tests). Note `@TestProfile` triggers an in-JVM Quarkus restart (per test-infra memory) — this is expected and slow-ish.

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/oidc/OidcIdentity.java \
        src/main/java/site/asm0dey/calit/oidc/OidcSignInException.java \
        src/main/java/site/asm0dey/calit/oidc/OidcSignInService.java \
        src/test/java/site/asm0dey/calit/oidc/OidcSignInServiceTest.java
git commit -m "feat(oidc): OidcSignInService — resolve/link/provision + group-driven admin"
```

---

### Task 3: Add `quarkus-oidc` + config (boots disabled by default)

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Produces: config keys `calit.oidc.enabled`, `calit.oidc.admin-group`, `quarkus.oidc.*`, and the `sso` HTTP permission scoping `/api/oidc/login` to the OIDC code flow. Consumed by Tasks 4 & 5.

- [ ] **Step 1: Add the dependency**

In `pom.xml`, add next to the other `io.quarkus` extensions (near line 92, the security block):

```xml
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-oidc</artifactId>
    </dependency>
```

- [ ] **Step 2: Verify the app still builds and boots disabled**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS. (No config yet; `quarkus-oidc` on the classpath must not fail the build.)

- [ ] **Step 3: Add the OIDC config block**

In `src/main/resources/application.properties`, append after the existing `quarkus.http.auth.*` block (after the `google-login` permission, ~line 140):

```properties
# ---- OIDC / SSO (e.g. Authelia) — optional; entirely off unless OIDC_ENABLED=true ----
# When disabled, quarkus-oidc does NO discovery at boot and /api/oidc/login is not an active auth path.
calit.oidc.enabled=${OIDC_ENABLED:false}
quarkus.oidc.tenant-enabled=${OIDC_ENABLED:false}
# Authelia base issuer URL; endpoints are discovered from {issuer}/.well-known/openid-configuration.
quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL:}
quarkus.oidc.client-id=${OIDC_CLIENT_ID:}
quarkus.oidc.credentials.secret=${OIDC_CLIENT_SECRET:}
quarkus.oidc.application-type=web-app
# openid is implicit; request email (+verified) and the groups claim used for admin mapping.
quarkus.oidc.authentication.scopes=email,profile,groups
# OIDC group whose members get calit admin; blank = no OIDC-driven admin. Local admin is never demoted.
calit.oidc.admin-group=${OIDC_ADMIN_GROUP:}
# Scope the OIDC authorization-code flow to the SSO login path ONLY; the rest of the app stays on form auth.
# quarkus-oidc consumes the code on this same path (redirect_uri = {APP_BASE_URL}/api/oidc/login),
# then OidcLoginResource bridges the verified identity into the form-auth cookie.
quarkus.http.auth.permission.sso.paths=/api/oidc/login
quarkus.http.auth.permission.sso.policy=authenticated
quarkus.http.auth.permission.sso.auth-mechanism=code

# Keep SSO off in tests unless a @TestProfile turns it on (mirrors Google being disabled in %test).
%test.calit.oidc.enabled=false
%test.quarkus.oidc.tenant-enabled=false
```

- [ ] **Step 4: Verify dev boot with OIDC unset does not fail on discovery**

Run: `mvn -q -DskipTests package` again (full config present).
Expected: BUILD SUCCESS. (Runtime: with `OIDC_ENABLED` unset, `quarkus.oidc.tenant-enabled=false` means no discovery attempt — unlike the Google mapping, no dummy values are needed to boot `quarkus:dev`.)

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "feat(oidc): add quarkus-oidc, scope code flow to /api/oidc/login, off by default"
```

---

### Task 4: `OidcLoginResource` — bridge the OIDC identity into form auth

**Files:**
- Create: `src/main/java/site/asm0dey/calit/oidc/OidcLoginResource.java`
- Reuse (no change): `templates/GoogleLoginResource/bridge.html`, `LoginTicketService`

**Interfaces:**
- Consumes: `@IdToken JsonWebToken` (quarkus-oidc), `OidcSignInService.resolveOrProvision` (Task 2), `LoginTicketService.issue(Long, Instant)`, `GoogleLoginResource.Templates.bridge(String, String)`.
- Produces: `GET /api/oidc/login` — reachable only when OIDC-authenticated (the `sso` permission redirects an unauthenticated hit to the provider). Returns the auto-submit bridge page and expires the OIDC session cookie. On `OidcSignInException`, 302 to `/login?notice=sso_*`.

**Note on session cookie:** the OIDC session cookie is expired on every response so each SSO click re-authenticates at the provider — this is what makes admin/group revocation take effect on the next login. The default cookie is `q_session` (Authelia id_tokens are small, not chunked).

- [ ] **Step 1: Write the resource**

Create `src/main/java/site/asm0dey/calit/oidc/OidcLoginResource.java`:

```java
package site.asm0dey.calit.oidc;

import io.quarkus.oidc.IdToken;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import site.asm0dey.calit.google.GoogleLoginResource;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.LoginTicketService;

/**
 * The "Sign in with SSO" (OIDC / Authelia) landing. quarkus-oidc protects this path with the
 * authorization-code flow: an unauthenticated hit is redirected to the provider, which redirects
 * back here with a session. This resource then reads the verified id_token, resolves/provisions the
 * {@link AppUser}, mints a single-use login ticket, and returns an auto-submitting form that POSTs
 * the ticket to /j_security_check (which mints the form-auth session cookie) — the same bridge the
 * Google flow uses. The OIDC session cookie is expired so every SSO click re-checks the provider.
 */
@Path("/api/oidc/login")
public class OidcLoginResource {

    // Default quarkus-oidc session cookie. ponytail: single cookie assumed; add q_session_1.. handling
    // only if OIDC token chunking is ever enabled (Authelia id_tokens are small).
    private static final String OIDC_SESSION_COOKIE = "q_session";

    @Inject
    @IdToken
    JsonWebToken idToken;

    private final OidcSignInService signInService;
    private final LoginTicketService loginTickets;
    private final Clock clock;

    @Inject
    public OidcLoginResource(OidcSignInService signInService, LoginTicketService loginTickets, Clock clock) {
        this.signInService = signInService;
        this.loginTickets = loginTickets;
        this.clock = clock;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response login() {
        var now = clock.instant();
        var identity = new OidcIdentity(
                idToken.getSubject(),
                idToken.getClaim("email"),
                Boolean.TRUE.equals(idToken.getClaim("email_verified")),
                groups());

        AppUser user;
        try {
            user = signInService.resolveOrProvision(identity);
        } catch (OidcSignInException e) {
            return dropOidcSession(redirectToLogin(
                            switch (e.reason) {
                                case SIGNUP_DISABLED -> "sso_signup_disabled";
                                case AMBIGUOUS_EMAIL -> "sso_ambiguous";
                            }))
                    .build();
        }

        String token = loginTickets.issue(user.id, now);
        // ponytail: reuse the Google bridge template — it is IdP-agnostic (username + one-time ticket only).
        // The page carries a single-use login token in its body — never cache it.
        return dropOidcSession(Response.ok(GoogleLoginResource.Templates.bridge(user.username, token))
                        .header("Cache-Control", "no-store"))
                .build();
    }

    /** The id_token "groups" claim as a Set, or empty when the provider sends none. */
    private Set<String> groups() {
        Set<String> g = idToken.getGroups();
        return g == null ? Set.of() : g;
    }

    private static Response.ResponseBuilder dropOidcSession(Response.ResponseBuilder b) {
        // Expire the OIDC session so the next SSO click re-authenticates at the provider —
        // required for group/admin changes to take effect on next login.
        return b.cookie(new NewCookie.Builder(OIDC_SESSION_COOKIE)
                .path("/")
                .maxAge(0)
                .build());
    }

    private static Response.ResponseBuilder redirectToLogin(String notice) {
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/login?notice=" + java.net.URLEncoder.encode(notice, StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. (`GoogleLoginResource.Templates.bridge` is a `public static` method, so the cross-package reuse compiles; `io.quarkus.oidc.IdToken` resolves from the Task 3 dependency.)

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -DspotlessFiles=.*OidcLoginResource.*
git add src/main/java/site/asm0dey/calit/oidc/OidcLoginResource.java
git commit -m "feat(oidc): OidcLoginResource bridges verified OIDC identity into form-auth session"
```

---

### Task 5: Login page "Sign in with SSO" button + i18n

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/LoginResource.java`
- Modify: `src/main/resources/templates/LoginResource/login.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Modify: `src/main/resources/messages/msg_de.properties`
- Modify: `src/main/resources/messages/msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/oidc/OidcLoginPageTest.java`

**Interfaces:**
- Consumes: `calit.oidc.enabled` config (Task 3).
- Produces: `Templates.login(String title, boolean error, boolean googleEnabled, boolean oidcEnabled, String notice)`; new messages `auth_login_sso_btn`, `auth_login_notice_sso_signup_disabled`, `auth_login_notice_sso_ambiguous`, `auth_login_notice_sso_generic`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/oidc/OidcLoginPageTest.java`:

```java
package site.asm0dey.calit.oidc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OidcLoginPageTest {

    // Default test profile has calit.oidc.enabled=false, so the SSO button must NOT render.
    @Test
    void loginPage_hidesSsoButton_whenDisabled() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(not(containsString("/api/oidc/login")));
    }

    // With OIDC off, the code mechanism is inactive: a direct hit is unauthorized (no redirect to a provider).
    @Test
    void ssoLoginPath_isUnauthorized_whenDisabled() {
        given().redirects()
                .follow(false)
                .when()
                .get("/api/oidc/login")
                .then()
                .statusCode(401);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OidcLoginPageTest`
Expected: the first test may already pass (button absent), but compile/run must be green first. If `/api/oidc/login` returns something other than 401 (e.g. 404 because no route yet — Task 4 added it, so expect 401 already). Primary purpose: lock behavior before adding the button. Run and note actual results; proceed to add the button.

- [ ] **Step 3: Add the messages to `AppMessages.java`**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, add after `auth_login_notice_google_generic()` (after line 826):

```java
    @Message("Sign in with SSO")
    String auth_login_sso_btn();

    @Message("Single sign-on is not enabled for new accounts.")
    String auth_login_notice_sso_signup_disabled();

    @Message("This email matches more than one account; please sign in with your password instead.")
    String auth_login_notice_sso_ambiguous();

    @Message("Single sign-on could not be completed. Please try again.")
    String auth_login_notice_sso_generic();
```

- [ ] **Step 4: Add German translations**

In `src/main/resources/messages/msg_de.properties`, add after line 249 (`auth_login_notice_google_generic=...`):

```properties
auth_login_sso_btn=Mit SSO anmelden
auth_login_notice_sso_signup_disabled=Single Sign-on ist für neue Konten nicht aktiviert.
auth_login_notice_sso_ambiguous=Diese E-Mail-Adresse ist mehreren Konten zugeordnet; bitte melden Sie sich stattdessen mit Ihrem Passwort an.
auth_login_notice_sso_generic=Single Sign-on konnte nicht abgeschlossen werden. Bitte versuchen Sie es erneut.
```

- [ ] **Step 5: Add Hebrew translations**

In `src/main/resources/messages/msg_he.properties`, add after line 249 (`auth_login_notice_google_generic=...`):

```properties
auth_login_sso_btn=התחברות עם SSO
auth_login_notice_sso_signup_disabled=כניסה יחידה (SSO) אינה מופעלת עבור חשבונות חדשים.
auth_login_notice_sso_ambiguous=כתובת אימייל זו משויכת ליותר מחשבון אחד; אנא התחבר עם הסיסמה שלך במקום זאת.
auth_login_notice_sso_generic=לא ניתן היה להשלים את הכניסה היחידה (SSO). אנא נסה שוב.
```

- [ ] **Step 6: Thread `oidcEnabled` through `LoginResource`**

In `src/main/java/site/asm0dey/calit/web/LoginResource.java`:

Change the template signature (line 23):

```java
        public static native TemplateInstance login(
                String title, boolean error, boolean googleEnabled, boolean oidcEnabled, String notice);
```

Add a config field after `googleClientId` (after line 36):

```java
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "calit.oidc.enabled", defaultValue = "false")
    boolean oidcEnabled;
```

Update the `login` return (lines 46-49) to pass `oidcEnabled`:

```java
        var googleEnabled = googleClientId != null && !googleClientId.isBlank();
        AppMessages m = messages.forLocale(activeLocale.current());
        return Response.ok(
                        Templates.login(m.auth_login_title(), error, googleEnabled, oidcEnabled, noticeMessage(m, notice)))
                .build();
```

Add SSO notice cases to `noticeMessage` (inside the switch, after the `"google"` case, line 60):

```java
            case "sso_signup_disabled" -> m.auth_login_notice_sso_signup_disabled();
            case "sso_ambiguous" -> m.auth_login_notice_sso_ambiguous();
            case "sso" -> m.auth_login_notice_sso_generic();
```

- [ ] **Step 7: Add the button to `login.html`**

In `src/main/resources/templates/LoginResource/login.html`:

Add the param declaration after line 3 (`{@java.lang.Boolean googleEnabled}`):

```html
{@java.lang.Boolean oidcEnabled}
```

Replace the Google block (lines 25-28) so the divider shows if EITHER provider is enabled and both buttons can appear:

```html
      {#if googleEnabled || oidcEnabled}
        <div class="divider text-base-content/50">{msg:auth_login_or_divider}</div>
      {/if}
      {#if googleEnabled}
        <a href="/api/google/login" class="btn btn-outline btn-block">{msg:auth_login_google_btn}</a>
      {/if}
      {#if oidcEnabled}
        <a href="/api/oidc/login" class="btn btn-outline btn-block mt-2">{msg:auth_login_sso_btn}</a>
      {/if}
```

- [ ] **Step 8: Run the tests**

Run: `mvn test -Dtest=OidcLoginPageTest`
Expected: PASS (2 tests) — SSO button still hidden (disabled in %test), `/api/oidc/login` → 401.

Run a broader smoke to catch template/param mismatches:

Run: `mvn test -Dtest=LoginResource*,OidcLoginPageTest`
Expected: PASS (no Qute "missing parameter" errors from the new `oidcEnabled` param).

- [ ] **Step 9: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/LoginResource.java \
        src/main/resources/templates/LoginResource/login.html \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/oidc/OidcLoginPageTest.java
git commit -m "feat(oidc): SSO login button + i18n (de/he), gated by calit.oidc.enabled"
```

---

### Task 6: End-to-end SSO flow test with a mock OIDC provider (optional but recommended)

This proves the bridge wires end-to-end (redirect → code exchange → id_token → ticket → form cookie) using an in-JVM mock provider. quarkus-oidc's crypto is upstream-tested; this test only covers OUR wiring. Skip only if time-boxed — Tasks 1-5 already cover all custom logic.

**Files:**
- Modify: `pom.xml` (test dependency)
- Test: `src/test/java/site/asm0dey/calit/oidc/OidcBridgeFlowTest.java`

**Interfaces:**
- Consumes: `io.quarkus:quarkus-test-oidc-server` (`OidcWiremockTestResource`), which stands up a Wiremock OIDC provider and injects tokens.

- [ ] **Step 1: Add the test dependency**

In `pom.xml`, in the test-scoped block (near `quarkus-test-security`, line 105):

```xml
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-test-oidc-server</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Write the flow test**

Create `src/test/java/site/asm0dey/calit/oidc/OidcBridgeFlowTest.java`:

```java
package site.asm0dey.calit.oidc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.RestAssured;
import io.restassured.filter.cookie.CookieFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Full SSO bridge: hit /api/oidc/login unauthenticated -> mock provider round-trip ->
 * OidcLoginResource emits the auto-submit bridge form POSTing a ticket to /j_security_check.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(OidcBridgeFlowTest.OidcOn.class)
class OidcBridgeFlowTest {

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // OidcWiremockTestResource sets quarkus.oidc.auth-server-url/client-id; we enable our gates.
            return Map.of(
                    "calit.oidc.enabled", "true",
                    "quarkus.oidc.tenant-enabled", "true",
                    "calit.signup.enabled", "true",
                    "quarkus.oidc.client-id", "quarkus-web-app",
                    "quarkus.oidc.credentials.secret", "secret",
                    "quarkus.oidc.application-type", "web-app");
        }
    }

    @Test
    void ssoLogin_bridgesToJSecurityCheck() {
        // Wiremock provider auto-authenticates; follow the code flow back to /api/oidc/login,
        // which returns the bridge page (auto-submit form to /j_security_check).
        CookieFilter cookies = new CookieFilter();
        given().filter(cookies)
                .redirects()
                .follow(true)
                .when()
                .get("/api/oidc/login")
                .then()
                .statusCode(200)
                .body(containsString("action=\"/j_security_check\""))
                .body(containsString("name=\"j_password\"")); // the single-use ticket
    }
}
```

Note: `OidcWiremockTestResource` issues an id_token with `sub`, `preferred_username`, and (by default) a `groups` claim. If the mock's default token lacks `email`, the flow provisions by sub with an empty settings email — still a valid bridge. The assertion only checks the bridge page renders, so it is robust to the mock's exact claim set. If the mock id_token has no `email` and you want the link path exercised, consult the `quarkus-test-oidc-server` docs for `OidcWiremockTestResource` token customization — but that is beyond this task's scope.

- [ ] **Step 3: Run the flow test**

Run: `mvn test -Dtest=OidcBridgeFlowTest`
Expected: PASS (1 test). This uses a `@TestProfile` (in-JVM restart) + Wiremock resource; slower than plain tests.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/test/java/site/asm0dey/calit/oidc/OidcBridgeFlowTest.java
git commit -m "test(oidc): end-to-end SSO bridge flow via mock OIDC provider"
```

---

### Task 7: Full suite + docs + release notes

**Files:**
- Modify: `.env.example`
- Modify: `README.md`
- Docs (on `docs-site` branch): configuration + reverse-proxy/SSO page; changelog on next release.

**Interfaces:** none (documentation + config reference).

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: All tests pass (existing suite + the new OIDC tests). If a `@TestProfile`/Dev Services timeout appears, re-run — this is the shared-fork behavior noted in the test-infra memory, not an OIDC bug.

- [ ] **Step 2: Add env vars to `.env.example`**

Append to `.env.example`:

```bash
# ---- OIDC / SSO (e.g. Authelia) — optional ----
# Set OIDC_ENABLED=true and configure the rest to enable "Sign in with SSO".
OIDC_ENABLED=false
# Base issuer URL; endpoints discovered from ${OIDC_ISSUER_URL}/.well-known/openid-configuration
OIDC_ISSUER_URL=https://auth.example.com
OIDC_CLIENT_ID=calit
OIDC_CLIENT_SECRET=change-me
# OIDC group whose members get calit admin; leave blank for no OIDC-driven admin.
# A locally-granted admin is never demoted by the IdP.
OIDC_ADMIN_GROUP=calit-admins
```

- [ ] **Step 3: Document in `README.md`**

Add an "OIDC / SSO (Authelia)" subsection to the configuration reference in `README.md` covering: the five `OIDC_*` env vars; that the provider's client redirect URI must be `${APP_BASE_URL}/api/oidc/login`; that `email` + `groups` scopes/claims are required (email for account linking, groups for admin mapping); auto-provisioning is gated by `SIGNUP_ENABLED`; and that OIDC only ever grants admin via `OIDC_ADMIN_GROUP`, never demotes a local admin.

- [ ] **Step 4: Update the docs site (docs-site branch) — MUST include TWO worked examples**

Per CLAUDE.md, user-facing changes land on `docs-site` in the same effort. On the `docs-site` branch, add an "OIDC / SSO" page (or section in the configuration/reverse-proxy docs). It **must contain two complete, copy-pasteable configuration examples side by side**:

**(a) Generic OIDC provider example** — the calit side, provider-neutral (works for Keycloak, Auth0, Zitadel, Authentik, etc.). Show the full `OIDC_*` env block plus the three facts a user must give ANY provider:
- redirect URI: `${APP_BASE_URL}/api/oidc/login`
- scopes: `openid email profile groups`
- claims calit reads: `sub`, `email`, `email_verified`, `groups`

```bash
# calit — generic OIDC client config (any compliant provider)
OIDC_ENABLED=true
OIDC_ISSUER_URL=https://idp.example.com          # base issuer; calit discovers {issuer}/.well-known/openid-configuration
OIDC_CLIENT_ID=calit
OIDC_CLIENT_SECRET=<plaintext secret from the provider>
OIDC_ADMIN_GROUP=calit-admins                     # group whose members get admin; blank = no OIDC admin
# Register in your provider:
#   redirect URI : https://cal.example.com/api/oidc/login
#   scopes       : openid email profile groups
#   client auth  : client_secret_post (or client_secret_basic)
```

**(b) Authelia example** — the provider side, concrete. Show the Authelia `configuration.yml` client block AND the matching calit env, and note the secret hash/plaintext split:

```yaml
# Authelia configuration.yml — identity_providers.oidc.clients
identity_providers:
  oidc:
    clients:
      - client_id: calit
        client_name: calit
        # Generate: authelia crypto hash generate pbkdf2 --password '<plaintext>'
        # Store the HASH here; put the PLAINTEXT in calit's OIDC_CLIENT_SECRET.
        client_secret: '$pbkdf2-sha512$310000$...'
        public: false
        authorization_policy: two_factor
        redirect_uris:
          - https://cal.example.com/api/oidc/login
        scopes: [openid, email, profile, groups]
        token_endpoint_auth_method: client_secret_post
```

```yaml
# Authelia users_database.yml — group membership drives calit admin
users:
  pavel:
    disabled: false
    groups:
      - calit-admins        # matches OIDC_ADMIN_GROUP -> admin in calit
```

```bash
# calit .env — matching the Authelia client above
OIDC_ENABLED=true
OIDC_ISSUER_URL=https://auth.example.com
OIDC_CLIENT_ID=calit
OIDC_CLIENT_SECRET=<the plaintext you hashed for Authelia>
OIDC_ADMIN_GROUP=calit-admins
```

Both examples must also state the shared behavior notes: account linking needs the calit settings email to match the provider's verified email; new-account creation is gated by `SIGNUP_ENABLED`; OIDC only ever grants admin via `OIDC_ADMIN_GROUP` and never demotes a local admin; the Authelia secret is stored as a hash on the provider but plaintext in calit (mismatch → silent token-exchange failure back to `/login`).

On the next release, add a changelog entry under `docs-site/src/content/docs/releases/changelog.md` and bump the README image tag.

- [ ] **Step 5: Commit (main branch)**

```bash
git add .env.example README.md
git commit -m "docs(oidc): document OIDC/SSO env vars and provider setup"
```

---

## Self-Review

**Spec coverage:**
- OIDC login via Authelia → Tasks 3-4 (quarkus-oidc code flow scoped to `/api/oidc/login`, bridge to form auth). ✓
- Link-by-email + auto-provision gated by SIGNUP_ENABLED → Task 2 (`resolveOrProvision`). ✓
- quarkus-oidc extension (chosen over the manual bridge) → Task 3. ✓
- OIDC session as login-only, form cookie stays the session → Tasks 3 (per-path `auth-mechanism=code`) + 4 (`dropOidcSession`, bridge to `/j_security_check`). ✓
- Take role from Authelia, never demote local admin → Task 1 (`applyOidcAdmin`: `isAdmin || oidcAdmin`) + Task 2 tests (`linksByVerifiedEmail_toExistingLocalAdmin_withoutDemotingIt`, `secondLoginRevokesOidcAdmin_whenGroupRemoved`). ✓
- Revoke OIDC-granted admin on re-login → Task 1 `applyOidcAdmin(false)` + Task 4 `dropOidcSession` (forces provider re-check). ✓
- Degraded/optional (boots unconfigured) → Task 3 (`tenant-enabled=${OIDC_ENABLED:false}`, `%test` off) + Task 5 test (`ssoLoginPath_isUnauthorized_whenDisabled`). ✓
- i18n de+he → Task 5 steps 4-5. ✓
- Docs + env reference → Task 7. ✓
- Docs must ship TWO worked config examples (generic OIDC client + concrete Authelia provider) → Task 7 step 4. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; every test shows assertions; commands have expected output.

**Type consistency:** `OidcIdentity(sub, email, emailVerified, groups)` — same shape produced (Task 2) and consumed (Task 4). `resolveOrProvision(OidcIdentity) -> AppUser` consistent across Tasks 2/4/tests. `applyOidcAdmin(boolean)`, `createOidcUser(String,String,boolean)`, `findByOidcSub(String)` identical in Task 1 definition and Task 2 usage. `Templates.login(title, error, googleEnabled, oidcEnabled, notice)` — new 5-arg signature updated in both `LoginResource.java` and referenced by `login.html` params (Task 5). Notice codes `sso_signup_disabled` / `sso_ambiguous` produced in Task 4 and mapped in Task 5 `noticeMessage`. ✓

**Known simplifications (ponytail):**
- Single OIDC provider only (no multi-tenant OIDC) — YAGNI; Authelia is one issuer. Add a provider column if a second IdP is ever needed.
- Reuses the Google `bridge.html` template cross-package — it is IdP-agnostic. Extract to a neutral location only if the coupling ever bites.
- `q_session` assumed single (un-chunked) cookie — correct for small Authelia id_tokens.
