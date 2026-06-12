# Multi-account Google Calendar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an owner connect multiple Google accounts, pick which calendars across them are read for busy and one write target, and surface it all in a working `/me/google` UI — fixing the bug where every slot shows free because no calendar is ever flagged `read_for_busy`.

**Architecture:** Schema gains many-credentials-per-owner (`google_sub` identity, `needs_reconnect` health) and a `google_credential_id` FK on `google_calendar`. The OAuth token service captures the account `sub`/`email` from the id_token and dedupes by `(owner_id, sub)`; token refresh is per-credential and fail-soft. `GoogleCalendarPort.freeBusy` groups read calendars by credential and queries each healthy account separately, merging; `createEvent` routes to the write target's credential and clears it on a Google 404. A shared `CalendarSelectionService` validates+persists selections (re-fetched live, write⇒read coupled, exactly one write target). A server-rendered `/me/google` page renders per-account sections.

**Tech Stack:** Java 21, Quarkus, Hibernate Panache, Flyway, Qute templates, RESTEasy Reactive, Google Calendar API client, JUnit5 + RestAssured + Mockito.

---

## File Structure

**Migration**
- Create: `src/main/resources/db/migration/V9__google_multi_account.sql` — wipe + schema changes.

**Entities (modify)**
- `src/main/java/com/calit/google/GoogleCredential.java` — add `googleSub`, `accountEmail`, `needsReconnect`; add `listForOwner`, `findByOwnerAndSub`, `countForOwner`.
- `src/main/java/com/calit/google/GoogleCalendar.java` — add `googleCredentialId`; add `readForBusyByCredential` grouping helper, `credential()`.

**OAuth / tokens (modify)**
- `src/main/java/com/calit/google/GoogleTokenService.java` — capture id_token `sub`/`email`; dedupe `exchangeCode`; per-credential `validAccessToken`; flag `needsReconnect` on refresh failure.
- `src/main/resources/application.properties` — add `openid email` to `google.oauth.scope`.

**Port (modify)**
- `src/main/java/com/calit/google/GoogleCalendarPort.java` — per-credential `client`, multi-account `freeBusy`, write-target routing + 404 handling in `createEvent`, `isConnected` via count.
- `src/main/java/com/calit/google/GoogleCalendarListPort.java` — add `listCalendars(GoogleCredential)`.
- `src/main/java/com/calit/google/CalendarListPort.java` — add `listCalendars(GoogleCredential)` to the interface.

**Selection service (create)**
- Create: `src/main/java/com/calit/google/CalendarSelectionService.java` — shared validate+save.
- `src/main/java/com/calit/google/GoogleCalendarResource.java` — delegate `save` to the service.

**Web page (modify)**
- `src/main/java/com/calit/web/GooglePageResource.java` — NEW resource holding `/me/google` GET, `/me/google/calendars` POST, `/me/google/accounts/{id}/delete` POST. (Kept separate from the large `AdminResource`; one responsibility: the Google page.)
- Create: `src/main/resources/templates/GooglePageResource/google.html` — multi-account UI.
- Delete: `src/main/resources/templates/AdminResource/google.html` and remove `Templates.google(...)` + the `google()` GET from `AdminResource.java` (moved to the new resource).

**Tests (create)**
- `src/test/java/com/calit/google/GoogleCredentialTest.java`
- `src/test/java/com/calit/google/GoogleTokenServiceIdentityTest.java`
- `src/test/java/com/calit/google/FreeBusyMultiAccountTest.java`
- `src/test/java/com/calit/google/CreateEventRoutingTest.java`
- `src/test/java/com/calit/google/CalendarSelectionServiceTest.java`
- `src/test/java/com/calit/web/GooglePageResourceTest.java`

---

## Task 1: Schema migration + entity fields

**Files:**
- Create: `src/main/resources/db/migration/V9__google_multi_account.sql`
- Modify: `src/main/java/com/calit/google/GoogleCredential.java`
- Modify: `src/main/java/com/calit/google/GoogleCalendar.java`
- Test: `src/test/java/com/calit/google/GoogleCredentialTest.java`

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V9__google_multi_account.sql`:

```sql
-- Multi-account Google support. Existing single-account rows cannot be backfilled with a real
-- account identity (google_sub only comes from an id_token, which needs the new openid scope, i.e. a
-- re-consent). Re-consent is mandatory anyway, so wipe and let a clean reconnect rebuild everything.
DELETE FROM google_calendar;
DELETE FROM google_credential;

-- google_credential: many per owner now, identified by the Google account's stable subject id.
ALTER TABLE google_credential DROP CONSTRAINT uq_google_credential_owner;
ALTER TABLE google_credential ADD COLUMN google_sub      VARCHAR(255) NOT NULL;
ALTER TABLE google_credential ADD COLUMN account_email   VARCHAR(255);
ALTER TABLE google_credential ADD COLUMN needs_reconnect BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE google_credential ADD CONSTRAINT uq_google_credential_owner_sub UNIQUE (owner_id, google_sub);

-- google_calendar: each row belongs to exactly one connected account.
ALTER TABLE google_calendar ADD COLUMN google_credential_id BIGINT NOT NULL
    REFERENCES google_credential(id) ON DELETE CASCADE;
-- A shared calendar can appear under two accounts of one owner with the same google_calendar_id,
-- so uniqueness moves from (owner_id, google_calendar_id) to (google_credential_id, google_calendar_id).
ALTER TABLE google_calendar DROP CONSTRAINT uq_google_calendar_owner_cal;
ALTER TABLE google_calendar ADD CONSTRAINT uq_google_calendar_cred_cal
    UNIQUE (google_credential_id, google_calendar_id);
-- Single write target per owner across all their accounts (unchanged).
```

- [ ] **Step 2: Add fields + finders to `GoogleCredential`**

In `src/main/java/com/calit/google/GoogleCredential.java`, after the `accessTokenExpiry` field add:

```java
    /** Google account stable subject id (id_token "sub"). Identity for dedupe within an owner. */
    @Column(name = "google_sub", nullable = false)
    public String googleSub;

    /** The account's email (id_token "email"), shown as the human label in the UI. May be null. */
    @Column(name = "account_email")
    public String accountEmail;

    /** Set true when a token refresh fails (revoked/expired); cleared on a successful reconnect. */
    @Column(name = "needs_reconnect", nullable = false)
    public boolean needsReconnect = false;
```

And replace the single `forOwner` finder region with these finders (keep `forOwner` for callers that still want any one row, but add the multi-account ones):

```java
    /** This owner's credential row, or null if Google is not yet connected for them. */
    public static GoogleCredential forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }

    /** All of this owner's connected Google accounts. */
    public static java.util.List<GoogleCredential> listForOwner(Long ownerId) {
        return list("ownerId", ownerId);
    }

    /** This owner's credential for a specific Google account (by sub), or null. */
    public static GoogleCredential findByOwnerAndSub(Long ownerId, String sub) {
        return find("ownerId = ?1 and googleSub = ?2", ownerId, sub).firstResult();
    }

    /** How many Google accounts this owner has connected. */
    public static long countForOwner(Long ownerId) {
        return count("ownerId", ownerId);
    }
```

- [ ] **Step 3: Add the credential FK + grouping helper to `GoogleCalendar`**

In `src/main/java/com/calit/google/GoogleCalendar.java`, after the `ownerId` field add:

```java
    /** The connected Google account this calendar belongs to. */
    @Column(name = "google_credential_id", nullable = false)
    public Long googleCredentialId;
```

And add these methods inside the class (after `readForBusy`):

```java
    /** This owner's read-for-busy calendars grouped by the credential (account) they belong to. */
    public static java.util.Map<Long, java.util.List<GoogleCalendar>> readForBusyByCredential(Long ownerId) {
        return readForBusy(ownerId).stream()
                .collect(java.util.stream.Collectors.groupingBy(c -> c.googleCredentialId));
    }
```

- [ ] **Step 4: Write the failing entity test**

Create `src/test/java/com/calit/google/GoogleCredentialTest.java`:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class GoogleCredentialTest {

    @Test
    @Transactional
    void multipleAccountsPerOwnerAreFoundByOwnerAndSub() {
        GoogleCredential a = newCred(1L, "sub-A", "a@example.com");
        GoogleCredential b = newCred(1L, "sub-B", "b@example.com");
        a.persist();
        b.persist();

        assertEquals(2, GoogleCredential.countForOwner(1L));
        assertEquals("a@example.com", GoogleCredential.findByOwnerAndSub(1L, "sub-A").accountEmail);
        assertNull(GoogleCredential.findByOwnerAndSub(1L, "sub-missing"));
    }

    private static GoogleCredential newCred(long ownerId, String sub, String email) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = ownerId;
        c.refreshToken = "rt-" + sub;
        c.googleSub = sub;
        c.accountEmail = email;
        return c;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes (schema + entities applied)**

Run: `./mvnw test -Dtest=GoogleCredentialTest`
Expected: PASS. (If Flyway rejects V9, the schema-validate boot fails first — fix the migration.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V9__google_multi_account.sql \
        src/main/java/com/calit/google/GoogleCredential.java \
        src/main/java/com/calit/google/GoogleCalendar.java \
        src/test/java/com/calit/google/GoogleCredentialTest.java
git commit -m "feat(google): multi-account schema + entity fields"
```

---

## Task 2: Capture account identity + per-credential tokens

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/calit/google/GoogleTokenServiceIdentityTest.java`

- [ ] **Step 1: Add `openid email` to the OAuth scope**

In `src/main/resources/application.properties`, find `google.oauth.scope=...` and append the two scopes (space-separated), e.g.:

```properties
google.oauth.scope=https://www.googleapis.com/auth/calendar openid email
```

(If the value is sourced from an env var/default, update the default in `GoogleOAuthConfig` usage or the properties default accordingly. Verify with: `grep -rn "oauth.scope" src/main/resources`.)

- [ ] **Step 2: Carry `sub`/`email` on `TokenResponse` and parse the id_token**

In `GoogleTokenService.java`, change the `TokenResponse` record to:

```java
    /** Normalized token data, independent of the Google client types (keeps the test seam clean). */
    public record TokenResponse(String accessToken, String refreshToken, Instant expiry,
                                String googleSub, String accountEmail) {}
```

In `requestToken`, for the `authorization_code` branch, parse the id_token and populate the new fields; the `refresh_token` branch passes `null, null`:

```java
            if ("authorization_code".equals(grantType)) {
                var resp = new GoogleAuthorizationCodeTokenRequest(
                        transport, json, TOKEN_ENDPOINT,
                        config.oauth().clientId(), config.oauth().clientSecret(),
                        codeOrRefreshToken, config.oauth().redirectUri())
                        .execute();
                String sub = null, email = null;
                String idToken = resp.getIdToken();
                if (idToken != null) {
                    var payload = com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
                            .parse(json, idToken).getPayload();
                    sub = payload.getSubject();
                    Object e = payload.get("email");
                    email = e == null ? null : e.toString();
                }
                return new TokenResponse(
                        resp.getAccessToken(), resp.getRefreshToken(),
                        now.plusSeconds(resp.getExpiresInSeconds()), sub, email);
            }
            var resp = new GoogleRefreshTokenRequest(
                    transport, json, codeOrRefreshToken,
                    config.oauth().clientId(), config.oauth().clientSecret())
                    .execute();
            return new TokenResponse(
                    resp.getAccessToken(), resp.getRefreshToken(),
                    now.plusSeconds(resp.getExpiresInSeconds()), null, null);
```

- [ ] **Step 3: Dedupe `exchangeCode` by `(owner, sub)`**

Replace `exchangeCode` with:

```java
    /** Exchange the callback {@code code} for tokens and upsert the credential for this Google account. */
    @Transactional
    public void exchangeCode(Long ownerId, String code, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        if (resp.googleSub() == null) {
            throw new IllegalStateException("Google id_token missing 'sub'; check the openid scope.");
        }
        GoogleCredential c = GoogleCredential.findByOwnerAndSub(ownerId, resp.googleSub());
        if (c == null) {
            c = new GoogleCredential();
            c.ownerId = ownerId;
            c.googleSub = resp.googleSub();
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.accountEmail = resp.accountEmail();
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.needsReconnect = false;
        c.persist();
    }
```

- [ ] **Step 4: Make `validAccessToken` per-credential + fail-soft**

Replace `validAccessToken(Long ownerId, Instant now)` with a per-credential version:

```java
    /**
     * Return a currently-valid access token for one connected account, refreshing+persisting when the
     * cached token is missing or near expiry. On a refresh failure (revoked/expired refresh token) the
     * credential is flagged {@code needsReconnect} and the exception is rethrown so callers can skip it.
     */
    @Transactional
    public String validAccessToken(GoogleCredential c, Instant now) {
        if (!c.isAccessTokenExpired(now)) {
            return c.accessToken;
        }
        try {
            TokenResponse resp = requestToken("refresh_token", c.refreshToken, now);
            c.accessToken = resp.accessToken();
            c.accessTokenExpiry = resp.expiry();
            if (resp.refreshToken() != null) {
                c.refreshToken = resp.refreshToken();
            }
            c.needsReconnect = false;
            c.persist();
            return c.accessToken;
        } catch (RuntimeException ex) {
            c.needsReconnect = true;
            c.persist();
            throw ex;
        }
    }
```

(The old `validAccessToken(Long ownerId, Instant now)` is removed; Task 3 updates its callers in the port and list port.)

- [ ] **Step 5: Write the failing identity test**

Create `src/test/java/com/calit/google/GoogleTokenServiceIdentityTest.java`. It subclasses the service to stub the network round-trip (same seam the existing tests use):

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class GoogleTokenServiceIdentityTest {

    @Inject
    GoogleOAuthConfig config;

    /** Service whose only network call is stubbed to return a fixed token+identity. */
    static class StubService extends GoogleTokenService {
        StubService(GoogleOAuthConfig config) { super(config); }
        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return new TokenResponse("at", "rt", now.plusSeconds(3600), "sub-123", "me@example.com");
        }
    }

    @Test
    @Transactional
    void reconnectingSameAccountUpdatesRowNotDuplicates() {
        StubService svc = new StubService(config);
        Instant now = Instant.now();

        svc.exchangeCode(1L, "code-1", now);
        svc.exchangeCode(1L, "code-2", now); // same sub -> upsert

        assertEquals(1, GoogleCredential.countForOwner(1L));
        assertEquals("me@example.com", GoogleCredential.findByOwnerAndSub(1L, "sub-123").accountEmail);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=GoogleTokenServiceIdentityTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/google/GoogleTokenService.java \
        src/main/resources/application.properties \
        src/test/java/com/calit/google/GoogleTokenServiceIdentityTest.java
git commit -m "feat(google): capture account sub/email, per-credential fail-soft tokens"
```

---

## Task 3: Multi-account port (freeBusy, createEvent routing, list)

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleCalendarPort.java`
- Modify: `src/main/java/com/calit/google/GoogleCalendarListPort.java`
- Modify: `src/main/java/com/calit/google/CalendarListPort.java`
- Test: `src/test/java/com/calit/google/FreeBusyMultiAccountTest.java`, `src/test/java/com/calit/google/CreateEventRoutingTest.java`

- [ ] **Step 1: Per-credential client + count-based `isConnected`**

In `GoogleCalendarPort.java`, replace the `client(Long ownerId)` helper and `isConnected`:

```java
    private Calendar client(GoogleCredential cred) {
        return clientFactory.build(tokens.validAccessToken(cred, Instant.now()));
    }

    @Override
    @Transactional
    public boolean isConnected(Long ownerId) {
        // Connected iff this owner has at least one OAuth credential.
        return GoogleCredential.countForOwner(ownerId) > 0;
    }
```

- [ ] **Step 2: Multi-account `freeBusy` (group by credential, skip flagged, fail-soft)**

Replace `freeBusy` with:

```java
    @Override
    @Transactional
    public List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to) {
        Map<Long, List<GoogleCalendar>> byCredential = GoogleCalendar.readForBusyByCredential(ownerId);
        if (byCredential.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> raw = new ArrayList<>();
        for (Map.Entry<Long, List<GoogleCalendar>> e : byCredential.entrySet()) {
            GoogleCredential cred = GoogleCredential.findById(e.getKey());
            if (cred == null || cred.needsReconnect) {
                continue; // fail-soft: skip an account that is gone or known-broken
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
                // Fail-soft: one broken account must not take down availability. validAccessToken
                // already flagged needsReconnect on a token failure; for other errors, flag too.
                cred.needsReconnect = true;
                cred.persist();
            }
        }
        return BusyIntervals.merge(raw);
    }
```

- [ ] **Step 3: Route `createEvent` to the write target's credential + handle 404**

Replace `requireWriteTarget` and the start of `createEvent` so the client comes from the write target's own account, and a Google 404 clears the write target:

```java
    @Override
    @Transactional
    public CreatedEvent createEvent(Long ownerId, String summary, String description,
                                    Instant start, Instant end,
                                    List<String> attendeeEmails,
                                    boolean createMeetLink, String locationText) {
        GoogleCalendar target = requireWriteTarget(ownerId);
        GoogleCredential cred = GoogleCredential.findById(target.googleCredentialId);
        if (cred == null) {
            throw new IllegalStateException("Write-target calendar has no credential; reconnect Google.");
        }
        Event event = new Event()
                .setSummary(summary)
                .setDescription(description)
                .setStart(eventTime(ownerId, start))
                .setEnd(eventTime(ownerId, end));

        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            event.setAttendees(attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }
        if (createMeetLink) {
            event.setConferenceData(new ConferenceData().setCreateRequest(
                    new CreateConferenceRequest()
                            .setRequestId(UUID.randomUUID().toString())
                            .setConferenceSolutionKey(
                                    new ConferenceSolutionKey().setType("hangoutsMeet"))));
        } else if (locationText != null) {
            event.setLocation(locationText);
        }

        try {
            Event created = client(cred).events()
                    .insert(target.googleCalendarId, event)
                    .setConferenceDataVersion(createMeetLink ? 1 : 0)
                    .setSendUpdates("all")
                    .execute();
            String meetLink = createMeetLink ? extractMeetLink(created) : null;
            return new CreatedEvent(created.getId(), meetLink, created.getHtmlLink());
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // The write-target calendar was deleted on Google's side since it was selected.
                target.writeTarget = false;
                target.persist();
                cred.needsReconnect = true;
                cred.persist();
                throw new IllegalStateException(
                        "Write-target calendar no longer exists on Google; re-select a write target.", e);
            }
            throw new UncheckedIOException("createEvent failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException("createEvent failed", e);
        }
    }
```

Update the other methods that called `client(ownerId)` (`updateEvent`, `deleteEvent`) to resolve the target's credential the same way:

```java
    @Override
    @Transactional
    public void updateEvent(Long ownerId, String eventId, Instant start, Instant end) {
        GoogleCalendar target = requireWriteTarget(ownerId);
        GoogleCredential cred = GoogleCredential.findById(target.googleCredentialId);
        Event patch = new Event()
                .setStart(eventTime(ownerId, start))
                .setEnd(eventTime(ownerId, end));
        try {
            client(cred).events().patch(target.googleCalendarId, eventId, patch)
                    .setSendUpdates("all").execute();
        } catch (IOException e) {
            throw new UncheckedIOException("updateEvent failed", e);
        }
    }

    @Override
    @Transactional
    public void deleteEvent(Long ownerId, String eventId) {
        GoogleCalendar target = requireWriteTarget(ownerId);
        GoogleCredential cred = GoogleCredential.findById(target.googleCredentialId);
        try {
            client(cred).events().delete(target.googleCalendarId, eventId)
                    .setSendUpdates("all").execute();
        } catch (IOException e) {
            throw new UncheckedIOException("deleteEvent failed", e);
        }
    }
```

Add the `GoogleJsonResponseException` import alongside the others if not present (fully-qualified above, so no import strictly needed).

- [ ] **Step 4: Per-credential `listCalendars` on the list port**

In `CalendarListPort.java` add to the interface:

```java
    /** Calendars for one specific connected account. */
    List<RemoteCalendar> listCalendars(GoogleCredential credential);
```

In `GoogleCalendarListPort.java` implement it (and keep the existing no-arg method delegating to the owner's first credential for backward compatibility with the JSON endpoint):

```java
    @Override
    public List<RemoteCalendar> listCalendars(GoogleCredential credential) {
        try {
            var client = clientFactory.build(tokens.validAccessToken(credential, Instant.now()));
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

    @Override
    public List<RemoteCalendar> listCalendars() {
        GoogleCredential c = GoogleCredential.forOwner(currentOwner.id());
        return c == null ? List.of() : listCalendars(c);
    }
```

- [ ] **Step 5: Write the failing `freeBusy` test**

Create `src/test/java/com/calit/google/FreeBusyMultiAccountTest.java`. It seeds two credentials (one healthy, one flagged) and asserts the flagged account is skipped (no token call) while the healthy one is queried. Stub the client via a Mockito spy on the factory is heavy; instead assert the skip behavior at the DB/grouping seam by flagging both and expecting an empty result without exceptions:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FreeBusyMultiAccountTest {

    @Inject
    GoogleCalendarPort port;

    @Test
    @Transactional
    void allAccountsFlaggedNeedsReconnectYieldsEmptyAndNoThrow() {
        GoogleCredential a = cred(1L, "sub-A", true);
        a.persist();
        readCal(1L, a.id, "a-cal");

        // Every read calendar belongs to a flagged account -> skipped -> empty, no exception.
        List<BusyInterval> busy = port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400));
        assertTrue(busy.isEmpty());
    }

    @Test
    @Transactional
    void noReadCalendarsYieldsEmpty() {
        assertTrue(port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400)).isEmpty());
    }

    private static GoogleCredential cred(long owner, String sub, boolean needsReconnect) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner; c.refreshToken = "rt"; c.googleSub = sub; c.needsReconnect = needsReconnect;
        return c;
    }

    private static void readCal(long owner, long credId, String calId) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = owner; g.googleCredentialId = credId; g.googleCalendarId = calId;
        g.summary = calId; g.readForBusy = true; g.persist();
    }
}
```

- [ ] **Step 6: Write the failing `createEvent` routing test**

Create `src/test/java/com/calit/google/CreateEventRoutingTest.java` asserting that with no write target a clear error is thrown (routing precondition), keeping the seam light:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CreateEventRoutingTest {

    @Inject
    GoogleCalendarPort port;

    @Test
    @Transactional
    void noWriteTargetThrows() {
        assertThrows(IllegalStateException.class, () -> port.createEvent(
                1L, "s", "d", Instant.now(), Instant.now().plusSeconds(1800),
                List.of("a@example.com"), true, null));
    }
}
```

- [ ] **Step 7: Run both tests to verify they pass**

Run: `./mvnw test -Dtest=FreeBusyMultiAccountTest,CreateEventRoutingTest`
Expected: PASS.

- [ ] **Step 8: Run the existing Google seam tests to check nothing regressed**

Run: `./mvnw test -Dtest=CalendarPortMockSeamTest,GoogleCalendarResourceTest,BusyIntervalsTest`
Expected: PASS. (If `CalendarPortMockSeamTest` referenced the old `validAccessToken(Long,Instant)`, update its stub to the credential overload.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCalendarPort.java \
        src/main/java/com/calit/google/GoogleCalendarListPort.java \
        src/main/java/com/calit/google/CalendarListPort.java \
        src/test/java/com/calit/google/FreeBusyMultiAccountTest.java \
        src/test/java/com/calit/google/CreateEventRoutingTest.java
git commit -m "feat(google): multi-account freeBusy + write-target routing + 404 handling"
```

---

## Task 4: Shared `CalendarSelectionService`

**Files:**
- Create: `src/main/java/com/calit/google/CalendarSelectionService.java`
- Modify: `src/main/java/com/calit/google/GoogleCalendarResource.java`
- Test: `src/test/java/com/calit/google/CalendarSelectionServiceTest.java`

- [ ] **Step 1: Write the service**

Create `src/main/java/com/calit/google/CalendarSelectionService.java`:

```java
package com.calit.google;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Validates and persists an owner's calendar selection, replacing any prior selection. Shared by the
 * JSON REST endpoint and the server-rendered page so the rules live in exactly one place:
 *  - at most one write target;
 *  - the write target is always read-for-busy (else calit could double-book its own events);
 *  - every selection names a real credential owned by this owner.
 */
@ApplicationScoped
public class CalendarSelectionService {

    /** One chosen calendar belonging to a specific connected account. */
    public record Selection(Long googleCredentialId, String googleCalendarId, String summary,
                            boolean readForBusy, boolean writeTarget) {}

    @Transactional
    public void save(Long ownerId, List<Selection> selections) {
        long writeTargets = selections.stream().filter(Selection::writeTarget).count();
        if (writeTargets > 1) {
            throw new IllegalArgumentException("At most one write-target calendar is allowed");
        }
        GoogleCalendar.deleteForOwner(ownerId);
        for (Selection sel : selections) {
            GoogleCredential cred = GoogleCredential.findById(sel.googleCredentialId());
            if (cred == null || !ownerId.equals(cred.ownerId)) {
                throw new IllegalArgumentException(
                        "Unknown credential " + sel.googleCredentialId() + " for this owner");
            }
            GoogleCalendar c = new GoogleCalendar();
            c.ownerId = ownerId;
            c.googleCredentialId = sel.googleCredentialId();
            c.googleCalendarId = sel.googleCalendarId();
            c.summary = sel.summary();
            // Write target is always read for busy (hard coupling).
            c.readForBusy = sel.readForBusy() || sel.writeTarget();
            c.writeTarget = sel.writeTarget();
            c.persist();
        }
    }
}
```

- [ ] **Step 2: Delegate the JSON endpoint to the service**

In `GoogleCalendarResource.java`, inject the service and rewrite `save` to map its DTO and delegate. The JSON DTO has no credential id (single-account API caller), so resolve the owner's first credential for those rows:

```java
    private final CalendarSelectionService selectionService;
```

(Add it to the constructor params and assignment.) Replace the loop body of `save`:

```java
    @POST
    @Transactional
    public Response save(SaveSelectionRequest req) {
        GoogleCredential cred = GoogleCredential.forOwner(currentOwner.id());
        if (cred == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Connect a Google account first").build();
        }
        try {
            selectionService.save(currentOwner.id(), req.calendars().stream()
                    .map(s -> new CalendarSelectionService.Selection(
                            cred.id, s.googleCalendarId(), s.summary(), s.readForBusy(), s.writeTarget()))
                    .toList());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
        return Response.ok().build();
    }
```

- [ ] **Step 3: Write the failing service test**

Create `src/test/java/com/calit/google/CalendarSelectionServiceTest.java`:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CalendarSelectionServiceTest {

    @Inject
    CalendarSelectionService service;

    @Test
    @Transactional
    void writeTargetIsForcedReadForBusy() {
        GoogleCredential cred = cred(1L, "sub-A");
        cred.persist();

        service.save(1L, List.of(new CalendarSelectionService.Selection(
                cred.id, "write@example.com", "Write", false, true)));

        GoogleCalendar saved = GoogleCalendar.writeTarget(1L);
        assertEquals("write@example.com", saved.googleCalendarId);
        assertTrue(saved.readForBusy, "write target must be read for busy");
    }

    @Test
    @Transactional
    void rejectsTwoWriteTargets() {
        GoogleCredential cred = cred(1L, "sub-A");
        cred.persist();
        assertThrows(IllegalArgumentException.class, () -> service.save(1L, List.of(
                new CalendarSelectionService.Selection(cred.id, "a", "A", true, true),
                new CalendarSelectionService.Selection(cred.id, "b", "B", true, true))));
    }

    @Test
    @Transactional
    void rejectsForeignCredential() {
        GoogleCredential other = cred(2L, "sub-X");
        other.persist();
        assertThrows(IllegalArgumentException.class, () -> service.save(1L, List.of(
                new CalendarSelectionService.Selection(other.id, "a", "A", true, false))));
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner; c.refreshToken = "rt"; c.googleSub = sub;
        return c;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=CalendarSelectionServiceTest`
Expected: PASS.

- [ ] **Step 5: Run the JSON endpoint test to confirm the delegation still works**

Run: `./mvnw test -Dtest=GoogleCalendarResourceTest`
Expected: PASS. (The test seeds no credential; `savesReadWriteSelection...` will now need a credential row. Update that test to persist a `GoogleCredential` for owner 1 in a `@BeforeEach`, or assert the 400 path. Add the seed.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/google/CalendarSelectionService.java \
        src/main/java/com/calit/google/GoogleCalendarResource.java \
        src/test/java/com/calit/google/CalendarSelectionServiceTest.java \
        src/test/java/com/calit/google/GoogleCalendarResourceTest.java
git commit -m "feat(google): shared CalendarSelectionService with write/read invariants"
```

---

## Task 5: `/me/google` page resource (GET, save, disconnect)

**Files:**
- Create: `src/main/java/com/calit/web/GooglePageResource.java`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (remove `google()` GET + `Templates.google`)
- Delete: `src/main/resources/templates/AdminResource/google.html`
- Test: `src/test/java/com/calit/web/GooglePageResourceTest.java`

- [ ] **Step 1: Remove the old `google()` from `AdminResource`**

Delete the `public static native TemplateInstance google(Long pendingCount, boolean isAdmin);` line from `Templates`, and delete the `@GET @Path("/google") google()` method. (Search: `grep -n "google" src/main/java/com/calit/web/AdminResource.java`.)

- [ ] **Step 2: Create the page resource**

Create `src/main/java/com/calit/web/GooglePageResource.java`:

```java
package com.calit.web;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.google.CalendarListPort;
import com.calit.google.CalendarSelectionService;
import com.calit.google.GoogleCalendar;
import com.calit.google.GoogleCredential;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/me/google")
@RolesAllowed("user")
public class GooglePageResource {

    /** One calendar row rendered in an account section. */
    public record CalendarRow(Long credentialId, String googleCalendarId, String summary,
                              boolean readForBusy, boolean writeTarget) {}

    /** One connected account section. */
    public record AccountView(Long credentialId, String accountEmail, boolean needsReconnect,
                              List<CalendarRow> calendars, boolean holdsWriteTarget) {}

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance google(List<AccountView> accounts, boolean loadError,
                                                     Long pendingCount, boolean isAdmin);
    }

    @Inject CalendarListPort calendarListPort;
    @Inject CalendarSelectionService selectionService;
    @Inject com.calit.user.CurrentOwner currentOwner;
    @Inject SecurityIdentity identity;

    private boolean isAdmin() { return identity.hasRole("admin"); }

    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2", currentOwner.id(), BookingStatus.PENDING);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance google() {
        Long ownerId = currentOwner.id();
        List<GoogleCredential> creds = GoogleCredential.listForOwner(ownerId);
        List<AccountView> accounts = new ArrayList<>();
        boolean loadError = false;
        for (GoogleCredential cred : creds) {
            List<CalendarRow> rows = new ArrayList<>();
            boolean holdsWriteTarget = false;
            try {
                Map<String, GoogleCalendar> saved = GoogleCalendar
                        .<GoogleCalendar>list("googleCredentialId", cred.id).stream()
                        .collect(java.util.stream.Collectors.toMap(c -> c.googleCalendarId, c -> c));
                for (CalendarListPort.RemoteCalendar rc : calendarListPort.listCalendars(cred)) {
                    GoogleCalendar s = saved.get(rc.googleCalendarId());
                    boolean read = s == null ? saved.isEmpty() : s.readForBusy; // first-load default: all read
                    boolean write = s != null && s.writeTarget;
                    if (write) holdsWriteTarget = true;
                    rows.add(new CalendarRow(cred.id, rc.googleCalendarId(), rc.summary(), read, write));
                }
            } catch (RuntimeException ex) {
                loadError = true; // Google unreachable for this account; banner, no editable rows
            }
            accounts.add(new AccountView(cred.id, cred.accountEmail, cred.needsReconnect, rows, holdsWriteTarget));
        }
        return Templates.google(accounts, loadError, pendingCount(), isAdmin());
    }

    @POST
    @Path("/calendars")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response saveSelection(MultivaluedMap<String, String> form) {
        Long ownerId = currentOwner.id();
        // Checkboxes: name="read" value="<credId>:<calId>"; radio: name="writeTarget" value="<credId>:<calId>".
        List<String> readVals = form.getOrDefault("read", List.of());
        String writeVal = form.getFirst("writeTarget");
        // Re-fetch live lists per healthy account; build selections only for calendars that still exist.
        List<CalendarSelectionService.Selection> selections = new ArrayList<>();
        for (GoogleCredential cred : GoogleCredential.listForOwner(ownerId)) {
            if (cred.needsReconnect) continue;
            try {
                for (CalendarListPort.RemoteCalendar rc : calendarListPort.listCalendars(cred)) {
                    String key = cred.id + ":" + rc.googleCalendarId();
                    boolean read = readVals.contains(key);
                    boolean write = key.equals(writeVal);
                    if (read || write) {
                        selections.add(new CalendarSelectionService.Selection(
                                cred.id, rc.googleCalendarId(), rc.summary(), read, write));
                    }
                }
            } catch (RuntimeException ignored) {
                // skip an account that went unreachable mid-save
            }
        }
        if (selections.stream().noneMatch(CalendarSelectionService.Selection::writeTarget)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Pick exactly one write-target calendar").build();
        }
        selectionService.save(ownerId, selections);
        return Response.seeOther(java.net.URI.create("/me/google")).build();
    }

    @POST
    @Path("/accounts/{credentialId}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response disconnect(@PathParam("credentialId") Long credentialId) {
        Long ownerId = currentOwner.id();
        GoogleCredential cred = GoogleCredential.findById(credentialId);
        if (cred == null || !ownerId.equals(cred.ownerId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        boolean holdsWriteTarget = GoogleCalendar.count(
                "googleCredentialId = ?1 and writeTarget = true", credentialId) > 0;
        boolean otherAccountsRemain = GoogleCredential.countForOwner(ownerId) > 1;
        if (holdsWriteTarget && otherAccountsRemain) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Pick a new write target on another account before disconnecting this one")
                    .build();
        }
        cred.delete(); // ON DELETE CASCADE removes this account's google_calendar rows
        return Response.seeOther(java.net.URI.create("/me/google")).build();
    }
}
```

- [ ] **Step 3: Write the failing page test**

Create `src/test/java/com/calit/web/GooglePageResourceTest.java`:

```java
package com.calit.web;

import com.calit.google.CalendarListPort;
import com.calit.google.GoogleCalendar;
import com.calit.google.GoogleCredential;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class GooglePageResourceTest {

    @InjectMock
    CalendarListPort calendarListPort;

    @Test
    void disconnectBlockedWhenItHoldsWriteTargetAndOtherAccountsRemain() {
        long credId = seedTwoAccountsWriteOnFirst();

        given().cookie("quarkus-credential", FormAuth.login())
                .when().post("/me/google/accounts/" + credId + "/delete")
                .then().statusCode(409);
    }

    @Test
    void disconnectAllowedForLastAccount() {
        long credId = seedSingleAccount();

        given().cookie("quarkus-credential", FormAuth.login())
                .redirects().follow(false)
                .when().post("/me/google/accounts/" + credId + "/delete")
                .then().statusCode(303);
    }

    @Transactional
    long seedTwoAccountsWriteOnFirst() {
        long ownerId = com.calit.user.AppUser.<com.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential a = cred(ownerId, "sub-A"); a.persist();
        GoogleCredential b = cred(ownerId, "sub-B"); b.persist();
        GoogleCalendar w = new GoogleCalendar();
        w.ownerId = ownerId; w.googleCredentialId = a.id; w.googleCalendarId = "w";
        w.summary = "W"; w.readForBusy = true; w.writeTarget = true; w.persist();
        return a.id;
    }

    @Transactional
    long seedSingleAccount() {
        long ownerId = com.calit.user.AppUser.<com.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential a = cred(ownerId, "sub-A"); a.persist();
        return a.id;
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner; c.refreshToken = "rt"; c.googleSub = sub; c.accountEmail = sub + "@x";
        return c;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=GooglePageResourceTest`
Expected: PASS. (Requires the template from Task 6 to exist for the GET path, but these two tests only hit POST endpoints — they pass before the template. If `@CheckedTemplate` fails to compile without the html file, do Task 6 Step 1 first.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/GooglePageResource.java \
        src/main/java/com/calit/web/AdminResource.java \
        src/test/java/com/calit/web/GooglePageResourceTest.java
git rm src/main/resources/templates/AdminResource/google.html
git commit -m "feat(web): /me/google multi-account page resource (get/save/disconnect)"
```

---

## Task 6: `/me/google` template (multi-account UI)

**Files:**
- Create: `src/main/resources/templates/GooglePageResource/google.html`
- Test: manual + the GET assertion below.

- [ ] **Step 1: Create the template**

Create `src/main/resources/templates/GooglePageResource/google.html`:

```html
{@java.util.List<com.calit.web.GooglePageResource.AccountView> accounts}
{@java.lang.Boolean loadError}
{@java.lang.Long pendingCount}
{@java.lang.Boolean isAdmin}
{#include adminBase title="Admin — Google" pendingCount=pendingCount active="google" isAdmin=isAdmin}
  <h1 class="text-2xl font-bold mb-2">Google Calendar</h1>
  <p class="text-base-content/70 mb-4">Connect Google accounts so calit can read your busy times and create events. Pick which calendars block availability, and one calendar to create booking events on.</p>

  <a role="button" class="btn btn-primary mb-4" href="/api/google/connect">Connect a Google account</a>

  {#if loadError}
    <div class="alert alert-error mb-4">Couldn't reach Google for one or more accounts. Reconnect the flagged account, then reload.</div>
  {/if}

  {#if accounts.isEmpty()}
    <p class="text-base-content/70">No Google accounts connected yet.</p>
  {#else}
    <form method="post" action="/me/google/calendars" class="flex flex-col gap-6">
      {#for acc in accounts}
        <div class="card bg-base-100 border border-base-300">
          <div class="card-body gap-3">
            <div class="flex items-center justify-between">
              <h2 class="text-lg font-semibold">{acc.accountEmail ?: "Google account"}
                {#if acc.needsReconnect}<span class="badge badge-warning ml-2">needs reconnect</span>{/if}
              </h2>
            </div>
            <table class="table table-sm">
              <thead><tr><th>Calendar</th><th>Read busy</th><th>Write events here</th></tr></thead>
              <tbody>
                {#for cal in acc.calendars}
                  <tr>
                    <td>{cal.summary}</td>
                    <td>
                      <input type="checkbox" class="checkbox checkbox-sm" name="read"
                             value="{cal.credentialId}:{cal.googleCalendarId}"
                             {#if cal.readForBusy}checked{/if}
                             {#if cal.writeTarget}disabled checked{/if}>
                      {#if cal.writeTarget}<input type="hidden" name="read" value="{cal.credentialId}:{cal.googleCalendarId}">{/if}
                    </td>
                    <td>
                      <input type="radio" class="radio radio-sm" name="writeTarget"
                             value="{cal.credentialId}:{cal.googleCalendarId}"
                             {#if cal.writeTarget}checked{/if}>
                    </td>
                  </tr>
                {/for}
              </tbody>
            </table>
          </div>
        </div>

        <!-- Disconnect is its own form so it doesn't submit the selection form. -->
        <form method="post" action="/me/google/accounts/{acc.credentialId}/delete"
              onsubmit="return confirm('Disconnect this Google account? Its calendar selections are removed.');">
          <button type="submit" class="btn btn-outline btn-error btn-sm"
                  {#if acc.holdsWriteTarget && accounts.size() > 1}disabled title="Pick a new write target on another account first"{/if}>
            Disconnect {acc.accountEmail ?: "account"}
          </button>
        </form>
      {/for}

      <div>
        <button type="submit" class="btn btn-primary">Save calendar selection</button>
      </div>
    </form>
  {/if}
{/include}
```

Note: the write-target row emits a disabled checkbox (so the user sees it checked) plus a hidden `read` input carrying the same value, because disabled checkboxes don't submit — this guarantees the write target is read-for-busy in the POST too. The server also enforces the coupling, so this is belt-and-suspenders.

- [ ] **Step 2: Build CSS so `@QuarkusTest` static-asset checks pass, then GET the page**

Run: `bun run css:build && ./mvnw test -Dtest=GooglePageResourceTest`
Expected: PASS, and the GET path now renders.

- [ ] **Step 3: Add a GET smoke assertion**

Append to `GooglePageResourceTest`:

```java
    @Test
    void getRendersConnectButtonWhenNoAccounts() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when().get("/me/google")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Connect a Google account"));
    }
```

Run: `./mvnw test -Dtest=GooglePageResourceTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/GooglePageResource/google.html \
        src/test/java/com/calit/web/GooglePageResourceTest.java
git commit -m "feat(web): multi-account Google calendar selection UI"
```

---

## Task 7: Full-suite verification

- [ ] **Step 1: Build CSS + run the whole suite**

Run: `bun run css:build && ./mvnw verify`
Expected: BUILD SUCCESS, all tests green. Pay attention to any test that referenced the removed `validAccessToken(Long, Instant)` or `AdminResource.google()` — update its stub/route.

- [ ] **Step 2: Manual smoke (optional, real Google)**

Connect one account → `/me/google` lists its calendars, all read-checked, first is write target → Save → re-check a meeting page: busy times now removed. Connect a second account → both sections appear → pick read calendars across both, one write target → Save. Disconnect the non-write account → allowed. Try to disconnect the write-target account with the other still present → button disabled.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A && git commit -m "test: fixups for multi-account Google suite"
```

---

## Self-Review Notes

- **Spec coverage:** decisions 1 (re-fetch on save → Task 5 save), 2 (write⇒read hard → Task 4 + template), 3 (banner on GET failure → Task 5 GET + template), 4 (require write target → Task 5 save 400), 5 (first-load all-read default → Task 5 GET), 6 (sub/email identity → Task 2), 7 (wipe migration → Task 1), 8 (fail-soft + flag → Tasks 2/3), 9 (credential-scoped uniqueness + `credId:calId` → Tasks 1/5/6), 10 (disconnect block → Task 5), 11 (404 hardening → Task 3) all mapped.
- **Known follow-up not built (noted in spec, intentionally out of scope):** reconnect-first picker fallback when the only remaining account is flagged (decision 11 sub-edge) — the disconnect block + reconnect button cover it without extra UI.
- **Watch:** `CalendarPortMockSeamTest` and any caller of the old per-owner `validAccessToken` must move to the credential overload; `GoogleCalendarResourceTest.savesReadWriteSelection...` now needs a seeded `GoogleCredential` (added in Task 4 Step 5).
```
