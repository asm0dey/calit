# Plan 2 — Google Calendar Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implement **after Plan 1** — this plan reuses the bootstrapped project (pom.xml, application.properties, Flyway, Dev Services).

**Goal:** Dual-way Google Calendar sync for the single owner: one-time OAuth2 (offline refresh token, stored once), reading FREE/BUSY across N configured calendars, writing events to ONE configured calendar, and auto-creating a Google Meet link on each created event. Exposes a clean `CalendarPort` seam so downstream plans (booking/reschedule/cancel) consume it and mock it in tests.

**Architecture:** Two Panache entities persist owner state: `GoogleCredential` (a singleton holding the OAuth refresh/access tokens) and `GoogleCalendar` (the owner's chosen read/write calendars). A JAX-RS layer (`GoogleOAuthResource`, `GoogleCalendarResource`) drives the consent flow and the calendar-selection UX. A `GoogleTokenService` exchanges the auth code, persists the refresh token, and always hands out a fresh access token (refreshing when expired). The `CalendarPort` interface is the public contract; `GoogleCalendarPort` is the `@ApplicationScoped` implementation that talks to the Google Calendar Java client. The overlapping-interval **merge logic is extracted into a pure static helper `BusyIntervals.merge(...)`** so it is unit-testable without Google. Conference (Meet) links are requested via `conferenceData.createRequest` with `setConferenceDataVersion(1)`. Google is **never** called from tests; downstream code mocks `CalendarPort` with `@InjectMock`.

**Tech Stack:** Java 25, Quarkus 3.35.3, Hibernate ORM Panache, PostgreSQL, Flyway, quarkus-rest (+jackson), Google API Java client (`google-api-client`, `google-oauth-client`, `google-api-services-calendar`), JUnit 5, RestAssured, `quarkus-junit5-mockito`. Tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**. No live Google credentials are required for any test.

---

### Task 1: Maven deps + Google OAuth config keys

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the Google client + Mockito test dependencies to `pom.xml`**

In `pom.xml`, inside the existing `<dependencies>` block (right before the closing `</dependencies>`), add:

```xml
    <!-- Google Calendar sync (Plan 2) -->
    <dependency>
      <groupId>com.google.api-client</groupId>
      <artifactId>google-api-client</artifactId>
      <version>2.7.0</version>
    </dependency>
    <dependency>
      <groupId>com.google.oauth-client</groupId>
      <artifactId>google-oauth-client</artifactId>
      <version>1.36.0</version>
    </dependency>
    <dependency>
      <groupId>com.google.apis</groupId>
      <artifactId>google-api-services-calendar</artifactId>
      <version>v3-rev20240906-2.0.0</version>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5-mockito</artifactId>
      <scope>test</scope>
    </dependency>
```

> **Note:** The Google libraries pull in `google-http-client` and `google-http-client-jackson2` transitively; do not add them explicitly. The `google-api-services-calendar` BOM-aligned version above is compatible with `google-api-client` 2.7.0.

- [ ] **Step 2: Add Google OAuth config keys to `src/main/resources/application.properties`**

Append to `src/main/resources/application.properties`:

```properties
# --- Google OAuth (Plan 2) ---
# All env-driven; never commit real secrets. Empty defaults keep the app booting without Google configured.
google.oauth.client-id=${GOOGLE_OAUTH_CLIENT_ID:}
google.oauth.client-secret=${GOOGLE_OAUTH_CLIENT_SECRET:}
google.oauth.redirect-uri=${GOOGLE_OAUTH_REDIRECT_URI:http://localhost:8080/api/google/callback}
# Comma-free scope; calendar read/write + freebusy.
google.oauth.scope=https://www.googleapis.com/auth/calendar
# CSRF state signing key. Stateless across replicas: every replica must share this secret so any
# replica can mint the state at /connect and any (other) replica can verify it at /callback without
# server-side session. Set a strong random value in %prod via env.
google.oauth.state-secret=${GOOGLE_OAUTH_STATE_SECRET:dev-only-insecure-state-secret-change-me}
# Application name sent to the Google Calendar client.
google.application-name=calit
```

- [ ] **Step 3: Confirm the project still builds with the new deps**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. The new dependencies resolve and download; the existing smoke test from Plan 1 still passes (Dev Services Postgres + Flyway V1 boot cleanly).

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "chore: add Google API client deps and OAuth config keys"
```

---

### Task 2: Flyway V2 schema (google_credential, google_calendar)

**Files:**
- Create: `src/main/resources/db/migration/V2__google.sql`

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V2__google.sql`:

```sql
-- Owner OAuth tokens. Singleton row (id = 1).
CREATE TABLE google_credential (
    id                  BIGINT       PRIMARY KEY,
    refresh_token       TEXT         NOT NULL,
    access_token        TEXT,
    access_token_expiry TIMESTAMPTZ
);

-- Calendars the owner chose to read (busy) from and/or write to.
CREATE TABLE google_calendar (
    id                 BIGSERIAL    PRIMARY KEY,
    google_calendar_id VARCHAR(255) NOT NULL UNIQUE,
    summary            VARCHAR(255) NOT NULL,
    read_for_busy      BOOLEAN      NOT NULL DEFAULT FALSE,
    write_target       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- At most one write-target calendar: partial unique index over the rows where write_target is true.
CREATE UNIQUE INDEX idx_google_calendar_single_write_target
    ON google_calendar (write_target)
    WHERE write_target = TRUE;
```

- [ ] **Step 2: Verify the schema applies at startup**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. Flyway runs V1 then V2 at boot against the Dev Services DB; no migration errors in the log.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V2__google.sql
git commit -m "feat: add google_credential and google_calendar schema (V2)"
```

---

### Task 3: GoogleCredential entity (singleton)

**Files:**
- Create: `src/main/java/com/calit/google/GoogleCredential.java`
- Test: `src/test/java/com/calit/google/GoogleCredentialTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/google/GoogleCredentialTest.java`:

```java
package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleCredentialTest {

    @Test
    @TestTransaction
    void getReturnsNullWhenNotConnected() {
        assertNull(GoogleCredential.get());
    }

    @Test
    @TestTransaction
    void persistsAndReadsSingletonWithTokens() {
        GoogleCredential c = new GoogleCredential();
        c.id = GoogleCredential.SINGLETON_ID;
        c.refreshToken = "refresh-abc";
        c.accessToken = "access-xyz";
        c.accessTokenExpiry = Instant.parse("2030-01-01T00:00:00Z");
        c.persist();

        GoogleCredential loaded = GoogleCredential.get();
        assertNotNull(loaded);
        assertEquals(GoogleCredential.SINGLETON_ID, loaded.id);
        assertEquals("refresh-abc", loaded.refreshToken);
        assertEquals("access-xyz", loaded.accessToken);
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), loaded.accessTokenExpiry);
    }

    @Test
    @TestTransaction
    void accessTokenIsExpiredWhenNullOrPast() {
        GoogleCredential c = new GoogleCredential();
        c.id = GoogleCredential.SINGLETON_ID;
        c.refreshToken = "refresh-abc";
        // No access token yet.
        assertTrue(c.isAccessTokenExpired(Instant.parse("2026-06-08T00:00:00Z")));

        c.accessToken = "access-xyz";
        c.accessTokenExpiry = Instant.parse("2026-06-08T00:00:00Z");
        // Exactly at expiry counts as expired (with safety margin).
        assertTrue(c.isAccessTokenExpired(Instant.parse("2026-06-08T00:00:00Z")));
        // Comfortably before expiry: not expired.
        assertEquals(false, c.isAccessTokenExpired(Instant.parse("2026-06-07T23:00:00Z")));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=GoogleCredentialTest`
Expected: FAIL — compilation error, `GoogleCredential` does not exist.

- [ ] **Step 3: Write the entity**

`src/main/java/com/calit/google/GoogleCredential.java`:

```java
package com.calit.google;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "google_credential")
public class GoogleCredential extends PanacheEntityBase {

    public static final long SINGLETON_ID = 1L;

    /** Refresh the access token this long before its real expiry to avoid edge-of-expiry failures. */
    public static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);

    @Id
    public Long id;

    /** Long-lived offline refresh token. Obtained once during the consent flow. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    public String refreshToken;

    /** Short-lived access token, refreshed on demand. Null until first refresh. */
    @Column(name = "access_token", columnDefinition = "text")
    public String accessToken;

    /** Instant the current access token stops being valid. Null when no access token is cached. */
    @Column(name = "access_token_expiry")
    public Instant accessTokenExpiry;

    /** The single credential row, or null if Google is not yet connected. */
    public static GoogleCredential get() {
        return findById(SINGLETON_ID);
    }

    /** True when there is no cached access token, or it expires within the safety margin of {@code now}. */
    public boolean isAccessTokenExpired(Instant now) {
        if (accessToken == null || accessTokenExpiry == null) {
            return true;
        }
        return !now.plus(SAFETY_MARGIN).isBefore(accessTokenExpiry);
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=GoogleCredentialTest`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCredential.java \
  src/test/java/com/calit/google/GoogleCredentialTest.java
git commit -m "feat: add GoogleCredential singleton entity with expiry check"
```

---

### Task 4: GoogleCalendar entity (read/write selection)

**Files:**
- Create: `src/main/java/com/calit/google/GoogleCalendar.java`
- Test: `src/test/java/com/calit/google/GoogleCalendarTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/google/GoogleCalendarTest.java`:

```java
package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleCalendarTest {

    @Test
    @TestTransaction
    void listsOnlyReadForBusyCalendars() {
        cal("work@example.com", "Work", true, false);
        cal("busy@example.com", "Side", true, false);
        cal("ignored@example.com", "Ignored", false, false);

        List<GoogleCalendar> readers = GoogleCalendar.readForBusy();

        assertEquals(2, readers.size());
        assertTrue(readers.stream().allMatch(c -> c.readForBusy));
    }

    @Test
    @TestTransaction
    void returnsTheSingleWriteTarget() {
        cal("read@example.com", "Read", true, false);
        cal("write@example.com", "Write", false, true);

        GoogleCalendar target = GoogleCalendar.writeTarget();

        assertNotNull(target);
        assertEquals("write@example.com", target.googleCalendarId);
    }

    @Test
    @TestTransaction
    void writeTargetIsNullWhenNoneSelected() {
        cal("read@example.com", "Read", true, false);
        assertNull(GoogleCalendar.writeTarget());
    }

    private GoogleCalendar cal(String id, String summary, boolean read, boolean write) {
        GoogleCalendar c = new GoogleCalendar();
        c.googleCalendarId = id;
        c.summary = summary;
        c.readForBusy = read;
        c.writeTarget = write;
        c.persist();
        return c;
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=GoogleCalendarTest`
Expected: FAIL — compilation error, `GoogleCalendar` does not exist.

- [ ] **Step 3: Write the entity**

`src/main/java/com/calit/google/GoogleCalendar.java`:

```java
package com.calit.google;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "google_calendar")
public class GoogleCalendar extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The Google-side calendar id (often an email address or an opaque id). */
    @Column(name = "google_calendar_id", nullable = false, unique = true)
    public String googleCalendarId;

    @Column(nullable = false)
    public String summary;

    /** Include this calendar's busy blocks when computing free/busy. */
    @Column(name = "read_for_busy", nullable = false)
    public boolean readForBusy = false;

    /** Create new booking events on this calendar. At most one row may have this true. */
    @Column(name = "write_target", nullable = false)
    public boolean writeTarget = false;

    /** All calendars whose busy time should be subtracted from availability. */
    public static List<GoogleCalendar> readForBusy() {
        return list("readForBusy = true");
    }

    /** The single calendar new events are written to, or null if none is selected yet. */
    public static GoogleCalendar writeTarget() {
        return find("writeTarget = true").firstResult();
    }

    /** Upsert by Google calendar id; returns the managed row. */
    public static GoogleCalendar findByGoogleId(String googleCalendarId) {
        return find("googleCalendarId", googleCalendarId).firstResult();
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=GoogleCalendarTest`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCalendar.java \
  src/test/java/com/calit/google/GoogleCalendarTest.java
git commit -m "feat: add GoogleCalendar entity with read/write selection queries"
```

---

### Task 5: Public contract — BusyInterval, CreatedEvent, CalendarPort + pure BusyIntervals.merge

**Files:**
- Create: `src/main/java/com/calit/google/BusyInterval.java`
- Create: `src/main/java/com/calit/google/CreatedEvent.java`
- Create: `src/main/java/com/calit/google/CalendarPort.java`
- Create: `src/main/java/com/calit/google/BusyIntervals.java`
- Test: `src/test/java/com/calit/google/BusyIntervalsTest.java`

> **Contract note (Plans 3 & 4 depend on these EXACT signatures):**
> ```java
> public record BusyInterval(java.time.Instant start, java.time.Instant end) {}
> public record CreatedEvent(String googleEventId, String meetLink, String htmlLink) {}
> public interface CalendarPort {
>     java.util.List<BusyInterval> freeBusy(java.time.Instant from, java.time.Instant to);
>     CreatedEvent createEvent(String summary, String description,
>                              java.time.Instant start, java.time.Instant end,
>                              java.util.List<String> attendeeEmails);
>     void updateEvent(String eventId, java.time.Instant start, java.time.Instant end);
>     void deleteEvent(String eventId);
> }
> ```

- [ ] **Step 1: Write the failing pure-helper test (plain JUnit, no Quarkus)**

`src/test/java/com/calit/google/BusyIntervalsTest.java`:

```java
package com.calit.google;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusyIntervalsTest {

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    private static BusyInterval bi(String from, String to) {
        return new BusyInterval(t(from), t(to));
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertTrue(BusyIntervals.merge(List.of()).isEmpty());
    }

    @Test
    void nonOverlappingIntervalsKeptSeparateAndSorted() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z"),
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z")));

        assertEquals(2, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"), merged.get(0));
        assertEquals(bi("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z"), merged.get(1));
    }

    @Test
    void overlappingIntervalsAreMerged() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:30:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")));

        assertEquals(1, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"), merged.get(0));
    }

    @Test
    void adjacentTouchingIntervalsAreMerged() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")));

        assertEquals(1, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"), merged.get(0));
    }

    @Test
    void fullyContainedIntervalIsAbsorbed() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")));

        assertEquals(1, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z"), merged.get(0));
    }

    @Test
    void outOfOrderMixIsSortedAndMergedCorrectly() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T14:00:00Z", "2026-06-08T15:00:00Z"),
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"),
                bi("2026-06-08T09:30:00Z", "2026-06-08T11:00:00Z"),
                bi("2026-06-08T14:30:00Z", "2026-06-08T16:00:00Z")));

        assertEquals(2, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"), merged.get(0));
        assertEquals(bi("2026-06-08T14:00:00Z", "2026-06-08T16:00:00Z"), merged.get(1));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=BusyIntervalsTest`
Expected: FAIL — compilation error, `BusyInterval` / `BusyIntervals` do not exist.

- [ ] **Step 3: Write `BusyInterval`**

`src/main/java/com/calit/google/BusyInterval.java`:

```java
package com.calit.google;

import java.time.Instant;

/** A busy block in absolute UTC time. Half-open [start, end). */
public record BusyInterval(Instant start, Instant end) {}
```

- [ ] **Step 4: Write `CreatedEvent`**

`src/main/java/com/calit/google/CreatedEvent.java`:

```java
package com.calit.google;

/**
 * Result of creating a Google Calendar event.
 *
 * @param googleEventId Google's event id (used later for update/delete)
 * @param meetLink      the Google Meet join URL (hangoutLink), or null if none was generated
 * @param htmlLink      the calendar.google.com web link to the event
 */
public record CreatedEvent(String googleEventId, String meetLink, String htmlLink) {}
```

- [ ] **Step 5: Write `CalendarPort`**

`src/main/java/com/calit/google/CalendarPort.java`:

```java
package com.calit.google;

import java.time.Instant;
import java.util.List;

/**
 * Owner-level calendar operations. The real implementation talks to Google;
 * downstream tests replace it with a Mockito {@code @InjectMock}.
 */
public interface CalendarPort {

    /** Merged busy intervals across all read-for-busy calendars within [from, to). */
    List<BusyInterval> freeBusy(Instant from, Instant to);

    /** Create an event on the write-target calendar with an auto-generated Google Meet link. */
    CreatedEvent createEvent(String summary, String description,
                             Instant start, Instant end,
                             List<String> attendeeEmails);

    /** Move an existing event to a new time window (reschedule). */
    void updateEvent(String eventId, Instant start, Instant end);

    /** Remove an existing event (cancel). */
    void deleteEvent(String eventId);
}
```

- [ ] **Step 6: Write the pure `BusyIntervals` helper**

`src/main/java/com/calit/google/BusyIntervals.java`:

```java
package com.calit.google;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure interval algebra for busy time. No Google or CDI dependencies — unit-tested directly.
 */
public final class BusyIntervals {

    private BusyIntervals() {
    }

    /**
     * Sort the given intervals by start, then collapse any that overlap or merely touch
     * (end == next.start) into single intervals. Returns a new immutable-friendly list;
     * the input is never mutated.
     */
    public static List<BusyInterval> merge(List<BusyInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(BusyInterval::start).thenComparing(BusyInterval::end));

        List<BusyInterval> merged = new ArrayList<>();
        Instant curStart = sorted.get(0).start();
        Instant curEnd = sorted.get(0).end();

        for (int i = 1; i < sorted.size(); i++) {
            BusyInterval next = sorted.get(i);
            if (!next.start().isAfter(curEnd)) {
                // Overlapping or touching: extend the current block.
                if (next.end().isAfter(curEnd)) {
                    curEnd = next.end();
                }
            } else {
                merged.add(new BusyInterval(curStart, curEnd));
                curStart = next.start();
                curEnd = next.end();
            }
        }
        merged.add(new BusyInterval(curStart, curEnd));
        return merged;
    }
}
```

- [ ] **Step 7: Run it to confirm it passes**

Run: `mvn test -Dtest=BusyIntervalsTest`
Expected: PASS (all 6 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/calit/google/BusyInterval.java \
  src/main/java/com/calit/google/CreatedEvent.java \
  src/main/java/com/calit/google/CalendarPort.java \
  src/main/java/com/calit/google/BusyIntervals.java \
  src/test/java/com/calit/google/BusyIntervalsTest.java
git commit -m "feat: add CalendarPort contract and pure BusyIntervals.merge helper"
```

---

### Task 6: GoogleTokenService — code exchange + access-token refresh

**Files:**
- Create: `src/main/java/com/calit/google/GoogleOAuthConfig.java`
- Create: `src/main/java/com/calit/google/GoogleTokenService.java`
- Test: `src/test/java/com/calit/google/GoogleTokenServiceTest.java`

> **Design:** `GoogleTokenService` owns the OAuth round-trips so the resources and the port stay thin. Two seams keep it testable without hitting Google:
> 1. The consent-URL builder is a **pure string method** (`buildConsentUrl()`) — assertable directly.
> 2. The HTTP exchange/refresh is funneled through a single protected method `requestToken(...)` returning a `TokenResponse` record; the test subclasses the service to stub that one method, so `exchangeCode` / `validAccessToken` persistence logic is covered with zero network calls.
>
> **Horizontal-scalability note (shared-DB token state):** The app runs as N stateless replicas, so the access token must NOT be cached in instance memory. `validAccessToken` reads the singleton `GoogleCredential` from Postgres, and when it refreshes it **writes the new access token + expiry (and a rotated refresh token, if Google returns one) back to the shared `GoogleCredential` row inside the same `@Transactional` method**. Every replica therefore re-reads the row each call and benefits from any other replica's refresh; no replica holds a stale in-process token.
> A concurrent refresh from two replicas is tolerable and needs **no distributed lock**: Google may hand each replica its own fresh access token; both write to the same row (last-write-wins) and either token stays valid until its own expiry. The long-lived refresh token is unchanged unless Google rotates it, in which case the rotated value is persisted the same way. Correctness never depends on which replica wins.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/google/GoogleTokenServiceTest.java`:

```java
package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleTokenServiceTest {

    @Inject
    GoogleOAuthConfig config;

    /** Subclass that stubs the single network call so no Google traffic happens in tests. */
    static class StubTokenService extends GoogleTokenService {
        TokenResponse next;

        StubTokenService(GoogleOAuthConfig config, TokenResponse next) {
            super(config);
            this.next = next;
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return next;
        }
    }

    @Test
    void buildConsentUrlIncludesOfflineAndConsentAndScope() {
        GoogleTokenService svc = new GoogleTokenService(config);
        String url = svc.buildConsentUrl();

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("prompt=consent"));
        assertTrue(url.contains("response_type=code"));
        // Scope is URL-encoded (':' -> %3A, '/' -> %2F).
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcalendar"));
        // A signed, non-empty CSRF state is present (stateless — no HttpSession).
        assertTrue(url.contains("&state="));
    }

    @Test
    void stateRoundTripsStatelesslyWithinTtl() {
        GoogleTokenService svc = new GoogleTokenService(config);
        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        String state = svc.issueState(now);

        // A fresh, untampered state validates on any replica using only the shared secret.
        assertTrue(svc.validateState(state, now.plusSeconds(60)));
        // Expired beyond the TTL window: rejected.
        assertEquals(false, svc.validateState(state, now.plus(GoogleTokenService.STATE_TTL).plusSeconds(1)));
        // Tampered signature: rejected.
        assertEquals(false, svc.validateState(state + "x", now.plusSeconds(60)));
        // Garbage / missing: rejected.
        assertEquals(false, svc.validateState("not-a-state", now));
        assertEquals(false, svc.validateState(null, now));
    }

    @Test
    @TestTransaction
    void exchangeCodePersistsRefreshTokenSingleton() {
        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        StubTokenService svc = new StubTokenService(config,
                new GoogleTokenService.TokenResponse("access-1", "refresh-1",
                        now.plusSeconds(3600)));

        svc.exchangeCode("auth-code-123", now);

        GoogleCredential c = GoogleCredential.get();
        assertNotNull(c);
        assertEquals(GoogleCredential.SINGLETON_ID, c.id);
        assertEquals("refresh-1", c.refreshToken);
        assertEquals("access-1", c.accessToken);
        assertEquals(now.plusSeconds(3600), c.accessTokenExpiry);
    }

    @Test
    @TestTransaction
    void validAccessTokenReturnsCachedWhenNotExpired() {
        GoogleCredential c = new GoogleCredential();
        c.id = GoogleCredential.SINGLETON_ID;
        c.refreshToken = "refresh-1";
        c.accessToken = "cached-access";
        c.accessTokenExpiry = Instant.parse("2026-06-08T13:00:00Z");
        c.persist();

        StubTokenService svc = new StubTokenService(config, null); // must NOT be used
        String token = svc.validAccessToken(Instant.parse("2026-06-08T12:00:00Z"));

        assertEquals("cached-access", token);
    }

    @Test
    @TestTransaction
    void validAccessTokenRefreshesWhenExpired() {
        GoogleCredential c = new GoogleCredential();
        c.id = GoogleCredential.SINGLETON_ID;
        c.refreshToken = "refresh-1";
        c.accessToken = "stale-access";
        c.accessTokenExpiry = Instant.parse("2026-06-08T12:00:00Z");
        c.persist();

        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        StubTokenService svc = new StubTokenService(config,
                new GoogleTokenService.TokenResponse("fresh-access", null,
                        now.plusSeconds(3600)));

        String token = svc.validAccessToken(now);

        assertEquals("fresh-access", token);
        GoogleCredential reloaded = GoogleCredential.get();
        assertEquals("fresh-access", reloaded.accessToken);
        // Refresh responses omit a new refresh token; the original is preserved.
        assertEquals("refresh-1", reloaded.refreshToken);
        assertEquals(now.plusSeconds(3600), reloaded.accessTokenExpiry);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=GoogleTokenServiceTest`
Expected: FAIL — compilation error, `GoogleOAuthConfig` / `GoogleTokenService` do not exist.

- [ ] **Step 3: Write `GoogleOAuthConfig`**

`src/main/java/com/calit/google/GoogleOAuthConfig.java`:

```java
package com.calit.google;

import io.smallrye.config.ConfigMapping;

/** Maps the {@code google.oauth.*} and {@code google.application-name} keys from application.properties. */
@ConfigMapping(prefix = "google")
public interface GoogleOAuthConfig {

    OAuth oauth();

    /** Application name reported to the Google Calendar client. */
    String applicationName();

    interface OAuth {
        String clientId();

        String clientSecret();

        String redirectUri();

        String scope();

        /** Shared HMAC key for signing the stateless CSRF {@code state}; identical on every replica. */
        String stateSecret();
    }
}
```

- [ ] **Step 4: Write `GoogleTokenService`**

`src/main/java/com/calit/google/GoogleTokenService.java`:

```java
package com.calit.google;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Handles the owner OAuth flow: builds the consent URL, exchanges the auth code for tokens
 * (persisting the offline refresh token once), and always returns a valid access token,
 * refreshing via the refresh token when the cached one has expired.
 */
@ApplicationScoped
public class GoogleTokenService {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** A minted CSRF state is only accepted back within this window. */
    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final String HMAC_ALGO = "HmacSHA256";

    protected final GoogleOAuthConfig config;

    @Inject
    public GoogleTokenService(GoogleOAuthConfig config) {
        this.config = config;
    }

    /** Normalized token data, independent of the Google client types (keeps the test seam clean). */
    public record TokenResponse(String accessToken, String refreshToken, Instant expiry) {}

    /** The Google consent URL the owner is redirected to. Pure string building — no network. */
    public String buildConsentUrl() {
        return buildConsentUrl(Instant.now());
    }

    /**
     * The Google consent URL, carrying a stateless, signed CSRF {@code state}. Pure string building.
     *
     * <p>Horizontal scalability: {@code /connect} and {@code /callback} can be served by different
     * replicas, so the state must be self-describing — no HttpSession. The state is
     * {@code base64url(nonce:issuedAtEpochSec) + "." + base64url(HMAC-SHA256(...))} signed with the
     * shared {@code google.oauth.state-secret}. Any replica validates it at {@code /callback} by
     * recomputing the HMAC and checking the {@link #STATE_TTL} window — no shared mutable state.
     */
    public String buildConsentUrl(Instant now) {
        return AUTH_ENDPOINT + "?"
                + "client_id=" + enc(config.oauth().clientId())
                + "&redirect_uri=" + enc(config.oauth().redirectUri())
                + "&response_type=code"
                + "&scope=" + enc(config.oauth().scope())
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + enc(issueState(now));
    }

    /** Mint a signed, time-stamped state value. Stateless: nothing is stored server-side. */
    public String issueState(Instant now) {
        String payload = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                + ":" + now.getEpochSecond();
        return payload + "." + b64(hmac(payload));
    }

    /**
     * Validate a state returned on the callback: signature must verify and the issue time must be
     * within {@link #STATE_TTL} of {@code now}. No server-side session or lock — any replica can do
     * this using only the shared secret. Returns false for any malformed, forged, or expired value.
     */
    public boolean validateState(String state, Instant now) {
        if (state == null || state.isBlank()) {
            return false;
        }
        int dot = state.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String payload = state.substring(0, dot);
        String sig = state.substring(dot + 1);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            return false;
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        try {
            long issuedAt = Long.parseLong(payload.substring(colon + 1));
            Instant issued = Instant.ofEpochSecond(issuedAt);
            return !issued.isAfter(now) && !issued.plus(STATE_TTL).isBefore(now);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    config.oauth().stateSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign OAuth state", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Exchange the callback {@code code} for tokens and persist the singleton credential. */
    @Transactional
    public void exchangeCode(String code, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        GoogleCredential c = GoogleCredential.get();
        if (c == null) {
            c = new GoogleCredential();
            c.id = GoogleCredential.SINGLETON_ID;
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.persist();
    }

    /**
     * Return a currently-valid access token, refreshing and persisting a new one when the
     * cached token is missing or within the safety margin of expiry.
     *
     * <p>Horizontal scalability: state lives in shared Postgres, never in instance memory.
     * Each call re-reads the singleton {@link GoogleCredential}; a refresh writes the new
     * access token + expiry (and any rotated refresh token) back to that row in this same
     * transaction so other replicas pick it up on their next read. A concurrent refresh from
     * another replica is safe — last write wins and either fresh token is valid until its own
     * expiry — so no distributed lock is needed.
     */
    @Transactional
    public String validAccessToken(Instant now) {
        GoogleCredential c = GoogleCredential.get();
        if (c == null) {
            throw new IllegalStateException("Google is not connected: no GoogleCredential. Run /api/google/connect.");
        }
        if (!c.isAccessTokenExpired(now)) {
            return c.accessToken;
        }
        TokenResponse resp = requestToken("refresh_token", c.refreshToken, now);
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        // Google only returns a new refresh token if it rotated the old one; persist it when present.
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        // Write the refreshed token back to the shared row so every replica benefits; no node-local cache.
        c.persist();
        return c.accessToken;
    }

    /**
     * The single network round-trip. Overridable so tests can stub it without touching Google.
     *
     * @param grantType            "authorization_code" or "refresh_token"
     * @param codeOrRefreshToken   the auth code (for exchange) or the refresh token (for refresh)
     */
    protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
        NetHttpTransport transport = new NetHttpTransport();
        GsonFactory json = GsonFactory.getDefaultInstance();
        try {
            if ("authorization_code".equals(grantType)) {
                var resp = new GoogleAuthorizationCodeTokenRequest(
                        transport, json, TOKEN_ENDPOINT,
                        config.oauth().clientId(), config.oauth().clientSecret(),
                        codeOrRefreshToken, config.oauth().redirectUri())
                        .execute();
                return new TokenResponse(
                        resp.getAccessToken(),
                        resp.getRefreshToken(),
                        now.plusSeconds(resp.getExpiresInSeconds()));
            }
            var resp = new GoogleRefreshTokenRequest(
                    transport, json, codeOrRefreshToken,
                    config.oauth().clientId(), config.oauth().clientSecret())
                    .execute();
            return new TokenResponse(
                    resp.getAccessToken(),
                    resp.getRefreshToken(),
                    now.plusSeconds(resp.getExpiresInSeconds()));
        } catch (TokenResponseException e) {
            throw new IllegalStateException("Google token request failed: " + e.getStatusCode()
                    + " " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Google token request I/O error", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=GoogleTokenServiceTest`
Expected: PASS (all 5 tests). No network is touched: `buildConsentUrl` and the state round-trip are pure; the exchange/refresh tests use `StubTokenService` to override `requestToken`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/google/GoogleOAuthConfig.java \
  src/main/java/com/calit/google/GoogleTokenService.java \
  src/test/java/com/calit/google/GoogleTokenServiceTest.java
git commit -m "feat: add GoogleTokenService (consent URL, code exchange, access-token refresh)"
```

---

### Task 7: GoogleCalendarClientFactory — build authorized Calendar client

**Files:**
- Create: `src/main/java/com/calit/google/GoogleCalendarClientFactory.java`

> **Design:** Building a Google `Calendar` service requires an access token, an HTTP transport, and a JSON factory. Isolating this in a factory keeps `GoogleCalendarPort` focused on mapping logic. It is **not** unit-tested directly (it only wires Google types together); it is exercised indirectly only against live Google, which we never do in CI. `GoogleCalendarPort` is the unit that downstream code mocks.

- [ ] **Step 1: Write the factory (no test — pure Google wiring)**

`src/main/java/com/calit/google/GoogleCalendarClientFactory.java`:

```java
package com.calit.google;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds an authorized Google Calendar service client from a bearer access token.
 * Pure wiring of Google client types; the testable behavior lives in GoogleCalendarPort
 * (mocked downstream) and GoogleTokenService (stubbed in tests).
 */
@ApplicationScoped
public class GoogleCalendarClientFactory {

    /** Scope reference kept to document the required grant; the token already carries it. */
    public static final String SCOPE = CalendarScopes.CALENDAR;

    private final GoogleOAuthConfig config;

    @Inject
    public GoogleCalendarClientFactory(GoogleOAuthConfig config) {
        this.config = config;
    }

    /** Build a Calendar service authorized with the given bearer access token. */
    public Calendar build(String accessToken) {
        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);
        return new Calendar.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName(config.applicationName())
                .build();
    }
}
```

- [ ] **Step 2: Confirm it compiles (full suite still green)**

Run: `mvn test -Dtest=GoogleCredentialTest,GoogleCalendarTest,BusyIntervalsTest,GoogleTokenServiceTest`
Expected: PASS. The factory compiles against the Google Calendar types; no new test is added because it is pure wiring.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCalendarClientFactory.java
git commit -m "feat: add GoogleCalendarClientFactory for authorized Calendar clients"
```

---

### Task 8: GoogleCalendarPort — the real CalendarPort implementation

**Files:**
- Create: `src/main/java/com/calit/google/GoogleCalendarPort.java`

> **Design:** `GoogleCalendarPort implements CalendarPort` and is `@ApplicationScoped`. It composes `GoogleTokenService`, `GoogleCalendarClientFactory`, the `GoogleCalendar` selection entity, and the pure `BusyIntervals.merge`. Because every Google call requires a valid access token + a built `Calendar` client (both of which need live Google or HTTP), this class is **not** unit-tested against Google; downstream plans mock the `CalendarPort` interface (Task 9 proves the seam). The mapping rules it implements:
> - `freeBusy`: build a `FreeBusyRequest` listing every `GoogleCalendar.readForBusy()` id; collect each calendar's `busy` ranges into `BusyInterval`s; return `BusyIntervals.merge(...)`.
> - `createEvent`: build an `Event` on `GoogleCalendar.writeTarget()`; attach a `ConferenceData` `createRequest` with a random `requestId` and Hangouts-Meet solution; call `events().insert(...).setConferenceDataVersion(1)`; map `getHangoutLink()` (falling back to the conference entry point) → `meetLink`, `getId()` → `googleEventId`, `getHtmlLink()` → `htmlLink`; add attendees.
> - `updateEvent`: patch only `start`/`end` on the write-target calendar.
> - `deleteEvent`: delete by id on the write-target calendar.

- [ ] **Step 1: Write the implementation (no Google-touching test — see Task 9 for the seam test)**

`src/main/java/com/calit/google/GoogleCalendarPort.java`:

```java
package com.calit.google;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.ConferenceSolutionKey;
import com.google.api.services.calendar.model.CreateConferenceRequest;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.FreeBusyCalendar;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import com.google.api.services.calendar.model.TimePeriod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real CalendarPort backed by the Google Calendar API. @ApplicationScoped so downstream
 * tests can replace it with a Mockito {@code @InjectMock CalendarPort}.
 */
@ApplicationScoped
public class GoogleCalendarPort implements CalendarPort {

    private final GoogleTokenService tokens;
    private final GoogleCalendarClientFactory clientFactory;

    @Inject
    public GoogleCalendarPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
    }

    private Calendar client() {
        return clientFactory.build(tokens.validAccessToken(Instant.now()));
    }

    @Override
    public List<BusyInterval> freeBusy(Instant from, Instant to) {
        List<GoogleCalendar> readers = GoogleCalendar.readForBusy();
        if (readers.isEmpty()) {
            return List.of();
        }
        FreeBusyRequest request = new FreeBusyRequest()
                .setTimeMin(new DateTime(from.toEpochMilli()))
                .setTimeMax(new DateTime(to.toEpochMilli()))
                .setItems(readers.stream()
                        .map(c -> new FreeBusyRequestItem().setId(c.googleCalendarId))
                        .toList());
        try {
            FreeBusyResponse response = client().freebusy().query(request).execute();
            List<BusyInterval> raw = new ArrayList<>();
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
            return BusyIntervals.merge(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("freeBusy query failed", e);
        }
    }

    @Override
    public CreatedEvent createEvent(String summary, String description,
                                    Instant start, Instant end,
                                    List<String> attendeeEmails) {
        GoogleCalendar target = requireWriteTarget();
        Event event = new Event()
                .setSummary(summary)
                .setDescription(description)
                .setStart(eventTime(start))
                .setEnd(eventTime(end));

        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            event.setAttendees(attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }

        // Request a fresh Google Meet conference for this event only.
        event.setConferenceData(new ConferenceData().setCreateRequest(
                new CreateConferenceRequest()
                        .setRequestId(UUID.randomUUID().toString())
                        .setConferenceSolutionKey(
                                new ConferenceSolutionKey().setType("hangoutsMeet"))));

        try {
            Event created = client().events()
                    .insert(target.googleCalendarId, event)
                    .setConferenceDataVersion(1)
                    .execute();
            return new CreatedEvent(created.getId(), extractMeetLink(created), created.getHtmlLink());
        } catch (IOException e) {
            throw new UncheckedIOException("createEvent failed", e);
        }
    }

    @Override
    public void updateEvent(String eventId, Instant start, Instant end) {
        GoogleCalendar target = requireWriteTarget();
        Event patch = new Event()
                .setStart(eventTime(start))
                .setEnd(eventTime(end));
        try {
            client().events().patch(target.googleCalendarId, eventId, patch).execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEvent failed", e);
        }
    }

    @Override
    public void deleteEvent(String eventId) {
        GoogleCalendar target = requireWriteTarget();
        try {
            client().events().delete(target.googleCalendarId, eventId).execute();
        } catch (IOException e) {
            throw new UncheckedIOException("deleteEvent failed", e);
        }
    }

    private GoogleCalendar requireWriteTarget() {
        GoogleCalendar target = GoogleCalendar.writeTarget();
        if (target == null) {
            throw new IllegalStateException("No write-target Google calendar selected. POST /api/google/calendars.");
        }
        return target;
    }

    private static EventDateTime eventTime(Instant instant) {
        return new EventDateTime()
                .setDateTime(new DateTime(instant.toEpochMilli()))
                .setTimeZone("UTC");
    }

    /** Prefer the top-level hangoutLink; fall back to the first video conference entry point. */
    private static String extractMeetLink(Event event) {
        if (event.getHangoutLink() != null) {
            return event.getHangoutLink();
        }
        ConferenceData cd = event.getConferenceData();
        if (cd != null && cd.getEntryPoints() != null) {
            return cd.getEntryPoints().stream()
                    .filter(ep -> "video".equals(ep.getEntryPointType()))
                    .map(ep -> ep.getUri())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
```

- [ ] **Step 2: Confirm it compiles (run the existing google suite)**

Run: `mvn test -Dtest=GoogleCredentialTest,GoogleCalendarTest,BusyIntervalsTest,GoogleTokenServiceTest`
Expected: PASS. `GoogleCalendarPort` compiles against the Google Calendar model classes and the `CalendarPort` interface; no Google call is made by any test.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCalendarPort.java
git commit -m "feat: add GoogleCalendarPort (freeBusy merge, Meet-link event create, update, delete)"
```

---

### Task 9: Prove the CalendarPort seam with an @InjectMock consumer

**Files:**
- Create: `src/main/java/com/calit/google/BusySummaryService.java`
- Test: `src/test/java/com/calit/google/CalendarPortMockSeamTest.java`

> **Why:** Plans 3 & 4 consume `CalendarPort` and replace `GoogleCalendarPort` with `@InjectMock`. This task adds a tiny CDI bean that injects `CalendarPort` and a test that mocks it with canned `BusyInterval`s — proving the seam works end-to-end (CDI resolves the mock over the real `@ApplicationScoped` bean, no Google contacted). `BusySummaryService` is a trivial, real consumer (counts merged busy minutes) so downstream plans have a worked example.

- [ ] **Step 1: Write the failing seam test**

`src/test/java/com/calit/google/CalendarPortMockSeamTest.java`:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
class CalendarPortMockSeamTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BusySummaryService service;

    @Test
    void consumerSeesMockedBusyIntervals() {
        Instant from = Instant.parse("2026-06-08T09:00:00Z");
        Instant to = Instant.parse("2026-06-08T17:00:00Z");

        when(calendarPort.freeBusy(from, to)).thenReturn(List.of(
                new BusyInterval(Instant.parse("2026-06-08T09:00:00Z"), Instant.parse("2026-06-08T10:00:00Z")),
                new BusyInterval(Instant.parse("2026-06-08T11:00:00Z"), Instant.parse("2026-06-08T11:30:00Z"))));

        long busyMinutes = service.busyMinutes(from, to);

        assertEquals(90, busyMinutes);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=CalendarPortMockSeamTest`
Expected: FAIL — compilation error, `BusySummaryService` does not exist.

- [ ] **Step 3: Write the consumer bean**

`src/main/java/com/calit/google/BusySummaryService.java`:

```java
package com.calit.google;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Example downstream consumer of CalendarPort. Demonstrates the mockable seam:
 * Plans 3 & 4 inject CalendarPort the same way and mock it in tests.
 */
@ApplicationScoped
public class BusySummaryService {

    private final CalendarPort calendarPort;

    @Inject
    public BusySummaryService(CalendarPort calendarPort) {
        this.calendarPort = calendarPort;
    }

    /** Total busy minutes in [from, to), using the port's already-merged intervals. */
    public long busyMinutes(Instant from, Instant to) {
        List<BusyInterval> busy = calendarPort.freeBusy(from, to);
        long total = 0;
        for (BusyInterval b : busy) {
            total += Duration.between(b.start(), b.end()).toMinutes();
        }
        return total;
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=CalendarPortMockSeamTest`
Expected: PASS. The `@InjectMock CalendarPort` overrides `GoogleCalendarPort`; `BusySummaryService` sums the canned intervals (60 + 30 = 90). Google is never contacted.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/BusySummaryService.java \
  src/test/java/com/calit/google/CalendarPortMockSeamTest.java
git commit -m "test: prove CalendarPort @InjectMock seam with example consumer"
```

---

### Task 10: OAuth flow endpoints (connect, callback)

**Files:**
- Create: `src/main/java/com/calit/google/GoogleOAuthResource.java`
- Test: `src/test/java/com/calit/google/GoogleOAuthResourceTest.java`

> **Design:** `/connect` returns a 302 to the consent URL — assertable without Google (we only check the `Location` header). `/callback` first validates the CSRF `state`, then exchanges the code; to keep the test off the network, the test **mocks `GoogleTokenService`** with `@InjectMock` so `exchangeCode`/`validateState` are stubbed, then asserts the redirect to `/admin`.
>
> **Stateless OAuth (horizontal scalability):** Both endpoints are stateless and carry no server-side session. The `state` minted at `/connect` is the signed, short-lived token from `GoogleTokenService.issueState(...)`; `/callback` validates it with `validateState(...)` using only the shared secret, so the consent and callback requests may legitimately land on **different replicas**. A forged, tampered, or expired `state` yields 400. No `HttpSession`, no sticky sessions, no distributed lock.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/google/GoogleOAuthResourceTest.java`:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@QuarkusTest
class GoogleOAuthResourceTest {

    @InjectMock
    GoogleTokenService tokenService;

    @Test
    void connectRedirectsToGoogleConsent() {
        Mockito.when(tokenService.buildConsentUrl())
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?access_type=offline&prompt=consent");

        RestAssured.given().redirects().follow(false)
                .when().get("/api/google/connect")
                .then().statusCode(302)
                .header("Location", containsString("accounts.google.com"))
                .header("Location", containsString("access_type=offline"));
    }

    @Test
    void callbackExchangesCodeAndRedirectsToAdmin() {
        // Stateless CSRF: the mocked service accepts this state without any session.
        Mockito.when(tokenService.validateState(eq("good-state"), any(Instant.class))).thenReturn(true);
        doNothing().when(tokenService).exchangeCode(eq("the-code"), any(Instant.class));

        RestAssured.given().redirects().follow(false)
                .when().get("/api/google/callback?code=the-code&state=good-state")
                .then().statusCode(302)
                .header("Location", containsString("/admin"));

        verify(tokenService).exchangeCode(eq("the-code"), any(Instant.class));
    }

    @Test
    void callbackWithInvalidStateReturns400() {
        // Forged/expired state is rejected before any code exchange — no session to consult.
        Mockito.when(tokenService.validateState(eq("bad-state"), any(Instant.class))).thenReturn(false);

        given().when().get("/api/google/callback?code=the-code&state=bad-state")
                .then().statusCode(400).body(containsString("Invalid or expired OAuth state"));

        verify(tokenService, Mockito.never()).exchangeCode(any(), any());
    }

    @Test
    void callbackWithErrorReturns400() {
        given().when().get("/api/google/callback?error=access_denied")
                .then().statusCode(400).body(containsString("access_denied"));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=GoogleOAuthResourceTest`
Expected: FAIL — 404 (no `/api/google/connect` resource yet).

- [ ] **Step 3: Write the resource**

`src/main/java/com/calit/google/GoogleOAuthResource.java`:

```java
package com.calit.google;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.Instant;

@Path("/api/google")
public class GoogleOAuthResource {

    private final GoogleTokenService tokenService;

    @Inject
    public GoogleOAuthResource(GoogleTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** Kick off the owner consent flow: 302 to Google. */
    @GET
    @Path("/connect")
    public Response connect() {
        return Response.status(Response.Status.FOUND)
                .location(URI.create(tokenService.buildConsentUrl()))
                .build();
    }

    /** Google redirects back here with ?code=...&state=... (or ?error=...). */
    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_PLAIN)
    public Response callback(@QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (error != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Google authorization failed: " + error)
                    .build();
        }
        Instant now = Instant.now();
        // Stateless CSRF check: validate the signed state with no session. /connect and /callback
        // may be served by different replicas, so verification uses only the shared signing secret.
        if (!tokenService.validateState(state, now)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid or expired OAuth state")
                    .build();
        }
        if (code == null || code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing authorization code")
                    .build();
        }
        tokenService.exchangeCode(code, now);
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/admin"))
                .build();
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=GoogleOAuthResourceTest`
Expected: PASS (all 4 tests). `GoogleTokenService` is mocked (including `validateState`), so no Google traffic and no session are involved.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/GoogleOAuthResource.java \
  src/test/java/com/calit/google/GoogleOAuthResourceTest.java
git commit -m "feat: add Google OAuth connect/callback endpoints"
```

---

### Task 11: Calendar selection endpoints (list Google calendars, save read/write choice)

**Files:**
- Create: `src/main/java/com/calit/google/CalendarListPort.java`
- Create: `src/main/java/com/calit/google/GoogleCalendarListPort.java`
- Create: `src/main/java/com/calit/google/GoogleCalendarResource.java`
- Test: `src/test/java/com/calit/google/GoogleCalendarResourceTest.java`

> **Design:** Listing the owner's Google calendars (`calendarList.list`) needs Google, so it sits behind a tiny `CalendarListPort` interface (real impl `GoogleCalendarListPort`, `@ApplicationScoped`). The resource test **mocks `CalendarListPort`** with `@InjectMock` for `GET`, and exercises the real DB persistence for `POST` (which is pure Panache). `POST` clears prior selection then upserts each requested calendar, enforcing the single-write-target rule.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/google/GoogleCalendarResourceTest.java`:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class GoogleCalendarResourceTest {

    @InjectMock
    CalendarListPort calendarListPort;

    @Test
    void listsGoogleCalendars() {
        Mockito.when(calendarListPort.listCalendars()).thenReturn(List.of(
                new CalendarListPort.RemoteCalendar("work@example.com", "Work"),
                new CalendarListPort.RemoteCalendar("personal@example.com", "Personal")));

        given().when().get("/api/google/calendars")
                .then().statusCode(200)
                .body("googleCalendarId", hasItem("work@example.com"))
                .body("summary", hasItem("Personal"));
    }

    @Test
    void savesReadWriteSelectionAndEnforcesSingleWriteTarget() {
        String writeId = "write-" + System.nanoTime() + "@example.com";
        String readId = "read-" + System.nanoTime() + "@example.com";

        String body = "{\"calendars\":["
                + "{\"googleCalendarId\":\"" + readId + "\",\"summary\":\"Read\",\"readForBusy\":true,\"writeTarget\":false},"
                + "{\"googleCalendarId\":\"" + writeId + "\",\"summary\":\"Write\",\"readForBusy\":false,\"writeTarget\":true}"
                + "]}";

        given().contentType("application/json").body(body)
                .when().post("/api/google/calendars")
                .then().statusCode(200);

        // The write target query returns exactly the one flagged calendar.
        given().when().get("/api/google/calendars/write-target")
                .then().statusCode(200)
                .body("googleCalendarId", is(writeId));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=GoogleCalendarResourceTest`
Expected: FAIL — compilation error / 404 (`CalendarListPort` and the resource do not exist).

- [ ] **Step 3: Write `CalendarListPort`**

`src/main/java/com/calit/google/CalendarListPort.java`:

```java
package com.calit.google;

import java.util.List;

/** Lists the owner's available Google calendars so they can choose read/write ones. */
public interface CalendarListPort {

    /** A calendar as reported by Google's calendarList.list. */
    record RemoteCalendar(String googleCalendarId, String summary) {}

    List<RemoteCalendar> listCalendars();
}
```

- [ ] **Step 4: Write `GoogleCalendarListPort`**

`src/main/java/com/calit/google/GoogleCalendarListPort.java`:

```java
package com.calit.google;

import com.google.api.services.calendar.model.CalendarListEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

/** Real CalendarListPort backed by Google's calendarList.list. @ApplicationScoped, mockable downstream. */
@ApplicationScoped
public class GoogleCalendarListPort implements CalendarListPort {

    private final GoogleTokenService tokens;
    private final GoogleCalendarClientFactory clientFactory;

    @Inject
    public GoogleCalendarListPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
    }

    @Override
    public List<RemoteCalendar> listCalendars() {
        try {
            var client = clientFactory.build(tokens.validAccessToken(Instant.now()));
            List<CalendarListEntry> entries = client.calendarList().list().execute().getItems();
            if (entries == null) {
                return List.of();
            }
            return entries.stream()
                    .map(e -> new RemoteCalendar(e.getId(),
                            e.getSummary() == null ? e.getId() : e.getSummary()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("calendarList.list failed", e);
        }
    }
}
```

- [ ] **Step 5: Write `GoogleCalendarResource`**

`src/main/java/com/calit/google/GoogleCalendarResource.java`:

```java
package com.calit.google;

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

import java.util.List;

@Path("/api/google/calendars")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GoogleCalendarResource {

    private final CalendarListPort calendarListPort;

    @Inject
    public GoogleCalendarResource(CalendarListPort calendarListPort) {
        this.calendarListPort = calendarListPort;
    }

    public record CalendarSelection(String googleCalendarId, String summary,
                                    boolean readForBusy, boolean writeTarget) {}

    public record SaveSelectionRequest(List<CalendarSelection> calendars) {}

    /** List the owner's Google calendars so they can pick read/write ones. */
    @GET
    public List<CalendarListPort.RemoteCalendar> list() {
        return calendarListPort.listCalendars();
    }

    /** Persist the read/write selection. Replaces any prior selection; enforces one write target. */
    @POST
    @Transactional
    public Response save(SaveSelectionRequest req) {
        long writeTargets = req.calendars().stream().filter(CalendarSelection::writeTarget).count();
        if (writeTargets > 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("At most one write-target calendar is allowed").build();
        }
        // Replace prior selection wholesale so removed calendars stop being read.
        GoogleCalendar.deleteAll();
        for (CalendarSelection sel : req.calendars()) {
            GoogleCalendar c = new GoogleCalendar();
            c.googleCalendarId = sel.googleCalendarId();
            c.summary = sel.summary();
            c.readForBusy = sel.readForBusy();
            c.writeTarget = sel.writeTarget();
            c.persist();
        }
        return Response.ok().build();
    }

    /** Convenience read used by the admin UI (Plan 5) and the test. */
    @GET
    @Path("/write-target")
    public GoogleCalendar writeTarget() {
        GoogleCalendar target = GoogleCalendar.writeTarget();
        if (target == null) {
            throw new NotFoundException("No write-target calendar selected");
        }
        return target;
    }
}
```

- [ ] **Step 6: Run it to confirm it passes**

Run: `mvn test -Dtest=GoogleCalendarResourceTest`
Expected: PASS (both tests). `CalendarListPort` is mocked for `GET`; `POST`/`write-target` exercise real Panache persistence (no Google).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/google/CalendarListPort.java \
  src/main/java/com/calit/google/GoogleCalendarListPort.java \
  src/main/java/com/calit/google/GoogleCalendarResource.java \
  src/test/java/com/calit/google/GoogleCalendarResourceTest.java
git commit -m "feat: add Google calendar list + read/write selection endpoints"
```

---

### Task 12: Full-suite green + final commit

**Files:**
- (none new)

- [ ] **Step 1: Run the entire suite**

Run: `mvn test`
Expected: PASS — Plan 1 tests (Health, OwnerSettings, MeetingType, AvailabilityRule, SlotService, MeetingTypeResource) plus Plan 2 tests (GoogleCredential, GoogleCalendar, BusyIntervals, GoogleTokenService, CalendarPortMockSeam, GoogleOAuthResource, GoogleCalendarResource). No test contacts Google; Dev Services Postgres + Flyway V1→V2 boot cleanly.

- [ ] **Step 2: Tag the milestone (optional) and confirm clean tree**

```bash
git status --short
```
Expected: clean working tree (everything already committed task-by-task).

---

## Self-Review against spec

**1. Spec coverage (Plan 2 scope — features 2 & 3):**

| Requirement | Task |
|---|---|
| OAuth2 offline refresh token, stored once (feat 2) | Task 3 (`GoogleCredential` singleton), Task 6 (`GoogleTokenService.exchangeCode` persists refresh token; `validAccessToken` refreshes and writes back to the shared row; stateless signed `state` via `issueState`/`validateState`), Task 10 (`/connect` access_type=offline + prompt=consent, `/callback` validates state then exchanges) |
| Read FREE/BUSY from N configured calendars (feat 2) | Task 4 (`GoogleCalendar.readForBusy()`), Task 5 (`BusyIntervals.merge`), Task 8 (`GoogleCalendarPort.freeBusy` → freebusy.query across all readers → merge), Task 11 (select which calendars are read) |
| Write events to ONE configured calendar (feat 2) | Task 2 (partial unique index: one write target), Task 4 (`GoogleCalendar.writeTarget()`), Task 8 (`createEvent`/`updateEvent`/`deleteEvent` target the write calendar), Task 11 (select + enforce single write target) |
| Auto-create a Google Meet link on each created event (feat 3) | Task 8 (`conferenceData.createRequest` + random `requestId` + `setConferenceDataVersion(1)`; `extractMeetLink` maps hangoutLink/entryPoint → `CreatedEvent.meetLink`) |
| Reschedule / cancel hooks for Plan 3 (supports feat 5) | Task 5 (contract), Task 8 (`updateEvent`, `deleteEvent`) |

Out of scope here (later plans): bookable-slot subtraction of busy/buffers (Plan 3), booking persistence (Plan 3), emails (Plan 4), admin/invitee UI for connecting Google and choosing calendars (Plan 5 renders over these endpoints).

**2. Placeholder scan:** No TBD/TODO/"handle errors"/"similar to"/"etc." placeholders. Every step shows complete code and an exact `mvn test -Dtest=...` command with an expected FAIL/PASS. No live Google credentials are required by any test: the merge helper is pure JUnit; token logic uses a `StubTokenService` overriding the single `requestToken` seam; resources/consumers mock `CalendarPort`, `CalendarListPort`, and `GoogleTokenService` via `@InjectMock`.

**3. Type-consistency check (the exposed contract Plans 3 & 4 consume):** Package `com.calit.google`.

- `public record BusyInterval(java.time.Instant start, java.time.Instant end) {}` — Task 5. ✔ matches the cross-plan contract verbatim.
- `public record CreatedEvent(String googleEventId, String meetLink, String htmlLink) {}` — Task 5. ✔ verbatim.
- `public interface CalendarPort { List<BusyInterval> freeBusy(Instant, Instant); CreatedEvent createEvent(String summary, String description, Instant start, Instant end, List<String> attendeeEmails); void updateEvent(String eventId, Instant start, Instant end); void deleteEvent(String eventId); }` — Task 5. ✔ all four signatures match verbatim.
- `GoogleCalendarPort implements CalendarPort`, `@ApplicationScoped` — Task 8. ✔ replaceable by `@io.quarkus.test.junit.mockito.InjectMock CalendarPort` (proven in Task 9).

These are the only types downstream plans bind to; entities (`GoogleCredential`, `GoogleCalendar`), services (`GoogleTokenService`, factory, `*ListPort`), and resources are internal to Plan 2.

**4. Horizontal scalability (overview NFR):** Plan 2 satisfies the overview's "run as N identical stateless replicas" requirement for Google handling. All credential state lives in shared Postgres (`GoogleCredential` singleton), never in instance memory: `validAccessToken` re-reads the row each call and **writes refreshed tokens back to the shared row inside its `@Transactional`** (Task 6), so any replica picks up another's refresh and none caches a stale token. A concurrent refresh from two replicas is safe — last-write-wins, either fresh token is valid until its own expiry, and a rotated refresh token is persisted when Google returns one — so **no distributed lock is required**. The OAuth `/connect` and `/callback` endpoints are stateless: the CSRF `state` is a signed, short-lived HMAC token (`issueState`/`validateState`, Task 6), **not an `HttpSession`**, so the two requests may be served by different replicas and any replica validates the state with only the shared secret (Task 10). Therefore any replica can serve OAuth callbacks and make Calendar API calls.

**Known assumptions (carried forward):** all event times are written/compared in UTC (`EventDateTime` time zone "UTC"), consistent with the overview's time model; the owner connects Google and selects calendars once via the Plan 5 admin UI before booking flows run; `freeBusy` returns an empty list when no read calendars are configured (Plan 3 then treats the whole work-hour window as free).
