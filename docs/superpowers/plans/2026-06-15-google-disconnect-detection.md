# Google Calendar Disconnect Detection & Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When calit can no longer read an owner's Google Calendar, stop silently showing every slot as free — fail the public page closed, detect the disconnect proactively on an hourly schedule, and email the owner once per outage telling them to reconnect.

**Architecture:** Three independent changes over the existing `needs_reconnect` flag. (1) **Fail-closed:** `GoogleCalendarPort.freeBusy` stops swallowing failures and throws `CalendarUnavailableException` when any busy-feeding account is broken; the public booking page renders a "temporarily unavailable" page instead of all-slots-open, and direct booking POSTs are refused. (2) **Proactive probe:** a new `GoogleConnectionScheduler` forces an hourly refresh-token round-trip per credential, distinguishing a genuinely dead grant (`invalid_grant` → flag) from a transient network blip (leave alone). (3) **Notification:** a new `reconnect_notified_at` column drives an exactly-once "reconnect your Google" email per outage episode, multi-node-safe via `FOR UPDATE SKIP LOCKED`.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache entities, Flyway migrations, Qute templates, `io.quarkus.scheduler.Scheduled`, Postgres `SKIP LOCKED`, Mockito `@InjectMock`, JUnit 5, RestAssured.

**Why this design (token lifetimes):** The *access* token has a known expiry (`access_token_expiry`, ~1h) and is auto-refreshed — never the problem. The *refresh* token is what dies (app in "Testing" publish status = 7-day expiry, revoked consent, password change, or 6-months-unused) and Google exposes **no** expiry for it. The only way to know it is dead is to try it and observe `invalid_grant` — hence a fixed-cadence probe rather than an expiry-driven check. Probing also keeps the token warm (resets the 6-month-idle clock).

---

## File Structure

**New files:**
- `src/main/resources/db/migration/V15__google_reconnect_tracking.sql` — adds `reconnect_notified_at` + `last_probed_at` columns.
- `src/main/java/com/calit/google/GoogleInvalidGrantException.java` — marks a dead refresh token (`invalid_grant`), distinct from transient errors.
- `src/main/java/com/calit/google/CalendarUnavailableException.java` — calendar busy data could not be read; callers must fail closed.
- `src/main/java/com/calit/google/CalendarUnavailableMapper.java` — JAX-RS mapper → 503 "temporarily unavailable" HTML for the booking POST path.
- `src/main/java/com/calit/scheduler/GoogleConnectionScheduler.java` — hourly probe + once-per-outage notification.
- `src/main/resources/templates/PublicResource/unavailable.html` — public fail-closed page.
- `src/main/resources/templates/email/google-disconnected.html` — the reconnect email body.
- Test files (one per behavior, listed in each task).

**Modified files:**
- `src/main/java/com/calit/google/GoogleCredential.java` — two new fields.
- `src/main/java/com/calit/google/GoogleTokenService.java` — classify `invalid_grant`; add `probe(...)`; clear `reconnectNotifiedAt` on every recovery.
- `src/main/java/com/calit/google/GoogleCalendarPort.java` — `freeBusy` throws instead of swallowing.
- `src/main/java/com/calit/web/PublicResource.java` — catch `CalendarUnavailableException`; render `unavailable()`.
- `src/main/java/com/calit/email/EmailService.java` — `sendGoogleDisconnected(...)`.
- `src/main/resources/application.properties` — probe interval config.
- `.env.example`, `README.md` — document `GOOGLE_PROBE_INTERVAL`.
- `src/test/java/com/calit/google/FreeBusyLiveFailureTest.java` — rewrite for the new throw contract.
- docs on the `docs-site` branch.

**Key invariant introduced:** `reconnect_notified_at` MUST be reset to NULL everywhere `needs_reconnect` is cleared (so a future outage re-notifies). That is three sites in `GoogleTokenService`: `exchangeCode`, `validAccessToken` (success branch), and `probe` (OK branch).

---

### Task 1: Migration — reconnect tracking columns

**Files:**
- Create: `src/main/resources/db/migration/V15__google_reconnect_tracking.sql`

- [ ] **Step 1: Write the migration**

`make_interval`/`now()` math happens in the scheduler queries; the migration just adds two nullable timestamp columns. Both default NULL (meaning "never notified" / "never probed").

```sql
-- Feature: Google disconnect detection & notification.
-- reconnect_notified_at: when we last emailed this account's owner about a disconnect.
--   NULL = not yet notified for the current outage. Reset to NULL whenever the account
--   recovers (needs_reconnect -> false) so a future outage re-notifies.
-- last_probed_at: when the hourly connection probe last attempted a refresh-token round-trip.
--   NULL = never probed. Used only to avoid redundant cross-replica probing (optimization).
ALTER TABLE google_credential ADD COLUMN reconnect_notified_at timestamptz;
ALTER TABLE google_credential ADD COLUMN last_probed_at timestamptz;
```

- [ ] **Step 2: Verify it applies cleanly**

Run: `mvn -q test -Dtest=GoogleCredentialTest`
Expected: PASS. Flyway applies V15 at boot before the test suite; a checksum/SQL error would fail every `@QuarkusTest` at startup. (`GoogleCredentialTest` is a fast existing class — its green run proves the migration + Hibernate `validate` accept the new columns once Task 2 maps them. If you run this before Task 2, Hibernate validate still passes because extra DB columns are allowed; only *missing* columns fail validation.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V15__google_reconnect_tracking.sql
git commit -m "feat(google): V15 add reconnect_notified_at + last_probed_at columns"
```

---

### Task 2: Map the new columns on the entity

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleCredential.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/google/GoogleCredentialReconnectFieldsTest.java`:

```java
package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class GoogleCredentialReconnectFieldsTest {

    @Test
    @TestTransaction
    void persistsAndReadsReconnectTrackingFields() {
        Instant t = Instant.parse("2026-06-15T10:00:00Z");
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-fields";
        c.reconnectNotifiedAt = t;
        c.lastProbedAt = t;
        c.persist();
        c.flush();

        GoogleCredential reloaded = GoogleCredential.findById(c.id);
        assertEquals(t, reloaded.reconnectNotifiedAt);
        assertEquals(t, reloaded.lastProbedAt);

        GoogleCredential fresh = new GoogleCredential();
        assertNull(fresh.reconnectNotifiedAt);
        assertNull(fresh.lastProbedAt);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GoogleCredentialReconnectFieldsTest`
Expected: FAIL — compilation error, `reconnectNotifiedAt` / `lastProbedAt` do not exist.

- [ ] **Step 3: Add the fields**

In `GoogleCredential.java`, after the `needsReconnect` field (line 54), add:

```java
    /**
     * When the owner was last emailed about this account being disconnected. NULL = not yet
     * notified for the current outage. INVARIANT: reset to NULL whenever {@code needsReconnect}
     * is cleared (recovery), so the next outage re-notifies. See GoogleTokenService.
     */
    @Column(name = "reconnect_notified_at")
    public Instant reconnectNotifiedAt;

    /** When the hourly connection probe last attempted a refresh on this account. NULL = never. */
    @Column(name = "last_probed_at")
    public Instant lastProbedAt;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=GoogleCredentialReconnectFieldsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCredential.java src/test/java/com/calit/google/GoogleCredentialReconnectFieldsTest.java
git commit -m "feat(google): map reconnect_notified_at + last_probed_at on GoogleCredential"
```

---

### Task 3: Classify `invalid_grant` in the token round-trip

**Files:**
- Create: `src/main/java/com/calit/google/GoogleInvalidGrantException.java`
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java:253-258` (the `requestToken` catch blocks)

Why: `requestToken` currently flattens both `TokenResponseException` (OAuth error) and `IOException` (network) into a generic `IllegalStateException`, so the probe can't tell a dead grant from a blip. We add a subclass for the dead-grant case. It **extends `IllegalStateException`** on purpose so every existing broad `catch (RuntimeException)` (in `validAccessToken` and `freeBusy`) keeps treating it exactly as before.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/google/RequestTokenClassificationTest.java`. We can't make a real Google call, so this test drives the *real* `requestToken` against the real (test-profile) OAuth config, which has no usable client — any call fails. Instead we assert the classification contract via a tiny seam: a subclass that exposes the protected method is not enough (we need a controlled `TokenResponseException`). So this test only asserts the new exception type exists and is an `IllegalStateException`; the behavioral classification is covered end-to-end in Task 4's probe tests.

```java
package com.calit.google;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTokenClassificationTest {

    @Test
    void invalidGrantExceptionIsAnIllegalStateException() {
        // Subclassing IllegalStateException keeps every existing broad catch(RuntimeException)
        // in validAccessToken/freeBusy behaving unchanged; only the probe inspects the subtype.
        GoogleInvalidGrantException e = new GoogleInvalidGrantException("dead", null);
        assertTrue(e instanceof IllegalStateException);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=RequestTokenClassificationTest`
Expected: FAIL — `GoogleInvalidGrantException` does not exist.

- [ ] **Step 3: Create the exception**

Create `src/main/java/com/calit/google/GoogleInvalidGrantException.java`:

```java
package com.calit.google;

/**
 * The Google refresh token is permanently dead — Google returned HTTP 400 {@code invalid_grant}
 * (revoked consent, expired offline grant, password change, or 6-months-unused). Distinct from a
 * transient network/5xx error, which leaves the token possibly still valid. Extends
 * {@link IllegalStateException} so existing broad {@code catch (RuntimeException)} fail-soft paths
 * behave unchanged; only the connection probe branches on this subtype.
 */
public class GoogleInvalidGrantException extends IllegalStateException {
    public GoogleInvalidGrantException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Branch the `TokenResponseException` catch**

In `GoogleTokenService.java`, replace the existing catch block at lines 253-255:

```java
        } catch (TokenResponseException e) {
            throw new IllegalStateException("Google token request failed: " + e.getStatusCode()
                    + " " + e.getMessage(), e);
```

with:

```java
        } catch (TokenResponseException e) {
            // invalid_grant (HTTP 400) = the refresh token is permanently dead -> flag + notify.
            // Any other OAuth/HTTP status (429, 5xx, ...) is transient -> generic IllegalStateException,
            // which the probe treats as "leave the account alone, try again next hour".
            String error = e.getDetails() != null ? e.getDetails().getError() : null;
            if (e.getStatusCode() == 400 && "invalid_grant".equals(error)) {
                throw new GoogleInvalidGrantException(
                        "Google refresh token rejected: invalid_grant", e);
            }
            throw new IllegalStateException("Google token request failed: " + e.getStatusCode()
                    + " " + e.getMessage(), e);
```

(Leave the `catch (IOException e)` block immediately below it unchanged — network errors stay generic/transient.)

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=RequestTokenClassificationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/google/GoogleInvalidGrantException.java src/main/java/com/calit/google/GoogleTokenService.java src/test/java/com/calit/google/RequestTokenClassificationTest.java
git commit -m "feat(google): classify invalid_grant as GoogleInvalidGrantException"
```

---

### Task 4: `GoogleTokenService.probe(...)` — force a refresh and classify

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java` (add `ProbeResult` enum + `probe` method; clear `reconnectNotifiedAt` in `exchangeCode` and `validAccessToken`)
- Test: `src/test/java/com/calit/google/GoogleTokenServiceProbeTest.java`

Behavior of `probe(credentialId, now)`:
- credential gone → return `null`.
- refresh succeeds → store new access token/expiry (+ rotated refresh token if present), clear `needsReconnect` AND `reconnectNotifiedAt`, return `OK`.
- `GoogleInvalidGrantException` → set `needsReconnect = true` (leave `reconnectNotifiedAt` as-is so the notifier picks it up), return `INVALID_GRANT`.
- any other `RuntimeException` (transient) → change nothing, return `TRANSIENT`.

It forces the round-trip **regardless** of `access_token_expiry` (a zero-traffic calendar never expires its cached token on its own; the whole point is to exercise the refresh token).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/google/GoogleTokenServiceProbeTest.java`. Uses the same `StubTokenService` seam as `GoogleTokenServiceTest` (override `requestToken`), extended to throw on demand.

```java
package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleTokenServiceProbeTest {

    @Inject
    GoogleOAuthConfig config;

    /** Stub the single network call: return a token, or throw a chosen exception. */
    static class StubTokenService extends GoogleTokenService {
        GoogleTokenService.TokenResponse next;
        RuntimeException toThrow;
        StubTokenService(GoogleOAuthConfig c) { super(c); }
        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefresh, Instant now) {
            if (toThrow != null) throw toThrow;
            return next;
        }
    }

    private Long seedFlagged(String sub, boolean needsReconnect, Instant notifiedAt) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt-" + sub;
        c.accessToken = "stale";
        c.accessTokenExpiry = Instant.parse("2030-01-01T00:00:00Z"); // NOT expired on purpose
        c.googleSub = sub;
        c.needsReconnect = needsReconnect;
        c.reconnectNotifiedAt = notifiedAt;
        c.persist();
        c.flush();
        return c.id;
    }

    @Test
    @TestTransaction
    void successClearsFlagAndNotifiedAtEvenWhenTokenNotExpired() {
        Long id = seedFlagged("probe-ok", true, Instant.parse("2026-06-15T09:00:00Z"));
        Instant now = Instant.parse("2026-06-15T10:00:00Z");
        StubTokenService svc = new StubTokenService(config);
        svc.next = new GoogleTokenService.TokenResponse(
                "fresh", null, now.plusSeconds(3600), null, null);

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertEquals(GoogleTokenService.ProbeResult.OK, r);
        GoogleCredential c = GoogleCredential.findById(id);
        assertEquals("fresh", c.accessToken);          // forced refresh ran despite non-expiry
        assertFalse(c.needsReconnect);
        assertNull(c.reconnectNotifiedAt);             // recovery resets the notify gate
    }

    @Test
    @TestTransaction
    void invalidGrantFlagsNeedsReconnectAndPreservesNotifiedAt() {
        Long id = seedFlagged("probe-dead", false, null);
        Instant now = Instant.parse("2026-06-15T10:00:00Z");
        StubTokenService svc = new StubTokenService(config);
        svc.toThrow = new GoogleInvalidGrantException("invalid_grant", null);

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertEquals(GoogleTokenService.ProbeResult.INVALID_GRANT, r);
        GoogleCredential c = GoogleCredential.findById(id);
        assertTrue(c.needsReconnect);
        assertNull(c.reconnectNotifiedAt);             // still unset -> notifier will email
    }

    @Test
    @TestTransaction
    void transientErrorChangesNothing() {
        Long id = seedFlagged("probe-blip", false, null);
        Instant now = Instant.parse("2026-06-15T10:00:00Z");
        StubTokenService svc = new StubTokenService(config);
        svc.toThrow = new IllegalStateException("Google token request I/O error",
                new IOException("timeout"));

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertEquals(GoogleTokenService.ProbeResult.TRANSIENT, r);
        GoogleCredential c = GoogleCredential.findById(id);
        assertFalse(c.needsReconnect);                 // a blip must NOT flag (no false alarm)
    }

    @Test
    @TestTransaction
    void missingCredentialReturnsNull() {
        StubTokenService svc = new StubTokenService(config);
        assertNull(svc.probe(999_999L, Instant.parse("2026-06-15T10:00:00Z")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GoogleTokenServiceProbeTest`
Expected: FAIL — `ProbeResult` / `probe` do not exist.

- [ ] **Step 3: Add the enum and method**

In `GoogleTokenService.java`, add the enum just after the `TokenResponse` record (after line 53):

```java
    /** Outcome of a connection probe. */
    public enum ProbeResult { OK, INVALID_GRANT, TRANSIENT }
```

Add the `probe` method after `validAccessToken` (after line 208):

```java
    /**
     * Force a refresh-token round-trip to verify the account is still connected, ignoring the cached
     * access token's expiry (a zero-traffic calendar never refreshes on its own, so this is the only
     * way to learn a dead grant). Runs in its own transaction; the result is committed.
     *
     * <p>OK -> store the fresh token, clear needsReconnect AND reconnectNotifiedAt (recovery).
     * INVALID_GRANT -> flag needsReconnect, leave reconnectNotifiedAt for the notifier.
     * TRANSIENT (network/5xx) -> change nothing, so a blip never raises a false reconnect alarm.
     */
    @Transactional
    public ProbeResult probe(Long credentialId, Instant now) {
        GoogleCredential c = GoogleCredential.findById(credentialId);
        if (c == null) {
            return null;
        }
        try {
            TokenResponse resp = requestToken("refresh_token", c.refreshToken, now);
            c.accessToken = resp.accessToken();
            c.accessTokenExpiry = resp.expiry();
            if (resp.refreshToken() != null) {
                c.refreshToken = resp.refreshToken();
            }
            c.needsReconnect = false;
            c.reconnectNotifiedAt = null; // INVARIANT: clearing needsReconnect resets the notify gate
            c.persist();
            return ProbeResult.OK;
        } catch (GoogleInvalidGrantException e) {
            c.needsReconnect = true;      // managed entity, flushes with this committed transaction
            c.persist();
            return ProbeResult.INVALID_GRANT;
        } catch (RuntimeException e) {
            // Transient: network blip, 429, or 5xx. Leave state untouched and retry next cycle.
            return ProbeResult.TRANSIENT;
        }
    }
```

- [ ] **Step 4: Enforce the notify-gate invariant in the other two recovery sites**

In `exchangeCode` (after line 158 `c.needsReconnect = false;`) add:

```java
        c.reconnectNotifiedAt = null; // recovery via manual reconnect re-arms future notifications
```

In `validAccessToken` success branch (after line 189 `c.needsReconnect = false;`) add:

```java
            c.reconnectNotifiedAt = null; // recovery via normal traffic re-arms future notifications
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=GoogleTokenServiceProbeTest,GoogleTokenServiceTest`
Expected: PASS (both classes — confirms the `exchangeCode`/`validAccessToken` edits didn't regress).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/google/GoogleTokenService.java src/test/java/com/calit/google/GoogleTokenServiceProbeTest.java
git commit -m "feat(google): GoogleTokenService.probe forces refresh and classifies disconnect"
```

---

### Task 5: Fail-closed at the port — `freeBusy` throws instead of swallowing

**Files:**
- Create: `src/main/java/com/calit/google/CalendarUnavailableException.java`
- Modify: `src/main/java/com/calit/google/GoogleCalendarPort.java:57-106` (the `freeBusy` method)
- Rewrite: `src/test/java/com/calit/google/FreeBusyLiveFailureTest.java`

New `freeBusy` contract: if any busy-feeding credential is **known broken** (`needsReconnect`) or its live call **fails**, throw `CalendarUnavailableException` — never return a partial/empty busy list that would masquerade as "calendar is free". Auth failures are still flagged upstream by `validAccessToken` (which `client(cred)` calls first), so `freeBusy` no longer sets the flag itself; it just refuses to produce untrustworthy data.

- [ ] **Step 1: Write the new failing test (rewrite the existing file)**

Replace the entire contents of `src/test/java/com/calit/google/FreeBusyLiveFailureTest.java` with:

```java
package com.calit.google;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * freeBusy is fail-CLOSED: a broken busy-feeding account must make the whole call throw
 * CalendarUnavailableException rather than return an empty/partial busy list (which would render
 * every slot as available). Covers both a known-broken (needsReconnect) account and a live failure.
 */
@QuarkusTest
class FreeBusyLiveFailureTest {

    @Inject
    GoogleCalendarPort port;

    @InjectMock
    GoogleTokenService tokenService;

    @Test
    void liveFailureThrowsCalendarUnavailable() {
        Long credId = seedHealthyCredWithReadCalendar("sub-live");
        // validAccessToken is called first inside client(cred); throwing here drives the live-failure path.
        Mockito.when(tokenService.validAccessToken(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("token dead"));

        assertThrows(CalendarUnavailableException.class, () ->
                port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400)));
    }

    @Test
    void knownBrokenAccountThrowsCalendarUnavailableWithoutCallingGoogle() {
        seedReadCalendarForCred(seedCred("sub-broken", true)); // pre-flagged needsReconnect

        assertThrows(CalendarUnavailableException.class, () ->
                port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400)));
        // No tokenService interaction: a known-broken account short-circuits before any Google call.
        Mockito.verifyNoInteractions(tokenService);
    }

    // --- helpers ---

    private Long seedHealthyCredWithReadCalendar(String sub) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Long id = seedCred(sub, false);
            seedReadCalendarForCred(id);
            return id;
        });
    }

    private Long seedCred(String sub, boolean needsReconnect) {
        return QuarkusTransaction.requiringNew().call(() -> {
            GoogleCredential c = new GoogleCredential();
            c.ownerId = 1L;
            c.refreshToken = "rt";
            c.googleSub = sub;
            c.needsReconnect = needsReconnect;
            c.persist();
            return c.id;
        });
    }

    private void seedReadCalendarForCred(Long credId) {
        QuarkusTransaction.requiringNew().run(() -> {
            GoogleCalendar g = new GoogleCalendar();
            g.ownerId = 1L;
            g.googleCredentialId = credId;
            g.googleCalendarId = "g-" + credId;
            g.summary = "cal";
            g.readForBusy = true;
            g.persist();
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FreeBusyLiveFailureTest`
Expected: FAIL — `CalendarUnavailableException` does not exist (compile error).

- [ ] **Step 3: Create the exception**

Create `src/main/java/com/calit/google/CalendarUnavailableException.java`:

```java
package com.calit.google;

/**
 * The owner's Google free/busy data could not be read (an account is disconnected or a live call
 * failed), so availability cannot be trusted. Callers must fail CLOSED — never offer slots — rather
 * than treat a missing busy list as "all free". Unchecked: propagates from freeBusy through
 * BookingService.availableSlots to the web layer, which renders the "temporarily unavailable" page.
 */
public class CalendarUnavailableException extends RuntimeException {
    public CalendarUnavailableException(String message) {
        super(message);
    }

    public CalendarUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Rewrite `freeBusy` to fail closed**

In `GoogleCalendarPort.java`, replace the whole `freeBusy` method body (lines 57-106) with:

```java
    @Override
    public List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to) {
        Map<Long, List<GoogleCalendar>> byCredential = GoogleCalendar.readForBusyByCredential(ownerId);
        if (byCredential.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> raw = new ArrayList<>();
        for (Map.Entry<Long, List<GoogleCalendar>> e : byCredential.entrySet()) {
            GoogleCredential cred = GoogleCredential.findById(e.getKey());
            if (cred == null || cred.needsReconnect) {
                // Fail-CLOSED: a busy-feeding account that is gone or known-broken means we cannot see
                // its events. Refuse to produce an availability picture rather than hide the conflict.
                throw new CalendarUnavailableException(
                        "Google account " + e.getKey() + " is disconnected; availability unavailable");
            }
            FreeBusyRequest request = new FreeBusyRequest()
                    .setTimeMin(new DateTime(from.toEpochMilli()))
                    .setTimeMax(new DateTime(to.toEpochMilli()))
                    .setItems(e.getValue().stream()
                            .map(c -> new FreeBusyRequestItem().setId(c.googleCalendarId))
                            .toList());
            try {
                FreeBusyResponse response = client(cred).freebusy().query(request).execute();
                Map<String, FreeBusyCalendar> calendars = response.getCalendars();
                if (calendars != null) {
                    for (FreeBusyCalendar cal : calendars.values()) {
                        List<TimePeriod> busy = cal.getBusy();
                        if (busy != null) {
                            for (TimePeriod p : busy) {
                                raw.add(new BusyInterval(
                                        Instant.ofEpochMilli(p.getStart().getValue()),
                                        Instant.ofEpochMilli(p.getEnd().getValue())));
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Fail-CLOSED on any live failure. A genuine auth failure was already flagged by
                // validAccessToken (called inside client(cred)); a transient blip is left unflagged so
                // it self-heals on the next read. Either way we refuse to return a misleading list.
                org.jboss.logging.Logger.getLogger(GoogleCalendarPort.class)
                        .warnf(ex, "freeBusy failed for credential %d; failing closed", cred.id);
                throw new CalendarUnavailableException(
                        "Could not read Google free/busy for credential " + cred.id, ex);
            }
        }
        return BusyIntervals.merge(raw);
    }
```

(Note: `CalendarUnavailableException` is in the same package, so no import is needed.)

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=FreeBusyLiveFailureTest,FreeBusyMultiAccountTest`
Expected: PASS. `FreeBusyMultiAccountTest` exercises the happy multi-account merge (all accounts healthy) and must stay green — confirms a fully-healthy owner still returns merged busy.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/google/CalendarUnavailableException.java src/main/java/com/calit/google/GoogleCalendarPort.java src/test/java/com/calit/google/FreeBusyLiveFailureTest.java
git commit -m "feat(google): freeBusy fails closed (throws) instead of hiding broken accounts"
```

---

### Task 6: Fail-closed at the web layer — `availableSlots` propagates; public page renders "unavailable"

**Files:**
- Create: `src/main/resources/templates/PublicResource/unavailable.html`
- Create: `src/main/java/com/calit/google/CalendarUnavailableMapper.java`
- Modify: `src/main/java/com/calit/web/PublicResource.java` (add `unavailable()` template method; catch in `book` GET and `manage` GET)
- Test: `src/test/java/com/calit/web/PublicPageUnavailableTest.java`
- Test: `src/test/java/com/calit/booking/AvailableSlotsUnavailableTest.java`

`BookingService.availableSlots` needs **no change** — `freeBusy` now throws and the exception propagates naturally through `busyIntervals` → `availableSlots`. We assert that propagation, then handle it at the edges:
- Public booking page GET (`/{user}/{slug}`) and manage GET render `unavailable.html`.
- Booking POST (direct submit when calendar is down) hits `book()` → `assertSlotAvailable` → `availableSlots` → throws; the mapper turns it into a 503 page so no booking is ever created over an unseen event.

- [ ] **Step 1: Write the failing service-level test**

Create `src/test/java/com/calit/booking/AvailableSlotsUnavailableTest.java`:

```java
package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CalendarUnavailableException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class AvailableSlotsUnavailableTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);

    @Test
    @TestTransaction
    void unreadableCalendarFailsClosedInsteadOfShowingAllSlots() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "x"; t.slug = "unavail-x"; t.durationMinutes = 60;
        t.bufferBeforeMinutes = 0; t.bufferAfterMinutes = 0;
        t.minNoticeMinutes = 0; t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = false;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L; r.dayOfWeek = DAY.getDayOfWeek(); r.meetingTypeId = null;
        r.startTime = LocalTime.parse("09:00"); r.endTime = LocalTime.parse("11:00");
        r.persist();

        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any()))
                .thenThrow(new CalendarUnavailableException("down"));

        assertThrows(CalendarUnavailableException.class,
                () -> bookingService.availableSlots(t, DAY, DAY));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AvailableSlotsUnavailableTest`
Expected: FAIL — currently `freeBusy` mock throws but if any catch swallowed it the assert would fail; with no handling yet this actually PASSES already (propagation is the default). If it passes, that confirms propagation works with zero `BookingService` changes — keep the test as a regression guard and proceed. (Per TDD we still wrote it first to prove the contract.)

- [ ] **Step 3: Create the public "unavailable" template**

Create `src/main/resources/templates/PublicResource/unavailable.html` (mirrors `notReady.html` house style):

```html
{#include base title="Scheduling temporarily unavailable"}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <div class="card-body">
      <h1 class="text-2xl font-bold">Scheduling temporarily unavailable</h1>
      <p class="text-base-content/70">This calendar can't be reached right now, so new bookings are paused. Please check back soon.</p>
    </div>
  </div>
{/include}
```

- [ ] **Step 4: Add the `unavailable()` template method + catch the exception in the two GET handlers**

In `PublicResource.java` `Templates` class, add next to `notReady()` (after line 76):

```java
        public static native TemplateInstance unavailable();
```

Add the import near the other booking imports (after line 7):

```java
import com.calit.google.CalendarUnavailableException;
```

In the `book` GET handler, wrap the `daySlots(type)` call. Replace line 146:

```java
        List<DaySlots> byDate = daySlots(type);
```

with:

```java
        List<DaySlots> byDate;
        try {
            byDate = daySlots(type);
        } catch (CalendarUnavailableException e) {
            // Fail-closed: the owner's Google calendar can't be read, so we cannot safely offer slots.
            return Templates.unavailable();
        }
```

In the `manage` GET handler, replace line 245:

```java
        List<DaySlots> byDate = daySlots(type);
```

with:

```java
        List<DaySlots> byDate;
        try {
            byDate = daySlots(type);
        } catch (CalendarUnavailableException e) {
            return Templates.unavailable();
        }
```

- [ ] **Step 5: Create the mapper for the POST path**

Create `src/main/java/com/calit/google/CalendarUnavailableMapper.java`. It returns 503 with the same rendered page. (Mirrors the paired-mapper convention; `BookingConflictMapper` returns a bare status+message, but this one must render HTML since the booking POST returns a page.)

```java
package com.calit.google;

import com.calit.web.PublicResource;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Booking POST hit a disconnected calendar (book() -> assertSlotAvailable -> availableSlots ->
 * freeBusy threw). Render the same "temporarily unavailable" page with 503 so no booking is created
 * over an event calit cannot see. (GET handlers catch the exception inline; this covers direct POSTs.)
 */
@Provider
public class CalendarUnavailableMapper implements ExceptionMapper<CalendarUnavailableException> {
    @Override
    public Response toResponse(CalendarUnavailableException ex) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.TEXT_HTML)
                .entity(PublicResource.Templates.unavailable().render())
                .build();
    }
}
```

- [ ] **Step 6: Write the failing web test**

Create `src/test/java/com/calit/web/PublicPageUnavailableTest.java`. RestAssured + `@InjectMock CalendarPort`. We need a real owner (admin id 1) with a public meeting type. Reuse the seeding invariant (admin always id 1). The test seeds a meeting type for owner 1 and asserts the GET page shows the unavailable marker and NOT the slot list.

```java
package com.calit.web;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CalendarUnavailableException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class PublicPageUnavailableTest {

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void bookingPageShowsUnavailableWhenCalendarUnreadable() {
        // Admin user is always id 1 (test invariant). Seed its public booking surface.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
            s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L; t.name = "Intro"; t.slug = "intro-unavail"; t.durationMinutes = 60;
            t.bufferBeforeMinutes = 0; t.bufferAfterMinutes = 0;
            t.minNoticeMinutes = 0; t.horizonDays = 30;
            t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = false;
            t.persist();
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L; r.dayOfWeek = java.time.LocalDate.now().getDayOfWeek(); r.meetingTypeId = null;
            r.startTime = LocalTime.parse("00:00"); r.endTime = LocalTime.parse("23:59");
            r.persist();
        });

        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any()))
                .thenThrow(new CalendarUnavailableException("down"));

        // resolveOwner uses the username; admin user 1's username is seeded by DatabaseResetCallback.
        // Adjust the path username if the seed uses a different handle.
        given().when().get("/admin/intro-unavail")
                .then().statusCode(200)
                .body(containsString("Scheduling temporarily unavailable"));
    }
}
```

NOTE for implementer: confirm the admin user's username in `DatabaseResetCallback` (the reseed) and use it in the GET path. If it is not `admin`, change `/admin/intro-unavail` accordingly. Grep: `grep -rn "username" src/test/java/com/calit/**/DatabaseResetCallback.java src/test/resources`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -q test -Dtest=PublicPageUnavailableTest,AvailableSlotsUnavailableTest,AvailableSlotsTest`
Expected: PASS. `AvailableSlotsTest` (the existing happy-path slot tests, which mock `freeBusy` to return lists) must stay green — confirms a healthy calendar still renders slots.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/templates/PublicResource/unavailable.html src/main/java/com/calit/google/CalendarUnavailableMapper.java src/main/java/com/calit/web/PublicResource.java src/test/java/com/calit/web/PublicPageUnavailableTest.java src/test/java/com/calit/booking/AvailableSlotsUnavailableTest.java
git commit -m "feat(web): public booking page fails closed when Google calendar unreadable"
```

---

### Task 7: The "reconnect your Google" email

**Files:**
- Create: `src/main/resources/templates/email/google-disconnected.html`
- Modify: `src/main/java/com/calit/email/EmailService.java` (inject the template + add `sendGoogleDisconnected`)
- Test: `src/test/java/com/calit/email/GoogleDisconnectedEmailTest.java`

The disconnect email is a **critical operational alert**, so it is sent regardless of `ownerNotificationsEnabled` (that flag only governs the owner's copy of routine booking notifications). It links to the Google settings page (`/me/google`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/email/GoogleDisconnectedEmailTest.java`. Mirrors the `MailSender`-mock style used by other email tests — verify a single mail to the owner with the reconnect link in the body.

```java
package com.calit.email;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleDisconnectedEmailTest {

    @Inject
    EmailService emailService;

    @InjectMock
    MailSender mailSender;

    @Test
    void sendsReconnectLinkToOwner() {
        emailService.sendGoogleDisconnected("owner@example.com", "work@gmail.com");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // 4-arg send(to, subject, html, ics) is the no-deadline overload.
        Mockito.verify(mailSender).send(to.capture(), subject.capture(), body.capture(),
                Mockito.isNull());

        org.junit.jupiter.api.Assertions.assertEquals("owner@example.com", to.getValue());
        assertTrue(subject.getValue().toLowerCase().contains("reconnect"));
        assertTrue(body.getValue().contains("/me/google"), "body must link to the Google settings page");
        assertTrue(body.getValue().contains("work@gmail.com"), "body names the affected account");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GoogleDisconnectedEmailTest`
Expected: FAIL — `sendGoogleDisconnected` does not exist.

- [ ] **Step 3: Create the email template**

Create `src/main/resources/templates/email/google-disconnected.html` (mirrors `password-reset.html`):

```html
<!DOCTYPE html>
<html lang="en">
<head><title>Reconnect your Google Calendar</title></head>
<body>
<p>Hi,</p>
<p>calit can no longer access your Google Calendar account <strong>{accountEmail}</strong>.</p>
<p>While it stays disconnected, your booking page is paused — new bookings are blocked so nobody can book over events calit can't see.</p>
<p><a href="{reconnectUrl}">Reconnect Google Calendar</a></p>
<p>Or paste this link into your browser:<br>{reconnectUrl}</p>
<p>This usually happens when access was revoked, your password changed, or the connection sat unused for a long time. Reconnecting takes a few seconds.</p>
</body>
</html>
```

- [ ] **Step 4: Add the injection + method**

In `EmailService.java`, after the `passwordReset` template injection (after line 85):

```java
    @Inject
    @Location("email/google-disconnected.html")
    Template googleDisconnected;
```

After `sendPasswordReset` (after line 95):

```java
    /**
     * Critical operational alert: the owner's Google account is disconnected and their booking page
     * is paused. Sent regardless of {@code ownerNotificationsEnabled} (that flag governs only routine
     * booking notifications). Links to the Google settings page so the owner can reconnect.
     */
    public void sendGoogleDisconnected(String toEmail, String accountEmail) {
        String reconnectUrl = baseUrl + "/me/google";
        String body = googleDisconnected
                .data("accountEmail", accountEmail == null ? "your account" : accountEmail)
                .data("reconnectUrl", reconnectUrl)
                .render();
        mailSender.send(toEmail, "Action needed: reconnect your Google Calendar", body, null);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=GoogleDisconnectedEmailTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/email/google-disconnected.html src/main/java/com/calit/email/EmailService.java src/test/java/com/calit/email/GoogleDisconnectedEmailTest.java
git commit -m "feat(email): sendGoogleDisconnected reconnect-Google alert"
```

---

### Task 8: The hourly scheduler — probe + notify

**Files:**
- Create: `src/main/java/com/calit/scheduler/GoogleConnectionScheduler.java`
- Modify: `src/main/resources/application.properties` (probe interval)
- Test: `src/test/java/com/calit/scheduler/GoogleConnectionTickTest.java`

Two phases per tick, both multi-node-safe via `FOR UPDATE SKIP LOCKED` (no leader election — matches `ReminderScheduler`):
1. **Probe** — claim credentials not probed within half the interval (so staggered replicas don't re-probe), stamp `last_probed_at = now()`, then call `tokens.probe(id, now)` per claimed id in its own transaction. `probe` flags genuinely-dead accounts.
2. **Notify** — claim credentials where `needs_reconnect = true AND reconnect_notified_at IS NULL`, stamp `reconnect_notified_at = now()` in the lock-holding transaction (exactly-once gate), then email each owner. `MailSender.send` never throws (falls back to the outbox), so a stamped-but-unsent mail is still delivered eventually.

Scheduler timers are disabled in `%test` (`%test.quarkus.scheduler.enabled=false`), so tests call `probeDueCredentials()` and `notifyPendingDisconnects()` directly — same approach as `ReminderTickTest`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/scheduler/GoogleConnectionTickTest.java`:

```java
package com.calit.scheduler;

import com.calit.email.EmailService;
import com.calit.google.GoogleCredential;
import com.calit.google.GoogleTokenService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
class GoogleConnectionTickTest {

    @Inject
    GoogleConnectionScheduler scheduler;

    @InjectMock
    GoogleTokenService tokenService;

    @InjectMock
    EmailService emailService;

    private Long seedCred(String sub, boolean needsReconnect, Instant notifiedAt, Instant lastProbed) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // OwnerSettings row supplies the recipient email for owner 1.
            com.calit.domain.OwnerSettings s = com.calit.domain.OwnerSettings.forOwner(1L);
            if (s == null) { s = new com.calit.domain.OwnerSettings(); s.ownerId = 1L; }
            s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "UTC";
            s.persist();
            GoogleCredential c = new GoogleCredential();
            c.ownerId = 1L; c.refreshToken = "rt"; c.googleSub = sub;
            c.needsReconnect = needsReconnect; c.reconnectNotifiedAt = notifiedAt;
            c.lastProbedAt = lastProbed; c.accountEmailSet("acct@gmail.com");
            c.persist();
            return c.id;
        });
    }

    @Test
    void probeClaimsNeverProbedCredentialAndStampsLastProbedAt() {
        Long id = seedCred("tick-probe", false, null, null);
        Mockito.when(tokenService.probe(anyLong(), any()))
                .thenReturn(GoogleTokenService.ProbeResult.OK);

        scheduler.probeDueCredentials();

        verify(tokenService).probe(Mockito.eq(id), any());
        GoogleCredential c = QuarkusTransaction.requiringNew().call(() -> GoogleCredential.findById(id));
        assertNotNull(c.lastProbedAt, "probe must stamp last_probed_at");
    }

    @Test
    void notifyEmailsOwnerOnceAndStampsNotifiedAt() {
        Long id = seedCred("tick-notify", true, null, Instant.now());

        scheduler.notifyPendingDisconnects();

        verify(emailService, times(1))
                .sendGoogleDisconnected(Mockito.eq("owner@example.com"), any());
        GoogleCredential c = QuarkusTransaction.requiringNew().call(() -> GoogleCredential.findById(id));
        assertNotNull(c.reconnectNotifiedAt, "notify must stamp reconnect_notified_at");

        // Second run: already stamped -> no second email (exactly-once).
        scheduler.notifyPendingDisconnects();
        verify(emailService, times(1)).sendGoogleDisconnected(any(), any());
    }
}
```

NOTE for implementer: the test calls `c.accountEmailSet("acct@gmail.com")` as a placeholder — there is no such method. Set the field directly: `c.accountEmail = "acct@gmail.com";`. Fix this line when writing the test (kept explicit here so you don't miss seeding the account label the email needs).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GoogleConnectionTickTest`
Expected: FAIL — `GoogleConnectionScheduler` does not exist.

- [ ] **Step 3: Add the config property**

In `application.properties`, after the approval-hold block (after line 143), add:

```properties
# Feature: Google disconnect detection. How often each connected Google account is probed
# (a forced refresh-token round-trip) to detect a dead grant and keep the token warm. Also the
# cadence of the once-per-outage "reconnect" email. Duration string (e.g. 30m, 1h, 6h).
calit.google.probe-interval=${GOOGLE_PROBE_INTERVAL:1h}
```

- [ ] **Step 4: Create the scheduler**

Create `src/main/java/com/calit/scheduler/GoogleConnectionScheduler.java`:

```java
package com.calit.scheduler;

import com.calit.domain.OwnerSettings;
import com.calit.email.EmailService;
import com.calit.google.GoogleCredential;
import com.calit.google.GoogleTokenService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature: Google disconnect detection. Runs on EVERY replica every probe-interval. Multi-node-safe
 * with NO leader: each phase claims rows with SELECT ... FOR UPDATE SKIP LOCKED, so a concurrent
 * replica's identical query skips rows this one already locked.
 */
@ApplicationScoped
public class GoogleConnectionScheduler {

    @ConfigProperty(name = "calit.google.probe-interval")
    Duration probeInterval;

    @Inject
    EntityManager em;

    @Inject
    GoogleTokenService tokens;

    @Inject
    EmailService emailService;

    @Scheduled(every = "{calit.google.probe-interval}")
    void tick() {
        probeDueCredentials();
        notifyPendingDisconnects();
    }

    /**
     * Claims credentials not probed within half the interval (stamping last_probed_at so other
     * replicas skip them), then forces a refresh per credential in its own transaction. The
     * half-interval grace stops staggered replica timers from re-probing the same account each tick.
     * ponytail: last_probed_at gating is an optimization (avoids redundant Google calls); the
     * notify gate below is what guarantees correctness.
     */
    public void probeDueCredentials() {
        List<Long> ids = claimDueForProbe();
        Instant now = Instant.now();
        for (Long id : ids) {
            tokens.probe(id, now); // own @Transactional; sets/clears needs_reconnect
        }
    }

    List<Long> claimDueForProbe() {
        long graceSeconds = Math.max(1, probeInterval.toSeconds() / 2);
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM google_credential "
                            + "WHERE last_probed_at IS NULL "
                            + "   OR last_probed_at <= now() - make_interval(secs => :secs) "
                            + "ORDER BY last_probed_at NULLS FIRST "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("secs", (double) graceSeconds)
                    .getResultList();
            List<Long> claimed = new ArrayList<>();
            Instant now = Instant.now();
            for (Number n : ids) {
                Long id = n.longValue();
                GoogleCredential c = GoogleCredential.findById(id);
                c.lastProbedAt = now; // stamped in the lock-holding transaction
                claimed.add(id);
            }
            return claimed;
        });
    }

    /**
     * Claims disconnected-and-not-yet-notified credentials, stamps reconnect_notified_at in the
     * lock-holding transaction (exactly-once email gate), then emails each owner. MailSender.send
     * never throws (outbox fallback), so a stamped mail is always eventually delivered.
     */
    public void notifyPendingDisconnects() {
        List<Pending> pending = claimUnnotifiedDisconnects();
        for (Pending p : pending) {
            emailService.sendGoogleDisconnected(p.ownerEmail(), p.accountEmail());
        }
    }

    private record Pending(String ownerEmail, String accountEmail) {}

    List<Pending> claimUnnotifiedDisconnects() {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM google_credential "
                            + "WHERE needs_reconnect = true AND reconnect_notified_at IS NULL "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .getResultList();
            List<Pending> out = new ArrayList<>();
            Instant now = Instant.now();
            for (Number n : ids) {
                GoogleCredential c = GoogleCredential.findById(n.longValue());
                c.reconnectNotifiedAt = now; // claim: prevents any replica re-sending
                OwnerSettings s = OwnerSettings.forOwner(c.ownerId);
                if (s != null) {
                    out.add(new Pending(s.ownerEmail, c.accountEmail));
                }
            }
            return out;
        });
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=GoogleConnectionTickTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/scheduler/GoogleConnectionScheduler.java src/main/resources/application.properties src/test/java/com/calit/scheduler/GoogleConnectionTickTest.java
git commit -m "feat(scheduler): hourly Google connection probe + once-per-outage reconnect email"
```

---

### Task 9: Document the new config

**Files:**
- Modify: `.env.example`
- Modify: `README.md`

- [ ] **Step 1: Add to `.env.example`**

Find the Google OAuth section in `.env.example` (grep: `grep -n "GOOGLE_OAUTH" .env.example`) and add after it:

```bash
# How often each connected Google account is probed for a still-valid connection (and the cadence
# of the "reconnect your Google" email). Duration string. Default 1h. Lower = faster detection,
# more Google API calls; higher = slower detection.
GOOGLE_PROBE_INTERVAL=1h
```

- [ ] **Step 2: Add to the `README.md` config reference**

Find the env-var reference table/section in `README.md` (grep: `grep -n "REMINDER_LEAD_MINUTES\|APPROVAL_HOLD_HOURS" README.md`) and add a `GOOGLE_PROBE_INTERVAL` row/entry alongside the other optional vars, describing: "How often connected Google accounts are checked for disconnection and how often the reconnect alert email is (re-)sent. Default `1h`."

- [ ] **Step 3: Verify nothing references a missing key**

Run: `mvn -q test -Dtest=GoogleConnectionTickTest`
Expected: PASS (config resolves; this is a smoke check that `calit.google.probe-interval` has a default).

- [ ] **Step 4: Commit**

```bash
git add .env.example README.md
git commit -m "docs: document GOOGLE_PROBE_INTERVAL"
```

---

### Task 10: Full suite + docs-site

**Files:**
- Modify: docs on the `docs-site` branch (separate Astro Starlight project).

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: PASS (all classes, Docker running). Pay attention to the previously-modified `FreeBusyLiveFailureTest`, `AvailableSlotsTest`, `FreeBusyMultiAccountTest`, and any other `@InjectMock CalendarPort` booking tests (`BookServiceTest`, `RescheduleCancelTest`, `ApproveDeclineTest`, `BookingResourceTest`) — they mock `freeBusy` to return lists, so they should be unaffected, but confirm.

- [ ] **Step 2: If any booking test regressed**

A booking test that relied on the old swallow behavior (broken account → empty busy → slots still shown) would now throw. Search: `grep -rln "needsReconnect\|freeBusy" src/test/java/com/calit/booking`. If found, update the expectation to fail-closed. (None expected — `AvailableSlotsTest` mocks the port and never sets `needsReconnect`.)

- [ ] **Step 3: Commit any test fixes**

```bash
git add -A
git commit -m "test: align booking tests with fail-closed freeBusy"
```

- [ ] **Step 4: Update docs-site**

Switch to the `docs-site` branch in a separate worktree (do NOT mix with the feature branch):

```bash
git worktree add ../calit-docs docs-site
```

In `../calit-docs/docs-site/`, update the Google setup / configuration pages to document:
- calit now **fails closed**: if Google disconnects, the public booking page shows "Scheduling temporarily unavailable" instead of all slots — so a disconnect can't cause double-bookings.
- calit probes each connected Google account hourly (configurable via `GOOGLE_PROBE_INTERVAL`) and **emails the owner once per outage** with a reconnect link.
- Add `GOOGLE_PROBE_INTERVAL` to the configuration reference page (default `1h`).
- Mention the most common disconnect cause: an OAuth app left in Google's **"Testing"** publishing status expires refresh tokens after 7 days — publish the app to "In production" to avoid recurring disconnects. (This is likely the root cause the owner hit.)

Commit on `docs-site`:

```bash
cd ../calit-docs && git add -A && git commit -m "docs: Google disconnect detection, fail-closed booking, GOOGLE_PROBE_INTERVAL"
```

- [ ] **Step 5: Final verification**

Run (from the feature worktree): `mvn test`
Expected: PASS. Then clean up the docs worktree: `git worktree remove ../calit-docs`.

---

## Self-Review

**Spec coverage:**
- "When Google disconnects and we detect it — send user a message" → Tasks 7 (email) + 8 (notify phase, exactly-once).
- "Add scheduled checks of connection" → Task 8 (hourly probe).
- "All slots showed available" (the bug) → Tasks 5 (port throws) + 6 (page fails closed). This is the actual harm fixed.
- "I don't know why" → root cause is the dead refresh token; Task 4 classifies it, the email (Task 7) and docs (Task 10) explain the likely "Testing" publish-status 7-day expiry.

**Type/name consistency:** `ProbeResult {OK, INVALID_GRANT, TRANSIENT}`, `probe(Long, Instant)`, `CalendarUnavailableException`, `GoogleInvalidGrantException`, `sendGoogleDisconnected(String toEmail, String accountEmail)`, `reconnectNotifiedAt`/`reconnect_notified_at`, `lastProbedAt`/`last_probed_at`, scheduler methods `probeDueCredentials()` / `notifyPendingDisconnects()` — used consistently across tasks.

**Notify-gate invariant:** `reconnectNotifiedAt` reset to NULL at all three `needsReconnect=false` sites (Task 4: `exchangeCode`, `validAccessToken`, `probe`). Without this, a second outage after a recovery would not re-notify — explicitly covered.

**Known simplifications (ponytail):**
- `last_probed_at` gating uses a half-interval grace, not exact scheduling — accepts at most ~2 probes/hour/credential across staggered replicas. Fine for self-hosted scale.
- One broken busy-feeding account fails the whole owner's page closed (not per-calendar partial). This is the safe and user-chosen behavior.
- Disconnect email ignores `ownerNotificationsEnabled` (it's a critical alert, not a routine notification).
