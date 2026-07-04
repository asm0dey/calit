# Pluggable CAPTCHA (ALTCHA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add self-hosted ALTCHA as a booking-form CAPTCHA option alongside Cloudflare Turnstile, selected by a global `CAPTCHA_PROVIDER` env var (`none` | `turnstile` | `altcha`).

**Architecture:** A single resolver bean picks the effective provider (back-compat: `TURNSTILE_ENABLED=true` ⇒ `turnstile`). One `CaptchaVerifier` switches on it server-side. ALTCHA challenges are minted by a new `GET /altcha/challenge` endpoint using the `org.altcha:altcha` v1 (hashcash) protocol; the widget JS is served from an mvnpm jar via `quarkus-web-dependency-locator`. The `book.html` template renders the matching widget.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute `@CheckedTemplate`, `org.altcha:altcha:2.0.2` (server), `org.mvnpm:altcha:3.1.0` (widget JS), `quarkus-web-dependency-locator`, RestAssured + `@TestProfile` tests.

## Global Constraints

- Build JDK: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` before any `./mvnw` (default mvn = JDK 21 → "release 25 not supported").
- Docker MUST be running for `mvn test` / `quarkus:dev` (Dev Services Postgres).
- Format before committing: `./mvnw spotless:apply` (or `bun run format`). CI fails on unformatted Java. Qute `.html` is NOT formatted.
- Owner-scoping does not apply — CAPTCHA is a global deploy-level concern. `AltchaResource` is intentionally public + unauthenticated.
- Booking form already carries the CSRF token; adding the `altcha` field introduces no new POST form, so no CSRF change.
- i18n: no new `@Message` keys expected. If any user-facing server string is added, its `de` AND `he` values must land in `messages/{msg,adm}_{de,he}.properties` in the same change.
- No runtime CDN: ALTCHA widget JS is self-hosted via the mvnpm jar.
- All ALTCHA use is the **v1** protocol: `org.altcha.altcha.v1.Altcha` (NOT the `v2` package in the same jar).

---

### Task 1: Dependencies + config properties

**Files:**
- Modify: `pom.xml` (add mvnpm repository + 3 dependencies)
- Modify: `src/main/resources/application.properties` (3 new properties)
- Test: `src/test/java/site/asm0dey/calit/web/AltchaStaticAssetTest.java`

**Interfaces:**
- Produces: the widget JS served at `/_static/altcha/dist/main/altcha.min.js`; config keys `calit.captcha.provider`, `calit.captcha.altcha.hmac-key`, `calit.captcha.altcha.max-number`.

- [ ] **Step 1: Write the failing test**

`src/test/java/site/asm0dey/calit/web/AltchaStaticAssetTest.java`:
```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AltchaStaticAssetTest {

    // Proves the mvnpm jar is on the classpath AND quarkus-web-dependency-locator
    // serves it at the version-less /_static/... path.
    @Test
    void altchaWidgetScriptIsServedVersionless() {
        given().when()
                .get("/_static/altcha/dist/main/altcha.min.js")
                .then()
                .statusCode(200)
                .body(containsString("altcha"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=AltchaStaticAssetTest`
Expected: FAIL — 404 (asset not on classpath yet).

- [ ] **Step 3: Add the mvnpm repository to `pom.xml`**

Add (as a sibling of `<dependencies>`, e.g. right after `</properties>`):
```xml
<repositories>
    <repository>
        <id>mvnpm.org</id>
        <name>mvnpm</name>
        <url>https://repo.mvnpm.org/maven2</url>
    </repository>
</repositories>
```
If a `<repositories>` block already exists, add just the `<repository>` entry inside it.

- [ ] **Step 4: Add the three dependencies to `pom.xml`**

Inside `<dependencies>`:
```xml
<!-- ALTCHA server-side challenge creation + verification (v1 hashcash protocol). -->
<dependency>
    <groupId>org.altcha</groupId>
    <artifactId>altcha</artifactId>
    <version>2.0.2</version>
</dependency>
<!-- ALTCHA widget JS (npm package, mirrored by mvnpm). Served at /_static/altcha/... -->
<dependency>
    <groupId>org.mvnpm</groupId>
    <artifactId>altcha</artifactId>
    <version>3.1.0</version>
    <scope>runtime</scope>
</dependency>
<!-- Version-less static paths for the mvnpm/webjar assets above. -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-web-dependency-locator</artifactId>
</dependency>
```
Note: `org.json:json` (used internally by altcha) is a `runtime`-scoped transitive dep of `org.altcha:altcha`; it lands on the runtime classpath automatically — do NOT add it explicitly.

- [ ] **Step 5: Add config properties to `application.properties`**

Add a new block (near the existing Turnstile block around line 158):
```properties
# --- CAPTCHA provider selection (global) ---
# none | turnstile | altcha. When unset, falls back to turnstile if TURNSTILE_ENABLED=true, else none.
calit.captcha.provider=${CAPTCHA_PROVIDER:}
# ALTCHA (self-hosted). hmac-key is REQUIRED when provider=altcha (startup fails otherwise).
calit.captcha.altcha.hmac-key=${ALTCHA_HMAC_KEY:}
# Upper bound the client brute-forces (proof-of-work difficulty).
calit.captcha.altcha.max-number=${ALTCHA_MAX_NUMBER:100000}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=AltchaStaticAssetTest`
Expected: PASS.

- [ ] **Step 7: Format + commit**

```bash
./mvnw -q spotless:apply
git add pom.xml src/main/resources/application.properties src/test/java/site/asm0dey/calit/web/AltchaStaticAssetTest.java
git commit -m "feat(captcha): add ALTCHA deps + config, serve widget JS via web-dependency-locator"
```

---

### Task 2: CAPTCHA provider resolver

**Files:**
- Create: `src/main/java/site/asm0dey/calit/booking/CaptchaProviderConfig.java`
- Test: `src/test/java/site/asm0dey/calit/booking/CaptchaProviderConfigTest.java`

**Interfaces:**
- Consumes: config keys from Task 1; existing `calit.turnstile.enabled`.
- Produces: `CaptchaProviderConfig` bean with `String provider()` returning `"none"` | `"turnstile"` | `"altcha"`; static `String resolve(String explicit, boolean turnstileEnabled)`.

- [ ] **Step 1: Write the failing test** (pure JUnit — no Quarkus)

`src/test/java/site/asm0dey/calit/booking/CaptchaProviderConfigTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CaptchaProviderConfigTest {

    @Test
    void explicitProviderWins() {
        assertEquals("altcha", CaptchaProviderConfig.resolve("altcha", true));
        assertEquals("turnstile", CaptchaProviderConfig.resolve("turnstile", false));
        assertEquals("none", CaptchaProviderConfig.resolve("none", true));
    }

    @Test
    void blankExplicitFallsBackToTurnstileFlag() {
        assertEquals("turnstile", CaptchaProviderConfig.resolve("", true));
        assertEquals("turnstile", CaptchaProviderConfig.resolve(null, true));
        assertEquals("none", CaptchaProviderConfig.resolve(null, false));
    }

    @Test
    void caseAndWhitespaceTolerant() {
        assertEquals("altcha", CaptchaProviderConfig.resolve("  ALTCHA ", false));
    }

    @Test
    void invalidProviderThrows() {
        assertThrows(IllegalArgumentException.class, () -> CaptchaProviderConfig.resolve("recaptcha", false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=CaptchaProviderConfigTest`
Expected: FAIL — `CaptchaProviderConfig` does not exist / does not compile.

- [ ] **Step 3: Create `CaptchaProviderConfig.java`**

```java
package site.asm0dey.calit.booking;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the effective global CAPTCHA provider. Explicit {@code CAPTCHA_PROVIDER} wins;
 * otherwise falls back to {@code turnstile} when the legacy {@code TURNSTILE_ENABLED} flag is on,
 * else {@code none}. Fails fast at startup if {@code altcha} is selected without an HMAC key.
 */
@ApplicationScoped
public class CaptchaProviderConfig {

    private static final Set<String> VALID = Set.of("none", "turnstile", "altcha");

    @ConfigProperty(name = "calit.captcha.provider")
    Optional<String> explicit;

    // Reuse the existing render flag (both turnstile flags come from ${TURNSTILE_ENABLED}).
    @ConfigProperty(name = "calit.turnstile.enabled", defaultValue = "false")
    boolean turnstileEnabled;

    @ConfigProperty(name = "calit.captcha.altcha.hmac-key")
    Optional<String> altchaHmacKey;

    public String provider() {
        return resolve(explicit.orElse(null), turnstileEnabled);
    }

    static String resolve(String explicit, boolean turnstileEnabled) {
        if (explicit != null && !explicit.isBlank()) {
            String p = explicit.trim().toLowerCase(Locale.ROOT);
            if (!VALID.contains(p)) {
                throw new IllegalArgumentException("Invalid CAPTCHA_PROVIDER: " + explicit);
            }
            return p;
        }
        return turnstileEnabled ? "turnstile" : "none";
    }

    // Fail fast: altcha with no HMAC key would silently accept forged solutions.
    void validate(@Observes StartupEvent ev) {
        if ("altcha".equals(provider()) && altchaHmacKey.filter(s -> !s.isBlank()).isEmpty()) {
            throw new IllegalStateException("CAPTCHA_PROVIDER=altcha requires ALTCHA_HMAC_KEY");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=CaptchaProviderConfigTest`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/site/asm0dey/calit/booking/CaptchaProviderConfig.java src/test/java/site/asm0dey/calit/booking/CaptchaProviderConfigTest.java
git commit -m "feat(captcha): add CaptchaProviderConfig resolver with startup validation"
```

---

### Task 3: CaptchaVerifier + thread altchaSolution through booking

**Files:**
- Rename: `src/main/java/site/asm0dey/calit/booking/TurnstileVerifier.java` → `CaptchaVerifier.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (inject `CaptchaVerifier`, overload `book(...)`, call `verify(token, altcha)`)
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (add `@RestForm("altcha")`, pass to `book`)
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingResource.java` (`BookRequest.altchaSolution`, pass to `book`)
- Test: `src/test/java/site/asm0dey/calit/booking/CaptchaVerifierTest.java`

**Interfaces:**
- Consumes: `CaptchaProviderConfig.provider()` (Task 2); `org.altcha.altcha.v1.Altcha.verifySolution(String,String,boolean)`.
- Produces: `CaptchaVerifier.verify(String turnstileToken, String altchaSolution)`; `BookingService.book(...)` 11-arg overload inserting `String altchaSolution` after `turnstileToken`.

- [ ] **Step 1: Write the failing test**

`src/test/java/site/asm0dey/calit/booking/CaptchaVerifierTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.altcha.altcha.v1.Altcha;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CaptchaVerifierTest.AltchaOn.class)
class CaptchaVerifierTest {

    static final String KEY = "test-hmac-secret";

    public static class AltchaOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.captcha.provider", "altcha",
                    "calit.captcha.altcha.hmac-key", KEY,
                    "calit.captcha.altcha.max-number", "100000");
        }
    }

    @Inject
    CaptchaVerifier verifier;

    /** Build the exact base64 payload the ALTCHA widget would POST after solving. */
    static String validPayload() throws Exception {
        var opts = new Altcha.ChallengeOptions()
                .algorithm(Altcha.Algorithm.SHA256)
                .maxNumber(100000)
                .hmacKey(KEY);
        Altcha.Challenge ch = Altcha.createChallenge(opts);
        Altcha.Solution sol = Altcha.solveChallenge(ch.challenge(), ch.salt(), Altcha.Algorithm.SHA256, ch.maxnumber(), 0);
        // salt is hex + "?expires=...&" — URL-encoded, contains no JSON-special chars.
        String json = "{\"algorithm\":\"" + ch.algorithm() + "\",\"challenge\":\"" + ch.challenge()
                + "\",\"number\":" + sol.number() + ",\"salt\":\"" + ch.salt()
                + "\",\"signature\":\"" + ch.signature() + "\"}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validSolutionPasses() throws Exception {
        String payload = validPayload();
        assertDoesNotThrow(() -> verifier.verify(null, payload));
    }

    @Test
    void missingSolutionThrows() {
        assertThrows(AbuseException.class, () -> verifier.verify(null, null));
        assertThrows(AbuseException.class, () -> verifier.verify(null, "   "));
    }

    @Test
    void tamperedSolutionThrows() throws Exception {
        // Flip the last base64 char to corrupt the signature/number.
        String payload = validPayload();
        String bad = payload.substring(0, payload.length() - 2) + (payload.endsWith("A=") ? "B=" : "A=");
        assertThrows(AbuseException.class, () -> verifier.verify(null, bad));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=CaptchaVerifierTest`
Expected: FAIL — `CaptchaVerifier` type does not exist.

- [ ] **Step 3: Rename `TurnstileVerifier.java` → `CaptchaVerifier.java` and rewrite it**

```bash
git mv src/main/java/site/asm0dey/calit/booking/TurnstileVerifier.java \
       src/main/java/site/asm0dey/calit/booking/CaptchaVerifier.java
```

Replace the file contents with:
```java
package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.altcha.altcha.v1.Altcha;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Server-side CAPTCHA verification. The active provider ({@code none} | {@code turnstile} |
 * {@code altcha}) is resolved by {@link CaptchaProviderConfig}. {@code none} is a no-op success
 * (local dev/tests never call an external service). Failures throw {@link AbuseException} (HTTP 400).
 */
@ApplicationScoped
public class CaptchaVerifier {

    @Inject
    CaptchaProviderConfig providerConfig;

    // --- Turnstile ---
    // SmallRye treats an empty-string config value as null for the String converter, so bind
    // the optional secret as Optional<String> (empty/unset in the off-by-default local/test case).
    @ConfigProperty(name = "calit.abuse.turnstile.secret")
    Optional<String> secret;

    @ConfigProperty(
            name = "calit.abuse.turnstile.verify-url",
            defaultValue = "https://challenges.cloudflare.com/turnstile/v0/siteverify")
    String verifyUrl;

    // --- ALTCHA ---
    @ConfigProperty(name = "calit.captcha.altcha.hmac-key")
    Optional<String> altchaHmacKey;

    // SEC-SSRF-01: bound the synchronous booking-path call so a hung upstream can't pin a thread.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Matches the success flag in the siteverify JSON, tolerating whitespace: {@code "success" : true}. */
    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*true");

    /** Enforces the active provider. Throws AbuseException (400) when the presented token is invalid. */
    public void verify(String turnstileToken, String altchaSolution) {
        switch (providerConfig.provider()) {
            case "turnstile" -> verifyTurnstile(turnstileToken);
            case "altcha" -> verifyAltcha(altchaSolution);
            default -> {
                /* none: no-op success */
            }
        }
    }

    private void verifyAltcha(String solution) {
        if (solution == null || solution.isBlank()) {
            throw new AbuseException("Missing ALTCHA solution");
        }
        try {
            boolean ok = Altcha.verifySolution(solution, altchaHmacKey.orElse(""), true);
            if (!ok) {
                throw new AbuseException("ALTCHA verification failed");
            }
        } catch (AbuseException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AbuseException("ALTCHA verification error: " + e.getMessage());
        }
    }

    private void verifyTurnstile(String token) {
        if (token == null || token.isBlank()) {
            throw new AbuseException("Missing Turnstile token");
        }
        try {
            var body = "secret=" + URLEncoder.encode(secret.orElse(""), StandardCharsets.UTF_8) + "&response="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            var req = HttpRequest.newBuilder(URI.create(verifyUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || !SUCCESS.matcher(resp.body()).find()) {
                throw new AbuseException("Turnstile verification failed");
            }
        } catch (AbuseException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AbuseException("Turnstile verification error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Update `BookingService.java`**

4a. Rename the injected verifier. Find the field (search `TurnstileVerifier`):
```java
    @Inject
    TurnstileVerifier turnstileVerifier;
```
Replace with:
```java
    @Inject
    CaptchaVerifier captchaVerifier;
```

4b. Replace the existing `book(...)` signature (line ~222) with a backward-compatible overload plus the real method. The existing method starts:
```java
    @Transactional
    public Booking book(
            Long ownerId,
            String meetingTypeSlug,
            Instant startUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String turnstileToken,
            String honeypot,
            String locale,
            List<String> guestEmails) {
```
Insert this overload immediately BEFORE it (keeps all existing 10-arg callers, incl. ~40 tests, compiling):
```java
    /** Backward-compatible overload: no ALTCHA solution (turnstile/none paths, and all existing tests). */
    @Transactional
    public Booking book(
            Long ownerId,
            String meetingTypeSlug,
            Instant startUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String turnstileToken,
            String honeypot,
            String locale,
            List<String> guestEmails) {
        return book(
                ownerId,
                meetingTypeSlug,
                startUtc,
                inviteeName,
                inviteeEmail,
                answers,
                turnstileToken,
                null,
                honeypot,
                locale,
                guestEmails);
    }
```
Then change the ORIGINAL method's signature to insert `String altchaSolution` after `turnstileToken`:
```java
    @Transactional
    public Booking book(
            Long ownerId,
            String meetingTypeSlug,
            Instant startUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String turnstileToken,
            String altchaSolution,
            String honeypot,
            String locale,
            List<String> guestEmails) {
```

4c. Inside the real method, replace the verify call (line ~243):
```java
        turnstileVerifier.verify(turnstileToken); // -> AbuseException (400) when enabled & invalid
```
with:
```java
        captchaVerifier.verify(turnstileToken, altchaSolution); // -> AbuseException (400) on invalid CAPTCHA
```

- [ ] **Step 5: Update `PublicResource.submitBooking` to forward the ALTCHA field**

5a. Add the form param (after the `cf-turnstile-response` param, line ~305):
```java
            @RestForm("cf-turnstile-response") String turnstileToken,
            @RestForm("altcha") String altchaSolution,
```

5b. Pass it into the `bookingService.book(...)` call (line ~333). Insert `altchaSolution` after `turnstileToken`:
```java
            booking = bookingService.book(
                    type.ownerId,
                    type.slug,
                    Instant.parse(startUtc),
                    inviteeName,
                    inviteeEmail,
                    answers,
                    turnstileToken,
                    altchaSolution,
                    website,
                    locale,
                    parseGuests(form));
```

- [ ] **Step 6: Update `BookingResource`**

6a. Add the field to `BookRequest` (after `turnstileToken`, line ~35):
```java
    public record BookRequest(
            String user,
            String slug,
            String startUtc,
            String inviteeName,
            String inviteeEmail,
            Map<String, String> answers,
            String turnstileToken,
            String altchaSolution,
            String honeypot) {}
```

6b. Pass it in the `bookingService.book(...)` call (line ~61, after `req.turnstileToken()`):
```java
        Booking b = bookingService.book(
                owner.id,
                req.slug(),
                Instant.parse(req.startUtc()),
                req.inviteeName(),
                req.inviteeEmail(),
                req.answers(),
                req.turnstileToken(),
                req.altchaSolution(),
                req.honeypot(),
                locale,
                java.util.List.of());
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=CaptchaVerifierTest,BookServiceTest,BookingResourceTest`
Expected: PASS (verifier tests green; existing booking tests unaffected by the overload).

- [ ] **Step 8: Format + commit**

```bash
./mvnw -q spotless:apply
git add -A
git commit -m "feat(captcha): CaptchaVerifier with ALTCHA branch; thread altcha token through book()"
```

---

### Task 4: ALTCHA challenge endpoint

**Files:**
- Create: `src/main/java/site/asm0dey/calit/booking/AltchaResource.java`
- Test: `src/test/java/site/asm0dey/calit/booking/AltchaChallengeTest.java`

**Interfaces:**
- Produces: `GET /altcha/challenge` → JSON `{algorithm, challenge, maxnumber, salt, signature}` (the ALTCHA widget's expected challenge shape).

- [ ] **Step 1: Write the failing test**

`src/test/java/site/asm0dey/calit/booking/AltchaChallengeTest.java`:
```java
package site.asm0dey.calit.booking;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AltchaChallengeTest.AltchaOn.class)
class AltchaChallengeTest {

    public static class AltchaOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.captcha.provider", "altcha",
                    "calit.captcha.altcha.hmac-key", "test-hmac-secret",
                    "calit.captcha.altcha.max-number", "100000");
        }
    }

    @Test
    void challengeEndpointReturnsSignedChallenge() {
        given().when()
                .get("/altcha/challenge")
                .then()
                .statusCode(200)
                .body("algorithm", notNullValue())
                .body("challenge", notNullValue())
                .body("salt", notNullValue())
                .body("signature", notNullValue())
                .body("maxnumber", notNullValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=AltchaChallengeTest`
Expected: FAIL — 404 (no `/altcha/challenge`).

- [ ] **Step 3: Create `AltchaResource.java`**

```java
package site.asm0dey.calit.booking;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Optional;
import org.altcha.altcha.v1.Altcha;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Mints ALTCHA proof-of-work challenges for the booking-form widget. Public + unauthenticated:
 * issuing a challenge is cheap and safe to expose. The widget fetches this, solves it client-side,
 * and posts the base64 solution back in the {@code altcha} form field (verified in {@link CaptchaVerifier}).
 */
@Path("/altcha")
public class AltchaResource {

    @ConfigProperty(name = "calit.captcha.altcha.hmac-key")
    Optional<String> hmacKey;

    @ConfigProperty(name = "calit.captcha.altcha.max-number", defaultValue = "100000")
    long maxNumber;

    @GET
    @Path("/challenge")
    @Produces(MediaType.APPLICATION_JSON)
    public Altcha.Challenge challenge() throws Exception {
        var opts = new Altcha.ChallengeOptions()
                .algorithm(Altcha.Algorithm.SHA256)
                .maxNumber(maxNumber)
                .hmacKey(hmacKey.orElse(""))
                .expiresInSeconds(300); // challenge is single-use within 5 minutes
        return Altcha.createChallenge(opts);
    }
}
```
(Jackson serializes the `Altcha.Challenge` record by component name → exactly `{algorithm, challenge, maxnumber, salt, signature}`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=AltchaChallengeTest`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/site/asm0dey/calit/booking/AltchaResource.java src/test/java/site/asm0dey/calit/booking/AltchaChallengeTest.java
git commit -m "feat(captcha): add GET /altcha/challenge endpoint"
```

---

### Task 5: Template render — provider switch in book.html

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (Templates.book signature, inject resolver, both call sites, drop `turnstileEnabled` field)
- Modify: `src/main/resources/templates/PublicResource/book.html` (param decl, head, widget slot)
- Test: `src/test/java/site/asm0dey/calit/web/BookPageAltchaEnabledTest.java`

**Interfaces:**
- Consumes: `CaptchaProviderConfig.provider()` (Task 2).
- Produces: `book.html` renders `<altcha-widget>` + `/_static/altcha/...` when provider=altcha, `.cf-turnstile` when turnstile, neither when none.

- [ ] **Step 1: Write the failing test**

`src/test/java/site/asm0dey/calit/web/BookPageAltchaEnabledTest.java`:
```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
@TestProfile(BookPageAltchaEnabledTest.AltchaOn.class)
class BookPageAltchaEnabledTest {

    public static class AltchaOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.captcha.provider", "altcha",
                    "calit.captcha.altcha.hmac-key", "test-hmac-secret");
        }
    }

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("carol");
        if (owner == null) {
            owner = AppUser.create("carol", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "altcha-type");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Altcha Type";
        t.slug = "altcha-type";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    @Test
    void bookPageRendersAltchaWidgetAndScript() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().when()
                .get("/carol/altcha-type")
                .then()
                .statusCode(200)
                .body(containsString("<altcha-widget"))
                .body(containsString("challengeurl=\"/altcha/challenge\""))
                .body(containsString("/_static/altcha/dist/main/altcha.min.js"))
                // No Cloudflare widget when altcha is active.
                .body(not(containsString("class=\"cf-turnstile\"")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=BookPageAltchaEnabledTest`
Expected: FAIL — no `<altcha-widget>` in output (template still keyed on `turnstileEnabled`).

- [ ] **Step 3: Change the `Templates.book` signature in `PublicResource.java`**

At line ~54, replace:
```java
                boolean turnstileEnabled,
                String turnstileSiteKey,
```
with:
```java
                String captchaProvider,
                String turnstileSiteKey,
```

- [ ] **Step 4: Inject the resolver + drop the old boolean field**

4a. Remove the now-unused field (lines ~133-135):
```java
    // Owner-configurable Turnstile (feature 16). When disabled, the template skips the widget.
    @ConfigProperty(name = "calit.turnstile.enabled", defaultValue = "false")
    boolean turnstileEnabled;
```
(Keep `turnstileSiteKeyConfig` + the `turnstileSiteKey()` helper — the turnstile branch still needs the site key.)

4b. Add the injection near the other `@Inject` fields (e.g. after `identity`):
```java
    @jakarta.inject.Inject
    site.asm0dey.calit.booking.CaptchaProviderConfig captchaProviderConfig;
```

- [ ] **Step 5: Update both `Templates.book(...)` call sites**

At the GET handler (line ~232) replace `turnstileEnabled,` with `captchaProviderConfig.provider(),`:
```java
                Layout.CALENDAR_SCRIPT,
                captchaProviderConfig.provider(),
                turnstileSiteKey(),
                calendarPort.isConnected(type.ownerId),
```
At the POST re-render (line ~358) replace `turnstileEnabled,` with `captchaProviderConfig.provider(),`:
```java
                    Layout.CALENDAR_SCRIPT,
                    captchaProviderConfig.provider(),
                    turnstileSiteKey(),
```

- [ ] **Step 6: Update `book.html`**

6a. Param declaration (line 10) — replace:
```
{@java.lang.Boolean turnstileEnabled}
```
with:
```
{@java.lang.String captchaProvider}
```

6b. Head block (lines 17-19) — replace:
```
    {#if turnstileEnabled}
    <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
    {/if}
```
with:
```
    {#if captchaProvider == 'turnstile'}
    <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
    {#else if captchaProvider == 'altcha'}
    <script async defer type="module" src="/_static/altcha/dist/main/altcha.min.js"></script>
    {/if}
```

6c. Widget slot (lines 106-108) — replace:
```
              {#if turnstileEnabled}
              <div class="cf-turnstile" data-sitekey="{turnstileSiteKey}"></div>
              {/if}
```
with:
```
              {#if captchaProvider == 'turnstile'}
              <div class="cf-turnstile" data-sitekey="{turnstileSiteKey}"></div>
              {#else if captchaProvider == 'altcha'}
              <altcha-widget challengeurl="/altcha/challenge" name="altcha"></altcha-widget>
              {/if}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test -Dtest=BookPageAltchaEnabledTest,BookPageTurnstileEnabledTest,BookPageTest`
Expected: PASS. `BookPageTurnstileEnabledTest` (sets `calit.turnstile.enabled=true` → provider resolves `turnstile`) still renders `.cf-turnstile`; `BookPageTest` (default provider `none`) still omits both.

- [ ] **Step 8: Format + commit**

```bash
./mvnw -q spotless:apply
git add -A
git commit -m "feat(captcha): render provider-specific widget in book.html"
```

---

### Task 6: Full suite + docs

**Files:**
- Modify: `.env.example` (3 vars)
- Modify: `README.md` (env reference table)
- (Separate branch, out of this plan's commits) `docs-site` changelog + config page

**Interfaces:** none (documentation).

- [ ] **Step 1: Run the full test suite**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca && ./mvnw test`
Expected: PASS (all existing + new tests).

- [ ] **Step 2: Add the three vars to `.env.example`**

Append near the Turnstile vars:
```bash
# CAPTCHA provider: none (default) | turnstile | altcha. Unset falls back to turnstile if TURNSTILE_ENABLED=true.
CAPTCHA_PROVIDER=none
# ALTCHA (self-hosted proof-of-work). Required when CAPTCHA_PROVIDER=altcha. Use a long random secret.
ALTCHA_HMAC_KEY=
# Proof-of-work difficulty (max number the client brute-forces). Default 100000.
ALTCHA_MAX_NUMBER=100000
```

- [ ] **Step 3: Add the vars to the `README.md` env reference table**

Add rows (match the existing table's column layout) for `CAPTCHA_PROVIDER`, `ALTCHA_HMAC_KEY`, `ALTCHA_MAX_NUMBER`, describing values/defaults as above, and note that `TURNSTILE_ENABLED=true` still implies `turnstile` when `CAPTCHA_PROVIDER` is unset.

- [ ] **Step 4: Format + commit**

```bash
bun run format
git add .env.example README.md
git commit -m "docs(captcha): document CAPTCHA_PROVIDER + ALTCHA env vars"
```

- [ ] **Step 5: docs-site (separate branch — do after merge)**

On the `docs-site` branch: add a CAPTCHA section to the configuration page (provider selection, self-hosted ALTCHA, the public `/altcha/challenge` endpoint for reverse-proxy allow-lists) and a changelog entry on the next release. Not part of this branch's commits.

---

## Self-Review

**Spec coverage:**
- Config (`CAPTCHA_PROVIDER`, `ALTCHA_HMAC_KEY`, `ALTCHA_MAX_NUMBER`, back-compat) → Task 1 + Task 2. ✓
- One `CaptchaVerifier`, switch on provider → Task 3. ✓
- Challenge endpoint → Task 4. ✓
- Template provider switch + self-hosted `type="module"` script → Task 5. ✓
- Form wiring (`@RestForm("altcha")`, `book()` thread, `BookRequest`) → Task 3. ✓
- mvnpm widget + web-dependency-locator (version-less, no importmap) → Task 1. ✓
- Server lib `org.altcha:altcha:2.0.2` (v1 protocol) → Task 1/3/4. ✓
- Tests (verifier valid/invalid/none, challenge JSON, render per provider) → Tasks 2-5. ✓
- Progressive-enhancement note, i18n note → Global Constraints. ✓
- Docs → Task 6. ✓

**Placeholder scan:** none — every step has full code or an exact edit.

**Type consistency:** `verify(String turnstileToken, String altchaSolution)` used identically in Task 3 verifier, `BookingService.book`, and both callers. `captchaProvider` (String) matches the `Templates.book` param and the `book.html` `{@java.lang.String captchaProvider}` decl. `CaptchaProviderConfig.provider()` return values (`none`/`turnstile`/`altcha`) match the template `{#if captchaProvider == '...'}` branches and the verifier `switch`. `Altcha.Challenge` component `maxnumber` matches the test's `.body("maxnumber", ...)` assertion.
