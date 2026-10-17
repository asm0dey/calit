# CalDAV Calendar Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Owner can connect CalDAV accounts alongside Google ones; busy time from both blocks slots, and Mirror events are written to whichever calendar the Owner picks.

**Architecture:** The two `google_*` tables become provider-neutral (`calendar_account`, `calendar`) with filtered compatibility views under the old names for one release. In Java, `CalendarAccount` is a single-table-inheritance root with `GoogleCredential` and `CaldavAccount` subclasses, so Google code keeps working unchanged. `GoogleCalendarPort` and a new `CaldavCalendarPort` each stay a full `CalendarPort` for their own provider (`@Typed`, so not injectable as `CalendarPort`). A new `RoutingCalendarPort` is the one `CalendarPort` bean: it merges the busy reads and routes each write by the provider of the calendar it lands on.

**Tech Stack:** Quarkus 3.39.5 / Java 25, Panache, Flyway, PostgreSQL, JDK `java.net.http.HttpClient` + `java.xml` DOM for WebDAV, ical4j (new dependency) for parsing and recurrence, Testcontainers Radicale for tests.

**Spec:** `docs/superpowers/specs/2026-10-17-caldav-calendar-design.md` (read it; this plan argues from it).

**Prerequisites (must be merged first):** beans `calit-5fw0` (Co-host write override removed; `MeetingTypeHost.googleCredentialId`/`googleCalendarId` no longer read) and `calit-bgxr` (calit `.ics` carries `CANCEL` + real `SEQUENCE`). This plan assumes both.

## Global Constraints

- Every migration leaves the **previous release** working on the new schema (rolling deploys). Never `RENAME`/`DROP` anything the previous release reads, except behind a compatibility view.
- Never edit an applied migration. The next migration number is `V37` (the last one is `V36__home_redirect.sql`).
- Owner scoping: every account/calendar query filters by owner.
- Every scheduling feature works without JavaScript.
- Every new user-facing string is an `@Message` default with `de` and `he` values in `src/main/resources/messages/{msg,adm}_{de,he}.properties`, using identical `{placeholder}` names.
- AssertJ for all assertions; Hamcrest only inside RestAssured `.body(...)`.
- JDK imports are `import module java.base;` (plus `java.net.http`, `java.xml` where used).
- New config: only `CALDAV_ALLOW_PRIVATE_HOSTS` (default `false`) → `calit.caldav.allow-private-hosts`. CalDAV is always available (no enable flag).
- CalDAV connect/read timeout: configurable `calit.caldav.timeout`, default `5s`.
- Full `mvn test` green before each PR. `mvn verify` (Spotless) green.
- Glossary terms: **Mirror event** (never "Google event" / "calendar event"), **Connected account** (either provider), **Degraded mode** (no connected account of any provider).

## Review Focus

1. **A CalDAV account's id reaches Google code**: Google's `freeBusy` looks up a CalDAV account id, gets nothing back, and throws `CalendarUnavailableException` ("disconnected"), so every slot page for an Owner with mixed accounts fails. Expected: each provider port only ever reads its own provider's calendars. Test: Task 2 `googleFreeBusyIgnoresCaldavCalendars`.
2. **Recurring and all-day busy events**: a weekly standup, or an all-day "out of office" in the Owner's zone, must block the slot, while a `TRANSP:TRANSPARENT` event must not. Tests: Task 4.
3. **Compatibility view under `FOR UPDATE SKIP LOCKED`**: the previous release's `GoogleConnectionScheduler` runs exactly this against `google_credential`. Expected: it still works through the view. Test: Task 1 `lockingSelectThroughViewWorks`.
4. **A CalDAV server that redirects to a private address** (`https://cal.example.com` → `302 http://10.0.0.5/`) while private hosts are disallowed. Expected: refused, nothing stored. Test: Task 3 `redirectToPrivateHostIsRefused`.
5. **Mixed accounts, one broken**: Google is fine but the Nextcloud box is down. Expected: slots fail closed (no slots, no double booking), same as a broken Google account. Test: Task 6 `oneBrokenProviderFailsClosed`.

---

## Phase A — PR 1: generalise the model (Google only, no behaviour change)

### Task 1: Migration V37 + compatibility views + the rule in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` ("Database / migrations" section)
- Create: `src/main/resources/db/migration/V37__calendar_account.sql`
- Test: `src/test/java/site/asm0dey/calit/migration/V37MigrationTest.java`

**Interfaces:**
- Produces tables `calendar_account` (ex-`google_credential`) and `calendar` (ex-`google_calendar`), plus views `google_credential` and `google_calendar`.

- [ ] **Step 1: Add the rule to `CLAUDE.md`** under "Database / migrations", one paragraph: the previous release must run on the new schema; use expand/contract; renames go through filtered single-table compatibility views (auto-updatable), dropped one release later. Then run `uv run` precedent (see memory `precedent-cli-via-uv`): `precedent check --topic db-migrations --chose expand-contract`, then `precedent record` with `--rejected "rename in place"` and `--rationale "N replicas, rolling deploys, external users"`.

- [ ] **Step 2: Write the failing test** `V37MigrationTest` (`@QuarkusTest`, native SQL through `EntityManager`, owner id 1):
  - `insertThroughViewGetsGoogleProvider`: insert a row via `INSERT INTO google_credential (owner_id, refresh_token, google_sub, needs_reconnect) VALUES (1,'r','sub1',false) RETURNING id`, then `SELECT provider FROM calendar_account WHERE id = :id` is `"GOOGLE"`.
  - `caldavRowsInvisibleThroughView`: insert into `calendar_account (owner_id, provider, caldav_server_url, caldav_username, caldav_password, needs_reconnect)` with `'CALDAV'`; `SELECT count(*) FROM google_credential WHERE id = :id` is `0`.
  - `calendarViewExposesOldColumnNames`: insert into `google_calendar (owner_id, google_credential_id, google_calendar_id, summary, read_for_busy, write_target, supports_meet)`; `SELECT account_id, remote_id FROM calendar` returns the same values.
  - `lockingSelectThroughViewWorks`: `SELECT id FROM google_credential WHERE last_probed_at IS NULL FOR UPDATE SKIP LOCKED LIMIT 50` executes without error.
  - `updateThroughViewWorks`: `UPDATE google_credential SET needs_reconnect = true WHERE id = :id` changes `calendar_account.needs_reconnect`.
  - `bookingOldColumnWriteMirrorsToNew`: insert a booking (reuse an existing booking fixture) setting only `google_event_id='e1'`; `event_id` is `'e1'`. Then `UPDATE booking SET google_event_id='e2'`; `event_id` is `'e2'`.
  - `bookingNewColumnWriteMirrorsToOld`: `UPDATE booking SET event_id='e3'`; `google_event_id` is `'e3'`. Same for `calendar_account_id` ↔ `google_credential_id` and `calendar_remote_id` ↔ `google_calendar_id`.
  - `bookingUnrelatedUpdateKeepsPair`: after `event_id='e3'`, `UPDATE booking SET google_event_id = google_event_id, invitee_name='x'` (what an old Hibernate full-row update does); both stay `'e3'`.
  - `accountDeleteNullsBothFks`: delete the `calendar_account` row; both `google_credential_id` and `calendar_account_id` are null.
  - `meetingTypeOverridePairMirrors`: both directions for `override_account_id` ↔ `google_credential_id` and `override_remote_calendar_id` ↔ `google_calendar_id`.

- [ ] **Step 3: Run it.** `mvn test -Dtest=V37MigrationTest`. Expected: FAIL (`relation "calendar_account" does not exist`).

- [ ] **Step 4: Write `V37__calendar_account.sql`:**
  - `ALTER TABLE google_credential RENAME TO calendar_account;`
  - `ALTER TABLE google_calendar RENAME TO calendar;`
  - `ALTER TABLE calendar RENAME COLUMN google_credential_id TO account_id;` and `RENAME COLUMN google_calendar_id TO remote_id;`
  - `calendar_account`:
    - add `provider text NOT NULL DEFAULT 'GOOGLE' CHECK (provider IN ('GOOGLE','CALDAV'))`
    - `DROP NOT NULL` on `refresh_token` and `google_sub`
    - add nullable `caldav_server_url text`, `caldav_username text`, `caldav_password text`, `caldav_principal_url text`, `caldav_home_url text`
    - add `CHECK (provider <> 'GOOGLE' OR (refresh_token IS NOT NULL AND google_sub IS NOT NULL))`
    - add `CHECK (provider <> 'CALDAV' OR (caldav_server_url IS NOT NULL AND caldav_username IS NOT NULL AND caldav_password IS NOT NULL))`
    - add a unique index on `(owner_id, caldav_principal_url) WHERE provider = 'CALDAV'`
  - `CREATE VIEW google_credential AS SELECT <every pre-V37 column, by its old name> FROM calendar_account WHERE provider = 'GOOGLE';`
  - `CREATE VIEW google_calendar AS SELECT id, owner_id, account_id AS google_credential_id, remote_id AS google_calendar_id, summary, read_for_busy, write_target, supports_meet FROM calendar WHERE account_id IN (SELECT id FROM calendar_account WHERE provider = 'GOOGLE');`
    - That `IN (subquery)` keeps the view auto-updatable. Verify with `SELECT is_updatable FROM information_schema.views WHERE table_name = 'google_calendar'` being `YES`.
    - If it isn't updatable, drop the filter on `google_calendar` instead. CalDAV calendars would then be visible to old code, but only through a CalDAV account the old code can't see, so it's harmless in practice. Leave a one-line SQL comment saying so.
  - `booking`: add `event_id text`, `calendar_remote_id text`, `calendar_account_id bigint REFERENCES calendar_account(id) ON DELETE SET NULL`; backfill `UPDATE booking SET event_id = google_event_id, calendar_remote_id = google_calendar_id, calendar_account_id = google_credential_id`.
  - `meeting_type`: add `override_remote_calendar_id text`, `override_account_id bigint REFERENCES calendar_account(id) ON DELETE SET NULL`; backfill the same way.
  - One plpgsql function + `BEFORE INSERT OR UPDATE FOR EACH ROW` trigger per table (`booking_calendar_compat`, `meeting_type_override_compat`). Per old/new pair: on `INSERT`, both = `coalesce(NEW.new, NEW.old)`; on `UPDATE`, if `NEW.new IS DISTINCT FROM OLD.new` then `NEW.old := NEW.new`, elsif `NEW.old IS DISTINCT FROM OLD.old` then `NEW.new := NEW.old`.
  - Header comment: "Views, old booking/meeting_type columns and their compat triggers are shims for the previous release; drop them all in the next release (bean created in Task 10)."
  - Pre-V37 `google_credential` columns: read `V3__google.sql`, `V9`, `V11`, `V12`, `V15`.

- [ ] **Step 5: Run it.** `mvn test -Dtest=V37MigrationTest`. Expected: PASS.

- [ ] **Step 6: Pin the previous release's contract with automated tests** (no manual step). Add to `V37MigrationTest`:
  - `previousReleaseFlywayValidates`: copy `db/migration/V1__…` through `V36__…` from the classpath into a `@TempDir`. Build `Flyway.configure().dataSource(<the test datasource>).locations("filesystem:" + dir)`; `.validate()` must not throw. This is exactly what the previous release's `migrate-at-start` does, because V37 counts as a future migration and Flyway ignores those by default.
  - `viewsPreserveOldColumnContract`: `SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'google_credential'` (then `'google_calendar'`) `ORDER BY column_name` equals a pinned list of the pre-V37 columns and types. Write that list by reading `V3`, `V9`, `V10`, `V11`, `V12`, `V15`. Hibernate `validate` checks only existence and type, and it counts views as tables (Hibernate 7.4.9 `AbstractInformationExtractorImpl:110-111`, verified), so this pins what the previous release's mapping needs.
  - `previousReleaseNativeSqlStillRuns`: before Task 2 rewrites them, copy every native SQL string on `origin/main` that names `google_credential`, `google_calendar`, `google_event_id`, `google_calendar_id` or `google_credential_id`. Find them with `git grep -n -e google_credential -e google_calendar -e google_event_id origin/main -- src/main/java`; they live in `GoogleConnectionScheduler`, `TokenBackfill`, `PrivacyService` and `PersonalData`. Execute each one, with dummy parameters, against the V37 schema. None throws, and each `UPDATE` is visible through the matching new table or column.
  - Delete the copied strings when the shims are dropped (bean created in Task 10).

- [ ] **Step 7: Commit.**
  ```bash
  git add CLAUDE.md src/main/resources/db/migration/V37__calendar_account.sql src/test/java/site/asm0dey/calit/migration/V37MigrationTest.java
  git commit -m "feat(db): calendar_account/calendar with compatibility views (V37)"
  ```

### Task 2: Provider-neutral entities and value types (pure refactor)

**Files:**
- Create: `src/main/java/site/asm0dey/calit/calendar/CalendarProvider.java`, `calendar/CalendarAccount.java`, `calendar/CaldavAccount.java`
- Move/rename: `google/GoogleCalendar.java` → `calendar/SelectedCalendar.java`; `google/CalendarRef.java`, `CreatedEvent.java`, `BusyInterval.java`, `BusyIntervals.java`, `CalendarPort.java`, `CalendarListPort.java`, `CalendarUnavailableException(+Mapper)`, `WriteTargetResolver.java` → package `calendar/`
- Modify: `google/GoogleCredential.java` (extends `CalendarAccount`), `booking/Booking.java`, `domain/MeetingType.java`, `crypto/TokenBackfill.java`, `privacy/PersonalData.java`, `privacy/PrivacyService.java`, `scheduler/GoogleConnectionScheduler.java`, `src/test/java/site/asm0dey/calit/test/DatabaseResetCallback.java`, and every caller (compile errors list them)
- Test: `src/test/java/site/asm0dey/calit/calendar/CalendarAccountModelTest.java`; `privacy/PersonalDataInventoryTest.java` must pass

**Interfaces (Produces):**
- `enum CalendarProvider { GOOGLE, CALDAV }`
- `@Entity @Table(name="calendar_account") @Inheritance(strategy=SINGLE_TABLE) @DiscriminatorColumn(name="provider") abstract class CalendarAccount extends PanacheEntityBase`
  - fields: `Long id`, `Long ownerId`, `boolean needsReconnect`, `Instant reconnectNotifiedAt`, `Instant lastProbedAt`
  - methods: `abstract CalendarProvider provider()`, `abstract String label()`, `static CalendarAccount findOwned(Long ownerId, Long accountId)` (null when missing or another owner's)
- `@DiscriminatorValue("GOOGLE") class GoogleCredential extends CalendarAccount`: keeps its Google fields and every static method unchanged. Its queries are subclass-scoped, so they see Google rows only. `label()` = `accountEmail`.
- `@DiscriminatorValue("CALDAV") class CaldavAccount extends CalendarAccount`
  - fields: `serverUrl`, `username`, `password` (`@Convert(EncryptedStringConverter.class)`), `principalUrl`, `homeUrl`
  - `label()` = `username + " @ " + host(serverUrl)`
  - `static List<CaldavAccount> listForOwner(Long ownerId)`
- `@Entity @Table(name="calendar") class SelectedCalendar` (ex-`GoogleCalendar`)
  - field renames: `googleCredentialId` → `accountId` (`account_id`), `googleCalendarId` → `remoteId` (`remote_id`)
  - all statics keep their names (`findByGoogleId` → `findByRemoteId`)
  - new `static List<SelectedCalendar> readForBusy(Long ownerId, CalendarProvider provider)` (joins `CalendarAccount` by `accountId`, filters provider)
  - new `static Map<Long, List<SelectedCalendar>> readForBusyByAccount(Long ownerId, CalendarProvider provider)` replaces `readForBusyByCredential`
  - `optionValue()` stays `accountId + ":" + remoteId`
- `record CalendarRef(Long accountId, String remoteCalendarId)`
- `record CreatedEvent(String eventId, String meetLink, String htmlLink, CalendarRef calendar)`
- `CalendarListPort.RemoteCalendar(String remoteId, String summary, boolean meetSupported)` (component renamed from `googleCalendarId`)
- `CalendarPort.createEvent(Long ownerId, CalendarRef target, String uid, String summary, String description, Instant start, Instant end, List<String> attendeeEmails, boolean createMeetLink, String locationText)`: new `uid` third parameter. `BookingService.java:523,647` pass the lead booking's `manageToken`. `GoogleCalendarPort` ignores it. Update the ~87 stub/verify lines in 22 test files (one more `any()` / `eq(...)`).
- `Booking`: `googleEventId` → `eventId` (`event_id`), `googleCalendarId` → `calendarRemoteId` (`calendar_remote_id`), `googleCredentialId` → `calendarAccountId` (`calendar_account_id`). Map **only** the new columns; the trigger keeps the old ones in step.
- `MeetingType`: `googleCalendarId` → `overrideRemoteCalendarId` (`override_remote_calendar_id`), `googleCredentialId` → `overrideAccountId` (`override_account_id`). Map only the new columns.
- `PersonalData` inventory lists both old and new booking/meeting_type columns (the schema test sees both).

- [ ] **Step 1: Write the failing test** `CalendarAccountModelTest` (`@QuarkusTest`, owner 1):
  - `googleQueriesIgnoreCaldavAccounts`: persist one `GoogleCredential` and one `CaldavAccount` for owner 1. Then `GoogleCredential.countForOwner(1L)` is `1`, `GoogleCredential.listForOwner(1L)` has size 1, and `CaldavAccount.listForOwner(1L)` has size 1.
  - `googleFreeBusyIgnoresCaldavCalendars`: plus one read-for-busy `SelectedCalendar` on each account. `SelectedCalendar.readForBusyByAccount(1L, GOOGLE).keySet()` contains exactly the Google account's id.
  - `entityWriteReachesOldColumns`: persist a `Booking` through Panache with `eventId="e1"`, `calendarRemoteId="cal"`, `calendarAccountId=<google account id>`, then flush. Native `SELECT google_event_id, google_calendar_id, google_credential_id FROM booking WHERE id = :id` returns the same three values. Same for `MeetingType.overrideAccountId`/`overrideRemoteCalendarId` → `google_credential_id`/`google_calendar_id`.
  - `oldColumnWriteVisibleToEntity`: native `UPDATE booking SET google_event_id='old' WHERE id = :id`, then `em.clear()`; `Booking.findById(id).eventId` is `"old"`.
  - `caldavPasswordEncryptedAtRest`: native `SELECT caldav_password FROM calendar_account WHERE id = :id` starts with `"enc:v1:"`.

- [ ] **Step 2: Run it.** `mvn test -Dtest=CalendarAccountModelTest`. Expected: compile failure (no `CaldavAccount`).

- [ ] **Step 3: Refactor.** Use IntelliJ Rename/Move refactorings where available; otherwise rename by hand and let the compiler find every caller (all of `src/main` and `src/test`).
  - Native SQL moves to the new names: `GoogleConnectionScheduler` gets `FROM calendar_account WHERE provider = 'GOOGLE' …`; also `TokenBackfill`, `PrivacyService`, `PersonalData`.
  - `DatabaseResetCallback` must `TRUNCATE` the tables, not the views.
  - `PersonalData` inventory: rename the entries to `calendar_account` / `calendar` with the new column names, and add the `caldav_*` columns (`caldav_username` and `caldav_server_url` are personal data; `caldav_password` is a secret like `refresh_token`). If the inventory test enumerates `information_schema.tables`, restrict it to `table_type = 'BASE TABLE'`.
  - Package move `google` → `calendar` for the neutral types: imports change everywhere. Keep Google-only classes in `google/`.

- [ ] **Step 4: Run the full suite.** `mvn test`. Expected: `BUILD SUCCESS`, 0 failures (pure refactor plus the new test).

- [ ] **Step 5: Commit.** `git add` exactly the changed paths (no `-A`; read `git status`), then `git commit -m "refactor(calendar): provider-neutral account/calendar model"`.

- [ ] **Step 6: Open PR 1** (Phase A) after `mvn verify`. Include a show-me diagram (memory `show-me-in-prs`) and explain the V37 shims and how `V37MigrationTest` pins the previous release's contract.

---

## Phase B — PR 2: CalDAV

### Task 3: SSRF-guarded HTTP for CalDAV

**Files:**
- Create: `src/main/java/site/asm0dey/calit/caldav/HostGuard.java`, `caldav/CaldavConfig.java`, `caldav/CaldavHttp.java`
- Modify: `src/main/resources/application.properties` (`calit.caldav.allow-private-hosts=${CALDAV_ALLOW_PRIVATE_HOSTS:false}`, `calit.caldav.timeout=5s`), `.env.example`
- Test: `src/test/java/site/asm0dey/calit/caldav/HostGuardTest.java`, `caldav/CaldavHttpTest.java`

**Interfaces (Produces):**
- `@ConfigMapping(prefix="calit.caldav") interface CaldavConfig { boolean allowPrivateHosts(); Duration timeout(); }`
- `final class HostGuard`
  - `static void check(URI uri, boolean allowPrivate)` throws `CaldavException(Reason.PRIVATE_HOST)`
  - refuses: non-`http(s)` schemes; any resolved address that is loopback, any-local, link-local, site-local, multicast, `fc00::/7`, or `100.64.0.0/10`
- `class CaldavException extends RuntimeException` with `enum Reason { PRIVATE_HOST, UNAUTHORIZED, NOT_CALDAV, UNREACHABLE, HTTP_ERROR }`, `Reason reason()`, `int status()` (0 when none)
- `@ApplicationScoped class CaldavHttp`
  - `Response send(CaldavAccount creds, String method, URI uri, Map<String,String> headers, String body)`
  - `Response send(String user, String password, String method, URI uri, Map<String,String> headers, String body)` (used before an account exists)
  - `record Response(int status, Map<String,List<String>> headers, String body)`
  - Basic auth; connect and request timeout = `CaldavConfig.timeout()`; `HttpClient.Redirect.NEVER`, following up to 5 redirects itself, with `HostGuard.check` before every hop
  - 401/403 → `CaldavException(UNAUTHORIZED)`; I/O or timeout → `UNREACHABLE`
  - Comment: `// shortcut: DNS rebinding between check and connect is not prevented, pin the resolved IP via a custom resolver if this instance is multi-tenant and hostile`

- [ ] **Step 1: Write failing tests.** `HostGuardTest` (plain JUnit):
  - `refusesPrivateAddresses`: parameterized over `http://127.0.0.1/`, `http://10.1.2.3/`, `http://192.168.1.10/`, `http://169.254.169.254/`, `http://[::1]/`, `http://[fd00::1]/`, `http://100.64.0.1/`; `assertThatThrownBy(...)` has reason `PRIVATE_HOST`.
  - `allowsPrivateWhenConfigured`: `check(URI.create("http://192.168.1.10/"), true)` does not throw.
  - `refusesNonHttpScheme`: `file:///etc/passwd` throws.

  `CaldavHttpTest` (`@QuarkusTest` with a WireMock or JDK `HttpServer` on localhost, `calit.caldav.allow-private-hosts=true` via a test profile):
  - `redirectToPrivateHostIsRefused`: the server answers `302 Location: http://10.0.0.5/`. With the guard re-enabled for the hop, expect `PRIVATE_HOST`.
  - `unauthorizedMapsToReason`: the server answers 401; expect `UNAUTHORIZED`.

- [ ] **Step 2: Run them.** `mvn test -Dtest='HostGuardTest,CaldavHttpTest'`. Expected: FAIL (classes missing).
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(caldav): SSRF-guarded HTTP client"`

### Task 4: CalDAV protocol client + busy expansion (Radicale-backed tests)

**Files:**
- Modify: `pom.xml` (add `org.mnode.ical4j:ical4j`, latest 4.x, exact version pinned: check the javadocs MCP `get_latest_version`). Testcontainers: use the version Quarkus already manages; add `org.testcontainers:testcontainers` test scope only if it isn't already on the test classpath.
- Create: `src/main/java/site/asm0dey/calit/caldav/CaldavClient.java`, `caldav/BusyExtractor.java`
- Create test resource: `src/test/java/site/asm0dey/calit/caldav/RadicaleResource.java` (`QuarkusTestResourceLifecycleManager`, `GenericContainer` on a pinned `tomsquest/docker-radicale:<tag>` image with htpasswd plain auth `alice:secret`; exposes `caldav.test.url`)
- Test: `caldav/CaldavClientTest.java`, `caldav/BusyExtractorTest.java`

**Interfaces (Produces):**
- `@ApplicationScoped class CaldavClient`
  - `Discovery discover(String serverUrl, String user, String password)`
    - RFC 6764: try `serverUrl` itself, then `/.well-known/caldav`; PROPFIND `current-user-principal`, then `calendar-home-set`
    - throws `NOT_CALDAV` when no principal is found
    - `record Discovery(URI principalUrl, URI homeUrl)`
  - `List<RemoteCalendar> listCalendars(CaldavAccount a)`
    - PROPFIND Depth 1 on `homeUrl`; keeps only collections whose `supported-calendar-component-set` contains `VEVENT` (or doesn't declare one)
    - `RemoteCalendar(remoteId = absolute href, summary = displayname or last path segment, meetSupported = false)`
  - `List<String> queryEvents(CaldavAccount a, String calendarHref, Instant from, Instant to)`
    - REPORT `calendar-query` with a `VEVENT` `time-range`; returns the raw `calendar-data` strings
    - no server-side `expand`: expansion is client-side, one code path for every server
  - `String put(CaldavAccount a, URI eventUrl, String ics, String ifMatch)` returns the new ETag (may be null). `ifMatch == null` sends `If-None-Match: *`.
  - `Fetched get(CaldavAccount a, URI eventUrl)` → `record Fetched(String ics, String etag)`, or null on 404/410
  - `void delete(CaldavAccount a, URI eventUrl)`: 404/410 counts as success
- `final class BusyExtractor`
  - `static List<BusyInterval> busy(List<String> calendarData, Instant from, Instant to, ZoneId ownerZone)`
  - ical4j parse; expands `RRULE`/`RDATE`/`EXDATE` and `RECURRENCE-ID` overrides within `[from, to)`
  - skips `TRANSP:TRANSPARENT` and `STATUS:CANCELLED`
  - an all-day (`VALUE=DATE`) event blocks its whole date(s) in `ownerZone`
  - returns `BusyIntervals.merge(...)`

- [ ] **Step 1: Write failing tests.** `BusyExtractorTest` (plain JUnit, inline iCal strings, window 2026-11-02T00:00Z to 2026-11-09T00:00Z):
  - `weeklyRecurrenceExpands`: `RRULE:FREQ=WEEKLY;COUNT=3` Monday 09:00 to 09:30 `TZID=Europe/Berlin` gives exactly one interval in the window, `2026-11-02T08:00Z` to `08:30Z`.
  - `exdateRemovesInstance`, `recurrenceIdOverrideMovesInstance`
  - `transparentIgnored`, `cancelledIgnored`
  - `allDayBlocksOwnerDay`: `DTSTART;VALUE=DATE:20261103` with ownerZone `Europe/Berlin` gives `2026-11-02T23:00Z` to `2026-11-03T23:00Z`.

  `CaldavClientTest` (`@QuarkusTest @QuarkusTestResource(RadicaleResource.class)`, `allow-private-hosts=true`):
  - `discoversFromBaseUrl`
  - `wrongPasswordIsUnauthorized`
  - `listsOnlyEventCalendars`
  - `putQueryGetDeleteRoundTrip`: create a calendar collection via MKCALENDAR in setup, put an event, `queryEvents` returns it, then `get`, then `delete`, then `get` is null
  - `deleteMissingIsSuccess`

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces. The XML bodies are short string constants. Parse multistatus with namespace-aware `DocumentBuilderFactory` (external entities disabled).
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(caldav): protocol client and busy extraction"`

### Task 5: `CaldavCalendarPort`

**Files:**
- Create: `src/main/java/site/asm0dey/calit/caldav/CaldavCalendarPort.java`
- Modify: `src/main/java/site/asm0dey/calit/email/IcsBuilder.java` (resource mode)
- Test: `caldav/CaldavCalendarPortTest.java` (Radicale), `email/IcsBuilderTest.java` (extend)

**Interfaces:**
- Consumes: `CaldavClient`, `BusyExtractor`, `SelectedCalendar.readForBusyByAccount(ownerId, CALDAV)`, `CaldavAccount`, `OwnerSettings.forOwner(ownerId).timezone`
- Produces: `@ApplicationScoped @Typed(CaldavCalendarPort.class) class CaldavCalendarPort implements CalendarPort`
  - `isConnected`: owner has ≥1 `CaldavAccount`
  - `freeBusy`: over CalDAV read-for-busy calendars only. Fail closed: a `needsReconnect` account or any `CaldavException` throws `CalendarUnavailableException`; an `UNAUTHORIZED` also sets `needsReconnect = true` in `QuarkusTransaction.requiringNew()`.
  - `createEvent`:
    - resolves the write calendar like `GoogleCalendarPort.writeContext` (owned override, else the owner's write target, restricted to CALDAV calendars)
    - uses the `uid` parameter (the booking's `manageToken`, the same UID as the invitee's `.ics`); `eventUrl = calendarHref + urlEncode(uid) + ".ics"`
    - body `IcsBuilder.buildResource(...)`: guests' names/emails appended to the description, `location = locationText`; `createMeetLink` is ignored
    - returns `CreatedEvent(eventUrl.toString(), null, null, new CalendarRef(account.id, calendarHref))`
  - `updateEvent` / `updateEventDetails`:
    - address the stored ref when its account is owned, else the write target (same rule as Google `writeAddress`)
    - `get` → ical4j: replace `DTSTART`/`DTEND` (or `SUMMARY`/`DESCRIPTION`), bump `SEQUENCE` → `put(..., etag)`
    - `get` returning null is tolerated and logged (matches current Google behaviour; bean `calit-o69e` owns recreate)
  - `deleteEvent`: `client.delete`; idempotent
- `IcsBuilder.buildResource(IcsEvent e)`: same as `build` but no `METHOD`, no `ORGANIZER`, no `ATTENDEE` (RFC 4791 resource; with no ATTENDEE, implicit scheduling never fires)

- [ ] **Step 1: Write failing tests.**
  - `IcsBuilderTest.resourceHasNoMethodOrParties`: the output contains `BEGIN:VEVENT` and `UID:`, and contains none of `METHOD:`, `ORGANIZER`, `ATTENDEE`.
  - `CaldavCalendarPortTest` (Radicale; connect owner 1 by persisting a `CaldavAccount` and a `SelectedCalendar` with `readForBusy = true, writeTarget = true`):
    - `createThenBusyBlocksSlot`: `createEvent(...)` for 10:00 to 10:30, then `freeBusy` over that day contains `[10:00, 10:30)`.
    - `createdEventHasNoAttendeesAndGuestsInDescription`: the fetched ICS has no `ATTENDEE`, and its description contains the guest email.
    - `rescheduleMovesEvent`: after `updateEvent`, the old time is free and the new time is busy.
    - `deleteFreesSlot`
    - `createdEventUidMatchesInviteeIcs`: create via `BookingService` (CalDAV write target, mock mailer); the fetched event's `UID` equals the `UID:` line of the `.ics` attached to the invitee's confirmation mail.
    - `wrongPasswordFailsClosedAndFlagsReconnect`: change the stored password; `freeBusy` throws `CalendarUnavailableException` and the account has `needsReconnect = true`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(caldav): CalendarPort for CalDAV accounts"`

### Task 6: `RoutingCalendarPort`, invite matrix, Meet relaxation

**Files:**
- Create: `src/main/java/site/asm0dey/calit/calendar/RoutingCalendarPort.java`, `calendar/MirrorProvider.java`
- Modify:
  - `google/GoogleCalendarPort.java`: add `@Typed(GoogleCalendarPort.class)`; `freeBusy` uses `readForBusyByAccount(ownerId, GOOGLE)`; `requireWriteTarget` only considers a Google write target
  - `email/EmailService.java:712,858,958` and the creator copy
  - `web/PublicResource.java:402,598,635`
  - `calendar/WriteTargetResolver.java` (`blocksMeet`)
- Test: `calendar/RoutingCalendarPortTest.java`, `email/CaldavInviteMatrixTest.java`, extend the existing `WriteTargetResolver` tests

**Interfaces:**
- Produces:
  - `final class MirrorProvider`
    - `static CalendarProvider of(Long ownerId, CalendarRef ref)`: the provider of `ref`'s account when it's owned; else the provider of the owner's write target's account; else `GOOGLE`
    - `static boolean providerNotifiesAttendees(Long ownerId, CalendarRef ref)` = `of(...) == GOOGLE`
  - `@ApplicationScoped class RoutingCalendarPort implements CalendarPort` (injects both typed ports)
    - `isConnected` = either is connected
    - `freeBusy` = `BusyIntervals.merge` of each *connected* provider's `freeBusy`; any throw propagates (fail closed)
    - `createEvent(target)` → port for `MirrorProvider.of(ownerId, target)`
    - `updateEvent` / `updateEventDetails` / `deleteEvent(ref)` → port for `MirrorProvider.of(ownerId, ref)`
- Email rule, replacing `calendarPort.isConnected(owner)` at those sites: `calendarPort.isConnected(owner) && MirrorProvider.providerNotifiesAttendees(owner, booking.calendarRef())`.
  - When the mirror is CalDAV (connected and not notifying), the **Creator's** own copy goes out without the `.ics`; the group-branch host whose `ownerId == meetingType.ownerId` also gets none.
  - Invitee, guests and co-hosts get the `.ics`.
  - `PublicResource:635`'s "offer .ics" uses the same rule.
  - `402`/`598`: read what the flag drives there and apply the same rule only if it is about invites.
- `blocksMeet`: true only when the resolved calendar's account is GOOGLE and `!supportsMeet`. Add `boolean meetUnavailable(Long ownerId, MeetingType type)`: the resolved calendar is non-Google and the location is `GOOGLE_MEET`. It drives the warning in Task 7.

- [ ] **Step 1: Write failing tests.**
  - `RoutingCalendarPortTest` (`@QuarkusTest`; `@InjectMock GoogleCalendarPort` and `@InjectMock CaldavCalendarPort`, which works because they are typed beans):
    - `mergesBusyFromBothProviders`
    - `oneBrokenProviderFailsClosed`: Google returns intervals, CalDAV throws `CalendarUnavailableException`; `freeBusy` throws.
    - `createRoutesToWriteTargetProvider`: the write target is on a CaldavAccount; `verify(caldav).createEvent(...)` and `verifyNoInteractions` on Google's `createEvent`.
    - `deleteRoutesByStoredRef`: stored ref on Google, write target on CalDAV; Google gets the delete.
  - `CaldavInviteMatrixTest` (`@QuarkusTest`, mock mailer; owner 1 has a CaldavAccount write target; booking with one guest):
    - `inviteeAndGuestGetIcs`
    - `creatorGetsNoIcs`
    - `cancelSendsCancelIcsToInvitee`: the attachment contains `METHOD:CANCEL` (relies on `calit-bgxr`)
    - `googleOwnerUnchanged`: same flow with a Google write target; invitee gets no `.ics`, as before.
  - `WriteTargetResolverTest.meetAllowedOnCaldavButBlockedOnMeetlessGoogle`
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run the full suite.** `mvn test`. Expected: green. Existing `@InjectMock CalendarPort` tests now mock the router and are unaffected.
- [ ] **Step 5: Commit.** `git commit -m "feat(calendar): route mirror events by provider; calit invites for CalDAV"`

### Task 7: Calendars settings page (connect, edit password, disconnect, select)

**Files:**
- Rename: `web/GooglePageResource.java` → `web/CalendarsPageResource.java` (`@Path("/me/calendars")`); `templates/GooglePageResource/google.html` → `templates/CalendarsPageResource/calendars.html`
- Create: `GET /me/google` → `303 /me/calendars` (a tiny resource method; keep `/me/google/...` POST paths working only if the previous release's open forms matter, which they don't, so skip that)
- Modify: the 4 template links to `/me/google`, `AppMessages`/`AdminMessages` + `de`/`he` files, `templates/AdminResource/meetingTypeDetail.html` (Meet warning)
- Test: `web/CalendarsPageCaldavTest.java`, update the existing Google page tests to the new path

**Interfaces:**
- Consumes: `CaldavClient.discover`, `CaldavClient.listCalendars`, `CaldavAccount`, `MirrorProvider`, `WriteTargetResolver.meetUnavailable`
- Produces (all `@RolesAllowed("user")`, CSRF token in every form, owner-scoped):
  - `GET /me/calendars`: all connected accounts (provider badge, `label()`, needs-reconnect state) with their calendars as today's checkboxes/radios, plus "Connect Google" (existing OAuth link) and a plain "Connect CalDAV" form (URL, username, password).
  - `POST /me/calendars/caldav`
    - discover, then list calendars. On `CaldavException`, re-render with an inline error per `Reason`: private host, wrong credentials, not a CalDAV server, unreachable. Nothing is stored.
    - on success, persist a `CaldavAccount` and redirect to `/me/calendars`
    - a duplicate `(owner, principalUrl)` updates the password of the existing account instead of adding a second one
  - `POST /me/calendars/caldav/{accountId}/password`: re-verify with discover; on success store it and clear `needsReconnect`/`reconnectNotifiedAt`.
  - `POST /me/calendars/calendars`: today's `saveSelection`, now listing remote calendars per account via the account's provider.
  - `POST /me/calendars/accounts/{accountId}/delete`: either provider; existing cascade semantics.
  - Meeting type detail: show the `meetUnavailable` warning text.

- [ ] **Step 1: Write failing tests** `CalendarsPageCaldavTest` (RestAssured, `@TestSecurity` user 1; `@InjectMock CaldavClient`):
  - `connectStoresAccountAndListsCalendars`
  - `privateHostShowsInlineErrorAndStoresNothing`: the mock throws `PRIVATE_HOST`; the body contains the localized message, and `CaldavAccount.count()` is `0`.
  - `wrongPasswordShowsInlineError`
  - `oldGooglePathRedirects`: `GET /me/google` gives 303 with `Location` ending `/me/calendars`.
  - `cannotDeleteAnotherOwnersAccount`: 404.
  - `meetWarningShownForCaldavWriteTarget`
  - i18n parity check: run the existing message-parity test.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** daisyUI components, as the existing page does. No JavaScript.
- [ ] **Step 4: Run the full suite.** Expected: green.
- [ ] **Step 5: Commit.** `git commit -m "feat(web): calendars page with CalDAV connect"`

### Task 8: Health probe for CalDAV accounts

**Files:**
- Modify: `scheduler/GoogleConnectionScheduler.java` → probe `calendar_account` rows of both providers. For CALDAV, the probe is a `PROPFIND` on `principalUrl`: success clears the flag, `UNAUTHORIZED` sets it.
- Modify: `email/EmailService.sendGoogleDisconnected` → `sendCalendarDisconnected(ownerId, toEmail, accountLabel, provider, locale)`, with copy that names the provider and the fix ("reconnect Google" / "re-enter your CalDAV password"), localized de/he.
- Test: `scheduler/CaldavProbeTest.java`

- [ ] **Step 1: Write failing tests** (`@InjectMock CaldavClient`):
  - `unauthorizedProbeFlagsAndEmailsOnce`: two probe runs give exactly one mail.
  - `successfulProbeClearsFlag`
- [ ] **Step 2: Run.** Expected: FAIL. **Step 3: Implement.** **Step 4: Run the full suite.** Expected: green.
- [ ] **Step 5: Commit.** `git commit -m "feat(scheduler): probe CalDAV accounts"`

### Task 9: Privacy export/erasure for CalDAV accounts

**Files:**
- Modify: `privacy/PrivacyService.java` (owner export includes CalDAV accounts minus the password; owner erasure deletes `calendar_account` rows of both providers, so Mirror event deletion goes through the router)
- Test: extend `privacy/OwnerExportTest.java`, `privacy/BookingErasureTest.java`

- [ ] **Step 1: Failing tests:**
  - `ownerExportListsCaldavAccountWithoutPassword`: the JSON has `caldav_username` and no `caldav_password` key.
  - `erasureDeletesCaldavMirrorEvent`: `@InjectMock CaldavCalendarPort`; verify `deleteEvent`.
- [ ] **Step 2–4:** Run (FAIL), implement, then run the full suite (green).
- [ ] **Step 5: Commit.** `git commit -m "feat(privacy): CalDAV accounts in export and erasure"`

### Task 10: Glossary, ADR, docs, follow-ups

**Files:**
- Modify: `CONTEXT.md` (already updated during design; re-check that no "Google event" remains); ADRs 0001/0004/0006/0007 wording → "Mirror event" / provider-neutral, meaning unchanged
- Create: `docs/adr/0011-caldav-mirror-events-carry-no-attendees.md` (check the next free number): CalDAV Mirror events are written without ATTENDEE/ORGANIZER so servers' implicit scheduling can't double-invite; calit's `.ics` is the invite. Rejected: `SCHEDULE-AGENT=CLIENT` (not honoured by every server); attendees + no calit `.ics` (calit cannot rely on the server sending anything).
- `docs-site` branch: new "CalDAV" setup page (URL formats and app passwords for Nextcloud, Fastmail, iCloud, mailbox.org, Radicale; `CALDAV_ALLOW_PRIVATE_HOSTS`); configuration reference entry; `## Unreleased` changelog bullets per CLAUDE.md style. Upgrade note: "the previous release keeps working during a rolling deploy; the compatibility views are dropped in the next release".
- Beans: create `Drop V37 compatibility shims (google_* views, old booking/meeting_type columns, compat triggers)` (task, `todo`, blocked by this release being tagged); mark `calit-yotg` completed with a `## Summary of Changes`.

- [ ] **Step 1:** Edit the ADRs and add the new one; `precedent record` the attendee decision (`--rejected "SCHEDULE-AGENT=CLIENT" --rejected "server-sent invites"`).
- [ ] **Step 2:** `grep -rn "Google event" CONTEXT.md docs/adr` returns nothing outside quoted history.
- [ ] **Step 3:** docs-site commit (direct push allowed per memory `direct-main-push-ok`; still confirm with the user before pushing).
- [ ] **Step 4:** Full `mvn verify` green, then open PR 2 with a show-me diagram.

---

## Calls made while planning (deviations from the spec, flagged)

- **Client-side recurrence expansion only** (spec call 3 preferred server `expand` with a client fallback). One code path, tested on Radicale, works on every server.
- ~~CalDAV event UID is a fresh UUID~~: reverted with the user. The UID equals the `.ics` UID (spec call 12), so an invitation auto-imported into the same CalDAV calendar merges instead of duplicating.
- **No recreate-on-404** for CalDAV updates. It mirrors current Google behaviour; bean `calit-o69e` owns recreate for both providers.
- **DNS rebinding** is not prevented; it's marked as a `shortcut:` comment in `CaldavHttp`.
