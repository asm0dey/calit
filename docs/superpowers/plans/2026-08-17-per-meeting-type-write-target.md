# Per-meeting-type Google write target (calit-bh5t) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each Host optionally give one meeting type its own **write override** — a connected Google calendar that type's events are created on instead of that Host's write target.

**Architecture:** Two nullable columns on `meeting_type` (the Creator's override) and two on `meeting_type_host` (each Co-host's own override for that shared type) store a `(google_credential_id, google_calendar_id)` pair — the same address shape `CalendarRef` already carries for bookings since calit-rma2. A new `WriteTargetResolver` in `google/` turns `(ownerId, type)` into the `GoogleCalendar` to write on: the override when it still names one of that Host's selected calendars, else their write target, else nothing (the port's existing "no write target" error). A **dangling override** — calendar unticked, or its account disconnected so the FK nulled the credential and left the calendar id behind — never fails a booking and is never silently erased: the write falls back, the page says the choice is not in effect, and the value survives unrelated saves. `CalendarPort.createEvent` gains a `CalendarRef target` parameter in the same position update/delete already have it, so the create side stops calling `requireWriteTarget(ownerId)` blind.

**Tech Stack:** Quarkus 3.38 / Java 25, Panache entities, Flyway migrations, Qute `@CheckedTemplate` server-rendered forms, JUnit 5 + RestAssured + Mockito (`@InjectMock`), Maven Surefire with `reuseForks=true`.

## Global Constraints

- Build JDK: run every Maven command with `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` first — the default `java` on this machine is 21 and the build fails with "release 25 not supported".
- Docker must be running: Quarkus Dev Services provisions the test Postgres. No embedded fallback.
- Never edit an applied Flyway migration. New `V*.sql` only. Latest applied is `V26` (calit-rma2); this plan adds `V27`.
- Hibernate runs `schema-management.strategy=validate` — an entity field whose column type doesn't match the migration fails at boot. `String` defaults to `varchar(255)`, so a `text` column needs `@Column(columnDefinition = "text")`.
- No backfill: every existing `meeting_type` / `meeting_type_host` row gets NULL columns, which mean "use that owner's default write target" — today's behaviour exactly.
- Owner scoping: an override is only usable for `ownerId` when a live `GoogleCalendar` row exists with that `(ownerId, googleCredentialId, googleCalendarId)`. Validate on save server-side, never UI-only.
- Vocabulary (`CONTEXT.md`): the Owner-level calendar is the **write target** — it already means "by default", so it is NOT renamed to "default write target" (the glossary reserves that phrasing). The new per-(type, host) choice is a **write override**; an override naming a calendar the Host no longer has is a **dangling override**. Use these words in code, UI copy and docs.
- A dangling override must never fail a booking and must never be erased behind the Host's back: the write falls back to their write target (WARN-logged), the form shows an alert, and an unrelated save round-trips the stored value untouched (the `"keep"` sentinel in Tasks 6-7). Only an explicit pick clears or changes it.
- A disconnected account leaves `google_credential_id` NULL with `google_calendar_id` still set. That half-row is a dangling override — surfaced and warned about — **not** "no override".
- Degraded (no-Google) mode stays working: everything new sits behind the existing `calendarPort.isConnected(...)` guards, and the pickers render only when the owner has a connected account.
- Every new or changed user-facing string ships `de` **and** `he` values in `src/main/resources/messages/adm_{de,he}.properties` in the same commit. Placeholder names identical across locales.
- Formatting gate: `mvn spotless:apply` before each commit (lefthook does this for staged `*.java`; run it if you commit outside the hook).
- Tests are `@QuarkusTest` against the shared fork; `DatabaseResetCallback` truncates + reseeds per test and the admin user is always id 1. Tests that commit outside `@TestTransaction` (RestAssured POSTs) must clean up their Google rows in `@AfterEach`.
- `google_calendar.write_target` **column** and its uniqueness index `idx_google_calendar_single_write_target` are unchanged — still at most one default per owner. The override needs no such constraint.

---

### Task 1: Schema, entity columns, owned-calendar finder

**Files:**
- Create: `src/main/resources/db/migration/V27__meeting_type_write_target.sql`
- Modify: `src/main/java/site/asm0dey/calit/domain/MeetingType.java:73` (after `slotIntervalMinutes`)
- Modify: `src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java:44` (after `bufferAfterMinutes`)
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendar.java:71` (after `findByGoogleId`)
- Test: `src/test/java/site/asm0dey/calit/domain/WriteTargetOverrideColumnsTest.java`

**Interfaces:**
- Produces: `MeetingType.googleCredentialId` (`Long`), `MeetingType.googleCalendarId` (`String`), the same two fields on `MeetingTypeHost`, `GoogleCalendar.findOwned(Long ownerId, Long googleCredentialId, String googleCalendarId) -> GoogleCalendar` (null when this owner has no such selected calendar), and `GoogleCalendar#optionValue() -> String` (the `"credentialId:googleCalendarId"` form value the pickers use).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/domain/WriteTargetOverrideColumnsTest.java`:

```java
package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;

/** The per-(type, host) write-calendar override columns round-trip, and default to "unset". */
@QuarkusTest
class WriteTargetOverrideColumnsTest {

    @Test
    @TestTransaction
    void meetingTypeStoresTheOverride() {
        GoogleCredential cred = seedCredential("sub-override-type");
        MeetingType t = seedType();
        t.googleCredentialId = cred.id;
        t.googleCalendarId = "work@example.com";
        t.persistAndFlush();

        MeetingType loaded = MeetingType.findById(t.id);
        assertEquals(cred.id, loaded.googleCredentialId);
        assertEquals("work@example.com", loaded.googleCalendarId);
    }

    @Test
    @TestTransaction
    void aFreshTypeHasNoOverride() {
        MeetingType t = seedType();
        t.persistAndFlush();

        MeetingType loaded = MeetingType.findById(t.id);
        assertNull(loaded.googleCredentialId);
        assertNull(loaded.googleCalendarId);
    }

    @Test
    @TestTransaction
    void hostRowStoresItsOwnOverride() {
        GoogleCredential cred = seedCredential("sub-override-host");
        MeetingType t = seedType();
        t.persistAndFlush();
        MeetingTypeHost h = MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED);
        h.googleCredentialId = cred.id;
        h.googleCalendarId = "cohost@example.com";
        h.persistAndFlush();

        MeetingTypeHost loaded = MeetingTypeHost.findById(h.id);
        assertEquals(cred.id, loaded.googleCredentialId);
        assertEquals("cohost@example.com", loaded.googleCalendarId);
    }

    @Test
    @TestTransaction
    void findOwnedMatchesOnlyThisOwnersSelectedCalendar() {
        GoogleCredential cred = seedCredential("sub-find-owned");
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCredentialId = cred.id;
        c.googleCalendarId = "work@example.com";
        c.summary = "Work";
        c.persistAndFlush();

        assertEquals(c.id, GoogleCalendar.findOwned(1L, cred.id, "work@example.com").id);
        assertNull(GoogleCalendar.findOwned(1L, cred.id, "other@example.com"));
        assertNull(GoogleCalendar.findOwned(2L, cred.id, "work@example.com"));
        assertNull(GoogleCalendar.findOwned(1L, 999_999L, "work@example.com"));
    }

    private static GoogleCredential seedCredential(String sub) {
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt";
        cred.googleSub = sub;
        cred.persist();
        return cred;
    }

    private static MeetingType seedType() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "override-seed";
        t.slug = "override-seed-" + UUID.randomUUID();
        t.durationMinutes = 30;
        return t;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=WriteTargetOverrideColumnsTest
```

Expected: compilation failure — `cannot find symbol: variable googleCredentialId` on `MeetingType` / `MeetingTypeHost`, and `cannot find symbol: method findOwned`.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V27__meeting_type_write_target.sql`:

```sql
-- calit-bh5t: an OPTIONAL per-(meeting type, host) write calendar. meeting_type holds the creator's
-- choice; meeting_type_host holds each co-host's own choice for that shared type. Both NULL (the
-- default, and what every existing row gets -- no backfill) means "use that owner's default write
-- target", i.e. exactly today's behaviour.
--
-- Stored as a (credential, Google calendar id) pair rather than an FK to google_calendar.id:
-- CalendarSelectionService.save() deletes and re-inserts every local row on each settings save, so
-- local ids churn whenever the owner touches a checkbox, while the Google-side id survives. The
-- credential is kept because a calendar id alone carries no OAuth token, and because a calendar
-- shared into two connected accounts yields two rows with the same google_calendar_id.
--
-- ON DELETE SET NULL: disconnecting the account degrades the override to "unset" (fall back to the
-- default write target) instead of blocking the delete.
ALTER TABLE meeting_type
    ADD COLUMN google_calendar_id   text,
    ADD COLUMN google_credential_id bigint REFERENCES google_credential (id) ON DELETE SET NULL;

ALTER TABLE meeting_type_host
    ADD COLUMN google_calendar_id   text,
    ADD COLUMN google_credential_id bigint REFERENCES google_credential (id) ON DELETE SET NULL;
```

- [ ] **Step 4: Add the entity fields**

In `src/main/java/site/asm0dey/calit/domain/MeetingType.java`, after the `slotIntervalMinutes` field (line 73), before `effectiveSlotIntervalMinutes()`:

```java
    /**
     * Optional per-type override of the creator's write calendar: Google's calendar id. Null means
     * "use the creator's default write target". Resolved by {@code WriteTargetResolver}.
     */
    @Column(name = "google_calendar_id", columnDefinition = "text")
    public String googleCalendarId;

    /** The connected account {@link #googleCalendarId} belongs to; nulled when that account is disconnected. */
    @Column(name = "google_credential_id")
    public Long googleCredentialId;
```

In `src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java`, after the `bufferAfterMinutes` field (line 44), before `createdAt`:

```java
    /** This host's own write-calendar override for this shared type (Google's calendar id); null = their default. */
    @Column(name = "google_calendar_id", columnDefinition = "text")
    public String googleCalendarId;

    /** The connected account {@link #googleCalendarId} belongs to; nulled when that account is disconnected. */
    @Column(name = "google_credential_id")
    public Long googleCredentialId;
```

- [ ] **Step 5: Add the owned-calendar finder**

In `src/main/java/site/asm0dey/calit/google/GoogleCalendar.java`, after `findByGoogleId` (line 71):

```java
    /**
     * This owner's selected calendar with the given Google id on the given connected account, or
     * null. Unlike {@link #findByGoogleId(Long, String)} this disambiguates a calendar shared into
     * two accounts, which is exactly what a stored (credential, calendar) override names.
     */
    public static GoogleCalendar findOwned(Long ownerId, Long googleCredentialId, String googleCalendarId) {
        if (googleCredentialId == null || googleCalendarId == null) {
            return null;
        }
        return find(
                        "ownerId = ?1 and googleCredentialId = ?2 and googleCalendarId = ?3",
                        ownerId,
                        googleCredentialId,
                        googleCalendarId)
                .firstResult();
    }

    /**
     * This calendar as a {@code "credentialId:googleCalendarId"} form value — the encoding the Google
     * settings page already uses for its checkboxes/radios, and what the write-calendar pickers
     * submit. A method (not template string-concat) because Qute has no concatenation operator.
     */
    public String optionValue() {
        return googleCredentialId + ":" + googleCalendarId;
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=WriteTargetOverrideColumnsTest
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/resources/db/migration/V27__meeting_type_write_target.sql \
        src/main/java/site/asm0dey/calit/domain/MeetingType.java \
        src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java \
        src/main/java/site/asm0dey/calit/google/GoogleCalendar.java \
        src/test/java/site/asm0dey/calit/domain/WriteTargetOverrideColumnsTest.java
git commit -m "feat(google): store an optional per-(type, host) write-calendar override"
```

---

### Task 2: WriteTargetResolver

**Files:**
- Create: `src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java`
- Test: `src/test/java/site/asm0dey/calit/google/WriteTargetResolverTest.java`

**Interfaces:**
- Consumes: `MeetingType.googleCredentialId/googleCalendarId`, `MeetingTypeHost.googleCredentialId/googleCalendarId`, `GoogleCalendar.findOwned(...)` (Task 1).
- Produces, all on `@ApplicationScoped WriteTargetResolver`:
  - `CalendarRef writeOverride(Long ownerId, MeetingType type)` — the stored override for this (type, host), or null when unset / `type` is null. A row whose credential was nulled by a disconnect still returns a ref (`credentialId == null`, calendar id set) — that is a dangling override, not "unset".
  - `GoogleCalendar resolveCalendar(Long ownerId, MeetingType type)` — usable override, else this Host's write target, else null.
  - `CalendarRef resolve(Long ownerId, MeetingType type)` — `resolveCalendar` as an address, or null when the Host has no write calendar at all (the port then raises its existing error).
  - `CalendarRef writeTargetRef(Long ownerId)` — this Host's write target as an address, ignoring any override (used when a save is about to clear one).
  - `boolean owns(Long ownerId, CalendarRef ref)` — true when `ref` names one of this Host's selected calendars; false for every dangling ref.
  - `boolean blocksMeet(Long ownerId, MeetingType type)` — true when the resolved calendar exists and cannot mint Meet links.
  - `static CalendarRef parseRef(String raw)` — parses the `"credentialId:googleCalendarId"` form value; null for blank/malformed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/WriteTargetResolverTest.java`:

```java
package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.user.AppUser;

/**
 * Resolution order for a write: the (type, host) override when it still names one of that owner's
 * selected calendars, else that owner's default write target. A dangling override degrades to the
 * default instead of failing the booking.
 */
@QuarkusTest
class WriteTargetResolverTest {

    @Inject
    WriteTargetResolver resolver;

    @Test
    @TestTransaction
    void noOverrideResolvesToTheDefaultWriteTarget() {
        Long credId = seedCredential("sub-res-none");
        seedCalendar(1L, credId, "default@example.com", true, true);
        MeetingType t = seedType(1L);

        assertNull(resolver.writeOverride(1L, t));
        assertEquals(new CalendarRef(credId, "default@example.com"), resolver.resolve(1L, t));
    }

    @Test
    @TestTransaction
    void creatorOverrideWins() {
        Long credId = seedCredential("sub-res-creator");
        seedCalendar(1L, credId, "default@example.com", true, true);
        seedCalendar(1L, credId, "work@example.com", false, true);
        MeetingType t = seedType(1L);
        t.googleCredentialId = credId;
        t.googleCalendarId = "work@example.com";
        t.persistAndFlush();

        assertEquals(new CalendarRef(credId, "work@example.com"), resolver.resolve(1L, t));
    }

    @Test
    @TestTransaction
    void cohostOverrideIsReadFromTheirOwnHostRow() {
        Long creatorCred = seedCredential("sub-res-creator-2");
        seedCalendar(1L, creatorCred, "creator@example.com", true, true);
        AppUser cohost = AppUser.create("cohost-resolver", "x", false);
        cohost.persist();
        Long cohostCred = seedCredential(cohost.id, "sub-res-cohost");
        seedCalendar(cohost.id, cohostCred, "cohost-default@example.com", true, true);
        seedCalendar(cohost.id, cohostCred, "cohost-work@example.com", false, true);

        MeetingType t = seedType(1L);
        t.persistAndFlush();
        MeetingTypeHost h = MeetingTypeHost.of(t.id, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED);
        h.googleCredentialId = cohostCred;
        h.googleCalendarId = "cohost-work@example.com";
        h.persistAndFlush();

        // The creator still writes on their own default; the co-host writes on their own override.
        assertEquals(new CalendarRef(creatorCred, "creator@example.com"), resolver.resolve(1L, t));
        assertEquals(new CalendarRef(cohostCred, "cohost-work@example.com"), resolver.resolve(cohost.id, t));
    }

    @Test
    @TestTransaction
    void danglingOverrideFallsBackToTheDefault() {
        Long credId = seedCredential("sub-res-dangling");
        seedCalendar(1L, credId, "default@example.com", true, true);
        MeetingType t = seedType(1L);
        t.googleCredentialId = credId;
        t.googleCalendarId = "unticked@example.com"; // no GoogleCalendar row: unticked since
        t.persistAndFlush();

        assertEquals(new CalendarRef(credId, "default@example.com"), resolver.resolve(1L, t));
        assertFalse(resolver.owns(1L, resolver.writeOverride(1L, t)));
    }

    @Test
    @TestTransaction
    void aDisconnectedAccountLeavesADanglingOverrideNotAnUnsetOne() {
        // Disconnecting nulls meeting_type.google_credential_id via the FK and leaves the calendar
        // id behind. That half-row must still read as an override so the Host is told about it.
        Long credId = seedCredential("sub-res-disconnected");
        seedCalendar(1L, credId, "default@example.com", true, true);
        MeetingType t = seedType(1L);
        t.googleCredentialId = null;
        t.googleCalendarId = "was-on-a-disconnected-account@example.com";
        t.persistAndFlush();

        CalendarRef override = resolver.writeOverride(1L, t);
        assertEquals("was-on-a-disconnected-account@example.com", override.googleCalendarId());
        assertNull(override.credentialId());
        assertFalse(resolver.owns(1L, override));
        assertEquals(new CalendarRef(credId, "default@example.com"), resolver.resolve(1L, t));
    }

    @Test
    @TestTransaction
    void noCalendarAtAllResolvesToNull() {
        MeetingType t = seedType(1L);

        assertNull(resolver.resolve(1L, t));
    }

    @Test
    @TestTransaction
    void meetGateFollowsTheResolvedCalendar() {
        Long credId = seedCredential("sub-res-meet");
        seedCalendar(1L, credId, "default@example.com", true, false); // default cannot Meet
        seedCalendar(1L, credId, "meet@example.com", false, true); // override can
        MeetingType t = seedType(1L);
        t.googleCredentialId = credId;
        t.googleCalendarId = "meet@example.com";
        t.persistAndFlush();

        assertFalse(resolver.blocksMeet(1L, t));
        assertTrue(resolver.blocksMeet(1L, seedType(1L))); // a type with no override sees the default
    }

    @Test
    void parsesTheFormValue() {
        assertEquals(new CalendarRef(7L, "a@example.com"), WriteTargetResolver.parseRef("7:a@example.com"));
        assertNull(WriteTargetResolver.parseRef(""));
        assertNull(WriteTargetResolver.parseRef(null));
        assertNull(WriteTargetResolver.parseRef("nonsense"));
        assertNull(WriteTargetResolver.parseRef("x:a@example.com"));
    }

    private static Long seedCredential(String sub) {
        return seedCredential(1L, sub);
    }

    private static Long seedCredential(Long ownerId, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = ownerId;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        return c.id;
    }

    private static void seedCalendar(Long ownerId, Long credId, String calId, boolean writeTarget, boolean meet) {
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = ownerId;
        c.googleCredentialId = credId;
        c.googleCalendarId = calId;
        c.summary = calId;
        c.readForBusy = writeTarget;
        c.writeTarget = writeTarget;
        c.supportsMeet = meet;
        c.persist();
    }

    private static MeetingType seedType(Long ownerId) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "resolver-seed";
        t.slug = "resolver-seed-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.persist();
        return t;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=WriteTargetResolverTest
```

Expected: compilation failure — `cannot find symbol: class WriteTargetResolver`.

- [ ] **Step 3: Write the resolver**

Create `src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java`:

```java
package site.asm0dey.calit.google;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;

/**
 * Which Google calendar a given host writes a given meeting type's events on:
 *
 * <ol>
 *   <li>the (type, host) override — {@code meeting_type} columns for the creator, that host's
 *       {@code meeting_type_host} row for a co-host — when it still names one of that owner's
 *       selected calendars;
 *   <li>otherwise the owner's default write target ({@code google_calendar.write_target});
 *   <li>otherwise nothing, and {@link GoogleCalendarPort} raises its existing "no write target"
 *       error.
 * </ol>
 *
 * A dangling override (calendar unticked, or the account disconnected so the FK nulled the
 * credential) never fails a booking: it degrades to the default, loudly in the log and visibly in
 * the edit form.
 */
@ApplicationScoped
public class WriteTargetResolver {

    private static final Logger LOG = Logger.getLogger(WriteTargetResolver.class);

    /**
     * The stored write override for this (type, host), or null when unset. A row whose credential
     * was nulled by disconnecting the account keeps its calendar id, and that half-row IS an
     * override — a dangling one. Reading it as "unset" would hide the very case the Host needs to
     * be told about.
     */
    public CalendarRef writeOverride(Long ownerId, MeetingType type) {
        if (type == null) {
            return null;
        }
        if (ownerId.equals(type.ownerId)) {
            return ref(type.googleCredentialId, type.googleCalendarId);
        }
        MeetingTypeHost host = type.id == null ? null : MeetingTypeHost.find(type.id, ownerId);
        return host == null ? null : ref(host.googleCredentialId, host.googleCalendarId);
    }

    /** True when {@code ref} names one of this owner's currently selected calendars. */
    public boolean owns(Long ownerId, CalendarRef ref) {
        return ref != null && GoogleCalendar.findOwned(ownerId, ref.credentialId(), ref.googleCalendarId()) != null;
    }

    /** The calendar this host writes {@code type} on, or null when they have no write calendar at all. */
    public GoogleCalendar resolveCalendar(Long ownerId, MeetingType type) {
        CalendarRef override = writeOverride(ownerId, type);
        if (override != null) {
            GoogleCalendar live =
                    GoogleCalendar.findOwned(ownerId, override.credentialId(), override.googleCalendarId());
            if (live != null) {
                return live;
            }
            LOG.warnf(
                    "Meeting type %s: write-calendar override %s (credential %s, owner %d) is no longer selected; falling back to the default write target",
                    type.id, override.googleCalendarId(), override.credentialId(), ownerId);
        }
        return GoogleCalendar.writeTarget(ownerId);
    }

    /** {@link #resolveCalendar} as an address for {@link CalendarPort}, or null when there is none. */
    public CalendarRef resolve(Long ownerId, MeetingType type) {
        return address(resolveCalendar(ownerId, type));
    }

    /**
     * This Host's write target as an address, ignoring any override. Callers that are about to
     * CLEAR an override use it to know where the type will write next, which is not the same as
     * "nowhere".
     */
    public CalendarRef writeTargetRef(Long ownerId) {
        return address(GoogleCalendar.writeTarget(ownerId));
    }

    private static CalendarRef address(GoogleCalendar calendar) {
        return calendar == null ? null : new CalendarRef(calendar.googleCredentialId, calendar.googleCalendarId);
    }

    /**
     * True when the calendar this host would write {@code type} on cannot mint Google Meet links, so
     * GOOGLE_MEET must be refused. False when there is no calendar yet — don't over-block, the owner
     * may pick a Meet-capable one later.
     */
    public boolean blocksMeet(Long ownerId, MeetingType type) {
        GoogleCalendar target = resolveCalendar(ownerId, type);
        return target != null && !target.supportsMeet;
    }

    /**
     * Parse the {@code "credentialId:googleCalendarId"} value the pickers submit (same encoding the
     * Google settings page already uses). Blank or malformed → null, meaning "no override".
     */
    public static CalendarRef parseRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int sep = raw.indexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            return null;
        }
        try {
            return new CalendarRef(Long.valueOf(raw.substring(0, sep)), raw.substring(sep + 1));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * The calendar id alone decides whether an override exists: a null credential means the account
     * was disconnected (the FK nulled it), which is a dangling override, not the absence of one.
     */
    private static CalendarRef ref(Long credentialId, String googleCalendarId) {
        return googleCalendarId == null ? null : new CalendarRef(credentialId, googleCalendarId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=WriteTargetResolverTest
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/google/WriteTargetResolver.java \
        src/test/java/site/asm0dey/calit/google/WriteTargetResolverTest.java
git commit -m "feat(google): resolve a meeting type's write calendar per host"
```

---

### Task 3: createEvent takes the calendar to write on

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/CalendarPort.java:31-39` (`createEvent` signature + javadoc)
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java:96-132` (`createEvent`), `:324-331` (`writeContext`)
- Modify (mechanical, stub arity): the 18 test files listed in Step 5
- Test: `src/test/java/site/asm0dey/calit/google/CreateEventTargetTest.java`

**Interfaces:**
- Consumes: `CalendarRef` (existing), `GoogleCalendar.findOwned(...)` (Task 1).
- Produces: `CalendarPort.createEvent(Long ownerId, CalendarRef target, String summary, String description, Instant start, Instant end, List<String> attendeeEmails, boolean createMeetLink, String locationText) -> CreatedEvent`. `target == null` (or one that no longer resolves) means "the owner's default write target", which is what every caller did before this task.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/CreateEventTargetTest.java`:

```java
package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/** createEvent inserts on the calendar it is given, and falls back to the default write target. */
@QuarkusTest
class CreateEventTargetTest {

    private Calendar.Events events;
    private GoogleTokenService tokens;

    @Test
    @Transactional
    void insertsOnTheGivenCalendar() throws IOException {
        seedOwnerSettings();
        Long credId = seedWriteTarget("sub-create-target", "default@example.com");
        seedCalendar(credId, "work@example.com");
        GoogleCalendarPort port = port();

        CreatedEvent created = port.createEvent(
                1L,
                new CalendarRef(credId, "work@example.com"),
                "s",
                "d",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of("a@example.com"),
                false,
                null);

        verify(events).insert(eqCalendar("work@example.com"), any());
        assertEquals("work@example.com", created.calendar().googleCalendarId());
        assertEquals(credId, created.calendar().credentialId());
    }

    @Test
    @Transactional
    void nullTargetInsertsOnTheDefaultWriteTarget() throws IOException {
        seedOwnerSettings();
        seedWriteTarget("sub-create-null", "default@example.com");
        GoogleCalendarPort port = port();

        port.createEvent(
                1L,
                null,
                "s",
                "d",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of(),
                false,
                null);

        verify(events).insert(eqCalendar("default@example.com"), any());
    }

    @Test
    @Transactional
    void unresolvableTargetInsertsOnTheDefaultWriteTarget() throws IOException {
        seedOwnerSettings();
        Long credId = seedWriteTarget("sub-create-dangling", "default@example.com");
        GoogleCalendarPort port = port();

        // No GoogleCalendar row for "gone@example.com": the picker's choice was unticked since.
        port.createEvent(
                1L,
                new CalendarRef(credId, "gone@example.com"),
                "s",
                "d",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of(),
                false,
                null);

        verify(events).insert(eqCalendar("default@example.com"), any());
    }

    /** Readability helper: Mockito's eq() for the calendar-id argument of events.insert. */
    private static String eqCalendar(String calendarId) {
        return argThat(calendarId::equals);
    }

    /** A port whose events.insert(...).execute() returns a fixed event. */
    private GoogleCalendarPort port() throws IOException {
        tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Insert insert = mock(Calendar.Events.Insert.class);
        when(insert.setConferenceDataVersion(anyInt())).thenReturn(insert);
        when(insert.setSendUpdates(anyString())).thenReturn(insert);
        when(insert.execute()).thenReturn(new Event().setId("evt-1").setHtmlLink("https://calendar.example"));
        events = mock(Calendar.Events.class);
        when(events.insert(anyString(), any())).thenReturn(insert);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
    }

    /** createEvent's eventTime() reads the owner's zone from OwnerSettings. */
    private static void seedOwnerSettings() {
        OwnerSettings s = new OwnerSettings();
        s.ownerId = 1L;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    /** Owner 1 gets one connected account and one default write-target calendar. Returns the credential id. */
    private static Long seedWriteTarget(String sub, String calendarId) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        GoogleCalendar wt = new GoogleCalendar();
        wt.ownerId = 1L;
        wt.googleCredentialId = c.id;
        wt.googleCalendarId = calendarId;
        wt.summary = "Default";
        wt.readForBusy = true;
        wt.writeTarget = true;
        wt.persist();
        return c.id;
    }

    /** A second selected (non-default) calendar on the same account. */
    private static void seedCalendar(Long credId, String calendarId) {
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCredentialId = credId;
        c.googleCalendarId = calendarId;
        c.summary = calendarId;
        c.persist();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=CreateEventTargetTest
```

Expected: compilation failure — `createEvent` in `GoogleCalendarPort` cannot be applied to the given types (9 args vs 8).

- [ ] **Step 3: Change the port interface**

In `src/main/java/site/asm0dey/calit/google/CalendarPort.java`, replace the `createEvent` javadoc + signature (lines 22-39) with:

```java
    /**
     * Create an event on the given calendar, always with {@code sendUpdates=all} so Google emails the
     * attendees the invite.
     *
     * @param target         where to create it: the meeting type's resolved write calendar. Null — or
     *                       one that no longer names a selected calendar — falls back to the owner's
     *                       default write target, which is what every create did before calit-bh5t
     * @param createMeetLink when true, attach a Google Meet conference (returns a non-null meetLink);
     *                       when false, no conference is created and {@code locationText} is set as the
     *                       event location instead, and the returned meetLink is null
     * @param locationText   per-type location text used when {@code createMeetLink} is false (may be null)
     */
    CreatedEvent createEvent(
            Long ownerId,
            CalendarRef target,
            String summary,
            String description,
            Instant start,
            Instant end,
            List<String> attendeeEmails,
            boolean createMeetLink,
            String locationText);
```

- [ ] **Step 4: Change the Google implementation**

In `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java`, replace the `createEvent` header and its first two statements (lines 96-109) with:

```java
    @Override
    @Transactional
    public CreatedEvent createEvent(
            Long ownerId,
            CalendarRef target,
            String summary,
            String description,
            Instant start,
            Instant end,
            List<String> attendeeEmails,
            boolean createMeetLink,
            String locationText) {
        var ctx = writeContext(ownerId, target);
        GoogleCalendar targetCalendar = ctx.target();
        GoogleCredential cred = ctx.cred();
```

Then rename the two later uses of the old local inside `createEvent` (the `insert(cred, target, event, createMeetLink)` call and `new CalendarRef(cred.id, target.googleCalendarId)`) to `targetCalendar`:

```java
        try {
            Event created = insert(cred, targetCalendar, event, createMeetLink);
            String meetLink = createMeetLink ? extractMeetLink(created) : null;
            return new CreatedEvent(
                    created.getId(),
                    meetLink,
                    created.getHtmlLink(),
                    new CalendarRef(cred.id, targetCalendar.googleCalendarId));
        } catch (GoogleJsonResponseException e) {
            return handleCreateFailure(e, cred, targetCalendar, event, createMeetLink);
        } catch (IOException e) {
            throw new UncheckedIOException("createEvent failed", e);
        }
    }
```

Add the ref-aware overload next to the existing `writeContext(Long)` (after line 331):

```java
    /**
     * The calendar to create on: the given (already resolved) override when it still names one of
     * this owner's selected calendars, else the owner's default write target. The second guard is
     * belt-and-braces — {@code WriteTargetResolver} already degrades a dangling override — so a
     * crafted or stale ref can never write onto another owner's calendar.
     */
    private WriteContext writeContext(Long ownerId, CalendarRef ref) {
        if (ref != null) {
            GoogleCalendar target = GoogleCalendar.findOwned(ownerId, ref.credentialId(), ref.googleCalendarId());
            if (target != null) {
                GoogleCredential cred = GoogleCredential.findById(target.googleCredentialId);
                if (cred != null && ownerId.equals(cred.ownerId)) {
                    return new WriteContext(target, cred);
                }
            }
        }
        return writeContext(ownerId);
    }
```

Note for the reviewer: `handleCreateFailure`'s 404 branch calls `clearDeletedWriteTarget`, which clears `writeTarget` on the row it was given. For an override row that flag is already false, so the call only sets `needsReconnect` on the credential — correct enough (the owner is prompted to reconnect and re-pick), and no worse than today.

- [ ] **Step 5: Update every existing stub to the new arity**

The signature change breaks Mockito stubs/verifies in 18 test files. The single-line ones are mechanical:

```bash
cd /home/finkel/work_self/calit
grep -rl "createEvent(anyLong()," src/test/java | xargs sed -i 's/createEvent(anyLong(), /createEvent(anyLong(), any(), /g'
grep -rn "createEvent(eq(" src/test/java
```

Then fix by hand the remaining call sites, inserting `any()` (or the expected `CalendarRef`) as the **second** argument:

- `src/test/java/site/asm0dey/calit/booking/GroupBookingWriteTest.java` — 3 multi-line stubs + `createEvent(eq(cohostId), anyString(), ...)`
- `src/test/java/site/asm0dey/calit/booking/BookServiceTest.java` — 3 multi-line stubs
- `src/test/java/site/asm0dey/calit/booking/ApproveDeclineTest.java` — 2 multi-line stubs
- `src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java` — 1 multi-line stub
- `src/test/java/site/asm0dey/calit/booking/BookingCalendarAddressTest.java` — the `stubGoogle` helper's multi-line stub
- `src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java` — `createEvent(eq(v.id), any(), ...)` (add one more `any()`)
- `src/test/java/site/asm0dey/calit/google/CreateEventRoutingTest.java` — the direct `port.createEvent(1L, "s", ...)` call gets `null` as its second argument:

```java
                () -> port.createEvent(
                        1L,
                        null,
                        "s",
                        "d",
                        Instant.now(),
                        Instant.now().plusSeconds(1800),
                        List.of("a@example.com"),
                        true,
                        null));
```

Any file that now uses `any()` without the static import needs `import static org.mockito.ArgumentMatchers.any;` — the compiler names them.

- [ ] **Step 6: Run the new test and the whole suite**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=CreateEventTargetTest
mvn test
```

Expected: `CreateEventTargetTest` PASS (3 tests); full suite green — no behaviour changed yet, every caller still passes a null/any target.

- [ ] **Step 7: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/google/CalendarPort.java \
        src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java \
        src/test/java/site/asm0dey/calit/google/CreateEventTargetTest.java \
        src/test/java
git commit -m "feat(google): createEvent writes on the calendar it is given"
```

---

### Task 4: BookingService passes the resolved target

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:48-60` (constructor), `:426` (group create), `:514` (single create)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingWriteTargetOverrideTest.java`

**Interfaces:**
- Consumes: `WriteTargetResolver.resolve(Long ownerId, MeetingType type)` (Task 2), `CalendarPort.createEvent(ownerId, target, ...)` (Task 3).
- Produces: no new public API — bookings now create their event on the resolved calendar, and (via `CreatedEvent.calendar()`, already wired by calit-rma2) store that address on the booking row.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/BookingWriteTargetOverrideTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarRef;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;

/** A booking's Google event is created on the meeting type's write calendar, not blindly on the default. */
@QuarkusTest
class BookingWriteTargetOverrideTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();

    @Test
    @TestTransaction
    void usesTheTypesOverride() {
        Long credId = seedCredential("sub-book-override");
        seedCalendar(credId, "default@example.com", true);
        seedCalendar(credId, "work@example.com", false);
        stubGoogle();

        book("book-override", credId, "work@example.com");

        verify(calendarPort)
                .createEvent(
                        eq(1L),
                        eq(new CalendarRef(credId, "work@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    @TestTransaction
    void withoutAnOverrideUsesTheDefaultWriteTarget() {
        Long credId = seedCredential("sub-book-default");
        seedCalendar(credId, "default@example.com", true);
        stubGoogle();

        book("book-default", null, null);

        verify(calendarPort)
                .createEvent(
                        eq(1L),
                        eq(new CalendarRef(credId, "default@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    @TestTransaction
    void aDanglingOverrideStillBooksOnTheDefault() {
        Long credId = seedCredential("sub-book-dangling");
        seedCalendar(credId, "default@example.com", true);
        stubGoogle();

        book("book-dangling", credId, "unticked@example.com");

        verify(calendarPort)
                .createEvent(
                        eq(1L),
                        eq(new CalendarRef(credId, "default@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    @TestTransaction
    void degradedModeCreatesNoEvent() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        book("book-degraded", null, null);

        verify(calendarPort, never())
                .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    private void stubGoogle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", null, null, null));
    }

    /** Seed owner settings + a 09:00-11:00 type (with the given override) on DAY, then book 09:00. */
    private Booking book(String slug, Long credId, String calendarId) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug + "-" + UUID.randomUUID();
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = MeetingType.LocationType.PHONE;
        t.googleCredentialId = credId;
        t.googleCalendarId = calendarId;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();

        return bookingService.book(
                1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
    }

    private static Long seedCredential(String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        return c.id;
    }

    private static void seedCalendar(Long credId, String calId, boolean writeTarget) {
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCredentialId = credId;
        c.googleCalendarId = calId;
        c.summary = calId;
        c.readForBusy = writeTarget;
        c.writeTarget = writeTarget;
        c.persist();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=BookingWriteTargetOverrideTest
```

Expected: FAIL — `usesTheTypesOverride` and the default/dangling cases report `createEvent` called with `null` as the target (BookingService still passes nothing).

- [ ] **Step 3: Inject the resolver and pass the target**

In `src/main/java/site/asm0dey/calit/booking/BookingService.java`, add the field next to `meetingHosts` and the constructor parameter (lines 40-60):

```java
    private final MeetingHosts meetingHosts;
    private final WriteTargetResolver writeTargets;
    private final long perEmailDailyCap;
```

```java
    @Inject
    public BookingService(
            SlotService slotService,
            CalendarPort calendarPort,
            CaptchaVerifier captchaVerifier,
            MeetingHosts meetingHosts,
            WriteTargetResolver writeTargets,
            @ConfigProperty(name = "calit.abuse.per-email-daily-cap", defaultValue = "10") long perEmailDailyCap) {
        this.slotService = slotService;
        this.calendarPort = calendarPort;
        this.captchaVerifier = captchaVerifier;
        this.meetingHosts = meetingHosts;
        this.writeTargets = writeTargets;
        this.perEmailDailyCap = perEmailDailyCap;
    }
```

Add the import alongside the other `google` imports (near line 26):

```java
import site.asm0dey.calit.google.WriteTargetResolver;
```

In `createGroupGoogleEvent` (line 426), pass the organizer's resolved target — resolution runs for whichever owner `chooseOrganizer` picked, so a co-host organizer gets their own override or their own default:

```java
        CreatedEvent created = calendarPort.createEvent(
                organizer,
                writeTargets.resolve(organizer, type),
                googleSummary(type, lead),
                googleDescription(type, lead),
                lead.startUtc,
                lead.endUtc,
                attendees,
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
```

In `createGoogleEvent` (line 514):

```java
        CreatedEvent created = calendarPort.createEvent(
                type.ownerId,
                writeTargets.resolve(type.ownerId, type),
                googleSummary(type, booking),
                googleDescription(type, booking),
                booking.startUtc,
                booking.endUtc,
                attendeeEmails(booking, owner),
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
```

- [ ] **Step 4: Run the test and the suite**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=BookingWriteTargetOverrideTest
mvn test
```

Expected: new test PASS (4 tests); full suite green (existing stubs use `any()` in the target position).

- [ ] **Step 5: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/BookingWriteTargetOverrideTest.java
git commit -m "feat(booking): create a booking's event on the type's write calendar"
```

---

### Task 5: The Meet gate follows the creator's resolved calendar

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` — `allowedLocationTypes()` (~line 601), `parseLocationType(String)` (~line 619), `applyEditableFields(...)`, plus the `WriteTargetResolver` injection
- Test: `src/test/java/site/asm0dey/calit/web/AdminMeetGatingOverrideTest.java`

**Interfaces:**
- Consumes: `WriteTargetResolver.blocksMeet(Long ownerId, MeetingType type)` (Task 2).
- Produces: `allowedLocationTypes(MeetingType type)` and `parseLocationType(String locationType, MeetingType type)` inside `AdminResource`; `applyEditableFields` passes the type it is mutating. Co-host overrides deliberately do not affect the gate — a co-host organizer on a non-Meet calendar degrades through the port's existing `handleCreateFailure` retry.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminMeetGatingOverrideTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * The GOOGLE_MEET gate follows the calendar the type actually writes on: a Meet-capable override
 * unblocks a type whose owner default cannot Meet, and a non-Meet override blocks one whose default
 * can.
 */
@QuarkusTest
class AdminMeetGatingOverrideTest {

    @AfterEach
    @Transactional
    void cleanup() {
        MeetingType.delete("slug like ?1", "meet-override-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
    }

    @Test
    void meetAllowedWhenTheTypeOverridesToAMeetCapableCalendar() {
        Long typeId = seed(false, true); // default cannot Meet, override can
        editWithLocation(typeId, "GOOGLE_MEET").statusCode(200);
    }

    @Test
    void meetRejectedWhenTheTypeOverridesToANonMeetCalendar() {
        Long typeId = seed(true, false); // default can Meet, override cannot
        editWithLocation(typeId, "GOOGLE_MEET").statusCode(400);
    }

    private io.restassured.response.ValidatableResponse editWithLocation(Long typeId, String locationType) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Meet override")
                .formParam("slug", "meet-override-" + typeId)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", locationType)
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + typeId + "/edit")
                .then();
    }

    /** Owner 1 gets a default write target + a second calendar, and a type overriding onto the second. */
    @Transactional
    Long seed(boolean defaultSupportsMeet, boolean overrideSupportsMeet) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-meet-override-" + UUID.randomUUID();
        c.persist();
        persistCalendar(c.id, "default@example.com", true, defaultSupportsMeet);
        persistCalendar(c.id, "override@example.com", false, overrideSupportsMeet);

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Meet override";
        t.slug = "meet-override-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.googleCredentialId = c.id;
        t.googleCalendarId = "override@example.com";
        t.persist();
        return t.id;
    }

    private static void persistCalendar(Long credId, String calId, boolean writeTarget, boolean meet) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = 1L;
        g.googleCredentialId = credId;
        g.googleCalendarId = calId;
        g.summary = calId;
        g.readForBusy = writeTarget;
        g.writeTarget = writeTarget;
        g.supportsMeet = meet;
        g.persist();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminMeetGatingOverrideTest
```

Expected: FAIL — `meetAllowedWhenTheTypeOverridesToAMeetCapableCalendar` gets 400 and `meetRejectedWhenTheTypeOverridesToANonMeetCalendar` gets 200: the gate still reads the owner-level write target only.

- [ ] **Step 3: Thread the type through the gate**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, add the collaborator to the existing constructor injection (field list at line ~150, constructor at line ~166) and the import `site.asm0dey.calit.google.WriteTargetResolver`:

```java
    final MeetingHosts meetingHosts;

    final WriteTargetResolver writeTargets;
```

```java
    @Inject
    public AdminResource(
            BookingService bookingService,
            MeetingHosts meetingHosts,
            WriteTargetResolver writeTargets,
            CurrentOwner currentOwner,
            SecurityIdentity identity,
            AdminMessageResolver adminMsgs,
            AppMessageResolver appMsgs,
            ActiveLocale activeLocale,
            @ConfigProperty(name = "app.base-url") String baseUrl,
            @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "1440") int reminderLeadMinutes) {
        this.bookingService = bookingService;
        this.meetingHosts = meetingHosts;
        this.writeTargets = writeTargets;
        this.currentOwner = currentOwner;
        this.identity = identity;
        this.adminMsgs = adminMsgs;
        this.appMsgs = appMsgs;
        this.activeLocale = activeLocale;
        this.baseUrl = baseUrl;
        this.reminderLeadMinutes = reminderLeadMinutes;
    }
```

Replace `allowedLocationTypes()` and `parseLocationType(String)` with type-aware versions:

```java
    /**
     * Location types offered on the create form, where no type (hence no override) exists yet: the
     * owner's default write target decides.
     */
    private LocationType[] allowedLocationTypes() {
        return allowedLocationTypes(null);
    }

    /**
     * Location types offered for {@code type}. Drops GOOGLE_MEET when the calendar this type writes
     * on — its own override, else the owner's default write target — can't mint Meet links, so the
     * option is never even shown (it would 400 at booking).
     */
    private LocationType[] allowedLocationTypes(MeetingType type) {
        if (writeTargets.blocksMeet(currentOwner.id(), type)) {
            return Arrays.stream(LocationType.values())
                    .filter(lt -> lt != LocationType.GOOGLE_MEET)
                    .toArray(LocationType[]::new);
        }
        return LocationType.values();
    }

    /**
     * Enforces the gate behind {@link #allowedLocationTypes(MeetingType)} for the actual write (the
     * edit form still shows every type so a stale value renders, and crafted POSTs must not slip
     * through): GOOGLE_MEET is rejected when the calendar this type writes on can't create Meet links.
     */
    private LocationType parseLocationType(String locationType, MeetingType type) {
        LocationType lt = LocationType.valueOf(locationType);
        if (lt == LocationType.GOOGLE_MEET && writeTargets.blocksMeet(currentOwner.id(), type)) {
            throw new BadRequestException(
                    "The selected write calendar can't create Google Meet links; pick another location.");
        }
        return lt;
    }
```

In `applyEditableFields(...)`, pass the type being mutated:

```java
        t.locationType = parseLocationType(locationType, t);
```

(`createMeetingType` builds its `MeetingType` with `ownerId` set before calling `applyEditableFields`, and a brand-new type carries no override, so the create path still gates on the owner's default.)

- [ ] **Step 4: Run the test and the existing gate test**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminMeetGatingOverrideTest
mvn test -Dtest=AdminMeetGatingTest
```

Expected: both PASS — the no-override cases in `AdminMeetGatingTest` still gate on the default write target.

- [ ] **Step 5: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/test/java/site/asm0dey/calit/web/AdminMeetGatingOverrideTest.java
git commit -m "feat(admin): gate Google Meet on the calendar the type writes on"
```

---

### Task 6: Creator's calendar picker on the meeting-type detail page

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` — `Templates.meetingTypeDetail` signature (line 55), `detailInstance(Long, String)` (~line 645), `editMeetingType` (~line 710)
- Modify: `src/main/resources/templates/AdminResource/meetingTypeDetail.html` (param header + the basics form, after the location-detail input)
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (after `adm_detail_label_location_detail`)
- Modify: `src/main/resources/messages/adm_de.properties`, `src/main/resources/messages/adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/AdminWriteCalendarTest.java`

**Interfaces:**
- Consumes: `WriteTargetResolver.writeOverride/owns/parseRef` (Task 2), `GoogleCalendar#optionValue()` (Task 1).
- Produces: four new `meetingTypeDetail` template params — `List<GoogleCalendar> writeCalendars` (this Host's selected calendars; empty when Google is not connected), `String writeCalendarValue` (`optionValue()` of a live override, `"keep"` when it dangles, `""` when unset), `boolean writeCalendarDangling`, `String notice` (info alert; distinct from the existing `error`) — plus a `writeCalendar` form field on `POST /me/meeting-types/{id}/edit` whose three meanings are: `""` = clear the override, `"keep"` = leave the stored value untouched, `"credentialId:googleCalendarId"` = set it (validated against this Host's selected calendars).
- Produces: `AdminResource#bookingsStayingBehind(MeetingType type, CalendarRef newTarget) -> long` — upcoming bookings of this type that already have a Google event on a different calendar, used for the counted notice and reused by Task 7.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminWriteCalendarTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.user.AppUser;

/**
 * The Creator picks a write override per type: a calendar that isn't theirs is refused, a dangling
 * override is warned about and survives an unrelated save, and moving a type with upcoming bookings
 * says those bookings stay where they were created.
 */
@QuarkusTest
class AdminWriteCalendarTest {

    @AfterEach
    @Transactional
    void cleanup() {
        Booking.delete("meetingTypeId in (select t.id from MeetingType t where t.slug like ?1)", "write-cal-%");
        MeetingType.delete("slug like ?1", "write-cal-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
        AppUser.delete("username", "write-cal-other");
    }

    @Test
    void savesTheChosenCalendar() {
        Long credId = seedOwnerCalendars();
        Long typeId = seedType(null, null);

        edit(typeId, credId + ":work@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertEquals(credId, t.googleCredentialId);
        assertEquals("work@example.com", t.googleCalendarId);
    }

    @Test
    void blankClearsTheOverride() {
        Long credId = seedOwnerCalendars();
        Long typeId = seedType(credId, "work@example.com");

        edit(typeId, "").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertNull(t.googleCredentialId);
        assertNull(t.googleCalendarId);
    }

    @Test
    void aForeignCalendarIsRejectedAndNothingIsPersisted() {
        seedOwnerCalendars();
        Long foreignCredId = seedForeignCalendar();
        Long typeId = seedType(null, null);

        edit(typeId, foreignCredId + ":foreign@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertNull(t.googleCredentialId);
        assertNull(t.googleCalendarId);
    }

    @Test
    void aDanglingOverrideIsWarnedAboutOnTheForm() {
        Long credId = seedOwnerCalendars();
        Long typeId = seedType(credId, "unticked@example.com");

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + typeId)
                .then()
                .statusCode(200)
                .body(containsString("data-write-calendar-dangling"))
                .body(containsString("value=\"keep\""));
    }

    @Test
    void aDisconnectedAccountAlsoWarns() {
        // The FK nulled google_credential_id and left the calendar id: still a dangling override.
        seedOwnerCalendars();
        Long typeId = seedType(null, "was-on-a-disconnected-account@example.com");

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + typeId)
                .then()
                .statusCode(200)
                .body(containsString("data-write-calendar-dangling"));
    }

    @Test
    void anUnrelatedSaveKeepsADanglingOverride() {
        // Renaming a type must not erase a write override the Host never touched: the dangling
        // option carries value="keep", and "keep" leaves both columns alone.
        Long credId = seedOwnerCalendars();
        Long typeId = seedType(credId, "unticked@example.com");

        edit(typeId, "keep").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertEquals(credId, t.googleCredentialId);
        assertEquals("unticked@example.com", t.googleCalendarId);
    }

    @Test
    void movingATypeWithUpcomingBookingsSaysTheyStayBehind() {
        Long credId = seedOwnerCalendars();
        Long typeId = seedType(credId, "default@example.com");
        seedUpcomingBooking(typeId, credId, "default@example.com");

        edit(typeId, credId + ":work@example.com")
                .statusCode(200)
                .body(containsString("stay on the calendar they were created on"));
    }

    private io.restassured.response.ValidatableResponse edit(Long typeId, String writeCalendar) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Write cal")
                .formParam("slug", "write-cal-" + typeId)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("writeCalendar", writeCalendar)
                .when()
                .post("/me/meeting-types/" + typeId + "/edit")
                .then();
    }

    /** Owner 1: one account, a default write target and a second selected calendar. Returns the credential id. */
    @Transactional
    Long seedOwnerCalendars() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-write-cal-" + UUID.randomUUID();
        c.persist();
        persistCalendar(1L, c.id, "default@example.com", true);
        persistCalendar(1L, c.id, "work@example.com", false);
        return c.id;
    }

    /** Another user's connected calendar — must never be selectable for owner 1. */
    @Transactional
    Long seedForeignCalendar() {
        AppUser other = AppUser.create("write-cal-other", "x", false);
        other.persist();
        GoogleCredential c = new GoogleCredential();
        c.ownerId = other.id;
        c.refreshToken = "rt";
        c.googleSub = "sub-write-cal-foreign-" + UUID.randomUUID();
        c.persist();
        persistCalendar(other.id, c.id, "foreign@example.com", true);
        return c.id;
    }

    @Transactional
    Long seedType(Long credId, String calendarId) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Write cal";
        t.slug = "write-cal-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.googleCredentialId = credId;
        t.googleCalendarId = calendarId;
        t.persist();
        return t.id;
    }

    /** One CONFIRMED booking a week out whose Google event lives on {@code calendarId}. */
    @Transactional
    void seedUpcomingBooking(Long typeId, Long credId, String calendarId) {
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = typeId;
        b.inviteeName = "Ada";
        b.inviteeEmail = "ada@example.com";
        b.startUtc = Instant.now().plus(7, ChronoUnit.DAYS);
        b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.googleEventId = "evt-staying";
        b.googleCredentialId = credId;
        b.googleCalendarId = calendarId;
        b.persist();
    }

    private static void persistCalendar(Long ownerId, Long credId, String calId, boolean writeTarget) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = ownerId;
        g.googleCredentialId = credId;
        g.googleCalendarId = calId;
        g.summary = calId;
        g.readForBusy = writeTarget;
        g.writeTarget = writeTarget;
        g.persist();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminWriteCalendarTest
```

Expected: FAIL — `savesTheChosenCalendar` finds `googleCalendarId` still null (the form field is ignored), and `aDanglingOverrideIsWarnedAboutOnTheForm` finds no `data-write-calendar-dangling` marker.

- [ ] **Step 3: Add the messages (English defaults + de + he)**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, after `adm_detail_label_location_detail()`:

```java
    @Message("Calendar for new events")
    String adm_detail_label_write_calendar();

    @Message("My write target")
    String adm_detail_write_calendar_default();

    @Message("Calendar no longer available — using my write target")
    String adm_detail_write_calendar_missing();

    @Message(
            "The calendar this meeting type writes on is no longer available, so new bookings use your write target. Pick a calendar to fix it.")
    String adm_detail_write_calendar_dangling();

    @Message(
            "{count} upcoming bookings stay on the calendar they were created on. Only new bookings use the new calendar.")
    String adm_detail_write_calendar_moved(long count);

    @Message("That calendar is not one of your selected Google calendars.")
    String adm_detail_error_write_calendar_unknown();
```

In `src/main/resources/messages/adm_de.properties`, after the `adm_detail_label_location_detail=` line (138):

```properties
adm_detail_label_write_calendar=Kalender für neue Termine
adm_detail_write_calendar_default=Mein Schreibkalender
adm_detail_write_calendar_missing=Kalender nicht mehr verfügbar — Ihr Schreibkalender wird verwendet
adm_detail_write_calendar_dangling=Der Kalender, in den dieser Termintyp schreibt, ist nicht mehr verfügbar; neue Buchungen landen in Ihrem Schreibkalender. Wählen Sie einen Kalender, um das zu beheben.
adm_detail_write_calendar_moved={count} bevorstehende Buchungen bleiben in dem Kalender, in dem sie erstellt wurden. Nur neue Buchungen nutzen den neuen Kalender.
adm_detail_error_write_calendar_unknown=Dieser Kalender gehört nicht zu Ihren ausgewählten Google-Kalendern.
```

In `src/main/resources/messages/adm_he.properties`, after the matching line (138):

```properties
adm_detail_label_write_calendar=יומן לפגישות חדשות
adm_detail_write_calendar_default=יומן הכתיבה שלי
adm_detail_write_calendar_missing=היומן אינו זמין עוד — נעשה שימוש ביומן הכתיבה שלך
adm_detail_write_calendar_dangling=היומן שסוג הפגישה הזה כותב אליו אינו זמין עוד, ולכן הזמנות חדשות ייווצרו ביומן הכתיבה שלך. בחר יומן כדי לתקן זאת.
adm_detail_write_calendar_moved={count} הזמנות עתידיות יישארו ביומן שבו נוצרו. רק הזמנות חדשות ישתמשו ביומן החדש.
adm_detail_error_write_calendar_unknown=היומן הזה אינו אחד מיומני Google שבחרת.
```

The `{count}` placeholder name must be identical in all three locales.

- [ ] **Step 4: Extend the template signature and render the picker**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, add three params to `Templates.meetingTypeDetail` (line 55), after `hosts`:

```java
        public static native TemplateInstance meetingTypeDetail(
                MeetingType type,
                List<BookingField> fields,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                List<HostRow> hosts,
                List<GoogleCalendar> writeCalendars,
                String writeCalendarValue,
                boolean writeCalendarDangling,
                LocationType[] locationTypes,
                FieldType[] fieldTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String error,
                String notice,
                String hostTypeaheadScript,
                String title);
```

Give `detailInstance` a third overload carrying the info notice, and compute the picker's state in the shared one:

```java
    /** Re-render the detail page for one meeting type (shared by every detail-scoped handler). */
    private TemplateInstance detailInstance(Long id) {
        return detailInstance(id, null, null);
    }

    /** Re-render the detail page with an error alert (co-host add + slug-collision guards). */
    private TemplateInstance detailInstance(Long id, String error) {
        return detailInstance(id, error, null);
    }

    /** Re-render the detail page with an error and/or an informational notice. */
    private TemplateInstance detailInstance(Long id, String error, String notice) {
        MeetingType t = requireType(id);
        // ... existing body unchanged up to the title ...
        var override = writeTargets.writeOverride(currentOwner.id(), t);
        var writeCalendars = GoogleCalendar.<GoogleCalendar>list("ownerId = ?1 order by summary", currentOwner.id());
        var writeCalendarDangling = override != null && !writeTargets.owns(currentOwner.id(), override);
        // "keep" round-trips a dangling override through an unrelated save instead of erasing it.
        var writeCalendarValue = override == null
                ? ""
                : (writeCalendarDangling ? "keep" : override.credentialId() + ":" + override.googleCalendarId());
        String title = m().adm_meetingTypeDetail_title_prefix().stripTrailing() + " " + t.name;
        return Templates.meetingTypeDetail(
                t,
                fields,
                rules,
                weekRows(rules.isEmpty() ? globalRules() : rules),
                overrides,
                hostRows(t),
                writeCalendars,
                writeCalendarValue,
                writeCalendarDangling,
                LocationType.values(),
                FieldType.values(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                error,
                notice,
                Layout.HOST_TYPEAHEAD_SCRIPT,
                title);
    }
```

In `src/main/resources/templates/AdminResource/meetingTypeDetail.html`, add the three declarations after the `hosts` line (line 6):

```html
{@java.util.List<site.asm0dey.calit.google.GoogleCalendar> writeCalendars}
{@java.lang.String writeCalendarValue}
{@java.lang.Boolean writeCalendarDangling}
{@java.lang.String notice}
```

the info alert next to the existing error alert (after line 21):

```html
  {#if notice}
  <div class="alert alert-info mb-4 max-w-2xl"><span>{notice}</span></div>
  {/if}
  {#if writeCalendarDangling}
  <div class="alert alert-warning mb-4 max-w-2xl" data-write-calendar-dangling><span>{adm:adm_detail_write_calendar_dangling}</span></div>
  {/if}
```

and the picker right after the location-detail input (line 53), inside the same basics form. The dangling entry carries the value `keep`, so saving anything else on this form leaves the stored override alone:

```html
          {#if writeCalendars.size > 0}
          <label class="label" for="mt-write-calendar">{adm:adm_detail_label_write_calendar}</label>
          <select id="mt-write-calendar" class="select w-full" name="writeCalendar">
            {#if writeCalendarDangling}
              <option value="keep" selected>{adm:adm_detail_write_calendar_missing}</option>
            {/if}
            <option value=""{#if writeCalendarValue == ''} selected{/if}>{adm:adm_detail_write_calendar_default}</option>
            {#for cal in writeCalendars}
              <option value="{cal.optionValue}"{#if writeCalendarValue == cal.optionValue} selected{/if}>{cal.summary}</option>
            {/for}
          </select>
          {/if}
```

- [ ] **Step 5: Save the picked calendar**

In `editMeetingType`, add the form param and apply it inside the transaction **before** `applyEditableFields` — the Meet gate (Task 5) must see the newly chosen calendar:

```java
    public TemplateInstance editMeetingType(
            @PathParam("id") Long id,
            @RestForm String name,
            @RestForm String slug,
            @RestForm int durationMinutes,
            @RestForm @DefaultValue("0") int bufferBeforeMinutes,
            @RestForm @DefaultValue("0") int bufferAfterMinutes,
            @RestForm String secret,
            @RestForm int minNoticeMinutes,
            @RestForm int horizonDays,
            @RestForm String locationType,
            @RestForm String locationDetail,
            @RestForm String slotIntervalMinutes,
            @RestForm String requiresApproval,
            @RestForm String writeCalendar) {
```

Inside the `QuarkusTransaction.requiringNew().run(...)` block, right after the slug guards and before `applyEditableFields` (the Meet gate from Task 5 must see the newly chosen calendar). `staying` is captured before the columns move, so the count describes the bookings left behind:

```java
                if (!KEEP_WRITE_CALENDAR.equals(writeCalendar)) {
                    var ref = requireOwnedCalendar(writeCalendar);
                    // Clearing the override does not mean "no calendar": the type falls back to the
                    // write target, so that is what the bookings left behind are compared against.
                    staying.set(bookingsStayingBehind(
                            t, ref != null ? ref : writeTargets.writeTargetRef(currentOwner.id())));
                    t.googleCredentialId = ref == null ? null : ref.credentialId();
                    t.googleCalendarId = ref == null ? null : ref.googleCalendarId();
                }
```

with `var staying = new java.util.concurrent.atomic.AtomicLong();` declared just before the transaction block (the lambda needs an effectively-final holder), and after it:

```java
        return staying.get() > 0
                ? detailInstance(id, null, m().adm_detail_write_calendar_moved(staying.get()))
                : detailInstance(id);
```

Add the two helpers next to `assertNoOwnerSlugCollision`:

```java
    /** Form value that means "leave the stored write override exactly as it is" (a dangling one). */
    private static final String KEEP_WRITE_CALENDAR = "keep";

    /**
     * Parse a submitted {@code "credentialId:googleCalendarId"} write-override choice, or null for
     * "use my write target". A pair that is not one of THIS owner's selected calendars is refused —
     * server-side, not UI-only — so a crafted POST can never point a type at someone else's calendar.
     */
    private CalendarRef requireOwnedCalendar(String raw) {
        CalendarRef ref = WriteTargetResolver.parseRef(raw);
        if (ref != null && !writeTargets.owns(currentOwner.id(), ref)) {
            throw new IllegalStateException(m().adm_detail_error_write_calendar_unknown());
        }
        return ref;
    }

    /**
     * How many upcoming bookings of {@code type} already have a Google event on a calendar other
     * than {@code newTarget} — the EFFECTIVE calendar after this save, so callers clearing an
     * override pass the write target, not null. Those events stay where they were created
     * (calit-rma2 addresses each booking by its stored ref) and the count drives the "they stay
     * behind" notice. A booking with no stored calendar id counts too: it predates V26.
     */
    static long bookingsStayingBehind(MeetingType type, CalendarRef newTarget) {
        String newCalendarId = newTarget == null ? null : newTarget.googleCalendarId();
        return Booking.count(
                "meetingTypeId = ?1 and status in ?2 and startUtc > ?3 and googleEventId is not null "
                        + "and (googleCalendarId is null or googleCalendarId <> ?4)",
                type.id,
                List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING),
                Instant.now(),
                newCalendarId == null ? "" : newCalendarId);
    }
```

with the imports `site.asm0dey.calit.google.CalendarRef` and `site.asm0dey.calit.google.WriteTargetResolver`. The existing `catch (IllegalStateException e) { return detailInstance(id, localizedMessage(e)); }` renders the rejection alert, and the rolled-back transaction leaves nothing persisted.

- [ ] **Step 6: Offer the picker on the create form too**

A brand-new type can name its calendar straight away instead of being created on the write target and edited afterwards. There is no stored value here, so no `"keep"` and no dangling state — just the blank default plus this Host's calendars.

Widen `Templates.meetingTypes` (line 40) with one param after `types`:

```java
        public static native TemplateInstance meetingTypes(
                List<MeetingType> types,
                List<GoogleCalendar> writeCalendars,
                LocationType[] locationTypes,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String username,
                String baseUrl,
                boolean hasShared,
                String error,
                String title);
```

and pass it in `renderMeetingTypes(String error)`:

```java
        return Templates.meetingTypes(
                singleHostTypes(),
                GoogleCalendar.<GoogleCalendar>list("ownerId = ?1 order by summary", currentOwner.id()),
                allowedLocationTypes(),
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                currentOwner.require().username,
                baseUrl,
                hasShared(),
                error,
                m().adm_meetingTypes_title()); // includes secret
```

In `src/main/resources/templates/AdminResource/meetingTypes.html`, declare the param after line 1 and add the select under the location-detail input (line 92), inside the same create form:

```html
{@java.util.List<site.asm0dey.calit.google.GoogleCalendar> writeCalendars}
```

```html
        {#if writeCalendars.size > 0}
        <label class="label" for="mt-new-write-calendar">{adm:adm_detail_label_write_calendar}</label>
        <select id="mt-new-write-calendar" class="select w-full" name="writeCalendar">
          <option value="" selected>{adm:adm_detail_write_calendar_default}</option>
          {#for cal in writeCalendars}
            <option value="{cal.optionValue}">{cal.summary}</option>
          {/for}
        </select>
        {/if}
```

In `createMeetingType`, add `@RestForm String writeCalendar` and apply it inside the transaction **before** `applyEditableFields`, so the Meet gate (Task 5) sees the chosen calendar rather than the write target:

```java
                var ref = requireOwnedCalendar(writeCalendar);
                t.googleCredentialId = ref == null ? null : ref.credentialId();
                t.googleCalendarId = ref == null ? null : ref.googleCalendarId();
```

No counted notice here — a type being created has no bookings to leave behind. The existing `catch (IllegalStateException e) { return renderMeetingTypes(localizedMessage(e)); }` already renders a refused calendar.

Add the two create-path cases to `AdminWriteCalendarTest`:

```java
    @Test
    void createUsesTheChosenCalendar() {
        Long credId = seedOwnerCalendars();
        String slug = "write-cal-created-" + UUID.randomUUID();

        create(slug, credId + ":work@example.com").statusCode(200);

        MeetingType t = MeetingType.find("slug", slug).firstResult();
        assertEquals(credId, t.googleCredentialId);
        assertEquals("work@example.com", t.googleCalendarId);
    }

    @Test
    void createRefusesAForeignCalendar() {
        seedOwnerCalendars();
        Long foreignCredId = seedForeignCalendar();
        String slug = "write-cal-refused-" + UUID.randomUUID();

        create(slug, foreignCredId + ":foreign@example.com").statusCode(200);

        assertNull(MeetingType.find("slug", slug).firstResult());
    }

    private io.restassured.response.ValidatableResponse create(String slug, String writeCalendar) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Write cal")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("writeCalendar", writeCalendar)
                .when()
                .post("/me/meeting-types")
                .then();
    }
```

- [ ] **Step 7: Run the test and the suite**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=AdminWriteCalendarTest
mvn test
```

Expected: new test PASS (9 tests); full suite green (`MeetingTypeDetail`- and `meetingTypes`-rendering tests still pass with the widened template signatures).

- [ ] **Step 8: Verify the i18n key parity**

Run:

```bash
for k in adm_detail_label_write_calendar adm_detail_write_calendar_default \
         adm_detail_write_calendar_missing adm_detail_write_calendar_dangling \
         adm_detail_write_calendar_moved adm_detail_error_write_calendar_unknown; do
  grep -c "^$k=" src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties
done
grep -c "{count}" src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties
```

Expected: `1` for every file/key pair (12 lines of `1`), and the `{count}` grep finds the placeholder in both locale files.

- [ ] **Step 9: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypeDetail.html \
        src/main/resources/templates/AdminResource/meetingTypes.html \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/test/java/site/asm0dey/calit/web/AdminWriteCalendarTest.java
git commit -m "feat(admin): pick a meeting type's write calendar"
```

---

### Task 7: Co-host's calendar picker on the shared-type page

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java` — `Templates.sharedAvailability` (line 58), `availabilityInstance` (line 204), `saveBuffers` (line 334)
- Modify: `src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html` (param header + the buffers form)
- Test: `src/test/java/site/asm0dey/calit/web/SharedWriteCalendarTest.java`

**Interfaces:**
- Consumes: `WriteTargetResolver.writeOverride/owns/parseRef` (Task 2), `AdminResource.bookingsStayingBehind(...)` (Task 6), and the six `adm_detail_*write_calendar*` message keys from Task 6 (no new keys).
- Produces: four new `sharedAvailability` template params (`List<GoogleCalendar> writeCalendars`, `String writeCalendarValue`, `boolean writeCalendarDangling`, `String notice`) and a `writeCalendar` field on `POST /me/shared/{typeId}/buffers`, with the same three values as Task 6 (`""` clear, `"keep"` leave alone, `"credentialId:googleCalendarId"` set), writing `MeetingTypeHost.googleCredentialId/googleCalendarId` for the current owner only.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/SharedWriteCalendarTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.user.AppUser;

/**
 * A co-host picks their OWN write calendar for a shared type: it lands on their meeting_type_host
 * row, and a calendar belonging to someone else is refused.
 */
@QuarkusTest
class SharedWriteCalendarTest {

    @AfterEach
    @Transactional
    void cleanup() {
        MeetingTypeHost.delete("meetingTypeId in (select t.id from MeetingType t where t.slug like ?1)", "shared-cal-%");
        MeetingType.delete("slug like ?1", "shared-cal-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
        AppUser.delete("username", "shared-cal-creator");
    }

    @Test
    void cohostSavesTheirOwnCalendar() {
        Long credId = seedOwnerCalendars();
        Long typeId = seedSharedType();

        saveBuffers(typeId, credId + ":work@example.com").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertEquals(credId, h.googleCredentialId);
        assertEquals("work@example.com", h.googleCalendarId);
    }

    @Test
    void aCalendarThatIsNotTheirsIsRejected() {
        seedOwnerCalendars();
        Long typeId = seedSharedType();
        Long foreignCredId = foreignCredentialId(typeId);

        saveBuffers(typeId, foreignCredId + ":creator@example.com").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertNull(h.googleCredentialId);
        assertNull(h.googleCalendarId);
    }

    @Test
    void savingBuffersKeepsADanglingOverride() {
        // A Co-host editing only their buffers must not lose a write override whose calendar they
        // happen to have unticked: the dangling option posts "keep".
        Long credId = seedOwnerCalendars();
        Long typeId = seedSharedType();
        setHostOverride(typeId, credId, "unticked@example.com");

        saveBuffers(typeId, "keep").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertEquals(credId, h.googleCredentialId);
        assertEquals("unticked@example.com", h.googleCalendarId);
    }

    @Transactional
    void setHostOverride(Long typeId, Long credId, String calendarId) {
        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        h.googleCredentialId = credId;
        h.googleCalendarId = calendarId;
        h.persist();
    }

    private io.restassured.response.ValidatableResponse saveBuffers(Long typeId, String writeCalendar) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("bufferBeforeMinutes", "")
                .formParam("bufferAfterMinutes", "")
                .formParam("writeCalendar", writeCalendar)
                .when()
                .post("/me/shared/" + typeId + "/buffers")
                .then();
    }

    /** Owner 1 (the logged-in co-host): one account with a default and a second calendar. */
    @Transactional
    Long seedOwnerCalendars() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-shared-cal-" + UUID.randomUUID();
        c.persist();
        persistCalendar(1L, c.id, "default@example.com", true);
        persistCalendar(1L, c.id, "work@example.com", false);
        return c.id;
    }

    /** A type owned by someone else, with owner 1 as an ACCEPTED co-host. */
    @Transactional
    Long seedSharedType() {
        AppUser creator = AppUser.create("shared-cal-creator", "x", false);
        creator.persist();
        MeetingType t = new MeetingType();
        t.ownerId = creator.id;
        t.name = "Shared cal";
        t.slug = "shared-cal-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.persist();
        MeetingTypeHost.of(t.id, creator.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
        return t.id;
    }

    /** A connected calendar owned by the type's creator, not by owner 1. */
    @Transactional
    Long foreignCredentialId(Long typeId) {
        MeetingType t = MeetingType.findById(typeId);
        GoogleCredential c = new GoogleCredential();
        c.ownerId = t.ownerId;
        c.refreshToken = "rt";
        c.googleSub = "sub-shared-cal-creator-" + UUID.randomUUID();
        c.persist();
        persistCalendar(t.ownerId, c.id, "creator@example.com", true);
        return c.id;
    }

    private static void persistCalendar(Long ownerId, Long credId, String calId, boolean writeTarget) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = ownerId;
        g.googleCredentialId = credId;
        g.googleCalendarId = calId;
        g.summary = calId;
        g.readForBusy = writeTarget;
        g.writeTarget = writeTarget;
        g.persist();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=SharedWriteCalendarTest
```

Expected: FAIL — `cohostSavesTheirOwnCalendar` sees `googleCalendarId` null; `saveBuffers` ignores the field.

- [ ] **Step 3: Render the picker on the shared page**

In `src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java`, widen the template signature (line 58), after `overrides`:

```java
        public static native TemplateInstance sharedAvailability(
                MeetingType type,
                MeetingTypeHost host,
                List<AvailabilityRule> rules,
                List<WeekRow> week,
                List<DateOverride> overrides,
                List<GoogleCalendar> writeCalendars,
                String writeCalendarValue,
                boolean writeCalendarDangling,
                DayOfWeek[] daysOfWeek,
                Long pendingCount,
                boolean isAdmin,
                String error,
                String notice,
                String title);
```

Inject the resolver into the existing constructor (fields at lines 76-86, constructor at line 89) and import `site.asm0dey.calit.google.CalendarRef`, `site.asm0dey.calit.google.GoogleCalendar`, `site.asm0dey.calit.google.WriteTargetResolver`:

```java
    final MeetingHosts meetingHosts;

    final WriteTargetResolver writeTargets;
```

```java
    @Inject
    public SharedMeetingsResource(
            CurrentOwner currentOwner,
            MeetingHosts meetingHosts,
            WriteTargetResolver writeTargets,
            BookingService bookingService,
            SecurityIdentity identity,
            AdminMessageResolver adminMsgs,
            ActiveLocale activeLocale) {
        this.currentOwner = currentOwner;
        this.meetingHosts = meetingHosts;
        this.writeTargets = writeTargets;
        this.bookingService = bookingService;
        this.identity = identity;
        this.adminMsgs = adminMsgs;
        this.activeLocale = activeLocale;
    }
```

Then give `availabilityInstance` the notice parameter and compute the picker's values (identical rules to Task 6 — `"keep"` for a dangling override so an unrelated buffers save round-trips it):

```java
    private TemplateInstance availabilityInstance(Long typeId, String error) {
        return availabilityInstance(typeId, error, null);
    }

    private TemplateInstance availabilityInstance(Long typeId, String error, String notice) {
        // ... existing body unchanged up to the return ...
        var override = writeTargets.writeOverride(currentOwner.id(), type);
        var writeCalendars = GoogleCalendar.<GoogleCalendar>list("ownerId = ?1 order by summary", currentOwner.id());
        var writeCalendarDangling = override != null && !writeTargets.owns(currentOwner.id(), override);
        var writeCalendarValue = override == null
                ? ""
                : (writeCalendarDangling ? "keep" : override.credentialId() + ":" + override.googleCalendarId());
        return Templates.sharedAvailability(
                type,
                h,
                rules,
                WeekRow.fromRules(week),
                overrides,
                writeCalendars,
                writeCalendarValue,
                writeCalendarDangling,
                DayOfWeek.values(),
                pendingCount(),
                isAdmin(),
                error,
                notice,
                m().adm_shared_availability_title(type.name));
    }
```

In `src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html`, add the declarations after the `overrides` line (line 5):

```html
{@java.util.List<site.asm0dey.calit.google.GoogleCalendar> writeCalendars}
{@java.lang.String writeCalendarValue}
{@java.lang.Boolean writeCalendarDangling}
{@java.lang.String notice}
```

the alerts next to the existing error alert (after line 16):

```html
  {#if notice}
  <div class="alert alert-info mb-4 max-w-2xl"><span>{notice}</span></div>
  {/if}
  {#if writeCalendarDangling}
  <div class="alert alert-warning mb-4 max-w-2xl" data-write-calendar-dangling><span>{adm:adm_detail_write_calendar_dangling}</span></div>
  {/if}
```

and the picker inside the buffers form, right before its submit button (line 30):

```html
          {#if writeCalendars.size > 0}
          <label class="label" for="sh-write-calendar">{adm:adm_detail_label_write_calendar}</label>
          <select id="sh-write-calendar" class="select w-full" name="writeCalendar">
            {#if writeCalendarDangling}
              <option value="keep" selected>{adm:adm_detail_write_calendar_missing}</option>
            {/if}
            <option value=""{#if writeCalendarValue == ''} selected{/if}>{adm:adm_detail_write_calendar_default}</option>
            {#for cal in writeCalendars}
              <option value="{cal.optionValue}"{#if writeCalendarValue == cal.optionValue} selected{/if}>{cal.summary}</option>
            {/for}
          </select>
          {/if}
```

- [ ] **Step 4: Save the co-host's choice**

Replace `saveBuffers` (line 334) with:

```java
    @POST
    @Path("/shared/{typeId}/buffers")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance saveBuffers(
            @PathParam("typeId") Long typeId,
            @RestForm String bufferBeforeMinutes,
            @RestForm String bufferAfterMinutes,
            @RestForm String writeCalendar) {
        // The host row is loaded + dirty-mutated INSIDE the tx so its changes flush on commit, which
        // happens before the render (#75). A write calendar that is not this owner's is refused and
        // nothing is saved; "keep" leaves a dangling override exactly as it is.
        var keep = "keep".equals(writeCalendar);
        CalendarRef ref = keep ? null : WriteTargetResolver.parseRef(writeCalendar);
        if (ref != null && !writeTargets.owns(currentOwner.id(), ref)) {
            return availabilityInstance(typeId, m().adm_detail_error_write_calendar_unknown());
        }
        var staying = new java.util.concurrent.atomic.AtomicLong();
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingTypeHost h = requireAcceptedHost(typeId);
            h.bufferBeforeMinutes = parseNonNegativeIntOrNull(bufferBeforeMinutes);
            h.bufferAfterMinutes = parseNonNegativeIntOrNull(bufferAfterMinutes);
            if (!keep) {
                // Clearing falls back to this Host's write target — compare against that, not null.
                staying.set(AdminResource.bookingsStayingBehind(
                        MeetingType.findById(typeId),
                        ref != null ? ref : writeTargets.writeTargetRef(currentOwner.id())));
                h.googleCredentialId = ref == null ? null : ref.credentialId();
                h.googleCalendarId = ref == null ? null : ref.googleCalendarId();
            }
        });
        return staying.get() > 0
                ? availabilityInstance(typeId, null, m().adm_detail_write_calendar_moved(staying.get()))
                : availabilityInstance(typeId, null);
    }
```

Note the count here is for the whole shared type, not just this Co-host's own rows — a group booking has one Google event, on the organizer's calendar, so "bookings that stay behind" is a property of the type, not of the Host reading the page.

- [ ] **Step 5: Run the test and the suite**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=SharedWriteCalendarTest
mvn test
```

Expected: new test PASS (3 tests); full suite green (`SharedMeetingsResourceTest` still passes with the widened template signature).

- [ ] **Step 6: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java \
        src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html \
        src/test/java/site/asm0dey/calit/web/SharedWriteCalendarTest.java
git commit -m "feat(shared): let a co-host pick their write calendar for a shared type"
```

---

### Task 8: Retire the superseded Meet helper, land the glossary and the ADR

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendar.java:57-65` (delete `writeTargetBlocksMeet`)
- Modify: `src/test/java/site/asm0dey/calit/google/CalendarSelectionServiceTest.java:73`
- Modify: `CONTEXT.md` (already written during the design session — verify, don't duplicate)
- Create: `docs/adr/0004-the-write-override-names-a-calendar-by-its-google-identity.md`

**Interfaces:**
- Produces: nothing at runtime. `GoogleCalendar.writeTarget(Long)` **keeps its name** — the glossary's *write target* already means "the calendar new events go to by default", so renaming it to `defaultWriteTarget` would both be redundant and collide with the phrasing `CONTEXT.md` reserves under *Fallback address*. `GoogleCalendar.writeTargetBlocksMeet(Long)` is deleted; `WriteTargetResolver.blocksMeet(ownerId, type)` from Task 5 is the only Meet gate.

- [ ] **Step 1: Delete the superseded helper**

In `src/main/java/site/asm0dey/calit/google/GoogleCalendar.java`, delete `writeTargetBlocksMeet(Long)` (lines 57-65) — Task 5 replaced its only production caller.

```bash
grep -rn "writeTargetBlocksMeet" src/main src/test
```

Expected: only `src/test/java/site/asm0dey/calit/google/CalendarSelectionServiceTest.java:73`. Replace that assertion with the resolver-free equivalent:

```java
        assertFalse(wt.supportsMeet, "a non-Meet write target is remembered as such");
```

(add `import static org.junit.jupiter.api.Assertions.assertFalse;` if missing).

- [ ] **Step 2: Verify the glossary entries are present**

`CONTEXT.md` gained **Write override** and **Dangling override** during the design session. Confirm both are there and unchanged, and that no code, template or docs copy introduced in Tasks 1-7 uses the avoided phrases:

```bash
grep -n "Write override" -A 6 CONTEXT.md
grep -rn "default write target" src/main src/test docs/superpowers/plans/2026-08-17-per-meeting-type-write-target.md
```

Expected: the two glossary entries exist; the second grep finds no occurrence in `src/`. (Occurrences inside this plan's rationale text are fine.)

- [ ] **Step 3: Verify the ADR**

`docs/adr/0004-the-write-override-names-a-calendar-by-its-google-identity.md` was written during the design session. Read it and confirm the shipped code matches its consequences — in particular that a disconnect leaves a dangling override rather than an unset one (Task 2), and that resolution degrades rather than failing (Tasks 2-4). Its content:

```markdown
# The write override names a calendar by its Google identity, not by a local row

A write override stores the pair `(google_credential_id, google_calendar_id)` — the connected
account plus Google's own calendar id — rather than a foreign key to the `google_calendar` row that
represents that calendar in calit.

## Considered options

**A foreign key to `google_calendar.id`** — the obvious relational modelling. Rejected: an Owner's
calendar selection is saved by deleting every one of their `google_calendar` rows and re-inserting
the submitted set, so those ids churn every time the Owner ticks any checkbox on the Google page. A
meeting type's override would be nulled or left dangling by an unrelated settings save, which is
exactly the failure the feature must not have. Google's calendar id survives an untick and a later
re-tick.

**Google's calendar id alone** — rejected on two counts. The write path needs a credential to mint
an access token, and the calendar id carries none. And a calendar shared into two connected accounts
appears as two rows with the same Google id and different credentials, so the id alone does not say
which account to write through.

## Consequences

- An override survives the Owner unticking and re-ticking the same calendar; it is the calendar's
  Google identity that is remembered, not calit's row for it.
- Disconnecting the account nulls the credential (`ON DELETE SET NULL`) and leaves the calendar id
  behind. That half-row is a **dangling override**, not the absence of one: writes fall back to the
  write target and the Host is told, rather than the choice vanishing silently.
- There is no referential integrity between an override and a calendar selection. "Does this
  override still point at something?" is answered by a lookup, and every read path must tolerate
  "no" — which is why resolution degrades instead of failing.
- Re-connecting a disconnected account mints a new credential id, so an override that dangled
  through a disconnect does not heal itself. The Host re-picks; the form tells them to.
```

- [ ] **Step 4: Run the suite**

Run:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn test
```

Expected: full suite green — one deleted helper, one assertion rewritten, no behaviour change.

- [ ] **Step 5: Commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/google/GoogleCalendar.java \
        src/test/java/site/asm0dey/calit/google/CalendarSelectionServiceTest.java \
        CONTEXT.md docs/adr/0004-the-write-override-names-a-calendar-by-its-google-identity.md
git commit -m "docs: the write override names a calendar by its Google identity"
```

---

### Task 9: Docs and changelog on the docs-site branch

**Files:**
- Modify (on branch `docs-site`): `docs-site/src/content/docs/releases/changelog.md`
- Modify (on branch `docs-site`): the Google setup page under `docs-site/src/content/docs/` (the page that documents connecting Google and choosing calendars — locate it with the grep in Step 1)

**Interfaces:**
- Consumes: the shipped behaviour from Tasks 1-8. Nothing in `main` depends on this task; it is part of "done", not follow-up.

- [ ] **Step 1: Switch to the docs branch and find the Google page**

```bash
cd /home/finkel/work_self/calit
git switch docs-site
grep -rln "write target\|Write events here" docs-site/src/content/docs/
```

Expected: the Google setup/configuration page path(s). Read the section that explains picking calendars.

- [ ] **Step 2: Document the per-type write calendar**

In that Google page, after the existing calendar-selection section, add:

```markdown
### Which calendar new events land on

The calendar you mark on the Google settings page is your **write target** — where your new events
are created by default. Every meeting type uses it unless that type says otherwise.

A meeting type can override it: open **Meeting types → (the type) → Basics** and pick a calendar
under *Calendar for new events*. "My write target" (the blank choice) keeps using the default, so
nothing changes for types you never touch. Only calendars you have selected on the Google settings
page appear in the list.

For a shared (multi-host) meeting type, each host picks their own: the creator on the meeting-type
page, a co-host under **Shared → (the type)**, next to their buffers. Whoever ends up organizing a
booking writes it on their own choice.

If you later unselect a calendar a meeting type points at, or disconnect the account it lives on,
bookings keep working: they fall back to your write target, and the meeting-type page warns you that
the choice is no longer in effect. Your pick is kept, not erased — saving other fields on that page
leaves it alone — so re-selecting the calendar puts it straight back to work. Reconnecting a
disconnected account is different: it comes back as a new connection, so you have to pick the
calendar again.

Events that already exist stay on the calendar they were created on. When you change a type's
calendar, calit tells you how many upcoming bookings that leaves behind; cancelling or rescheduling
one of them still reaches the calendar it lives on.
```

- [ ] **Step 3: Add the changelog entry**

In `docs-site/src/content/docs/releases/changelog.md`, under the `## Unreleased` heading (create it with its standing subtitle "Merged but not yet in a tagged release." if absent), add:

```markdown
- **Each meeting type can pick the Google calendar its events land on.** Before, every booking of
  every meeting type was created on the one calendar marked as your write target, so a personal
  "Coaching" type and a work "Client intro" type could not live on different calendars. Now a
  meeting type can name any of your selected calendars, your write target is what a type uses when
  it names none, and on a shared type each host picks their own — whoever organizes a booking writes
  it on their choice. If the chosen calendar is later unselected or its account is disconnected,
  bookings fall back to your write target instead of failing, the meeting-type page warns you, and
  your pick is kept rather than quietly erased. Changing a type's calendar tells you how many
  upcoming bookings stay on the calendar they were created on.
  ([#N](https://github.com/asm0dey/calit/pull/N))
```

Replace `#N` with the real PR number once it exists.

Upgrade note for the section (add it if the section has none, extend it otherwise):

```markdown
Nothing to do on upgrade — the new per-type setting is empty everywhere, which means "use my default
calendar", exactly the previous behaviour. Existing bookings are not moved: their events stay on the
calendar they were created on.
```

- [ ] **Step 4: Commit and return to main**

```bash
git add docs-site/src/content/docs
git commit -m "docs: per-meeting-type write calendar"
git switch main
```

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| Data model (4 nullable columns, `text`, no backfill) | 1 |
| Why (credential, calendar) rather than an FK | 1 (migration comment) |
| Resolution order + WARN on dangling | 2 |
| `chooseOrganizer` unchanged; resolution runs for the organizer | 4 |
| Write path: `createEvent` takes the resolved target | 3, 4 |
| UI: creator select on `meetingTypeDetail.html` | 6 |
| UI: co-host select on `/me/shared/{typeId}/availability` | 7 |
| UI: dangling override rendered as a disabled "no longer available" entry | 6, 7 |
| Server-side owner-scoped validation on save | 6, 7 |
| ~~`google.html` label becomes "default write target"~~ | **dropped** — `CONTEXT.md` reserves that phrasing; *write target* already means "by default" |
| ~~`GoogleCalendar.writeTarget` → `defaultWriteTarget`~~ | **dropped**, same reason (grilling, 2026-08-17) |
| Dangling override kept, warned about, never silently erased (`"keep"` sentinel) | 2, 6, 7 |
| Disconnect half-row (credential null, calendar id set) reads as dangling | 2, 6 |
| Counted "these bookings stay behind" notice on a move | 6, 7 |
| Glossary entries + ADR 0004 | 8 |
| Meet gate follows the creator's resolved calendar | 5 |
| Tests (no override / creator / co-host / dangling / foreign / Meet gate / degraded) | 1-7 |
| i18n de + he for every new or changed string | 6, 8 |
| Docs on `docs-site` | 9 |

**Naming consistency:** `GoogleCalendar.findOwned(ownerId, credentialId, googleCalendarId)` and `optionValue()` (Task 1) are used verbatim in Tasks 2, 3, 6 and 7. `WriteTargetResolver.resolve / resolveCalendar / writeOverride / owns / blocksMeet / parseRef` (Task 2) are used verbatim in Tasks 4-7. `createEvent(ownerId, target, summary, …)` (Task 3) matches the call sites in Task 4 and the stubs in every test. `AdminResource.bookingsStayingBehind(type, newTarget)` (Task 6) is called from Task 7. The `writeCalendar` form value has exactly three meanings (`""`, `"keep"`, `"credentialId:googleCalendarId"`) in both resources.

**Known limitations, accepted deliberately:** co-host overrides do not affect the Meet gate — the gate runs at edit time on the Creator's calendar, while the organizer is only known at booking time, so a Co-host organizer on a non-Meet calendar degrades through the port's existing `handleCreateFailure` (confirmed 2026-08-17: only the organizer's calendar matters, and that is not knowable at edit time). Nothing is backfilled. Busy-read calendars are unchanged. Changing a type's calendar leaves existing events where they are, now with a counted notice. Reconnecting a disconnected account mints a new credential id, so an override that dangled through a disconnect does not heal itself — the Host re-picks, prompted by the warning.
