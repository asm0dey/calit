# Multi-User Support — Phase 1: Auth Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single embedded-properties admin user with a DB-backed `app_user` table and argon2id password hashing, add first-run `/setup` bootstrap, and keep the existing form-login experience working — while staying single-user (no owner scoping yet).

**Architecture:** A new `com.calit.user` package holds the `AppUser` `@UserDefinition` entity, an argon2id `PasswordHasher` (Bouncy Castle), a `Usernames` validation util, a Quarkus `SecurityIdentityAugmentor` that rejects disabled users, and a `SetupResource` first-run flow gated by a request filter. Authentication wires through `quarkus-security-jpa`; password verification is bridged either by a custom Elytron `PasswordProvider` (primary) or a custom `IdentityProvider<UsernamePasswordAuthenticationRequest>` (fallback) — a spike picks whichever passes the login test. Flyway migration `V7__app_user.sql` creates the table. Form login config (`/login`, `/j_security_check`, encrypted-cookie session) and the `/admin` permission path are unchanged.

**Tech Stack:** Quarkus 3.36.1, quarkus-security (core), Bouncy Castle argon2id, Hibernate Panache, Flyway, Qute, Postgres.

---

> **IMPLEMENTED 2026-06-11 — deviations from the tasks below (this header is authoritative where they conflict; spec §2 is updated):**
> - **security-jpa was dropped.** Its generated `@UserDefinition` provider raced the custom one and intermittently rejected valid logins, and removing it left `TrustedAuthenticationRequest` (session) unhandled (HTTP 500 after login). Final design: `AppUser` is a **plain Panache entity**; auth = **two custom `IdentityProvider`s over `quarkus-security` core** — `AppUserIdentityProvider` (login, argon2id verify) + `AppUserTrustedIdentityProvider` (per-request session re-establish), sharing `AppUserSecurityIdentities.of(user)` (principal == username). `Argon2PasswordProvider` was removed.
> - **`FirstRunRedirectFilter` is a Vert.x `@RouteFilter(10000)`** (not a JAX-RS `@PreMatching` filter — that can't run blocking Panache pre-match). Its `count()` is wrapped in `QuarkusTransaction.requiringNew()` to avoid a per-request JDBC connection leak (pool exhaustion under load). `/setup` POST returns explicit 302.
> - **Test isolation:** `src/test/java/com/calit/user/TestUserBootstrap.java` seeds a baseline admin at startup so the redirect filter never hijacks public/API tests; `SetupFlowTest` restores the baseline in `@AfterEach`.
> - Result: full suite green (213 tests), confirmed deterministic over two runs.

---

## File Structure

| File | Status | Responsibility |
|------|--------|----------------|
| `pom.xml` | modified | Drop `quarkus-elytron-security-properties-file`; add `quarkus-security-jpa` and `org.bouncycastle:bcprov-jdk18on:1.78.1`. |
| `src/main/resources/application.properties` | modified | Remove `quarkus.security.users.embedded.*` and `ADMIN_PASSWORD`; keep form-auth + session key + `/admin` permission. |
| `src/main/resources/db/migration/V7__app_user.sql` | created | Flyway migration creating the `app_user` table. |
| `src/main/java/com/calit/user/AppUser.java` | created | `@UserDefinition` JPA Panache entity; `create(...)` factory; `findByUsername`, `usernameTaken`. |
| `src/main/java/com/calit/user/PasswordHasher.java` | created | `@ApplicationScoped` argon2id hash/verify (Bouncy Castle), MCF-style encoding. |
| `src/main/java/com/calit/user/Argon2PasswordProvider.java` | created | Elytron `PasswordProvider` bridging the stored argon2id hash (primary auth wiring). |
| `src/main/java/com/calit/user/Usernames.java` | created | Username normalize/validate/reserved-word util. |
| `src/main/java/com/calit/user/EnabledUserAugmentor.java` | created | `SecurityIdentityAugmentor` rejecting `enabled=false` users. |
| `src/main/java/com/calit/user/AppUserIdentityProvider.java` | created **only if the spike fails** | Fallback custom `IdentityProvider<UsernamePasswordAuthenticationRequest>` calling `PasswordHasher.verify`. |
| `src/main/java/com/calit/user/SetupResource.java` | created | `/setup` GET form + POST create-first-user; 404 once a user exists. |
| `src/main/java/com/calit/user/FirstRunRedirectFilter.java` | created | JAX-RS `@Provider` request filter redirecting to `/setup` while `AppUser.count()==0`. |
| `src/main/resources/templates/SetupResource/setup.html` | created | Qute create-first-user form. |
| `src/test/java/com/calit/user/PasswordHasherTest.java` | created | Unit test: argon2id hash/verify round-trip + encoding shape. |
| `src/test/java/com/calit/user/UsernamesTest.java` | created | Unit test: normalize/isValid/isReserved/validateNew. |
| `src/test/java/com/calit/user/AppUserPersistenceTest.java` | created | `@QuarkusTest`: persist + `findByUsername`/`usernameTaken`/`create` roles sync. |
| `src/test/java/com/calit/user/SetupFlowTest.java` | created | `@QuarkusTest`: `/setup` GET/POST + first-run redirect + 404-once-user-exists. |
| `src/test/java/com/calit/user/LoginSpikeTest.java` | created (spike) | `@QuarkusTest`: proves `/j_security_check` verifies a DB argon2id user; drives the primary-vs-fallback decision. |
| `src/test/java/com/calit/user/EnabledUserAugmentorTest.java` | created | `@QuarkusTest`: disabled user is rejected at `/admin` even with a valid cookie. |
| `src/test/java/com/calit/web/FormAuth.java` | modified | Seed an `admin`/`testpass` `AppUser` (idempotent, own tx) before `/j_security_check`. |

---

### Task 1: Dependencies & config swap (no behavior yet)

**Files:** `pom.xml`, `src/main/resources/application.properties`

- [ ] In `pom.xml`, remove the elytron-properties dependency line:
  ```xml
      <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-elytron-security-properties-file</artifactId></dependency>
  ```
- [ ] In `pom.xml`, in the `io.quarkus` block (right after the `quarkus-reactive-routes` line), add the security-jpa extension:
  ```xml
      <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-security-jpa</artifactId></dependency>
  ```
- [ ] In `pom.xml`, add Bouncy Castle to the third-party `<dependencies>` block (after the `quarkus-junit5-mockito` test dependency, before `</dependencies>`):
  ```xml
      <dependency>
        <groupId>org.bouncycastle</groupId>
        <artifactId>bcprov-jdk18on</artifactId>
        <version>1.78.1</version>
      </dependency>
  ```
- [ ] In `application.properties`, delete the four embedded-users lines:
  ```properties
  quarkus.security.users.embedded.enabled=true
  quarkus.security.users.embedded.plain-text=true
  quarkus.security.users.embedded.users.admin=${ADMIN_PASSWORD:changeme}
  quarkus.security.users.embedded.roles.admin=admin
  ```
- [ ] Confirm (do NOT change) these remain in `application.properties`: all `quarkus.http.auth.form.*` keys, `quarkus.http.auth.session.encryption-key` (both default and `%prod`), and the `/admin` permission block:
  ```properties
  quarkus.http.auth.permission.admin.paths=/admin,/admin/*
  quarkus.http.auth.permission.admin.policy=admin-policy
  quarkus.http.auth.policy.admin-policy.roles-allowed=admin
  ```
- [ ] Run the build to confirm dependency resolution and that the app still compiles (it will not boot-fail on missing identity store; auth simply has no users yet). Docker must be running for Dev Services Postgres. Command: `./mvnw -q -DskipTests package`. Expected: BUILD SUCCESS.
- [ ] Commit:
  ```bash
  git checkout -b phase-1-auth-foundation
  git add pom.xml src/main/resources/application.properties
  git commit -m "Swap embedded-properties auth for security-jpa + Bouncy Castle deps

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 2: `PasswordHasher` — argon2id hash/verify (pure unit, no Quarkus boot)

**Files:** `src/test/java/com/calit/user/PasswordHasherTest.java`, `src/main/java/com/calit/user/PasswordHasher.java`

- [ ] Write the failing test:
  ```java
  package com.calit.user;

  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertFalse;
  import static org.junit.jupiter.api.Assertions.assertNotEquals;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  class PasswordHasherTest {

      private final PasswordHasher hasher = new PasswordHasher();

      @Test
      void hashHasArgon2idMcfShape() {
          String encoded = hasher.hash("correct horse battery staple");
          assertTrue(encoded.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"),
                  "unexpected encoding: " + encoded);
          // $argon2id $v=19 $m=...,t=...,p=... $salt $hash  -> 6 segments after leading empty
          String[] parts = encoded.split("\\$");
          assertEquals(6, parts.length, "expected 6 MCF segments, got " + encoded);
      }

      @Test
      void verifyAcceptsCorrectPasswordAndRejectsWrong() {
          String encoded = hasher.hash("s3cret-pass");
          assertTrue(hasher.verify("s3cret-pass", encoded));
          assertFalse(hasher.verify("wrong-pass", encoded));
      }

      @Test
      void saltIsRandomPerHash() {
          assertNotEquals(hasher.hash("same"), hasher.hash("same"));
      }
  }
  ```
- [ ] Run it (it must FAIL to compile because `PasswordHasher` does not exist yet). Command: `./mvnw test -Dtest=PasswordHasherTest`. Expected FAIL: `cannot find symbol: class PasswordHasher` (compilation failure).
- [ ] Create the implementation:
  ```java
  package com.calit.user;

  import jakarta.enterprise.context.ApplicationScoped;

  import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
  import org.bouncycastle.crypto.params.Argon2Parameters;

  import java.nio.charset.StandardCharsets;
  import java.security.SecureRandom;
  import java.util.Base64;

  /**
   * Argon2id password hashing with OWASP-recommended parameters
   * (m=19456 KiB, t=2, p=1, 16-byte salt, 32-byte output).
   *
   * Encodes to an MCF-style string:
   *   $argon2id$v=19$m=19456,t=2,p=1$<saltBase64NoPad>$<hashBase64NoPad>
   */
  @ApplicationScoped
  public class PasswordHasher {

      private static final int MEMORY_KIB = 19456;
      private static final int ITERATIONS = 2;
      private static final int PARALLELISM = 1;
      private static final int SALT_LEN = 16;
      private static final int HASH_LEN = 32;
      private static final int VERSION = Argon2Parameters.ARGON2_VERSION_13; // 0x13 == 19

      private static final SecureRandom RNG = new SecureRandom();
      private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
      private static final Base64.Decoder B64D = Base64.getDecoder();

      public String hash(String raw) {
          byte[] salt = new byte[SALT_LEN];
          RNG.nextBytes(salt);
          byte[] out = derive(raw, salt);
          return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                  + "$" + B64.encodeToString(salt)
                  + "$" + B64.encodeToString(out);
      }

      public boolean verify(String raw, String encoded) {
          if (raw == null || encoded == null) {
              return false;
          }
          String[] parts = encoded.split("\\$");
          // ["", "argon2id", "v=19", "m=19456,t=2,p=1", saltB64, hashB64]
          if (parts.length != 6 || !"argon2id".equals(parts[1])) {
              return false;
          }
          byte[] salt;
          byte[] expected;
          try {
              salt = B64D.decode(parts[4]);
              expected = B64D.decode(parts[5]);
          } catch (IllegalArgumentException e) {
              return false;
          }
          byte[] actual = derive(raw, salt, expected.length);
          return constantTimeEquals(actual, expected);
      }

      private byte[] derive(String raw, byte[] salt) {
          return derive(raw, salt, HASH_LEN);
      }

      private byte[] derive(String raw, byte[] salt, int outLen) {
          Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                  .withVersion(VERSION)
                  .withMemoryAsKB(MEMORY_KIB)
                  .withIterations(ITERATIONS)
                  .withParallelism(PARALLELISM)
                  .withSalt(salt)
                  .build();
          Argon2BytesGenerator gen = new Argon2BytesGenerator();
          gen.init(params);
          byte[] out = new byte[outLen];
          gen.generateBytes(raw.getBytes(StandardCharsets.UTF_8), out, 0, out.length);
          return out;
      }

      private static boolean constantTimeEquals(byte[] a, byte[] b) {
          if (a.length != b.length) {
              return false;
          }
          int diff = 0;
          for (int i = 0; i < a.length; i++) {
              diff |= a[i] ^ b[i];
          }
          return diff == 0;
      }
  }
  ```
- [ ] Run the test. Command: `./mvnw test -Dtest=PasswordHasherTest`. Expected: PASS (3 tests).
- [ ] Commit:
  ```bash
  git add src/main/java/com/calit/user/PasswordHasher.java src/test/java/com/calit/user/PasswordHasherTest.java
  git commit -m "Add argon2id PasswordHasher with MCF-style encoding

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 3: `Usernames` — normalize/validate/reserved-words (pure unit)

**Files:** `src/test/java/com/calit/user/UsernamesTest.java`, `src/main/java/com/calit/user/Usernames.java`

> `validateNew` taking-taken-into-account requires a DB lookup, but `Usernames` itself stays pure: the "taken" check is delegated to a `java.util.function.Predicate<String>` argument so this util has no Quarkus/DB dependency and is unit-testable. `AppUser.usernameTaken` is passed in by callers (SetupResource, later phases).

- [ ] Write the failing test:
  ```java
  package com.calit.user;

  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertFalse;
  import static org.junit.jupiter.api.Assertions.assertThrows;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  class UsernamesTest {

      @Test
      void normalizeTrimsAndLowercases() {
          assertEquals("alice", Usernames.normalize("  Alice "));
          assertEquals("bob-smith", Usernames.normalize("Bob-Smith"));
      }

      @Test
      void isValidAcceptsGoodHandles() {
          assertTrue(Usernames.isValid("ab"));
          assertTrue(Usernames.isValid("a1"));
          assertTrue(Usernames.isValid("bob-smith"));
          assertTrue(Usernames.isValid("a-b-c-1-2"));
      }

      @Test
      void isValidRejectsBadHandles() {
          assertFalse(Usernames.isValid("a"));           // too short
          assertFalse(Usernames.isValid("-bob"));        // leading hyphen
          assertFalse(Usernames.isValid("bob-"));        // trailing hyphen
          assertFalse(Usernames.isValid("bob--smith"));  // double hyphen
          assertFalse(Usernames.isValid("Bob"));         // uppercase
          assertFalse(Usernames.isValid("bob_smith"));   // underscore
          assertFalse(Usernames.isValid("a".repeat(65)));// too long
          assertFalse(Usernames.isValid(""));
          assertFalse(Usernames.isValid(null));
      }

      @Test
      void isReservedCoversAllReservedWords() {
          for (String w : new String[]{
                  "me", "login", "logout", "signup", "setup",
                  "booking", "api", "q", "health", "calit", "index"}) {
              assertTrue(Usernames.isReserved(w), w + " should be reserved");
          }
          assertFalse(Usernames.isReserved("alice"));
      }

      @Test
      void validateNewReturnsNormalizedWhenFree() {
          assertEquals("alice", Usernames.validateNew("  Alice ", u -> false));
      }

      @Test
      void validateNewThrowsOnInvalidReservedOrTaken() {
          assertThrows(IllegalArgumentException.class, () -> Usernames.validateNew("a", u -> false));
          assertThrows(IllegalArgumentException.class, () -> Usernames.validateNew("Login", u -> false));
          assertThrows(IllegalArgumentException.class, () -> Usernames.validateNew("alice", u -> true));
      }
  }
  ```
- [ ] Run it. Command: `./mvnw test -Dtest=UsernamesTest`. Expected FAIL: `cannot find symbol: class Usernames` (compilation failure).
- [ ] Create the implementation:
  ```java
  package com.calit.user;

  import java.util.Set;
  import java.util.function.Predicate;
  import java.util.regex.Pattern;

  /** Username normalization, validation, and reserved-word checks. Pure (no DB). */
  public final class Usernames {

      private Usernames() {}

      private static final Pattern VALID = Pattern.compile("^[a-z0-9](-?[a-z0-9])*$");
      private static final int MIN_LEN = 2;
      private static final int MAX_LEN = 64;

      private static final Set<String> RESERVED = Set.of(
              "me", "login", "logout", "signup", "setup",
              "booking", "api", "q", "health", "calit", "index");

      /** Trim + lowercase. Null-safe: null stays null. */
      public static String normalize(String raw) {
          return raw == null ? null : raw.trim().toLowerCase();
      }

      /** True when value matches the handle regex and length bounds. Operates on the raw value. */
      public static boolean isValid(String value) {
          if (value == null) {
              return false;
          }
          int len = value.length();
          return len >= MIN_LEN && len <= MAX_LEN && VALID.matcher(value).matches();
      }

      /** True when the normalized value is a reserved word. */
      public static boolean isReserved(String value) {
          return RESERVED.contains(normalize(value));
      }

      /**
       * Normalizes {@code raw}, then rejects invalid, reserved, or already-taken handles.
       * @param taken predicate answering "is this normalized username already in use?"
       * @return the normalized, accepted username
       * @throws IllegalArgumentException if invalid, reserved, or taken
       */
      public static String validateNew(String raw, Predicate<String> taken) {
          String norm = normalize(raw);
          if (!isValid(norm)) {
              throw new IllegalArgumentException("Username must be 2-64 chars, lowercase letters/digits, single hyphens between.");
          }
          if (isReserved(norm)) {
              throw new IllegalArgumentException("That username is reserved.");
          }
          if (taken.test(norm)) {
              throw new IllegalArgumentException("That username is already taken.");
          }
          return norm;
      }
  }
  ```
- [ ] Run the test. Command: `./mvnw test -Dtest=UsernamesTest`. Expected: PASS (6 tests).
- [ ] Commit:
  ```bash
  git add src/main/java/com/calit/user/Usernames.java src/test/java/com/calit/user/UsernamesTest.java
  git commit -m "Add Usernames validation/reserved-word util

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 4: `V7__app_user.sql` migration + `AppUser` entity + `Argon2PasswordProvider`

**Files:** `src/main/resources/db/migration/V7__app_user.sql`, `src/main/java/com/calit/user/AppUser.java`, `src/main/java/com/calit/user/Argon2PasswordProvider.java`, `src/test/java/com/calit/user/AppUserPersistenceTest.java`

> Hibernate runs with `schema-management.strategy=validate`, so the entity columns must match the migration exactly. The `@UserDefinition`/`@Password(CUSTOM)` annotation requires the `Argon2PasswordProvider` class to exist at compile time, so it is created here alongside the entity (it is exercised end-to-end by the spike in Task 6).

- [ ] Write the failing persistence test:
  ```java
  package com.calit.user;

  import io.quarkus.test.TestTransaction;
  import io.quarkus.test.junit.QuarkusTest;
  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertFalse;
  import static org.junit.jupiter.api.Assertions.assertNotNull;
  import static org.junit.jupiter.api.Assertions.assertNull;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  @QuarkusTest
  class AppUserPersistenceTest {

      @Test
      @TestTransaction
      void createAdminSyncsRolesAndPersists() {
          AppUser u = AppUser.create("Root-User", "hash-placeholder", true);
          u.persist();
          assertNotNull(u.id);
          assertEquals("root-user", u.username);   // normalized
          assertEquals("user,admin", u.roles);
          assertTrue(u.isAdmin);
          assertTrue(u.enabled);
          assertFalse(u.mustChangePassword);
          assertFalse(u.settingsComplete);
          assertNotNull(u.createdAt);
      }

      @Test
      @TestTransaction
      void createNonAdminGetsUserRoleOnly() {
          AppUser u = AppUser.create("plainuser", "h", false);
          u.persist();
          assertEquals("user", u.roles);
          assertFalse(u.isAdmin);
      }

      @Test
      @TestTransaction
      void findByUsernameAndUsernameTaken() {
          AppUser.create("findme", "h", false).persist();
          assertNotNull(AppUser.findByUsername("findme"));
          assertNull(AppUser.findByUsername("nobody"));
          assertTrue(AppUser.usernameTaken("findme"));
          assertFalse(AppUser.usernameTaken("nobody"));
      }
  }
  ```
- [ ] Run it. Command: `./mvnw test -Dtest=AppUserPersistenceTest` (Docker must be running). Expected FAIL: `cannot find symbol: class AppUser` (compilation failure).
- [ ] Create the migration `src/main/resources/db/migration/V7__app_user.sql`:
  ```sql
  -- Phase 1 (multi-user auth foundation): DB-backed users replace the embedded
  -- properties admin. One row per user. Phase 2+ adds owner_id FKs to tenant tables;
  -- this migration only introduces the identity store.
  CREATE TABLE app_user (
      id                   BIGSERIAL    PRIMARY KEY,
      username             VARCHAR(64)  NOT NULL UNIQUE,   -- lowercased, URL-safe, validated in app
      password_hash        TEXT         NOT NULL,          -- argon2id, $argon2id$... MCF string
      roles                VARCHAR(64)  NOT NULL,          -- 'user' or 'user,admin'
      is_admin             BOOLEAN      NOT NULL DEFAULT FALSE,
      enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
      must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
      settings_complete    BOOLEAN      NOT NULL DEFAULT FALSE,
      created_at           TIMESTAMPTZ  NOT NULL
  );
  ```
- [ ] Create `src/main/java/com/calit/user/Argon2PasswordProvider.java`:
  ```java
  package com.calit.user;

  import io.quarkus.security.jpa.PasswordProvider;
  import org.wildfly.security.password.Password;
  import org.wildfly.security.password.interfaces.ClearPassword;

  /**
   * Bridges the stored argon2id MCF hash into Elytron's credential verification used by
   * security-jpa. Quarkus 3.x ships no first-class argon2 Elytron password type, so this
   * provider cannot hand Elytron a verifiable argon2 Password. We return the stored hash as a
   * ClearPassword so Elytron's default comparison only succeeds when the request credential
   * already equals the stored hash — which it never will for a plaintext form password. The
   * Task 6 spike (LoginSpikeTest) confirms this and, if it fails (expected), the custom
   * AppUserIdentityProvider fallback supersedes this provider for real verification.
   */
  public class Argon2PasswordProvider implements PasswordProvider {
      @Override
      public Password getPassword(String passwordInDatabase) {
          return ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, passwordInDatabase.toCharArray());
      }
  }
  ```
- [ ] Create `src/main/java/com/calit/user/AppUser.java`:
  ```java
  package com.calit.user;

  import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
  import io.quarkus.security.jpa.Password;
  import io.quarkus.security.jpa.PasswordType;
  import io.quarkus.security.jpa.Roles;
  import io.quarkus.security.jpa.UserDefinition;
  import io.quarkus.security.jpa.Username;
  import jakarta.persistence.Column;
  import jakarta.persistence.Entity;
  import jakarta.persistence.GeneratedValue;
  import jakarta.persistence.GenerationType;
  import jakarta.persistence.Id;
  import jakarta.persistence.Table;

  import java.time.Instant;

  /** DB-backed application user and the security-jpa @UserDefinition identity store. */
  @Entity
  @Table(name = "app_user")
  @UserDefinition
  public class AppUser extends PanacheEntityBase {

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      public Long id;

      @Username
      @Column(unique = true, nullable = false)
      public String username;

      @Password(value = PasswordType.CUSTOM, provider = Argon2PasswordProvider.class)
      @Column(name = "password_hash", nullable = false)
      public String passwordHash;

      @Roles
      @Column(nullable = false)
      public String roles;

      @Column(name = "is_admin", nullable = false)
      public boolean isAdmin = false;

      @Column(nullable = false)
      public boolean enabled = true;

      @Column(name = "must_change_password", nullable = false)
      public boolean mustChangePassword = false;

      @Column(name = "settings_complete", nullable = false)
      public boolean settingsComplete = false;

      @Column(name = "created_at", nullable = false)
      public Instant createdAt;

      /** Roles string kept in sync with isAdmin: admins get "user,admin", others "user". */
      private static String rolesFor(boolean admin) {
          return admin ? "user,admin" : "user";
      }

      /**
       * Factory for a new user. Normalizes the username, syncs roles with isAdmin, and stamps
       * created_at. Lifecycle flags default to false (caller sets them as needed before persist).
       */
      public static AppUser create(String username, String passwordHash, boolean admin) {
          AppUser u = new AppUser();
          u.username = Usernames.normalize(username);
          u.passwordHash = passwordHash;
          u.isAdmin = admin;
          u.roles = rolesFor(admin);
          u.createdAt = Instant.now();
          return u;
      }

      public static AppUser findByUsername(String username) {
          return find("username", Usernames.normalize(username)).firstResult();
      }

      public static boolean usernameTaken(String username) {
          return count("username", Usernames.normalize(username)) > 0;
      }
  }
  ```
- [ ] Run the test. Command: `./mvnw test -Dtest=AppUserPersistenceTest`. Expected: PASS (3 tests). This also proves the entity validates against the V7 schema and that `@Password(CUSTOM)` references a resolvable provider class.
- [ ] Commit:
  ```bash
  git add src/main/resources/db/migration/V7__app_user.sql src/main/java/com/calit/user/AppUser.java src/main/java/com/calit/user/Argon2PasswordProvider.java src/test/java/com/calit/user/AppUserPersistenceTest.java
  git commit -m "Add app_user migration, AppUser @UserDefinition entity, argon2 PasswordProvider

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 5: Update `FormAuth` to seed a DB user, and confirm `AdminAuthTest`

**Files:** `src/test/java/com/calit/web/FormAuth.java`, `src/test/java/com/calit/web/AdminAuthTest.java` (read-only check)

> Dev Services Postgres is ephemeral, so the `admin`/`testpass` user must be seeded before every login attempt. The seed is idempotent and runs in its own transaction via `QuarkusTransaction`. It hashes `testpass` with the real `PasswordHasher` so the stored hash matches what auth will verify (after Task 6). This task makes `FormAuth` compile and seed; `AdminAuthTest`'s logged-in assertion only fully passes after Task 6 wires verification — that ordering is expected and called out in Task 6.

- [ ] Replace `src/test/java/com/calit/web/FormAuth.java` entirely with:
  ```java
  package com.calit.web;

  import com.calit.user.AppUser;
  import com.calit.user.PasswordHasher;
  import io.quarkus.narayana.jta.QuarkusTransaction;

  import static io.restassured.RestAssured.given;

  /** Test helper: seeds a DB admin user, performs a form login, returns the credential cookie. */
  final class FormAuth {
      private FormAuth() {}

      private static final PasswordHasher HASHER = new PasswordHasher();

      /** Idempotently ensure an enabled admin user 'admin'/'testpass' exists. Own transaction. */
      static void ensureAdminSeeded() {
          QuarkusTransaction.requiringNew().run(() -> {
              if (!AppUser.usernameTaken("admin")) {
                  AppUser u = AppUser.create("admin", HASHER.hash("testpass"), true);
                  u.persist();
              }
          });
      }

      /** Logs in as the seeded test admin and returns the `quarkus-credential` cookie value. */
      static String login() {
          ensureAdminSeeded();
          return given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("j_username", "admin")
                  .formParam("j_password", "testpass")
                  .when().post("/j_security_check")
                  .then().extract().cookie("quarkus-credential");
      }
  }
  ```
- [ ] Confirm `AdminAuthTest` (do NOT edit it) still references `FormAuth.login()` and the `/admin` paths — no change needed; it is the consumer that Task 6 must make green.
- [ ] Run it to observe the current state. Command: `./mvnw test -Dtest=AdminAuthTest`. Expected at this point: `dashboardRequiresAuth` and `loginPageRenders` PASS; `dashboardServedWhenLoggedIn` may FAIL (no verification wiring yet → empty/anonymous credential cookie). This is the pre-spike baseline. (Do not commit a failing suite; proceed straight to Task 6, which is committed together with this FormAuth change.)

---

### Task 6: SPIKE — wire `/j_security_check` to verify the DB argon2id user (primary, else fallback)

**Files:** `src/test/java/com/calit/user/LoginSpikeTest.java`, then EITHER keep `Argon2PasswordProvider` as-is OR add `src/main/java/com/calit/user/AppUserIdentityProvider.java`; finally `src/test/java/com/calit/web/FormAuth.java` + `src/test/java/com/calit/web/AdminAuthTest.java`

> **Decision step.** security-jpa with `@Password(CUSTOM)` may not be able to verify an argon2id plaintext-vs-hash through Elytron (no argon2 Elytron password type in Quarkus 3.x). This task writes ONE test that asserts a real login succeeds, runs it against the PRIMARY wiring, and — only if it fails — adds the FALLBACK custom `IdentityProvider` and re-runs. Whichever makes the test green is kept; the loser wiring is removed/neutralized.

- [ ] Write the spike test (independent of `AdminAuthTest`, seeds its own user):
  ```java
  package com.calit.user;

  import io.quarkus.narayana.jta.QuarkusTransaction;
  import io.quarkus.test.junit.QuarkusTest;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;

  import static io.restassured.RestAssured.given;
  import static org.hamcrest.Matchers.notNullValue;

  @QuarkusTest
  class LoginSpikeTest {

      @BeforeEach
      void seed() {
          QuarkusTransaction.requiringNew().run(() -> {
              if (!AppUser.usernameTaken("spikeuser")) {
                  AppUser u = AppUser.create("spikeuser", new PasswordHasher().hash("spikepass"), true);
                  u.persist();
              }
          });
      }

      @Test
      void formLoginIssuesCredentialCookieForDbUser() {
          given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("j_username", "spikeuser")
                  .formParam("j_password", "spikepass")
                  .when().post("/j_security_check")
                  .then()
                  // A successful form login redirects (302) to the landing page and sets the cookie.
                  .statusCode(302)
                  .cookie("quarkus-credential", notNullValue());
      }

      @Test
      void wrongPasswordIsRejected() {
          given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("j_username", "spikeuser")
                  .formParam("j_password", "WRONG")
                  .when().post("/j_security_check")
                  .then()
                  // Failed login redirects to the error page, not the landing page.
                  .statusCode(302)
                  .header("Location", org.hamcrest.Matchers.containsString("error=true"));
      }
  }
  ```
- [ ] **PRIMARY run.** Command: `./mvnw test -Dtest=LoginSpikeTest` (Docker required). Record the result:
  - If BOTH tests PASS → the custom `PasswordProvider` path works. Skip the fallback creation step; leave `Argon2PasswordProvider` as the verification path but change it to return a verifiable password (see note). Go to "Finalize".
  - If `formLoginIssuesCredentialCookieForDbUser` FAILS (expected, because `ClearPassword`-of-hash never equals the plaintext) → proceed to the FALLBACK step.
  > Note for the PASS branch: if security-jpa ever exposes an argon2 Elytron type, `Argon2PasswordProvider.getPassword` should return that argon2 `Password` parsed from the MCF string. As of Quarkus 3.36.1 this type is not available, so the FALLBACK branch below is the expected outcome.
- [ ] **FALLBACK step (run only if the primary failed).** Create `src/main/java/com/calit/user/AppUserIdentityProvider.java`. This custom provider fully owns username/password verification, bypassing Elytron's credential comparison; the `@Password(CUSTOM)` annotation on `AppUser` stays only to satisfy security-jpa's `@UserDefinition` contract but is no longer the verification path.
  ```java
  package com.calit.user;

  import io.quarkus.security.AuthenticationFailedException;
  import io.quarkus.security.identity.AuthenticationRequestContext;
  import io.quarkus.security.identity.IdentityProvider;
  import io.quarkus.security.identity.SecurityIdentity;
  import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
  import io.quarkus.security.runtime.QuarkusSecurityIdentity;
  import io.smallrye.mutiny.Uni;
  import jakarta.enterprise.context.ApplicationScoped;
  import jakarta.enterprise.context.control.ActivateRequestContext;
  import jakarta.inject.Inject;

  /**
   * Verifies form-login credentials against the DB argon2id hash. Replaces the Elytron
   * credential-comparison path that cannot consume argon2. Loads the AppUser by username,
   * verifies the password with PasswordHasher, and builds a SecurityIdentity carrying the
   * user's roles. Disabled users are rejected here (and again by EnabledUserAugmentor).
   */
  @ApplicationScoped
  public class AppUserIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

      @Inject
      PasswordHasher passwordHasher;

      @Override
      public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
          return UsernamePasswordAuthenticationRequest.class;
      }

      @Override
      public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                                AuthenticationRequestContext context) {
          // Hibernate ORM is blocking; run the lookup on the worker pool with an active request context.
          return context.runBlocking(() -> authenticateBlocking(request));
      }

      @ActivateRequestContext
      SecurityIdentity authenticateBlocking(UsernamePasswordAuthenticationRequest request) {
          String username = request.getUsername();
          String password = new String(request.getPassword().getPassword());
          AppUser user = AppUser.findByUsername(username);
          if (user == null || !user.enabled || !passwordHasher.verify(password, user.passwordHash)) {
              throw new AuthenticationFailedException();
          }
          QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                  .setPrincipal(() -> user.username);
          for (String role : user.roles.split(",")) {
              builder.addRole(role.trim());
          }
          return builder.build();
      }
  }
  ```
- [ ] Re-run the spike with the fallback in place. Command: `./mvnw test -Dtest=LoginSpikeTest`. Expected: PASS (2 tests) — DB user logs in, wrong password rejected.
- [ ] **Finalize.** Run the original admin auth suite (now that verification works and `FormAuth` seeds the user). Command: `./mvnw test -Dtest=AdminAuthTest`. Expected: PASS (3 tests), including `dashboardServedWhenLoggedIn` and `Dashboard` body match.
- [ ] Commit the whole auth-wiring change together (FormAuth from Task 5 + the chosen wiring + spike test):
  ```bash
  git add src/test/java/com/calit/web/FormAuth.java src/test/java/com/calit/user/LoginSpikeTest.java
  # include the fallback only if it was created:
  git add src/main/java/com/calit/user/AppUserIdentityProvider.java 2>/dev/null || true
  git commit -m "Wire form login to DB argon2id users (custom IdentityProvider fallback) and seed FormAuth

  Spike confirmed Elytron @Password(CUSTOM) cannot verify argon2 plaintext-vs-hash, so a
  custom IdentityProvider<UsernamePasswordAuthenticationRequest> owns verification.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 7: `EnabledUserAugmentor` — reject disabled users on every request

**Files:** `src/test/java/com/calit/user/EnabledUserAugmentorTest.java`, `src/main/java/com/calit/user/EnabledUserAugmentor.java`

> Even with the custom IdentityProvider rejecting disabled users at login, an already-issued credential cookie of a just-disabled user stays valid until expiry. The augmentor re-checks `enabled` on every authenticated request and downgrades a disabled identity to anonymous, so `/admin` then 302-redirects to `/login`.

- [ ] Write the failing test (seeds an enabled admin, logs in to get a cookie, disables the user, then asserts the same cookie no longer grants `/admin`):
  ```java
  package com.calit.user;

  import io.quarkus.narayana.jta.QuarkusTransaction;
  import io.quarkus.test.junit.QuarkusTest;
  import org.junit.jupiter.api.Test;

  import static io.restassured.RestAssured.given;
  import static org.hamcrest.Matchers.containsString;

  @QuarkusTest
  class EnabledUserAugmentorTest {

      private static final PasswordHasher HASHER = new PasswordHasher();

      private void upsert(String username, boolean enabled) {
          QuarkusTransaction.requiringNew().run(() -> {
              AppUser u = AppUser.findByUsername(username);
              if (u == null) {
                  u = AppUser.create(username, HASHER.hash("pw12345"), true);
              }
              u.enabled = enabled;
              u.persist();
          });
      }

      @Test
      void disabledUserCookieIsRejected() {
          upsert("lockme", true);
          String cookie = given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("j_username", "lockme")
                  .formParam("j_password", "pw12345")
                  .when().post("/j_security_check")
                  .then().statusCode(302).extract().cookie("quarkus-credential");

          // Sanity: cookie works while enabled.
          given().cookie("quarkus-credential", cookie)
                  .when().get("/admin")
                  .then().statusCode(200).body(containsString("Dashboard"));

          // Disable the user; the still-valid cookie must now be rejected.
          upsert("lockme", false);
          given().redirects().follow(false)
                  .cookie("quarkus-credential", cookie)
                  .when().get("/admin")
                  .then().statusCode(302)
                  .header("Location", containsString("/login"));
      }
  }
  ```
- [ ] Run it. Command: `./mvnw test -Dtest=EnabledUserAugmentorTest`. Expected FAIL: the second `/admin` call returns 200 (cookie still trusted) because no augmentor revokes disabled users yet — assertion error on `statusCode(302)`.
- [ ] Create `src/main/java/com/calit/user/EnabledUserAugmentor.java`:
  ```java
  package com.calit.user;

  import io.quarkus.security.identity.AuthenticationRequestContext;
  import io.quarkus.security.identity.SecurityIdentity;
  import io.quarkus.security.identity.SecurityIdentityAugmentor;
  import io.quarkus.security.runtime.QuarkusSecurityIdentity;
  import io.smallrye.mutiny.Uni;
  import jakarta.enterprise.context.ApplicationScoped;
  import jakarta.enterprise.context.control.ActivateRequestContext;

  /**
   * Rejects authenticated requests whose AppUser has been disabled (enabled=false), even when a
   * previously-issued credential cookie is still cryptographically valid. A disabled identity is
   * downgraded to anonymous so role-guarded paths (e.g. /admin) redirect to /login.
   */
  @ApplicationScoped
  public class EnabledUserAugmentor implements SecurityIdentityAugmentor {

      @Override
      public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
          if (identity.isAnonymous()) {
              return Uni.createFrom().item(identity);
          }
          // Hibernate ORM is blocking.
          return context.runBlocking(() -> check(identity));
      }

      @ActivateRequestContext
      SecurityIdentity check(SecurityIdentity identity) {
          String username = identity.getPrincipal().getName();
          AppUser user = AppUser.findByUsername(username);
          if (user == null || !user.enabled) {
              // Drop all roles -> anonymous-equivalent identity; guarded paths will 401/redirect.
              return QuarkusSecurityIdentity.builder()
                      .setPrincipal(identity.getPrincipal())
                      .setAnonymous(true)
                      .build();
          }
          return identity;
      }
  }
  ```
- [ ] Run the test. Command: `./mvnw test -Dtest=EnabledUserAugmentorTest`. Expected: PASS (1 test).
- [ ] Commit:
  ```bash
  git add src/main/java/com/calit/user/EnabledUserAugmentor.java src/test/java/com/calit/user/EnabledUserAugmentorTest.java
  git commit -m "Reject disabled users via SecurityIdentityAugmentor

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 8: First-run `/setup` — resource, template, redirect filter

**Files:** `src/test/java/com/calit/user/SetupFlowTest.java`, `src/main/java/com/calit/user/SetupResource.java`, `src/main/resources/templates/SetupResource/setup.html`, `src/main/java/com/calit/user/FirstRunRedirectFilter.java`

> The redirect filter must let `/setup`, `/login`, `/j_security_check`, static assets (`/calit.css`, `/q/*`, `/favicon.ico`), and health (`/q/health`) through; everything else 302s to `/setup` while `AppUser.count()==0`. Once a user exists, `SetupResource` returns 404 and the filter is a no-op. Because tests share one ephemeral DB and other tests seed users, `SetupFlowTest` runs in a class whose assertions tolerate the "a user already exists" state by explicitly controlling counts via its own transactions; to keep it deterministic it asserts the empty-DB behavior only after deleting all users, and restores nothing (the DB is ephemeral per run, and each test method manages its own precondition).

- [ ] Write the failing test:
  ```java
  package com.calit.user;

  import io.quarkus.narayana.jta.QuarkusTransaction;
  import io.quarkus.test.junit.QuarkusTest;
  import org.junit.jupiter.api.Test;

  import static io.restassured.RestAssured.given;
  import static org.hamcrest.Matchers.containsString;

  @QuarkusTest
  class SetupFlowTest {

      private void deleteAllUsers() {
          QuarkusTransaction.requiringNew().run(() -> AppUser.deleteAll());
      }

      private void seedOneUser() {
          QuarkusTransaction.requiringNew().run(() -> {
              if (AppUser.count() == 0) {
                  AppUser.create("existing", new PasswordHasher().hash("pw12345"), true).persist();
              }
          });
      }

      @Test
      void setupFormRendersWhenNoUsers() {
          deleteAllUsers();
          given().when().get("/setup")
                  .then().statusCode(200)
                  .body(containsString("Create the first user"))
                  .body(containsString("name=\"username\""));
      }

      @Test
      void requestsRedirectToSetupWhenNoUsers() {
          deleteAllUsers();
          given().redirects().follow(false)
                  .when().get("/admin")
                  .then().statusCode(302)
                  .header("Location", containsString("/setup"));
      }

      @Test
      void setupCreatesFirstAdminUserThenRedirectsToLogin() {
          deleteAllUsers();
          given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("username", "Boss")
                  .formParam("password", "boss-pw-123")
                  .when().post("/setup")
                  .then().statusCode(302)
                  .header("Location", containsString("/login"));

          // The created user can now log in.
          given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("j_username", "boss")
                  .formParam("j_password", "boss-pw-123")
                  .when().post("/j_security_check")
                  .then().statusCode(302);
      }

      @Test
      void setupReturns404OnceAUserExists() {
          seedOneUser();
          given().when().get("/setup").then().statusCode(404);
          given().redirects().follow(false)
                  .contentType("application/x-www-form-urlencoded")
                  .formParam("username", "second")
                  .formParam("password", "whatever-12")
                  .when().post("/setup")
                  .then().statusCode(404);
      }
  }
  ```
- [ ] Run it. Command: `./mvnw test -Dtest=SetupFlowTest`. Expected FAIL: 404 on `GET /setup` (no resource) / no redirect to `/setup` — assertion errors (and compile is fine since it references only existing classes).
- [ ] Create the template `src/main/resources/templates/SetupResource/setup.html`:
  ```html
  {@java.lang.Boolean error}
  {#include base title="Set up calit"}
    <div class="card bg-base-100 border border-base-300 shadow-sm max-w-sm mx-auto">
      <div class="card-body">
        <h1 class="text-2xl font-bold">Create the first user</h1>
        <p class="text-base-content/70">This first account is the site administrator.</p>
        {#if error}<div role="alert" class="alert alert-error mt-2">Username invalid, reserved, or taken — try another.</div>{/if}
        <form method="post" action="/setup" class="mt-2">
          <fieldset class="fieldset">
            <label class="label" for="username">Username</label>
            <input id="username" class="input w-full" type="text" name="username" required autofocus>
            <label class="label" for="password">Password</label>
            <input id="password" class="input w-full" type="password" name="password" required>
            <button type="submit" class="btn btn-primary btn-block mt-2">Create administrator</button>
          </fieldset>
        </form>
      </div>
    </div>
  {/include}
  ```
- [ ] Create `src/main/java/com/calit/user/SetupResource.java`:
  ```java
  package com.calit.user;

  import io.quarkus.qute.CheckedTemplate;
  import io.quarkus.qute.TemplateInstance;
  import jakarta.inject.Inject;
  import jakarta.transaction.Transactional;
  import jakarta.ws.rs.FormParam;
  import jakarta.ws.rs.GET;
  import jakarta.ws.rs.NotFoundException;
  import jakarta.ws.rs.POST;
  import jakarta.ws.rs.Path;
  import jakarta.ws.rs.Produces;
  import jakarta.ws.rs.core.MediaType;
  import jakarta.ws.rs.core.Response;
  import java.net.URI;

  /**
   * First-run bootstrap. While no user exists, renders/creates the first (admin) user. Once any
   * user exists, every endpoint here returns 404 (the instance is bootstrapped).
   */
  @Path("/setup")
  public class SetupResource {

      @Inject
      PasswordHasher passwordHasher;

      @CheckedTemplate
      public static class Templates {
          public static native TemplateInstance setup(boolean error);
      }

      @GET
      @Produces(MediaType.TEXT_HTML)
      public TemplateInstance setupForm() {
          requireUnbootstrapped();
          return Templates.setup(false);
      }

      @POST
      @Transactional
      @Produces(MediaType.TEXT_HTML)
      public Response createFirstUser(@FormParam("username") String username,
                                      @FormParam("password") String password) {
          requireUnbootstrapped();
          final String normalized;
          try {
              normalized = Usernames.validateNew(username, AppUser::usernameTaken);
          } catch (IllegalArgumentException e) {
              return Response.status(Response.Status.BAD_REQUEST)
                      .entity(Templates.setup(true)).type(MediaType.TEXT_HTML).build();
          }
          if (password == null || password.isBlank()) {
              return Response.status(Response.Status.BAD_REQUEST)
                      .entity(Templates.setup(true)).type(MediaType.TEXT_HTML).build();
          }
          // First user is the site admin; no temp-password forcing, settings wizard is Phase 4.
          AppUser u = AppUser.create(normalized, passwordHasher.hash(password), true);
          u.mustChangePassword = false;
          u.settingsComplete = false;
          u.persist();
          return Response.seeOther(URI.create("/login")).build();
      }

      /** 404 once the instance has any user. */
      private void requireUnbootstrapped() {
          if (AppUser.count() > 0) {
              throw new NotFoundException();
          }
      }
  }
  ```
- [ ] Create `src/main/java/com/calit/user/FirstRunRedirectFilter.java`:
  ```java
  package com.calit.user;

  import jakarta.ws.rs.container.ContainerRequestContext;
  import jakarta.ws.rs.container.ContainerRequestFilter;
  import jakarta.ws.rs.container.PreMatching;
  import jakarta.ws.rs.core.Response;
  import jakarta.ws.rs.ext.Provider;
  import java.net.URI;

  /**
   * While no user exists, redirect every request to /setup so the instance gets bootstrapped —
   * except /setup itself, the login endpoints, static assets, and Quarkus management paths.
   * Once any user exists this filter is a no-op (the common case, one count() per request).
   */
  @Provider
  @PreMatching
  public class FirstRunRedirectFilter implements ContainerRequestFilter {

      @Override
      public void filter(ContainerRequestContext ctx) {
          String path = "/" + ctx.getUriInfo().getPath();
          if (isAllowedWhileUnbootstrapped(path)) {
              return;
          }
          if (AppUser.count() == 0) {
              ctx.abortWith(Response.seeOther(URI.create("/setup")).build());
          }
      }

      private boolean isAllowedWhileUnbootstrapped(String path) {
          return path.equals("/setup")
                  || path.equals("/login")
                  || path.equals("/j_security_check")
                  || path.startsWith("/q/")        // /q/health, dev/management endpoints
                  || path.equals("/calit.css")
                  || path.equals("/favicon.ico");
      }
  }
  ```
  > `count()` outside a transaction is a read-only Panache query and is permitted; if the runtime requires an active transaction for the count in `@PreMatching`, annotate `filter` with `@jakarta.transaction.Transactional`. Apply that only if the test surfaces a "no transaction in progress" failure.
- [ ] Run the test. Command: `./mvnw test -Dtest=SetupFlowTest`. Expected: PASS (4 tests).
- [ ] Commit:
  ```bash
  git add src/main/java/com/calit/user/SetupResource.java src/main/java/com/calit/user/FirstRunRedirectFilter.java src/main/resources/templates/SetupResource/setup.html src/test/java/com/calit/user/SetupFlowTest.java
  git commit -m "Add first-run /setup bootstrap with redirect filter

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 9: Full-suite green + final verification

**Files:** none (verification only)

- [ ] Run the entire test suite (Docker must be running for Dev Services Postgres). Command: `./mvnw test`. Expected: BUILD SUCCESS, all tests pass. If `SetupFlowTest`'s `deleteAllUsers` races other `@QuarkusTest` classes that seed users, confirm test isolation: each test that needs a logged-in user seeds it via `FormAuth`/its own `@BeforeEach`, and `SetupFlowTest` deletes-then-acts within single methods, so cross-class ordering cannot leave a stale precondition. If a flake appears, apply `@org.junit.jupiter.api.TestMethodOrder` is NOT needed — instead make each `SetupFlowTest` method self-sufficient (already done).
- [ ] Confirm no leftover references to the removed embedded auth. Command: `grep -rn "users.embedded\|ADMIN_PASSWORD\|elytron-security-properties" pom.xml src/main/resources/application.properties`. Expected: no matches.
- [ ] Confirm the new package is complete. Command: `ls src/main/java/com/calit/user`. Expected: `AppUser.java`, `Argon2PasswordProvider.java`, `EnabledUserAugmentor.java`, `FirstRunRedirectFilter.java`, `PasswordHasher.java`, `SetupResource.java`, `Usernames.java`, and `AppUserIdentityProvider.java` (if the fallback was taken in Task 6).
- [ ] Final commit (only if Task 9 produced any tweaks, e.g. the optional `@Transactional` on the filter):
  ```bash
  git add -A
  git commit -m "Phase 1 auth foundation: full suite green

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

## Out of scope for Phase 1 (do NOT implement here)

- `owner_id` columns/FKs on tenant tables and owner-scoped queries (Phase 2).
- `/admin` → `/me` rename and `/me/users` admin UI (Phase 2).
- Public `/{user}` and `/{user}/{slug}` routing, reserved-word path defence (Phase 3).
- Admin create-user, opt-in `SIGNUP_ENABLED`, first-login `/me/setup` wizard, lock/unlock UI (Phase 4).

The `Usernames` reserved list, `enabled`/`must_change_password`/`settings_complete` columns, and the `isAdmin`/`roles` sync are introduced now because later phases depend on these exact contracts.
