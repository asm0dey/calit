# Google Logging Test Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cover the 13 uncovered new lines introduced by commit `de1fa46` (Google diagnostic logging) so SonarCloud new-code coverage goes from 59.3% to ≥95%.

**Architecture:** Four independent test additions, each targeting one file. Two need no production change (`GooglePageResource`, `GoogleCalendarListPort`, `GoogleConfigStartupLog` are already testable through existing seams); one adds a single `protected` transport seam to `GoogleTokenService` so its real `requestToken` path can run against `MockHttpTransport` instead of the network.

**Tech Stack:** Java 25, Quarkus 3.38, JUnit 5, Mockito (via `quarkus-junit5-mockito`), RestAssured, JaCoCo → SonarCloud.

## Global Constraints

- Build JDK: `export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca` before any `./mvnw` command. Default JDK is 21 and fails with "release 25 not supported".
- Docker must be running: `@QuarkusTest` classes use Dev Services Postgres.
- Never add a new test dependency. `MockHttpTransport` / `MockLowLevelHttpResponse` already ship inside the compile-scope `com.google.http-client:google-http-client:1.47.1` jar.
- Java formatting is enforced: `mvn spotless:check` runs in `verify`. The pre-commit hook (`lefthook`) auto-runs `spotless:apply` on staged `*.java`, so committing is enough.
- Owner scoping: any query added in a test fixture must set `ownerId`. Admin user is always id 1; `AppUser.find("username", "admin")` is the established way to get it.
- No user-facing strings are added by this plan, so no i18n work is required.
- Track this work in beans, not TodoWrite (project rule). The bean for this plan already exists: **`calit-4ggu`**; tick its task boxes as you go.

## Baseline (measured 2026-08-15, SonarCloud project `asm0dey_calit`)

```
new_lines_to_cover   = 40
new_uncovered_lines  = 13   -> new_coverage 59.26%
coverage (overall)   = 81.1%
```

Uncovered new lines, per file:

| File | Uncovered lines | Task |
| --- | --- | --- |
| `src/main/java/site/asm0dey/calit/google/GoogleCalendarListPort.java` | 41, 44, 45, 46, 47, 48 | Task 2 |
| `src/main/java/site/asm0dey/calit/google/GoogleTokenService.java` | 326, 333, 337 | Task 4 |
| `src/main/java/site/asm0dey/calit/google/GoogleConfigStartupLog.java` | 34, 35 | Task 3 |
| `src/main/java/site/asm0dey/calit/web/GooglePageResource.java` | 109, 153 | Task 1 |

Re-check the same numbers at the end with:

```bash
curl -s "https://sonarcloud.io/api/measures/component?component=asm0dey_calit&metricKeys=new_coverage,new_lines_to_cover,new_uncovered_lines"
```

## File Structure

- Modify `src/test/java/site/asm0dey/calit/web/GooglePageResourceTest.java` — add two tests for the fail-soft path (banner on render, preservation on save). Existing class already has `@InjectMock CalendarListPort` and the seed helpers these tests need.
- Create `src/test/java/site/asm0dey/calit/google/GoogleCalendarListPortTest.java` — plain Mockito unit test (no `@QuarkusTest`) for the Google-error → `UncheckedIOException` mapping.
- Create `src/test/java/site/asm0dey/calit/google/GoogleConfigStartupLogTest.java` — plain Mockito unit test for the configured / not-configured branches.
- Modify `src/main/java/site/asm0dey/calit/google/GoogleTokenService.java:270` — replace the inline `new NetHttpTransport()` with a `protected HttpTransport transport()` seam.
- Create `src/test/java/site/asm0dey/calit/google/GoogleTokenServiceRequestTokenTest.java` — drives the real `requestToken` against `MockHttpTransport`.

---

### Task 1: GooglePageResource fail-soft path

Covers `GooglePageResource.java:109` and `:153` — the two WARN calls. Both sit inside `catch (RuntimeException ex)` blocks that no test ever enters, so this also closes a genuine behavioural gap: nothing today asserts that a Google outage keeps the saved configuration visible instead of wiping it.

**Files:**
- Modify: `src/test/java/site/asm0dey/calit/web/GooglePageResourceTest.java` (append tests before the `@Transactional` helper methods)
- Test: same file

**Interfaces:**
- Consumes: `seedHealthyXAndFlaggedY()` returning `long[] {healthyCredId, flaggedCredId}`, `assertCalendarExists(long credId, String googleCalId)`, `assertWriteTarget(long credId, String googleCalId)`, `FormAuth.login()` returning the session cookie value — all already in this test class.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/site/asm0dey/calit/web/GooglePageResourceTest.java`, directly after the `saveDoesNotWipeFlaggedAccountCalendars` test:

```java
    @Test
    void getShowsBannerAndKeepsSavedRowsWhenGoogleUnreachable() {
        var ids = seedHealthyXAndFlaggedY();
        var xId = ids[0];
        // The live listing blows up the way a 403 SERVICE_DISABLED does in production.
        Mockito.when(calendarListPort.listCalendars(Mockito.any()))
                .thenThrow(new java.io.UncheckedIOException(
                        "calendarList.list failed: HTTP 403 — Google Calendar API has not been used in project 1",
                        new java.io.IOException("403")));

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/google")
                .then()
                .statusCode(200)
                // The error banner is rendered...
                .body(org.hamcrest.Matchers.containsString("reach Google for one or more accounts"))
                // ...and the saved calendar for the healthy account is still listed, not wiped.
                .body(org.hamcrest.Matchers.containsString("X1"));

        assertCalendarExists(xId, "x1");
    }

    @Test
    void savePreservesSelectionWhenGoogleUnreachableMidSave() {
        var ids = seedHealthyXAndFlaggedY();
        var xId = ids[0];
        var yId = ids[1];
        // Google dies between page render and save: every listing attempt throws.
        Mockito.when(calendarListPort.listCalendars(Mockito.any()))
                .thenThrow(new java.io.UncheckedIOException(
                        "calendarList.list failed: HTTP 403", new java.io.IOException("403")));

        // The form carries nothing usable, so the write target must be preserved from the DB.
        given().cookie("quarkus-credential", FormAuth.login())
                .redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/google/calendars")
                .then()
                .statusCode(303);

        assertWriteTarget(xId, "x1");
        assertCalendarExists(yId, "y1");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GooglePageResourceTest#getShowsBannerAndKeepsSavedRowsWhenGoogleUnreachable+savePreservesSelectionWhenGoogleUnreachableMidSave'
```

Expected: both FAIL — the first because it is asserting behaviour nothing exercises yet, and the run must be watched for whether the failure is an assertion mismatch (banner text or `X1` not found) rather than an error in the fixture. If the banner assertion fails because of HTML escaping, print the body with `.log().body()` and match on the escaped form.

- [ ] **Step 3: Make them pass**

No production change should be needed — the behaviour already exists at `GooglePageResource.java:103-113` and `:145-155`. If a test fails, the fix belongs in the test (wrong assertion string, wrong seed), not in the resource. Do not weaken the assertions: the point is that the banner appears AND the saved rows survive.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GooglePageResourceTest'
```

Expected: PASS, all tests in the class green.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/site/asm0dey/calit/web/GooglePageResourceTest.java
git commit -m "test(google): cover the fail-soft path when Google is unreachable"
```

---

### Task 2: GoogleCalendarListPort error mapping

Covers `GoogleCalendarListPort.java:41,44-48` — the whole `GoogleJsonResponseException` branch. The class is at 0% coverage today because it is the real Google-backed port; every other test injects a mock of the `CalendarListPort` interface instead.

This is a plain JUnit + Mockito test with no `@QuarkusTest` annotation, so it costs no Quarkus boot and no Postgres.

**Files:**
- Create: `src/test/java/site/asm0dey/calit/google/GoogleCalendarListPortTest.java`
- Test: same file

**Interfaces:**
- Consumes: `new GoogleCalendarListPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory, CurrentOwner currentOwner)`; `GoogleCalendarListPort.listCalendars(GoogleCredential)` returning `List<CalendarListPort.RemoteCalendar>`; `GoogleTokenService.validAccessToken(GoogleCredential, Instant)` returning `String`; `GoogleCalendarClientFactory.build(String)` returning `com.google.api.services.calendar.Calendar`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/GoogleCalendarListPortTest.java`:

```java
package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.calendar.Calendar;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * Plain unit test (no @QuarkusTest): the point is the exception mapping, and booting Quarkus for it
 * would cost a Postgres container per run.
 */
class GoogleCalendarListPortTest {

    /** Wire a port whose Calendar client fails the calendarList.list call with the given exception. */
    private static GoogleCalendarListPort portThatFailsWith(IOException failure) throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.CalendarList.List list = mock(Calendar.CalendarList.List.class);
        when(list.execute()).thenThrow(failure);
        Calendar.CalendarList calendarList = mock(Calendar.CalendarList.class);
        when(calendarList.list()).thenReturn(list);
        Calendar client = mock(Calendar.class);
        when(client.calendarList()).thenReturn(calendarList);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarListPort(tokens, clientFactory, mock(CurrentOwner.class));
    }

    private static GoogleJsonResponseException serviceDisabled() {
        var details = new GoogleJsonError();
        details.setCode(403);
        details.setMessage("Google Calendar API has not been used in project 477339155409 before or it is disabled.");
        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders()), details);
    }

    @Test
    void googleErrorCarriesStatusAndMessageOnTheFirstLine() throws IOException {
        var port = portThatFailsWith(serviceDisabled());

        var thrown = assertThrows(UncheckedIOException.class, () -> port.listCalendars(new GoogleCredential()));

        // Operators read the first line of the WARN; the status and Google's own words must be there.
        assertTrue(thrown.getMessage().contains("HTTP 403"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("has not been used in project"), thrown.getMessage());
    }

    @Test
    void plainIoErrorStillWrapsWithTheCallName() throws IOException {
        var port = portThatFailsWith(new IOException("connect timed out"));

        var thrown = assertThrows(UncheckedIOException.class, () -> port.listCalendars(new GoogleCredential()));

        assertEquals("calendarList.list failed", thrown.getMessage());
        assertEquals("connect timed out", thrown.getCause().getMessage());
    }
}
```

- [ ] **Step 2: Run the test to verify it compiles and exercises the branch**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GoogleCalendarListPortTest'
```

Expected: PASS. If it fails with `MissingMethodInvocationException` on `when(list.execute())`, the inline mock maker is not active — in that case add `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` containing the single line `mock-maker-inline` and re-run. Do not add a dependency.

If it fails with `NullPointerException` inside `listCalendars`, check that the stub chain matches the production call `clientFactory.build(tokens.validAccessToken(credential, Instant.now())).calendarList().list().execute()` at `GoogleCalendarListPort.java:31-33`.

- [ ] **Step 3: No production change**

The mapping already exists at `GoogleCalendarListPort.java:41-52`. This task is test-only.

- [ ] **Step 4: Confirm the whole google package still passes**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GoogleCalendarListPortTest'
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/site/asm0dey/calit/google/GoogleCalendarListPortTest.java
git commit -m "test(google): cover calendarList error mapping"
```

---

### Task 3: GoogleConfigStartupLog branches

Covers `GoogleConfigStartupLog.java:34-35` — the degraded-mode branch. In `%test` the client id is always `test-client-id`, so only the configured branch ever runs.

Asserting on log output would need a log handler; instead assert on the observable consequence — the degraded branch must not touch the redirect URIs, the configured branch must read all of them. That is a real assertion about the branch taken, not a smoke test.

**Files:**
- Create: `src/test/java/site/asm0dey/calit/google/GoogleConfigStartupLogTest.java`
- Test: same file

**Interfaces:**
- Consumes: `new GoogleConfigStartupLog(GoogleOAuthConfig config)`; package-private `void logConfig(StartupEvent ev)`; `GoogleOAuthConfig.oauth()` returning `GoogleOAuthConfig.OAuth` with `clientId()`, `clientSecret()`, `redirectUri()`, `loginRedirectUri()`, `scope()`. `io.quarkus.runtime.StartupEvent` has a public no-arg constructor.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/GoogleConfigStartupLogTest.java`:

```java
package site.asm0dey.calit.google;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

/** Plain unit test: the class only reads config and logs, so booting Quarkus would buy nothing. */
class GoogleConfigStartupLogTest {

    private static GoogleOAuthConfig configWith(String clientId, String clientSecret) {
        var oauth = mock(GoogleOAuthConfig.OAuth.class);
        when(oauth.clientId()).thenReturn(clientId);
        when(oauth.clientSecret()).thenReturn(clientSecret);
        when(oauth.redirectUri()).thenReturn("https://book.example.com/api/google/callback");
        when(oauth.loginRedirectUri()).thenReturn("https://book.example.com/api/google/login/callback");
        when(oauth.scope()).thenReturn("https://www.googleapis.com/auth/calendar openid email");
        var config = mock(GoogleOAuthConfig.class);
        when(config.oauth()).thenReturn(oauth);
        return config;
    }

    @Test
    void degradedModeStopsBeforeReadingTheRedirectUris() {
        var config = configWith("", "");

        new GoogleConfigStartupLog(config).logConfig(new StartupEvent());

        // Taking the degraded branch is observable: the URI accessors are never reached.
        verify(config.oauth(), never()).redirectUri();
        verify(config.oauth(), never()).loginRedirectUri();
    }

    @Test
    void configuredModeLogsTheEffectiveRedirectUrisAndScope() {
        var config = configWith("1234-abc.apps.googleusercontent.com", "secret");

        new GoogleConfigStartupLog(config).logConfig(new StartupEvent());

        verify(config.oauth()).redirectUri();
        verify(config.oauth()).loginRedirectUri();
        verify(config.oauth()).scope();
    }
}
```

- [ ] **Step 2: Run the test**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GoogleConfigStartupLogTest'
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`. A `verify` failure on `redirectUri()` in the first test means the degraded branch did not return early — read `GoogleConfigStartupLog.java:32-35`.

- [ ] **Step 3: No production change**

Test-only task.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/site/asm0dey/calit/google/GoogleConfigStartupLogTest.java
git commit -m "test(google): cover the degraded-mode startup log branch"
```

---

### Task 4: GoogleTokenService requestToken against a mock transport

Covers `GoogleTokenService.java:326, 333, 337` — the error mapping in the real `requestToken`. Every existing test overrides `requestToken` wholesale, so none of its body has ever run.

This is the only task with a production change: one `protected` seam so a test subclass can swap the HTTP transport. `requestToken` stays exactly as it is otherwise.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleTokenService.java:270`
- Create: `src/test/java/site/asm0dey/calit/google/GoogleTokenServiceRequestTokenTest.java`
- Test: the new file

**Interfaces:**
- Consumes: `GoogleTokenService(GoogleOAuthConfig config)`; `protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now)`; `GoogleInvalidGrantException extends IllegalStateException`.
- Produces: `protected HttpTransport transport()` — a new overridable seam on `GoogleTokenService` returning the transport used for token round-trips. Test subclasses override it; production keeps returning `new NetHttpTransport()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/GoogleTokenServiceRequestTokenTest.java`:

```java
package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Drives the REAL requestToken body (which every other test stubs out) against an in-memory
 * transport, so Google's error payloads are mapped by production code, not by a stub.
 */
class GoogleTokenServiceRequestTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** GoogleTokenService wired to a canned HTTP transport instead of the network. */
    static class TransportStubbedService extends GoogleTokenService {
        private final HttpTransport transport;

        TransportStubbedService(HttpTransport transport) {
            super(config());
            this.transport = transport;
        }

        @Override
        protected HttpTransport transport() {
            return transport;
        }
    }

    private static GoogleOAuthConfig config() {
        var oauth = mock(GoogleOAuthConfig.OAuth.class);
        when(oauth.clientId()).thenReturn("test-client-id");
        when(oauth.clientSecret()).thenReturn("test-client-secret");
        when(oauth.redirectUri()).thenReturn("https://book.example.com/api/google/callback");
        var config = mock(GoogleOAuthConfig.class);
        when(config.oauth()).thenReturn(oauth);
        return config;
    }

    private static HttpTransport respondingWith(int status, String jsonBody) {
        return new MockHttpTransport.Builder()
                .setLowLevelHttpResponse(new MockLowLevelHttpResponse()
                        .setStatusCode(status)
                        .setContentType("application/json")
                        .setContent(jsonBody))
                .build();
    }

    @Test
    void deadRefreshTokenBecomesGoogleInvalidGrantException() {
        var svc = new TransportStubbedService(
                respondingWith(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired\"}"));

        assertThrows(
                GoogleInvalidGrantException.class, () -> svc.requestToken("refresh_token", "dead-refresh-token", NOW));
    }

    @Test
    void otherOauthErrorsCarryGoogleErrorAndDescription() {
        var svc = new TransportStubbedService(
                respondingWith(401, "{\"error\":\"invalid_client\",\"error_description\":\"Unauthorized\"}"));

        var thrown = assertThrows(
                IllegalStateException.class, () -> svc.requestToken("refresh_token", "some-refresh-token", NOW));

        assertFalse(thrown instanceof GoogleInvalidGrantException, "401 must NOT be treated as a dead grant");
        assertTrue(thrown.getMessage().contains("refresh_token"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("error=invalid_client"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("description=Unauthorized"), thrown.getMessage());
    }

    @Test
    void networkFailureBecomesIoErrorNotADeadGrant() {
        var transport = new MockHttpTransport.Builder()
                .setLowLevelHttpRequest(new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() throws IOException {
                        throw new IOException("connect timed out");
                    }
                })
                .build();
        var svc = new TransportStubbedService(transport);

        var thrown = assertThrows(
                IllegalStateException.class, () -> svc.requestToken("refresh_token", "some-refresh-token", NOW));

        assertFalse(thrown instanceof GoogleInvalidGrantException, "a blip must not flag the account dead");
        assertTrue(thrown.getMessage().contains("I/O error"), thrown.getMessage());
    }

    @Test
    void successfulRefreshReturnsTokenAndExpiry() {
        var svc = new TransportStubbedService(
                respondingWith(200, "{\"access_token\":\"fresh-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));

        var resp = svc.requestToken("refresh_token", "good-refresh-token", NOW);

        assertEquals("fresh-token", resp.accessToken());
        assertEquals(NOW.plusSeconds(3600), resp.expiry());
        assertNull(resp.googleSub(), "a refresh response carries no id_token claims");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GoogleTokenServiceRequestTokenTest'
```

Expected: COMPILATION FAILURE — `method does not override or implement a method from a supertype` on `transport()`, because the seam does not exist yet.

- [ ] **Step 3: Add the transport seam**

In `src/main/java/site/asm0dey/calit/google/GoogleTokenService.java`, replace line 270:

```java
        NetHttpTransport transport = new NetHttpTransport();
```

with:

```java
        HttpTransport transport = transport();
```

and add this method directly above `requestToken` (i.e. above its javadoc block at line 253):

```java
    /**
     * The HTTP transport used for token round-trips. Overridable so a test can drive the real
     * request/error-mapping code against an in-memory transport instead of Google.
     */
    protected com.google.api.client.http.HttpTransport transport() {
        return new NetHttpTransport();
    }
```

Add the import for `HttpTransport` next to the existing google-http-client imports at the top of the file:

```java
import com.google.api.client.http.HttpTransport;
```

(The existing `import com.google.api.client.http.javanet.NetHttpTransport;` stays — the default implementation still uses it.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GoogleTokenServiceRequestTokenTest'
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`.

If `deadRefreshTokenBecomesGoogleInvalidGrantException` fails with a plain `IllegalStateException`, the mock response was not parsed as a token error — check that the content type is `application/json` and the body uses the exact key `error`.

- [ ] **Step 5: Verify the existing stub subclasses still work**

The other Google tests subclass `GoogleTokenService` and override `requestToken`; the new seam must not disturb them.

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='GoogleTokenServiceTest,GoogleTokenServiceProbeTest,GoogleTokenServiceIdentityTest'
```

Expected: all green. If instead the run dies with `Ambiguous dependencies for type GoogleTokenService`, that is the known subset-run artifact of filtering `-Dtest` across several stub subclasses — re-verify with the full suite in Task 5 rather than chasing it here.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/google/GoogleTokenService.java \
        src/test/java/site/asm0dey/calit/google/GoogleTokenServiceRequestTokenTest.java
git commit -m "test(google): drive requestToken error mapping through a mock transport"
```

---

### Task 5: Full verification and Sonar re-check

**Files:**
- Modify: none (verification only)

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: nothing.

- [ ] **Step 1: Run the whole suite**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw test > /tmp/calit-coverage-verify.log 2>&1; echo "exit=$?"
grep -E "Tests run:.*Failures.*Errors, Skipped|BUILD" /tmp/calit-coverage-verify.log | tail -3
```

Expected: `BUILD SUCCESS` and a total of at least 786 tests (776 before this plan, +10 added here). Do not pipe the maven run itself through `tail` — that truncates the log and hides the failure summary.

- [ ] **Step 2: Verify formatting**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw spotless:check
```

Expected: BUILD SUCCESS. If it fails, run `./mvnw spotless:apply` and amend the affected commit.

- [ ] **Step 3: Push and let CI publish coverage to Sonar**

```bash
git push origin main
```

- [ ] **Step 4: Re-read the Sonar measures once CI finishes**

```bash
curl -s "https://sonarcloud.io/api/measures/component?component=asm0dey_calit&metricKeys=new_coverage,new_lines_to_cover,new_uncovered_lines,coverage"
```

Expected: `new_uncovered_lines` ≤ 2 and `new_coverage` ≥ 95. `GoogleTokenService.java:337` is the one line this plan may leave uncovered if the mock-transport IOException path does not reach it; anything beyond that means a task did not land.

- [ ] **Step 5: Close the bean**

```bash
beans update calit-4ggu -s completed --body-append "## Summary of Changes

Added 10 tests covering the diagnostic-logging code from de1fa46: GooglePageResource fail-soft path,
GoogleCalendarListPort error mapping, GoogleConfigStartupLog branches, GoogleTokenService.requestToken
against MockHttpTransport (one protected transport() seam added). Sonar new coverage: 59.3% -> <measured>%."
git add .beans && git commit -m "chore: close coverage bean" && git push origin main
```

---

## Notes for the implementer

- Every task is independent; they can be done in any order, and a failure in one does not block the others.
- Resist the urge to assert on log text. JBoss Logging output is not part of the contract; the assertions here target what the code *does* (branch taken, exception type, message content, DB rows preserved), which is what a reader cares about six months from now.
- The two `GooglePageResource` tests are the only ones that cover real user-facing behaviour rather than plumbing. If time runs out, do Task 1.
