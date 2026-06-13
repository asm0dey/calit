# Security Audit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the actionable findings from the 2026-06-12 defensive security audit (`security/audit/2026-06-12-calit.md`) without disconnecting any user's already-linked Google Calendar.

**Architecture:** Most fixes are localized hardening. The one UX-critical fix — encrypting Google OAuth tokens at rest (SEC-SECRET-02) — uses a JPA `AttributeConverter` that **encrypts on write and tolerantly decrypts** (returns legacy plaintext unchanged when it sees no ciphertext marker), plus a one-time startup backfill that rewrites existing plaintext rows as ciphertext via raw SQL. Because reads stay transparent and tokens themselves are never rotated, **every connected calendar keeps working through and after the migration.**

**Tech Stack:** Quarkus 3.36 / Java 25, Hibernate ORM Panache + PostgreSQL, Flyway migrations, Qute templates, BouncyCastle (already a dependency), `quarkus-rest-csrf`, Trivy/CodeQL/Dependabot in GitHub Actions.

---

## Context for the implementer (read once)

- **Build/test:** `mvn test` needs Docker running (Dev Services Postgres). One reused JVM fork; DB truncated+reseeded per test by `DatabaseResetCallback`. **Admin user is always id 1.**
- **Flyway is immutable:** never edit an applied `V*.sql`. Add a new `V12__*.sql`, `V13__*.sql`, … Hibernate is validate-only — migrations own the schema.
- **Owner scoping:** every tenant query filters by `currentOwner.id()`. Don't break this.
- **CSS:** run `bun run css:build` once or pages render unstyled. Not needed for backend tests.
- **Already done — do NOT redo:** SEC-SECRET-03 (dev-default OAuth state-secret) is already mitigated by `src/main/java/com/calit/StartupSecretCheck.java`, which fail-closes in `%prod` on a missing/dev-default/too-short `GOOGLE_OAUTH_STATE_SECRET` and `SESSION_ENCRYPTION_KEY`. Task 2 only *extends* that existing class with the new token key.
- **Not in scope / no code change:** SEC-DEP-03 (npm caret ranges — already mitigated by committed `bun.lock` + `--frozen-lockfile`). SEC-DEP-04 (CVE verification) is folded into SEC-DEP-01's scan gate.

Remediation order below follows the audit's section 6.

---

## File Structure

**New files:**
- `src/main/java/com/calit/crypto/TokenCipher.java` — AES-256-GCM encrypt/decrypt + ciphertext detection (SEC-SECRET-02).
- `src/main/java/com/calit/crypto/EncryptedStringConverter.java` — JPA `AttributeConverter` applying `TokenCipher` (SEC-SECRET-02).
- `src/main/java/com/calit/crypto/TokenBackfill.java` — one-time startup backfill of legacy plaintext rows (SEC-SECRET-02).
- `src/main/java/com/calit/audit/AuditLog.java` — structured audit-event emitter (SEC-SECRET-05).
- `src/test/java/com/calit/crypto/TokenCipherTest.java`
- `src/test/java/com/calit/crypto/TokenEncryptionAtRestTest.java`
- `src/test/java/com/calit/web/AdminLockoutGuardTest.java`
- `src/test/java/com/calit/booking/InviteeEmailValidationTest.java`
- `src/test/java/com/calit/email/IcsBuilderEscapeTest.java`
- `src/main/resources/db/migration/V12__google_token_length.sql` — widen token columns for ciphertext (SEC-SECRET-02).
- `.github/dependabot.yml`, `.github/workflows/codeql.yml` (SEC-DEP-01).

**Modified files:**
- `src/main/resources/application.properties` — SQL logging → `%dev`; token key config (SEC-SECRET-01, -02).
- `src/main/java/com/calit/StartupSecretCheck.java` — validate `TOKEN_ENCRYPTION_KEY` (SEC-SECRET-02).
- `src/main/java/com/calit/google/GoogleCredential.java` — `@Convert` on token fields (SEC-SECRET-02).
- `src/main/java/com/calit/web/UsersResource.java` — last-admin / self-lockout guard + audit (SEC-AUTHZ-01, -SECRET-05).
- `src/main/java/com/calit/booking/BookingService.java` — invitee email + input-bound validation (SEC-INPUT-01, -02).
- `src/main/java/com/calit/email/IcsBuilder.java` — CRLF-escape UID/ORGANIZER (SEC-INPUT-01).
- `src/main/java/com/calit/web/MeOwnerFilter.java` — fail closed 401 (SEC-AUTHZ-03).
- `src/main/java/com/calit/google/GoogleOAuthResource.java` — fixed error message + `nosniff` (SEC-INPUT-03).
- `src/main/java/com/calit/booking/TurnstileVerifier.java`, `google/GoogleTokenService.java`, `google/GoogleCalendarClientFactory.java` — HTTP timeouts (SEC-SSRF-01).
- `pom.xml` — add `quarkus-rest-csrf` (SEC-SECRET-04).
- `src/main/resources/templates/**` POST forms — CSRF hidden field (SEC-SECRET-04).
- `.github/workflows/ci.yml` — Trivy scan gate (SEC-DEP-01).
- `renovate.json` — `vulnerabilityAlerts` (SEC-DEP-01).
- `Dockerfile`, `docker-compose.yml` — digest pins + non-root `USER` (SEC-DEP-02, -05).

---

## Task 1: Scope SQL logging to dev (SEC-SECRET-01)

**Files:**
- Modify: `src/main/resources/application.properties:6`

One line. No PII/SQL in prod logs.

- [ ] **Step 1: Edit the property**

In `src/main/resources/application.properties`, change line 6 from:

```properties
quarkus.hibernate-orm.log.sql=true
```

to (profile-scoped — dev only):

```properties
%dev.quarkus.hibernate-orm.log.sql=true
```

- [ ] **Step 2: Verify no other SQL-log flags leak to prod**

Run: `grep -n "log.sql\|log.bind\|log.format" src/main/resources/application.properties`
Expected: only the `%dev.`-prefixed line above. No unprofiled SQL/bind-parameter logging.

- [ ] **Step 3: Build to confirm config parses**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "fix(security): scope SQL logging to %dev (SEC-SECRET-01)"
```

---

## Task 2: Encrypt Google OAuth tokens at rest (SEC-SECRET-02)

**This is the UX-critical task.** Existing connected calendars MUST keep working. The converter reads legacy plaintext transparently; the backfill rewrites it as ciphertext; tokens are never rotated, so Google connections survive.

### 2a — `TokenCipher` (AES-256-GCM)

**Files:**
- Create: `src/main/java/com/calit/crypto/TokenCipher.java`
- Test: `src/test/java/com/calit/crypto/TokenCipherTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/crypto/TokenCipherTest.java`:

```java
package com.calit.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCipherTest {

    // 32-byte key, hex-encoded (64 hex chars) — matches the prod key format.
    private static final String KEY = "0".repeat(64);

    private final TokenCipher cipher = new TokenCipher(KEY);

    @Test
    void roundTripsAValue() {
        String plaintext = "1//refresh-token-value";
        String encrypted = cipher.encrypt(plaintext);
        assertNotEquals(plaintext, encrypted);
        assertTrue(cipher.looksEncrypted(encrypted));
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void usesAFreshIvPerCall() {
        // GCM must never reuse an IV under one key — two encryptions of the same input differ.
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    @Test
    void decryptPassesThroughLegacyPlaintext() {
        // Tolerant read: pre-migration rows have no marker — return them unchanged so
        // already-connected calendars keep working during rollout.
        assertFalse(cipher.looksEncrypted("1//legacy-plaintext"));
        assertEquals("1//legacy-plaintext", cipher.decrypt("1//legacy-plaintext"));
    }

    @Test
    void handlesNulls() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.decrypt(null));
        assertFalse(cipher.looksEncrypted(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TokenCipherTest`
Expected: FAIL — `TokenCipher` does not exist (compilation error).

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/calit/crypto/TokenCipher.java`:

```java
package com.calit.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets stored at rest (Google OAuth tokens — SEC-SECRET-02).
 *
 * <p>Stored form: {@code "enc:v1:" + base64(iv || ciphertext||tag)}. The {@code enc:v1:} marker lets
 * {@link #decrypt} tell ciphertext from legacy plaintext, so rows written before this feature shipped
 * are returned unchanged — connected calendars survive the rollout without re-consent.
 *
 * <p>Key: {@code TOKEN_ENCRYPTION_KEY}, a 64-char hex string (32 bytes). Identical on every replica.
 */
@ApplicationScoped
public class TokenCipher {

    private static final String MARKER = "enc:v1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;       // GCM standard nonce length
    private static final int TAG_BITS = 128;       // GCM auth tag length

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@ConfigProperty(name = "token.encryption-key") String hexKey) {
        byte[] raw = hexToBytes(hexKey);
        if (raw.length != 32) {
            throw new IllegalStateException(
                "TOKEN_ENCRYPTION_KEY must decode to 32 bytes (64 hex chars); got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** True when {@code stored} carries the ciphertext marker (i.e. was written by this class). */
    public boolean looksEncrypted(String stored) {
        return stored != null && stored.startsWith(MARKER);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return MARKER + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!looksEncrypted(stored)) {
            return stored; // legacy plaintext — pass through (SEC-SECRET-02 rollout safety)
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(MARKER.length()));
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be an even-length hex string.");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
```

> Note: this class has a constructor taking the key so the unit test can construct it directly. CDI uses the same `@ConfigProperty` constructor injection at runtime.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TokenCipherTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/crypto/TokenCipher.java src/test/java/com/calit/crypto/TokenCipherTest.java
git commit -m "feat(security): AES-256-GCM TokenCipher with legacy-plaintext passthrough (SEC-SECRET-02)"
```

### 2b — Config + prod key guard

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/calit/StartupSecretCheck.java`

- [ ] **Step 1: Add the key config**

In `src/main/resources/application.properties`, near the session/state secret block (around line 28), add:

```properties
# AES-256-GCM key for Google OAuth tokens at rest (SEC-SECRET-02). 64 hex chars = 32 bytes.
# Generate: `openssl rand -hex 32`. Identical on every replica. Rotating it strands existing
# ciphertext, so keep it stable; %prod has no default and fails boot if unset (StartupSecretCheck).
token.encryption-key=${TOKEN_ENCRYPTION_KEY:dev-only-insecure-0000000000000000000000000000000000000000000000000000000000}
%prod.token.encryption-key=${TOKEN_ENCRYPTION_KEY}
```

> The dev default is 64 hex chars so `TokenCipher` constructs in `%dev`/`%test`; it contains the `dev-only-insecure` marker so the prod guard rejects it.

- [ ] **Step 2: Write the failing guard test**

Add to `src/main/java/com/calit/StartupSecretCheck.java` validation — but first prove it's wired. In `StartupSecretCheck.java`, add a field and a `validate` call:

Replace the two-field block:

```java
    @ConfigProperty(name = "google.oauth.state-secret")
    String stateSecret;

    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    String sessionKey;

    void onStart(@Observes StartupEvent ev) {
        // HMAC key for the OAuth CSRF state; >=32 chars (e.g. `openssl rand -hex 32`).
        validate("GOOGLE_OAUTH_STATE_SECRET", stateSecret, 32);
        // Quarkus form-auth cookie encryption key; framework minimum is 16 chars.
        validate("SESSION_ENCRYPTION_KEY", sessionKey, 16);
    }
```

with:

```java
    @ConfigProperty(name = "google.oauth.state-secret")
    String stateSecret;

    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    String sessionKey;

    @ConfigProperty(name = "token.encryption-key")
    String tokenKey;

    void onStart(@Observes StartupEvent ev) {
        // HMAC key for the OAuth CSRF state; >=32 chars (e.g. `openssl rand -hex 32`).
        validate("GOOGLE_OAUTH_STATE_SECRET", stateSecret, 32);
        // Quarkus form-auth cookie encryption key; framework minimum is 16 chars.
        validate("SESSION_ENCRYPTION_KEY", sessionKey, 16);
        // AES-256-GCM token key; 64 hex chars = 32 bytes (SEC-SECRET-02).
        validate("TOKEN_ENCRYPTION_KEY", tokenKey, 64);
    }
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. (The guard only runs under `@IfBuildProfile("prod")`; the dev default keeps `%test` booting. A prod-boot test is impractical in this suite — the existing class is already covered by manual prod verification; this mirrors it.)

- [ ] **Step 4: Document the env var**

In `.env.example` and `README.md`, add `TOKEN_ENCRYPTION_KEY` next to `SESSION_ENCRYPTION_KEY`:

```
# AES-256-GCM key for Google OAuth tokens at rest. 64 hex chars (openssl rand -hex 32). Required in prod.
TOKEN_ENCRYPTION_KEY=
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.properties src/main/java/com/calit/StartupSecretCheck.java .env.example README.md
git commit -m "feat(security): TOKEN_ENCRYPTION_KEY config + prod fail-closed guard (SEC-SECRET-02)"
```

### 2c — Converter + widen columns + apply to entity

**Files:**
- Create: `src/main/java/com/calit/crypto/EncryptedStringConverter.java`
- Create: `src/main/resources/db/migration/V12__google_token_length.sql`
- Modify: `src/main/java/com/calit/google/GoogleCredential.java:29-34`
- Test: `src/test/java/com/calit/crypto/TokenEncryptionAtRestTest.java`

- [ ] **Step 1: Write the failing at-rest test**

Create `src/test/java/com/calit/crypto/TokenEncryptionAtRestTest.java`:

```java
package com.calit.crypto;

import com.calit.google.GoogleCredential;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TokenEncryptionAtRestTest {

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void refreshTokenIsCiphertextInTheRowButPlaintextViaEntity() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;                 // admin is always id 1 in tests
        c.googleSub = "sub-enc-test";
        c.refreshToken = "1//super-secret-refresh";
        c.accessToken = "ya29.access-secret";
        c.accessTokenExpiry = Instant.now().plusSeconds(3600);
        c.persist();
        c.flush();

        // Raw column holds ciphertext (the marker), not the token.
        Object raw = em.createNativeQuery(
                "select refresh_token from google_credential where id = :id")
                .setParameter("id", c.id)
                .getSingleResult();
        assertTrue(raw.toString().startsWith("enc:v1:"), "stored token must be encrypted");
        assertFalse(raw.toString().contains("super-secret-refresh"), "plaintext must not be at rest");

        // Reading back through the entity transparently decrypts.
        GoogleCredential reloaded = GoogleCredential.findById(c.id);
        assertEquals("1//super-secret-refresh", reloaded.refreshToken);
        assertEquals("ya29.access-secret", reloaded.accessToken);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TokenEncryptionAtRestTest`
Expected: FAIL — raw column still contains `super-secret-refresh` (no converter yet).

- [ ] **Step 3: Write the converter**

Create `src/main/java/com/calit/crypto/EncryptedStringConverter.java`:

```java
package com.calit.crypto;

import jakarta.inject.Inject;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently encrypts a String column at rest via {@link TokenCipher} (SEC-SECRET-02).
 * Quarkus enables CDI injection into JPA converters, so {@link TokenCipher} is injected.
 * {@code autoApply=false} — applied explicitly with {@code @Convert} only on token columns.
 */
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Inject
    TokenCipher cipher;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData); // legacy plaintext passes through unchanged
    }
}
```

- [ ] **Step 4: Widen the token columns (new Flyway migration)**

Ciphertext is longer than plaintext; columns are already `text`, but pin intent and ensure no length cap. Create `src/main/resources/db/migration/V12__google_token_length.sql`:

```sql
-- SEC-SECRET-02: Google OAuth tokens are now stored AES-256-GCM-encrypted (base64 + marker),
-- which is longer than the raw token. Columns are already TEXT (unbounded), so this migration
-- is a documented no-op guard that fails loudly if a future change narrows them.
DO $$
BEGIN
    IF (SELECT data_type FROM information_schema.columns
        WHERE table_name = 'google_credential' AND column_name = 'refresh_token') <> 'text' THEN
        RAISE EXCEPTION 'refresh_token must remain TEXT to hold encrypted tokens';
    END IF;
END $$;
```

- [ ] **Step 5: Apply the converter on the entity**

In `src/main/java/com/calit/google/GoogleCredential.java`, add the import and `@Convert` annotations. Change lines 28-34 from:

```java
    /** Long-lived offline refresh token. Obtained once during the consent flow. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    public String refreshToken;

    /** Short-lived access token, refreshed on demand. Null until first refresh. */
    @Column(name = "access_token", columnDefinition = "text")
    public String accessToken;
```

to:

```java
    /** Long-lived offline refresh token. Obtained once during the consent flow. Encrypted at rest. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String refreshToken;

    /** Short-lived access token, refreshed on demand. Null until first refresh. Encrypted at rest. */
    @Column(name = "access_token", columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String accessToken;
```

Add these imports to the entity:

```java
import com.calit.crypto.EncryptedStringConverter;
import jakarta.persistence.Convert;
```

- [ ] **Step 6: Run to verify it passes**

Run: `mvn test -Dtest=TokenEncryptionAtRestTest`
Expected: PASS — raw column starts with `enc:v1:`; entity returns plaintext.

- [ ] **Step 7: Run the Google token suite to confirm no regression**

Run: `mvn test -Dtest=GoogleTokenServiceTest`
Expected: PASS (existing token persist/refresh behavior unchanged — converter is transparent).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/calit/crypto/EncryptedStringConverter.java \
        src/main/resources/db/migration/V12__google_token_length.sql \
        src/main/java/com/calit/google/GoogleCredential.java \
        src/test/java/com/calit/crypto/TokenEncryptionAtRestTest.java
git commit -m "feat(security): encrypt Google OAuth tokens at rest via JPA converter (SEC-SECRET-02)"
```

### 2d — Backfill existing plaintext rows (preserves connected calendars)

**Files:**
- Create: `src/main/java/com/calit/crypto/TokenBackfill.java`

This rewrites pre-migration plaintext rows as ciphertext **once** at startup, reading/writing raw columns so it bypasses the converter and can detect what's already encrypted. Idempotent. Tokens are never changed — only encrypted — so calendars stay connected.

- [ ] **Step 1: Write the backfill**

Create `src/main/java/com/calit/crypto/TokenBackfill.java`:

```java
package com.calit.crypto;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * One-time, idempotent backfill: encrypt any Google token rows still stored as plaintext
 * (rows written before SEC-SECRET-02 shipped). Runs at startup on every replica but only
 * rewrites rows lacking the ciphertext marker, so it is safe to run repeatedly and converges.
 * Tokens are re-encrypted, never rotated — every already-connected calendar keeps working.
 */
@ApplicationScoped
public class TokenBackfill {

    private static final Logger LOG = Logger.getLogger(TokenBackfill.class);

    @Inject
    EntityManager em;

    @Inject
    TokenCipher cipher;

    void onStart(@Observes StartupEvent ev) {
        encryptLegacy();
    }

    @Transactional
    void encryptLegacy() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "select id, refresh_token, access_token from google_credential").getResultList();
        int migrated = 0;
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String refresh = (String) row[1];
            String access = (String) row[2];
            boolean changed = false;
            if (refresh != null && !cipher.looksEncrypted(refresh)) {
                refresh = cipher.encrypt(refresh);
                changed = true;
            }
            if (access != null && !cipher.looksEncrypted(access)) {
                access = cipher.encrypt(access);
                changed = true;
            }
            if (changed) {
                em.createNativeQuery(
                        "update google_credential set refresh_token = :r, access_token = :a where id = :id")
                        .setParameter("r", refresh)
                        .setParameter("a", access)
                        .setParameter("id", id)
                        .executeUpdate();
                migrated++;
            }
        }
        if (migrated > 0) {
            LOG.infof("Encrypted %d legacy Google token row(s) at rest (SEC-SECRET-02).", migrated);
        }
    }
}
```

> Multi-replica note: each replica runs this at boot. The `looksEncrypted` skip makes concurrent runs converge harmlessly — a row already encrypted by another replica is left alone. No leader election needed (consistent with the scheduler design).

- [ ] **Step 2: Add a backfill test (legacy row gets encrypted)**

Append to `src/test/java/com/calit/crypto/TokenEncryptionAtRestTest.java` a method that writes a raw plaintext row, runs the backfill, and asserts it becomes ciphertext:

```java
    @Inject
    com.calit.crypto.TokenBackfill backfill;

    @Test
    @Transactional
    void backfillEncryptsLegacyPlaintextRow() {
        // Simulate a pre-migration row by writing raw plaintext past the converter.
        em.createNativeQuery("insert into google_credential " +
                "(owner_id, refresh_token, access_token, google_sub, needs_reconnect) " +
                "values (1, 'legacy-plain-refresh', 'legacy-plain-access', 'sub-legacy', false)")
                .executeUpdate();

        backfill.encryptLegacy();

        Object raw = em.createNativeQuery(
                "select refresh_token from google_credential where google_sub = 'sub-legacy'")
                .getSingleResult();
        assertTrue(raw.toString().startsWith("enc:v1:"), "legacy row must be encrypted after backfill");
    }
```

- [ ] **Step 3: Run the at-rest suite**

Run: `mvn test -Dtest=TokenEncryptionAtRestTest`
Expected: PASS (both methods).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/calit/crypto/TokenBackfill.java src/test/java/com/calit/crypto/TokenEncryptionAtRestTest.java
git commit -m "feat(security): startup backfill encrypts legacy plaintext tokens, keeps calendars connected (SEC-SECRET-02)"
```

---

## Task 3: Last-admin / self-lockout guard (SEC-AUTHZ-01)

**Files:**
- Modify: `src/main/java/com/calit/web/UsersResource.java:93-118`
- Test: `src/test/java/com/calit/web/AdminLockoutGuardTest.java`

Reject revoking-own-admin, locking-self, or removing the last enabled admin. Re-render with an error instead of mutating.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/web/AdminLockoutGuardTest.java`:

```java
package com.calit.web;

import com.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminLockoutGuardTest {

    // The sole admin (id 1) tries to strip their own admin role.
    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void soleAdminCannotRevokeOwnAdmin() {
        given().contentType("application/x-www-form-urlencoded")
                .when().post("/me/users/1/revoke-admin")
                .then().statusCode(200)
                .body(containsString("last enabled admin"));
        assertStillAdminAndEnabled();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void soleAdminCannotLockSelf() {
        given().contentType("application/x-www-form-urlencoded")
                .when().post("/me/users/1/lock")
                .then().statusCode(200)
                .body(containsString("cannot lock your own"));
        assertStillAdminAndEnabled();
    }

    @Transactional
    void assertStillAdminAndEnabled() {
        AppUser admin = AppUser.findById(1L);
        org.junit.jupiter.api.Assertions.assertTrue(admin.isAdmin, "admin role must be intact");
        org.junit.jupiter.api.Assertions.assertTrue(admin.enabled, "account must stay enabled");
    }
}
```

> `@TestSecurity(user = "admin", ...)` makes the principal name `"admin"`; the seeded admin (id 1) has username `admin`, so current-principal lookup resolves to id 1. Confirm the seed username with `grep -rn "admin" src/test/.../DatabaseResetCallback*` if the assertion on principal identity fails; adjust the `user=` value to match the seeded admin username.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=AdminLockoutGuardTest`
Expected: FAIL — revoke/lock currently succeed, no guard message.

- [ ] **Step 3: Add the guard helpers and apply them**

In `src/main/java/com/calit/web/UsersResource.java`, add imports:

```java
import io.quarkus.security.identity.SecurityIdentity;
```

Inject the identity (next to the other `@Inject` fields):

```java
    @Inject
    SecurityIdentity identity;
```

Add helper methods (above `grantAdmin`):

```java
    /** The currently-authenticated admin's own AppUser row (principal name == username). */
    private AppUser currentUser() {
        return AppUser.find("username", identity.getPrincipal().getName()).firstResult();
    }

    /** Count of admins that can still log in — the invariant we must never drive to zero. */
    private static long enabledAdminCount() {
        return AppUser.count("isAdmin = true and enabled = true");
    }

    private boolean isSelf(Long targetId) {
        AppUser me = currentUser();
        return me != null && me.id.equals(targetId);
    }
```

Replace `revokeAdmin` and `lock` with guarded versions:

```java
    @POST
    @Path("/{id}/revoke-admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance revokeAdmin(@PathParam("id") Long id) {
        AppUser target = requireUser(id);
        // Block removing the last enabled admin — there is no in-app recovery path (SEC-AUTHZ-01).
        if (target.isAdmin && enabledAdminCount() <= 1) {
            return render("Cannot revoke admin from the last enabled admin.");
        }
        target.setAdmin(false);
        return render(null);
    }

    @POST
    @Path("/{id}/lock")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance lock(@PathParam("id") Long id) {
        if (isSelf(id)) {
            return render("You cannot lock your own account.");
        }
        AppUser target = requireUser(id);
        // Locking the last enabled admin also destroys admin capability (SEC-AUTHZ-01).
        if (target.isAdmin && target.enabled && enabledAdminCount() <= 1) {
            return render("Cannot lock the last enabled admin.");
        }
        target.enabled = false;
        return render(null);
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=AdminLockoutGuardTest`
Expected: PASS (both methods); admin stays admin + enabled.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/UsersResource.java src/test/java/com/calit/web/AdminLockoutGuardTest.java
git commit -m "fix(security): block self-lockout and last-admin removal (SEC-AUTHZ-01)"
```

---

## Task 4: Invitee email validation + ICS CRLF escaping (SEC-INPUT-01)

**Files:**
- Modify: `src/main/java/com/calit/booking/BookingService.java` (early in `book`, ~line 142-156)
- Modify: `src/main/java/com/calit/email/IcsBuilder.java:36,44`
- Test: `src/test/java/com/calit/booking/InviteeEmailValidationTest.java`
- Test: `src/test/java/com/calit/email/IcsBuilderEscapeTest.java`

### 4a — Reject CRLF/oversized/multi-address invitee email

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/booking/InviteeEmailValidationTest.java`:

```java
package com.calit.booking;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class InviteeEmailValidationTest {

    @Inject
    BookingService bookingService;

    @Test
    void rejectsCrlfInjectionInEmail() {
        // Header/BCC smuggling attempt must be rejected before persist (SEC-INPUT-01).
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "intro", Instant.parse("2099-01-01T10:00:00Z"),
                        "Mallory", "a@b.com\r\nBcc: attacker@evil.com", Map.of(), null, null));
    }

    @Test
    void rejectsOversizedEmail() {
        String huge = "x".repeat(250) + "@b.com"; // > 254
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "intro", Instant.parse("2099-01-01T10:00:00Z"),
                        "Mallory", huge, Map.of(), null, null));
    }

    @Test
    void rejectsMalformedEmail() {
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "intro", Instant.parse("2099-01-01T10:00:00Z"),
                        "Mallory", "not-an-email", Map.of(), null, null));
    }
}
```

> `"intro"` is the default seeded meeting-type slug for the admin. Confirm via `grep -rn "slug" src/main/java/com/calit/availability/DefaultAvailabilitySeeder.java`; substitute the actual seeded slug if different.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=InviteeEmailValidationTest`
Expected: FAIL — no validation, booking proceeds (or fails for a different reason).

- [ ] **Step 3: Add validation at the top of `book`**

In `src/main/java/com/calit/booking/BookingService.java`, add a private validator and call it first thing in `book` (before `enforcePerEmailDailyCap`). Add this method to the class:

```java
    // RFC-pragmatic single-address check: one @, no CRLF/comma, <=254 chars. Not a full RFC 5322
    // parser — just enough to stop header/ICS injection and obvious malformed input (SEC-INPUT-01).
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^\\s@,]+@[^\\s@,]+\\.[^\\s@,]+$");

    private static void validateInviteeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BookingValidationException("Email is required.");
        }
        if (email.length() > 254) {
            throw new BookingValidationException("Email is too long.");
        }
        if (email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0) {
            throw new BookingValidationException("Email contains illegal characters.");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw new BookingValidationException("Enter a valid email address.");
        }
    }
```

In `book(...)`, add as the first statement of the method body:

```java
        validateInviteeEmail(inviteeEmail);
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=InviteeEmailValidationTest`
Expected: PASS (3 tests).

### 4b — CRLF-escape every ICS-interpolated value

- [ ] **Step 5: Write the failing ICS test**

Create `src/test/java/com/calit/email/IcsBuilderEscapeTest.java`:

```java
package com.calit.email;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;

class IcsBuilderEscapeTest {

    @Test
    void organizerAndUidCannotInjectNewIcsLines() {
        // Even if a hostile value reaches the builder, no raw CRLF may break out of the line
        // and forge new ICS properties (SEC-INPUT-01).
        String ics = IcsBuilder.build(
                "uid\r\nX-EVIL:1",
                "Meeting",
                null,
                "organizer@x.com\r\nATTENDEE:mailto:victim@y.com",
                Instant.parse("2099-01-01T10:00:00Z"),
                Instant.parse("2099-01-01T10:30:00Z"));
        assertFalse(ics.contains("X-EVIL:1"), "UID must not inject a new property line");
        assertFalse(ics.contains("ATTENDEE:mailto:victim@y.com"), "ORGANIZER must not inject a line");
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `mvn test -Dtest=IcsBuilderEscapeTest`
Expected: FAIL — UID and ORGANIZER are appended raw.

- [ ] **Step 7: Escape UID and ORGANIZER in `IcsBuilder`**

In `src/main/java/com/calit/email/IcsBuilder.java`, update the UID line (line 36) and ORGANIZER line (line 44), and extend `escape` to drop carriage returns. Change:

```java
        sb.append("UID:").append(uid).append("\r\n");
```

to:

```java
        sb.append("UID:").append(escape(uid)).append("\r\n");
```

Change:

```java
        sb.append("ORGANIZER:mailto:").append(organizerEmail).append("\r\n");
```

to:

```java
        sb.append("ORGANIZER:mailto:").append(escape(organizerEmail)).append("\r\n");
```

Update `escape` to also neutralize `\r` (currently only `\n`):

```java
    /** RFC 5545 text escaping for any interpolated value; also strips CR so no value can inject a line. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
```

- [ ] **Step 8: Run to verify it passes**

Run: `mvn test -Dtest=IcsBuilderEscapeTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/calit/booking/BookingService.java \
        src/main/java/com/calit/email/IcsBuilder.java \
        src/test/java/com/calit/booking/InviteeEmailValidationTest.java \
        src/test/java/com/calit/email/IcsBuilderEscapeTest.java
git commit -m "fix(security): validate invitee email + CRLF-escape all ICS values (SEC-INPUT-01)"
```

---

## Task 5: Bound free-text booking inputs (SEC-INPUT-02)

**Files:**
- Modify: `src/main/java/com/calit/booking/BookingService.java` (the validator from Task 4)
- Test: extend `src/test/java/com/calit/booking/InviteeEmailValidationTest.java`

- [ ] **Step 1: Write the failing test**

Add to `InviteeEmailValidationTest`:

```java
    @Test
    void rejectsOversizedInviteeName() {
        String longName = "n".repeat(201); // cap is 200
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "intro", Instant.parse("2099-01-01T10:00:00Z"),
                        longName, "a@b.com", Map.of(), null, null));
    }

    @Test
    void rejectsOversizedAnswer() {
        String longAnswer = "x".repeat(2001); // cap is 2000
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "intro", Instant.parse("2099-01-01T10:00:00Z"),
                        "Bob", "a@b.com", Map.of("note", longAnswer), null, null));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=InviteeEmailValidationTest`
Expected: FAIL — name/answer unbounded.

- [ ] **Step 3: Add the bounds check**

In `BookingService.java`, add a validator and call it right after `validateInviteeEmail(inviteeEmail);` in `book`:

```java
    private static void validateInputBounds(String inviteeName, Map<String, String> answers) {
        if (inviteeName == null || inviteeName.isBlank()) {
            throw new BookingValidationException("Name is required.");
        }
        if (inviteeName.length() > 200) {
            throw new BookingValidationException("Name is too long.");
        }
        if (answers != null) {
            for (String v : answers.values()) {
                if (v != null && v.length() > 2000) {
                    throw new BookingValidationException("An answer is too long.");
                }
            }
        }
    }
```

Call (immediately after the email validation line in `book`):

```java
        validateInputBounds(inviteeName, submitted);
```

> Use the same parameter name the method uses for the answers map. In `book` the answers param is the `Map<String,String>` argument — match its actual name (`submitted` per the persist site `validateRequiredFields(type, submitted)`; if the param is named differently, use that name).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=InviteeEmailValidationTest`
Expected: PASS (all 5 methods).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/BookingService.java src/test/java/com/calit/booking/InviteeEmailValidationTest.java
git commit -m "fix(security): bound invitee name and answer lengths (SEC-INPUT-02)"
```

---

## Task 6: MeOwnerFilter fail-closed (SEC-AUTHZ-03)

**Files:**
- Modify: `src/main/java/com/calit/web/MeOwnerFilter.java`

When an authenticated principal has no `AppUser` row, abort 401 instead of returning silently.

- [ ] **Step 1: Read the current filter**

Run: `sed -n '1,60p' src/main/java/com/calit/web/MeOwnerFilter.java`
Locate the branch where, after looking up the `AppUser` by principal, it `return;`s without setting `CurrentOwner`.

- [ ] **Step 2: Replace the silent return with an explicit 401 abort**

In the branch that currently returns when the user row is missing, replace `return;` with an abort. Add the import if absent:

```java
import jakarta.ws.rs.core.Response;
```

and change the missing-user branch to:

```java
            // Authenticated principal with no backing AppUser row — fail closed (SEC-AUTHZ-03)
            // rather than relying on downstream null-handling.
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
            return;
```

(`ctx` is the `ContainerRequestContext` parameter of `filter` — use its actual parameter name.)

- [ ] **Step 3: Run the existing /me filter tests + a broad slice**

Run: `mvn test -Dtest=*MeOwner*,*AdminResource*`
Expected: PASS — the augmentor makes this branch near-unreachable in practice, so existing tests stay green; the change only hardens the edge case.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/calit/web/MeOwnerFilter.java
git commit -m "fix(security): MeOwnerFilter fails closed with 401 on missing user (SEC-AUTHZ-03)"
```

---

## Task 7: OAuth callback error reflection (SEC-INPUT-03)

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleOAuthResource.java:45-48`

Stop reflecting the raw `?error=` param; log server-side, return a fixed message, add `nosniff`.

- [ ] **Step 1: Replace the reflected error response**

In `src/main/java/com/calit/google/GoogleOAuthResource.java`, change:

```java
        if (error != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Google authorization failed: " + error)
                    .build();
        }
```

to:

```java
        if (error != null) {
            // Do not reflect attacker-controlled ?error= into the response (SEC-INPUT-03).
            LOG.warnf("Google OAuth callback returned error: %s", error);
            return Response.status(Response.Status.BAD_REQUEST)
                    .header("X-Content-Type-Options", "nosniff")
                    .entity("Google authorization failed. Please try connecting again.")
                    .build();
        }
```

Add a logger field to the class if absent:

```java
    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(GoogleOAuthResource.class);
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run any OAuth resource tests**

Run: `mvn test -Dtest=*GoogleOAuth*,*GoogleCallback*`
Expected: PASS (or "no tests" — then this is covered by compile + manual check).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/calit/google/GoogleOAuthResource.java
git commit -m "fix(security): fixed OAuth callback error message + nosniff, no reflection (SEC-INPUT-03)"
```

---

## Task 8: Outbound HTTP timeouts (SEC-SSRF-01)

**Files:**
- Modify: `src/main/java/com/calit/booking/TurnstileVerifier.java:36`
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java:216`
- Modify: `src/main/java/com/calit/google/GoogleCalendarClientFactory.java:34-35`

Add connect/read timeouts so a hung upstream can't pin the synchronous booking path.

- [ ] **Step 1: Inspect the three clients**

Run: `sed -n '30,45p' src/main/java/com/calit/booking/TurnstileVerifier.java; echo '---'; sed -n '205,225p' src/main/java/com/calit/google/GoogleTokenService.java; echo '---'; sed -n '25,45p' src/main/java/com/calit/google/GoogleCalendarClientFactory.java`
Identify whether each uses `java.net.http.HttpClient` or the Google client's `HttpRequestInitializer`.

- [ ] **Step 2: Set timeouts on each `HttpClient` (java.net.http)**

For each `HttpClient.newBuilder()` / `HttpClient.newHttpClient()` in `TurnstileVerifier` and `GoogleTokenService`, add a connect timeout and follow-redirect policy:

```java
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
```

and on each `HttpRequest.newBuilder()...` add a per-request timeout:

```java
                .timeout(java.time.Duration.ofSeconds(10))
```

- [ ] **Step 3: Set timeouts on the Google API client**

In `GoogleCalendarClientFactory.java`, where the request initializer / transport is built, wrap it so each request gets connect+read timeouts. If it uses `HttpRequestInitializer`:

```java
        HttpRequestInitializer withTimeouts = request -> {
            if (delegate != null) {
                delegate.initialize(request);
            }
            request.setConnectTimeout(5000); // ms
            request.setReadTimeout(10000);    // ms
        };
```

and pass `withTimeouts` where the original initializer was used. (`delegate` is the existing credential initializer; match the actual variable.)

- [ ] **Step 4: Compile + run Turnstile/Google client tests**

Run: `mvn test -Dtest=*Turnstile*,*GoogleTokenService*,*GoogleCalendar*`
Expected: PASS — behavior unchanged for healthy upstreams.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/TurnstileVerifier.java \
        src/main/java/com/calit/google/GoogleTokenService.java \
        src/main/java/com/calit/google/GoogleCalendarClientFactory.java
git commit -m "fix(security): connect/read timeouts + redirect policy on outbound HTTP (SEC-SSRF-01)"
```

---

## Task 9: CSRF tokens on authenticated POSTs (SEC-SECRET-04)

**Files:**
- Modify: `pom.xml`
- Modify: every authenticated `@POST` Qute form under `src/main/resources/templates/**`
- Reference: Quarkus REST CSRF guide

> Use the find-docs / context7 skill for the exact `quarkus-rest-csrf` config and the Qute token tag before implementing — the extension's defaults and the form helper name (`{inject:csrf}` vs `{csrf}`) matter.

- [ ] **Step 1: Add the extension**

In `pom.xml`, add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-csrf</artifactId>
        </dependency>
```

- [ ] **Step 2: Configure CSRF (cookie, form field)**

In `src/main/resources/application.properties`, add:

```properties
# CSRF defense-in-depth for authenticated state-changing POSTs (SEC-SECRET-04). SameSite=Lax stays.
quarkus.rest-csrf.form-field-name=csrf-token
quarkus.rest-csrf.cookie-name=csrf-token
quarkus.rest-csrf.cookie-http-only=true
%prod.quarkus.rest-csrf.cookie-secure=true
```

- [ ] **Step 3: Enumerate the forms to patch**

Run: `grep -rln "method=\"post\"\|method='post'" src/main/resources/templates`
For EACH authenticated form (everything under `/me`, plus `SetupResource`, `GoogleCalendarResource` forms — NOT the public booking form unless it posts to an authenticated route), add the hidden CSRF field as the first child of `<form>`:

```html
            <input type="hidden" name="csrf-token" value="{inject:csrf}">
```

> Verify the correct tag name against the Quarkus REST CSRF docs (Step 0). Public, unauthenticated booking POSTs (`/{user}/{slug}`) typically should NOT require a session CSRF token; confirm the extension's `require-form-url-encoded` / path config so the public booking flow is not broken. Scope CSRF enforcement to authenticated paths if the public form would otherwise break.

- [ ] **Step 4: Run the full web POST suite**

Run: `mvn test -Dtest=*Resource*`
Expected: PASS. If authenticated-POST tests now 400 on missing token, update those tests to fetch the form (GET) and submit the returned token, mirroring real browser flow. If the public booking test breaks, narrow CSRF config to authenticated paths.

- [ ] **Step 5: Manual smoke (optional but recommended)**

Run `mvn quarkus:dev`, log in, submit a `/me` form — confirm it succeeds with the hidden field and 400s without it.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/resources/templates
git commit -m "feat(security): CSRF tokens on authenticated POST forms (SEC-SECRET-04)"
```

---

## Task 10: Audit logging of privileged actions (SEC-SECRET-05)

**Files:**
- Create: `src/main/java/com/calit/audit/AuditLog.java`
- Modify: `src/main/java/com/calit/web/UsersResource.java` (admin mutations)
- Modify: the login failure path (whichever class handles failed auth — locate first)

- [ ] **Step 1: Create the audit emitter**

Create `src/main/java/com/calit/audit/AuditLog.java`:

```java
package com.calit.audit;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Structured audit events for security-relevant actions (SEC-SECRET-05): privileged mutations and
 * auth outcomes. Never logs secrets — only actor / action / target / source IP. Emitted to the app
 * log under a dedicated category so log aggregation can route/retain them separately.
 */
@ApplicationScoped
public class AuditLog {

    private static final Logger LOG = Logger.getLogger("audit");

    public void event(String actor, String action, String target, String sourceIp) {
        LOG.infof("AUDIT actor=%s action=%s target=%s ip=%s",
                safe(actor), safe(action), safe(target), safe(sourceIp));
    }

    private static String safe(String s) {
        if (s == null) {
            return "-";
        }
        return s.replace('\n', ' ').replace('\r', ' '); // no log injection
    }
}
```

- [ ] **Step 2: Emit on admin mutations**

In `UsersResource.java`, inject `AuditLog` and emit after each successful privileged mutation (`create`, `grantAdmin`, `revokeAdmin`, `lock`, `unlock`). Example for `grantAdmin`:

```java
        requireUser(id).setAdmin(true);
        audit.event(identity.getPrincipal().getName(), "grant-admin", "user:" + id, null);
        return render(null);
```

Add the field:

```java
    @Inject
    com.calit.audit.AuditLog audit;
```

Repeat the `audit.event(...)` call (with the matching action string) in `create`, `revokeAdmin`, `lock`, `unlock`.

- [ ] **Step 3: Emit on failed login**

Run: `grep -rln "j_security_check\|AuthenticationFailed\|login" src/main/java/com/calit/user`
In the auth-failure handler (or an `@Observes` for Quarkus security `AuthenticationFailureEvent` if available), emit `audit.event(attemptedUsername, "login-failed", "-", sourceIp)`. If no single hook exists, add a JAX-RS exception path or a `SecurityIdentityAugmentor` log; keep it best-effort and non-blocking.

- [ ] **Step 4: Compile + run web suite**

Run: `mvn test -Dtest=*UsersResource*,*Login*`
Expected: PASS (audit calls are side-effecting logs; no behavior change).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/audit/AuditLog.java src/main/java/com/calit/web/UsersResource.java src/main/java/com/calit/user
git commit -m "feat(security): structured audit log for admin actions + failed logins (SEC-SECRET-05)"
```

---

## Task 11: Document/test both booking entry points share guards (SEC-AUTHZ-02)

**Files:**
- Modify: `src/main/java/com/calit/booking/BookingResource.java` (doc comment)
- Test: add to an existing booking test class

No behavior change — lock in the invariant that `/api/bookings` and the web form funnel through identical `BookingService.book` guards (including the Task 4/5 validation).

- [ ] **Step 1: Add a regression test**

In the existing API booking test (find it: `grep -rln "api/bookings" src/test`), add a test that the JSON API rejects a CRLF-injected email exactly like the web flow:

```java
    @Test
    void apiBookingRejectsCrlfEmailLikeWebFlow() {
        given().contentType("application/json")
                .body("{\"user\":\"admin\",\"slug\":\"intro\",\"startUtc\":\"2099-01-01T10:00:00Z\"," +
                      "\"inviteeName\":\"Mallory\",\"inviteeEmail\":\"a@b.com\\r\\nBcc: x@evil.com\"}")
                .when().post("/api/bookings")
                .then().statusCode(422); // BookingValidationException -> 422 via its mapper
    }
```

> Adjust the JSON shape to the actual `BookRequest` record fields and the mapper's status code (confirm via the `BookingValidationException` mapper).

- [ ] **Step 2: Run to verify it passes**

Run: `mvn test -Dtest=*BookingResource*`
Expected: PASS — the shared `BookingService.book` validation (Task 4) already covers the API path.

- [ ] **Step 3: Document the invariant**

In `BookingResource.java`, add a class doc comment:

```java
/**
 * Public JSON booking API. SECURITY: every mutation MUST route through {@link BookingService} so the
 * web form and this API share one set of guards (validation, abuse limits, conflict checks). Do not
 * add booking logic here — add it to BookingService or it will silently bypass this entry point
 * (SEC-AUTHZ-02).
 */
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/calit/booking/BookingResource.java src/test
git commit -m "test(security): assert API + web booking share guards; document invariant (SEC-AUTHZ-02)"
```

---

## Task 12: SCA / vulnerability scanning in CI (SEC-DEP-01, folds in SEC-DEP-04)

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.github/dependabot.yml`
- Create: `.github/workflows/codeql.yml`
- Modify: `renovate.json`

- [ ] **Step 1: Add a blocking Trivy fs scan job to CI**

In `.github/workflows/ci.yml`, add a job (runs alongside `test`):

```yaml
  scan:
    name: Vulnerability scan (Trivy)
    runs-on: ubuntu-latest
    steps:
      - name: Check out the repo
        uses: actions/checkout@v6
      - name: Trivy filesystem scan (fail on HIGH/CRITICAL)
        uses: aquasecurity/trivy-action@0.28.0
        with:
          scan-type: fs
          scan-ref: .
          severity: HIGH,CRITICAL
          exit-code: '1'
          ignore-unfixed: true
```

> Pin the `trivy-action` to its current release tag (check the action repo). `ignore-unfixed: true` avoids failing on CVEs with no available fix; drop it for stricter gating.

- [ ] **Step 2: Add a Trivy image scan after the image build**

In the `build` job (after the image is built, before push, or against the built digest), add a step that scans the built image with the same `severity`/`exit-code`. If scanning by digest is awkward in the matrix, add a dedicated post-`merge` image-scan job pulling the merged tag and failing on HIGH/CRITICAL.

- [ ] **Step 3: Create `.github/dependabot.yml`**

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: "/"
    schedule:
      interval: weekly
  - package-ecosystem: github-actions
    directory: "/"
    schedule:
      interval: weekly
  - package-ecosystem: docker
    directory: "/"
    schedule:
      interval: weekly
```

> Renovate already raises update PRs; Dependabot here is enabled primarily for its **security alerts**. Avoid duplicate version-bump noise by keeping Renovate as the bump driver and relying on Dependabot/GitHub for the alert feed, or disable Dependabot version updates and enable only alerts in repo settings.

- [ ] **Step 4: Create `.github/workflows/codeql.yml`**

```yaml
name: CodeQL
on:
  push:
    branches: [main]
  pull_request: {}
  schedule:
    - cron: '0 3 * * 1'
permissions:
  contents: read
  security-events: write
jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with:
          distribution: liberica
          java-version: '26'
      - uses: github/codeql-action/init@v3
        with:
          languages: java-kotlin
      - name: Build (no tests)
        run: ./mvnw -B -DskipTests package
      - uses: github/codeql-action/analyze@v3
```

> Match the JDK setup to the `test` job's existing `setup-java` config (distribution/version).

- [ ] **Step 5: Enable Renovate vulnerability alerts**

In `renovate.json`, add:

```json
  "vulnerabilityAlerts": { "enabled": true }
```

- [ ] **Step 6: Validate YAML locally**

Run: `for f in .github/workflows/ci.yml .github/workflows/codeql.yml .github/dependabot.yml; do python3 -c "import yaml,sys; yaml.safe_load(open('$f'))" && echo "OK $f"; done`
Expected: `OK` for all three.

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/codeql.yml .github/dependabot.yml renovate.json
git commit -m "ci(security): Trivy scan gate + CodeQL + Dependabot/Renovate alerts (SEC-DEP-01)"
```

---

## Task 13: Container hardening — digest pins + non-root (SEC-DEP-02, SEC-DEP-05)

**Files:**
- Modify: `Dockerfile`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add a non-root USER to the runtime stage**

First verify the Liberica runtime base's available non-root user/UID:

Run: `docker run --rm bellsoft/liberica-runtime-container:jre-26-musl id 2>/dev/null || echo "check base default user"`

In `Dockerfile`, in the `runtime` stage, after the `COPY` lines and before `ENTRYPOINT`, add ownership + non-root user:

```dockerfile
# Run as non-root (SEC-DEP-05). UID 1001 owns the app dir; no shell/root needed at runtime.
RUN chown -R 1001:1001 /app
USER 1001
```

> If the musl base lacks `chown`/a writable user DB, use a numeric `USER 1001` and ensure `/app` is world-readable (the fast-jar only needs read). Verify the app still boots: `docker build` then `docker run` and hit `/q/health/ready`.

- [ ] **Step 2: Pin base images by digest**

For each `FROM` in `Dockerfile` (`oven/bun:1`, `bellsoft/liberica-runtime-container:jdk-26-musl`, `...:jre-26-musl`), resolve and append the digest:

Run (per image): `docker buildx imagetools inspect oven/bun:1 | grep Digest`
Then change e.g.:

```dockerfile
FROM bellsoft/liberica-runtime-container:jre-26-musl@sha256:<digest> AS runtime
```

Do the same for the build and css stages. Pin `postgres:18` in `docker-compose.yml` likewise:

```yaml
    image: postgres:18@sha256:<digest>
```

> Add a Renovate comment so it manages digest bumps; Renovate's docker digest pinning keeps these current.

- [ ] **Step 3: Build the image to confirm it still runs as non-root**

Run: `docker build -t calit:harden . && docker run --rm calit:harden id`
Expected: build succeeds; `id` shows `uid=1001` (not 0).

- [ ] **Step 4: Commit**

```bash
git add Dockerfile docker-compose.yml renovate.json
git commit -m "build(security): digest-pin base images + run runtime as non-root 1001 (SEC-DEP-02, SEC-DEP-05)"
```

---

## Task 14: Full suite + final verification

- [ ] **Step 1: Run the entire test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests green (Docker running).

- [ ] **Step 2: Confirm no plaintext token can be read at rest (manual)**

Run `mvn quarkus:dev`, connect a Google account (or rely on `TokenEncryptionAtRestTest`), then query the dev Postgres:
`select refresh_token from google_credential limit 1;` — expect an `enc:v1:` value, never a raw token. Confirm the connected calendar still lists/creates events (UX preserved).

- [ ] **Step 3: Confirm the audit doc is fully addressed**

Re-read `security/audit/2026-06-12-calit.md` section 6 and tick each finding against a task above. Note in the PR body: SEC-SECRET-03 was already fixed (`StartupSecretCheck`); SEC-DEP-03 needs no code change (frozen lockfile); SEC-DEP-04 is covered by the Task 12 scan gate.

- [ ] **Step 4: Open the PR**

```bash
git push -u origin security-hardening
gh pr create --title "Security audit remediation (2026-06-12 audit)" \
  --body "Implements fixes for the 2026-06-12 defensive audit. Google calendars stay connected (transparent at-rest token encryption + backfill, no re-consent). See plan: docs/superpowers/plans/2026-06-13-security-audit-remediation.md"
```

> Work on a branch — `security-hardening` already exists. Do not commit straight to `main`.

---

## Self-Review notes

- **Spec coverage:** Every audit finding maps to a task — SEC-SECRET-01→T1, -02→T2, AUTHZ-01→T3, INPUT-01→T4, INPUT-02→T5, AUTHZ-03→T6, INPUT-03→T7, SSRF-01→T8, SECRET-04→T9, SECRET-05→T10, AUTHZ-02→T11, DEP-01/04→T12, DEP-02/05→T13. SEC-SECRET-03 already done; SEC-DEP-03 no-op (documented in T14 Step 3).
- **UX preservation:** Token encryption (T2) uses tolerant-decrypt converter + idempotent backfill + key never rotated → connected calendars never disconnect or require re-consent. Verified by `TokenEncryptionAtRestTest` and T14 Step 2.
- **Type consistency:** `TokenCipher` (constructor key, `encrypt`/`decrypt`/`looksEncrypted`) is used identically by `EncryptedStringConverter` and `TokenBackfill`. `BookingValidationException` is the existing exception type used in `book`. `AuditLog.event(actor, action, target, sourceIp)` signature is consistent across T10 call sites.
- **Assumptions to verify during execution** (flagged inline): seeded meeting-type slug (`intro`), seeded admin username (`admin`), exact answers-map parameter name in `BookingService.book`, Quarkus CSRF tag name, Liberica base non-root UID, and the `BookingValidationException` mapper status code (422). Each is called out at its task.
</content>
</invoke>
