# Multi-User Support — Phase 2: Owner Scoping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `owner_id` to every root tenant table, scope every persistence query and singleton lookup to the logged-in owner, rename the `/admin/*` management UI to `/me/*`, and delete the unused, unauthenticated `/api/*` CRUD resources — so that all data is owned and cross-owner access returns 404, even though there is still effectively one user.

**Architecture:** No Hibernate multi-tenancy, no row-level security — just owner-scoped Panache queries. A `@RequestScoped CurrentOwner` bean holds the request's `AppUser`; a JAX-RS `ContainerRequestFilter` (`MeOwnerFilter`) matched to `/me` + `/me/*` resolves it from the authenticated `SecurityIdentity` principal and calls `currentOwner.set(...)`. Singletons (`OwnerSettings`, `GoogleCredential`) become one-row-per-owner via `forOwner(Long)`. `Booking` denormalizes `ownerId` (copied from its meeting type at creation) so dashboard/pending queries — and the availability busy-set — filter by owner without a join: owners are fully isolated (each connects their own Google account), so one owner's HELD bookings never block another owner's availability. `availability_rule` and `date_override` also gain `owner_id` (denormalized, like `booking_field`) because they support **global rows** (`meeting_type_id IS NULL`, meaning "applies to all of this owner's meeting types") that have no parent to inherit ownership from. Children that always have a parent (`date_override_window`, `reminder`) stay scoped through their parent FK and gain no `owner_id`.

**Tech Stack:** Quarkus 3.36.1, Hibernate Panache, Flyway, Qute, Postgres, quarkus-security (core; security-jpa was dropped in Phase 1).

**Phase 1 is DONE (assume present, do not build):** `com.calit.user.AppUser` (PLAIN Panache entity — NOT a security-jpa `@UserDefinition`; fields: `id`, `username`, `passwordHash`, `roles`, `isAdmin`, `enabled`, `mustChangePassword`, `settingsComplete`, `createdAt`; statics `findByUsername`, `usernameTaken`, `create`), `com.calit.user.PasswordHasher`, `com.calit.user.Usernames`, `EnabledUserAugmentor`, two custom IdentityProviders (`AppUserIdentityProvider` + `AppUserTrustedIdentityProvider`) sharing `AppUserSecurityIdentities.of(user)` (**principal name == username** — `MeOwnerFilter` resolves the owner via `AppUser.findByUsername(identity.getPrincipal().getName())`), `SetupResource` + Vert.x `FirstRunRedirectFilter`, Flyway `V7__app_user.sql`, and DB-backed form login (test login user seeded by `FormAuth`, plus a `TestUserBootstrap` startup seeder). **Phases 3 and 4 are OUT OF SCOPE.**
>
> **⚠ Phase-1 carry-forward (address in this phase):** once `owner_id` FKs from tenant tables reference `app_user`, `SetupFlowTest.deleteAllUsers()` (`AppUser.deleteAll()`) will hit FK constraints or orphan tenant rows. As part of the V8/owner-scoping work, give `SetupFlowTest` (and `TestUserBootstrap`) FK-aware cleanup ordering or an isolated `@TestProfile`, and ensure the seeded baseline owner is consistent with any owner-scoped test fixtures.

> **Docker is required.** Tests use Quarkus Dev Services, which starts a throwaway Postgres container. Every `./mvnw test` invocation needs the Docker daemon running.

---

## File Structure

**Migration (new):**
- `src/main/resources/db/migration/V8__owner_scoping.sql` — add `owner_id BIGINT REFERENCES app_user(id)` to the 6 root tables, plus to `availability_rule` and `date_override` (which carry global `meeting_type_id IS NULL` rows that need their own owner attribution), + indexes; relax global-unique constraints to per-owner; drop the V1 global `booking_field` seed row.

**Domain entities (modified — add `Long ownerId` + scope statics):**
- `src/main/java/com/calit/domain/OwnerSettings.java` — drop `SINGLETON_ID`/`get()`, add `ownerId` + `forOwner(Long)`.
- `src/main/java/com/calit/domain/MeetingType.java` — add `ownerId`; `findBySlug(Long,String)`; `listPublic(Long)`; `listForOwner(Long)`.
- `src/main/java/com/calit/booking/Booking.java` — add `ownerId`; `heldOverlapping` gains an `ownerId` parameter so the availability busy-set is owner-scoped (owners are isolated — A's HELD booking is invisible to B). Pending/dashboard lists also get an owner filter in `AdminResource`.
- `src/main/java/com/calit/domain/AvailabilityRule.java` — add `ownerId`; `globalForOwner(Long)`; owner-scoped per-type lookups.
- `src/main/java/com/calit/domain/DateOverride.java` — add `ownerId`; owner-scoped `resolve` / per-type + global lookups.
- `src/main/java/com/calit/domain/BookingField.java` — add `ownerId`; `globalForOwner(Long)`; owner-aware `formFor(Long ownerId, Long meetingTypeId)`.
- `src/main/java/com/calit/google/GoogleCredential.java` — drop `SINGLETON_ID`/`get()`, add `ownerId` + `forOwner(Long)`.
- `src/main/java/com/calit/google/GoogleCalendar.java` — add `ownerId`; `readForBusy(Long)`, `writeTarget(Long)`, `findByGoogleId(Long,String)`, `deleteForOwner(Long)`.
- `src/main/java/com/calit/domain/Slugs.java` — `uniqueMeetingTypeSlug(Long ownerId, String base, Long excludeId)`.

**Owner-context plumbing (new):**
- `src/main/java/com/calit/user/CurrentOwner.java` — `@RequestScoped` holder.
- `src/main/java/com/calit/web/MeOwnerFilter.java` — `@Provider` `ContainerRequestFilter` for `/me` + `/me/*`.

**Service layer (modified — thread owner through):**
- `src/main/java/com/calit/booking/BookingService.java` — resolve owner from `meetingType.ownerId`; set `Booking.ownerId` on create.
- `src/main/java/com/calit/availability/SlotService.java` — `generateRawSlots` takes the owner's `OwnerSettings` from `type.ownerId`; global availability rules and date overrides are read via `globalForOwner(type.ownerId)` instead of an unscoped `meetingTypeId is null` query.
- `src/main/java/com/calit/availability/DefaultAvailabilitySeeder.java` — boot-time seeding of owner-less global rules is removed (a global rule now needs an `owner_id`, and at boot no user may exist). Per-user default-availability seeding moves to Phase 4's first-login wizard; a one-line note records this.
- `src/main/java/com/calit/email/EmailService.java` — `load(...)` resolves owner via `type.ownerId`.
- `src/main/java/com/calit/google/GoogleTokenService.java` — `exchangeCode`/`validAccessToken` take a `Long ownerId`.
- `src/main/java/com/calit/google/GoogleCalendarPort.java` — resolve owner via `CurrentOwner`; thread into token/calendar lookups + `eventTime`.

**Web layer (modified):**
- `src/main/java/com/calit/web/AdminResource.java` — `@Path("/me")`, `@RolesAllowed("user")`, inject `CurrentOwner`, scope every query.
- `src/main/java/com/calit/google/GoogleCalendarResource.java` — scope calendar list/save to `CurrentOwner`.
- `src/main/java/com/calit/google/GoogleOAuthResource.java` — pass `currentOwner.id()` into `exchangeCode`; redirect `/admin` → `/me`.
- `src/main/resources/templates/adminBase.html` — nav hrefs `/admin*` → `/me*`.
- `src/main/resources/application.properties` — permission path `/admin`,`/admin/*` → `/me`,`/me/*`; form `landing-page` `/admin` → `/me`.

**Deletions:**
- `src/main/java/com/calit/api/MeetingTypeResource.java`, `SettingsResource.java`, `BookingFieldResource.java`, `AvailabilityResource.java`
- `src/test/java/com/calit/api/MeetingTypeResourceTest.java`, `BookingFieldResourceTest.java`

**Tests (new + updated):**
- New: `src/test/java/com/calit/web/CrossOwnerIsolationTest.java`
- Updated (`/admin` → `/me`): `AdminAuthTest`, `AdminAvailabilityTest`, `AdminBookingFieldsTest`, `AdminDateOverridesTest`, `AdminGoogleTest`, `AdminMeetingTypeDetailTest`, `AdminMeetingTypeFormTest`, `AdminMeetingTypesTest`, `AdminPendingTest`, `AdminSettingsTest`, `LogoutTest`, `RememberMeTest`, and any other test that GETs/POSTs an `/admin*` path or calls `OwnerSettings.get()`/`GoogleCredential.get()`/`MeetingType.findBySlug(String)`.

---

> **Owner-resolution convention used throughout the plan.** Every test (existing and new) that needs to read or assert "the owner" must obtain it the same way. Phase 1's `FormAuth.login()` already seeds the test login user as an `app_user` row with username `admin`. Add this single shared helper and reuse it:
>
> ```java
> // src/test/java/com/calit/web/TestOwners.java
> package com.calit.web;
>
> import com.calit.user.AppUser;
> import io.quarkus.test.TestTransaction;
>
> /** Test helper: resolves seeded AppUser ids by username for owner-scoped seeding/asserts. */
> final class TestOwners {
>     private TestOwners() {}
>
>     /** The id of the AppUser FormAuth logs in as (username "admin"). */
>     @TestTransaction
>     static Long loginOwnerId() {
>         return AppUser.findByUsername("admin").id;
>     }
> }
> ```
>
> Create `TestOwners` as the very first step of Task 9 (it is only consumed by Tasks 9–10).

---

## Task 1: V8 migration — owner_id columns, per-owner constraints, drop global seed

**Files:**
- Create: `src/main/resources/db/migration/V8__owner_scoping.sql`
- Test: `src/test/java/com/calit/migration/V8MigrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V8 added owner_id to the 6 root tables plus availability_rule and date_override
 * (which carry global meeting_type_id IS NULL rows), and dropped the global booking_field seed.
 */
@QuarkusTest
class V8MigrationTest {

    @Inject
    EntityManager em;

    private boolean hasOwnerIdColumn(String table) {
        Long count = ((Number) em.createNativeQuery(
                "select count(*) from information_schema.columns " +
                "where table_name = :t and column_name = 'owner_id'")
                .setParameter("t", table)
                .getSingleResult()).longValue();
        return count == 1L;
    }

    @Test
    @Transactional
    void ownerIdAddedToAllSixRootTables() {
        for (String t : new String[]{"owner_settings", "meeting_type", "booking",
                "google_credential", "google_calendar", "booking_field"}) {
            assertTrue(hasOwnerIdColumn(t), "owner_id missing on " + t);
        }
    }

    @Test
    @Transactional
    void ownerIdAddedToAvailabilityRuleAndDateOverride() {
        // Global rows (meeting_type_id IS NULL) need their own owner attribution.
        assertTrue(hasOwnerIdColumn("availability_rule"), "owner_id missing on availability_rule");
        assertTrue(hasOwnerIdColumn("date_override"), "owner_id missing on date_override");
        // date_override_window stays parent-scoped — it must NOT gain an owner_id.
        assertTrue(!hasOwnerIdColumn("date_override_window"),
                "date_override_window must stay parent-scoped (no owner_id)");
    }

    @Test
    @Transactional
    void globalBookingFieldSeedRowDeleted() {
        Long count = ((Number) em.createNativeQuery(
                "select count(*) from booking_field where meeting_type_id is null and owner_id is null")
                .getSingleResult()).longValue();
        assertEquals(0L, count, "the V1 global description field must be gone");
    }

    @Test
    @Transactional
    void slugUniqueIsPerOwnerNotGlobal() {
        // Global single-column unique on slug must be gone; the composite (owner_id, slug) takes its place.
        Long globalUnique = ((Number) em.createNativeQuery(
                "select count(*) from information_schema.table_constraints tc " +
                "join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name " +
                "where tc.table_name = 'meeting_type' and tc.constraint_type = 'UNIQUE' " +
                "and kcu.column_name = 'slug' " +
                "and (select count(*) from information_schema.key_column_usage k2 " +
                "     where k2.constraint_name = tc.constraint_name) = 1")
                .getSingleResult()).longValue();
        assertEquals(0L, globalUnique, "single-column slug UNIQUE must be replaced by (owner_id, slug)");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=V8MigrationTest`
Expected: FAIL — `owner_id missing on owner_settings` (V8 does not exist yet).

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V8__owner_scoping.sql`. Copy the SQL style from V1/V3. Fresh start — no backfill (dev DB is reset). Comments explain each relaxation.

```sql
-- Phase 2 owner scoping: every root tenant table gains owner_id -> app_user(id).
-- Fresh start, no backfill (dev DB reset). Singleton/global-unique assumptions are dropped;
-- uniqueness becomes per-owner.

-- owner_settings: was a singleton (id = 1). Now one row per owner, owner_id UNIQUE.
ALTER TABLE owner_settings ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
ALTER TABLE owner_settings ADD CONSTRAINT uq_owner_settings_owner UNIQUE (owner_id);

-- meeting_type: slug moves from globally UNIQUE to UNIQUE (owner_id, slug).
ALTER TABLE meeting_type ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
ALTER TABLE meeting_type DROP CONSTRAINT meeting_type_slug_key;
ALTER TABLE meeting_type ADD CONSTRAINT uq_meeting_type_owner_slug UNIQUE (owner_id, slug);
CREATE INDEX idx_meeting_type_owner ON meeting_type (owner_id);

-- booking: owner_id denormalized from its meeting type so dashboard/pending filter without a join.
ALTER TABLE booking ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
CREATE INDEX idx_booking_owner_status ON booking (owner_id, status);

-- booking_field: drop the V1 global default seed (meeting_type_id IS NULL); global defaults are now
-- per-owner. owner_id + index for owner-scoped global-form lookups.
ALTER TABLE booking_field ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
DELETE FROM booking_field WHERE meeting_type_id IS NULL;
CREATE INDEX idx_booking_field_owner_scope ON booking_field (owner_id, meeting_type_id, position);

-- availability_rule: global rows (meeting_type_id IS NULL = "applies to all of this owner's types")
-- have no parent FK to inherit ownership from, so they carry their own denormalized owner_id (set on
-- EVERY rule at creation, not only globals, so queries filter uniformly). Index for the global lookup.
ALTER TABLE availability_rule ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
CREATE INDEX idx_availability_rule_owner_scope ON availability_rule (owner_id, meeting_type_id, day_of_week);

-- date_override: same as availability_rule — global (meeting_type_id IS NULL) overrides need their own
-- owner_id. date_override_window stays parent-scoped via date_override (NO owner_id column).
ALTER TABLE date_override ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
CREATE INDEX idx_date_override_owner_scope ON date_override (owner_id, meeting_type_id, override_date);

-- google_credential: was a singleton (id = 1). Now one row per owner, owner_id UNIQUE.
ALTER TABLE google_credential ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
ALTER TABLE google_credential ADD CONSTRAINT uq_google_credential_owner UNIQUE (owner_id);

-- google_calendar: google_calendar_id moves from globally UNIQUE to UNIQUE (owner_id, google_calendar_id)
-- (two owners may sync the same shared calendar). The single-write-target index becomes per-owner.
ALTER TABLE google_calendar ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
ALTER TABLE google_calendar DROP CONSTRAINT google_calendar_google_calendar_id_key;
ALTER TABLE google_calendar ADD CONSTRAINT uq_google_calendar_owner_cal
    UNIQUE (owner_id, google_calendar_id);
DROP INDEX idx_google_calendar_single_write_target;
CREATE UNIQUE INDEX idx_google_calendar_single_write_target
    ON google_calendar (owner_id)
    WHERE write_target = TRUE;
```

> **Constraint-name note:** Postgres auto-names a column-level `UNIQUE` as `<table>_<column>_key`. The V1 `meeting_type.slug UNIQUE` is `meeting_type_slug_key`; the V3 `google_calendar.google_calendar_id UNIQUE` is `google_calendar_google_calendar_id_key`. If a local DB differs, find the real name with `\d meeting_type` / `\d google_calendar` and substitute.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=V8MigrationTest`
Expected: PASS (all three tests green).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V8__owner_scoping.sql src/test/java/com/calit/migration/V8MigrationTest.java
git commit -m "feat: V8 owner-scoping migration (owner_id + per-owner uniqueness)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: CurrentOwner request-scoped bean

**Files:**
- Create: `src/main/java/com/calit/user/CurrentOwner.java`
- Test: `src/test/java/com/calit/user/CurrentOwnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.user;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CurrentOwnerTest {

    @Inject
    CurrentOwner currentOwner;

    @Test
    void unsetByDefaultAndRequireThrows401() {
        assertFalse(currentOwner.isSet());
        WebApplicationException ex = assertThrows(WebApplicationException.class, currentOwner::require);
        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void setStoresOwnerAndExposesId() {
        AppUser u = new AppUser();
        u.id = 42L;
        currentOwner.set(u);
        assertTrue(currentOwner.isSet());
        assertSame(u, currentOwner.get());
        assertSame(u, currentOwner.require());
        assertEquals(42L, currentOwner.id());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CurrentOwnerTest`
Expected: FAIL — `CurrentOwner` does not exist (compile error).

- [ ] **Step 3: Write the bean**

```java
package com.calit.user;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Request-scoped holder for the AppUser that owns the current request's data. Set by
 * {@code MeOwnerFilter} for /me and /me/* (Phase 3 will also set it for public /{user}/* routes).
 * Owner-scoped queries read {@link #id()} to filter their results.
 */
@RequestScoped
public class CurrentOwner {

    private AppUser owner;

    public void set(AppUser owner) {
        this.owner = owner;
    }

    public AppUser get() {
        return owner;
    }

    public boolean isSet() {
        return owner != null;
    }

    /** The owner's id, or null when unset. */
    public Long id() {
        return owner == null ? null : owner.id;
    }

    /** The owner, or a 401 WebApplicationException when no owner has been resolved. */
    public AppUser require() {
        if (owner == null) {
            throw new WebApplicationException("No owner in request scope", Response.Status.UNAUTHORIZED);
        }
        return owner;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CurrentOwnerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/user/CurrentOwner.java src/test/java/com/calit/user/CurrentOwnerTest.java
git commit -m "feat: add request-scoped CurrentOwner holder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Scope OwnerSettings to its owner (drop the singleton)

**Files:**
- Modify: `src/main/java/com/calit/domain/OwnerSettings.java`
- Test: `src/test/java/com/calit/domain/OwnerSettingsForOwnerTest.java`

> This task changes the entity API but leaves call sites compiling against the *old* `get()`/`SINGLETON_ID` until Tasks 6–8 thread the owner through. To keep the build green between tasks, **keep the `forOwner` addition in this task and migrate all call sites in the same task's Step 5 sweep** is NOT done here — instead, Tasks 6, 7, 8 update each caller. Because removing `get()` would break compilation mid-plan, this task ADDS `ownerId` + `forOwner(...)` and DEPRECATES (does not delete) `get()`/`SINGLETON_ID`. Task 8 deletes them once every caller is migrated.

- [ ] **Step 1: Write the failing test**

```java
package com.calit.domain;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class OwnerSettingsForOwnerTest {

    @Test
    @TestTransaction
    void forOwnerReturnsOnlyThatOwnersRow() {
        OwnerSettings a = new OwnerSettings();
        a.ownerId = 1001L; a.ownerName = "A"; a.ownerEmail = "a@x.com"; a.timezone = "UTC";
        a.persist();
        OwnerSettings b = new OwnerSettings();
        b.ownerId = 1002L; b.ownerName = "B"; b.ownerEmail = "b@x.com"; b.timezone = "Europe/Berlin";
        b.persist();

        assertEquals("A", OwnerSettings.forOwner(1001L).ownerName);
        assertEquals("Europe/Berlin", OwnerSettings.forOwner(1002L).timezone);
        assertNull(OwnerSettings.forOwner(9999L), "unknown owner -> null");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OwnerSettingsForOwnerTest`
Expected: FAIL — `ownerId`/`forOwner` do not exist (compile error).

- [ ] **Step 3: Edit the entity**

Replace the whole file with:

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "owner_settings")
public class OwnerSettings extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(name = "owner_name", nullable = false)
    public String ownerName;

    @Column(name = "owner_email", nullable = false)
    public String ownerEmail;

    @Column(nullable = false, length = 64)
    public String timezone;

    /** When false, the owner suppresses their own notification emails (Plan 4 gates on this). */
    @Column(name = "owner_notifications_enabled", nullable = false)
    public boolean ownerNotificationsEnabled = true;

    /** Returns this owner's settings row, or null if not yet configured. */
    public static OwnerSettings forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }
}
```

> **Schema note:** V1 declared `owner_settings.id` as a plain `BIGINT PRIMARY KEY` (no sequence) because it was a singleton (id = 1). Switching to `@GeneratedValue(IDENTITY)` requires the column be an identity/serial. Add this one line to the END of `V8__owner_scoping.sql` (after the `owner_settings` block) so Hibernate's IDENTITY strategy works:
>
> ```sql
> -- owner_settings.id was a fixed singleton (id = 1); make it an identity column now that rows are per-owner.
> ALTER TABLE owner_settings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
> ```
>
> Apply the same to `google_credential` in Task 7. (Both were `BIGINT PRIMARY KEY` singletons.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=OwnerSettingsForOwnerTest`
Expected: PASS.

> The wider build will NOT yet compile clean because callers still use `OwnerSettings.get()` — that is expected; this task's named test passes. Do **not** run the full suite until Task 8.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/OwnerSettings.java src/main/resources/db/migration/V8__owner_scoping.sql src/test/java/com/calit/domain/OwnerSettingsForOwnerTest.java
git commit -m "feat: scope OwnerSettings per owner via forOwner(ownerId)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: owner_id + per-owner statics on MeetingType, BookingField, AvailabilityRule, DateOverride, Slugs

**Files:**
- Modify: `src/main/java/com/calit/domain/MeetingType.java`
- Modify: `src/main/java/com/calit/domain/BookingField.java`
- Modify: `src/main/java/com/calit/domain/AvailabilityRule.java`
- Modify: `src/main/java/com/calit/domain/DateOverride.java`
- Modify: `src/main/java/com/calit/domain/Slugs.java`
- Test: `src/test/java/com/calit/domain/MeetingTypeOwnerScopeTest.java`
- Test: `src/test/java/com/calit/domain/AvailabilityRuleOwnerScopeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.domain;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MeetingTypeOwnerScopeTest {

    private MeetingType seed(Long owner, String slug, boolean active, boolean secret) {
        MeetingType t = new MeetingType();
        t.ownerId = owner; t.name = slug; t.slug = slug;
        t.durationMinutes = 30; t.active = active; t.secret = secret;
        t.persist();
        return t;
    }

    @Test
    @TestTransaction
    void findBySlugIsScopedToOwner() {
        seed(2001L, "intro-call", true, false);
        seed(2002L, "intro-call", true, false); // same slug, different owner is allowed

        assertEquals(2001L, MeetingType.findBySlug(2001L, "intro-call").ownerId);
        assertEquals(2002L, MeetingType.findBySlug(2002L, "intro-call").ownerId);
        assertNull(MeetingType.findBySlug(2003L, "intro-call"), "no such owner -> null");
    }

    @Test
    @TestTransaction
    void listPublicAndListForOwnerAreScoped() {
        seed(2001L, "a", true, false);
        seed(2001L, "b", true, true);   // secret -> not public
        seed(2001L, "c", false, false); // inactive -> not public
        seed(2002L, "d", true, false);  // other owner

        assertEquals(1, MeetingType.listPublic(2001L).size()); // only "a"
        assertEquals(3, MeetingType.listForOwner(2001L).size()); // a,b,c — includes secret+inactive
        assertTrue(MeetingType.listForOwner(2002L).stream().allMatch(t -> t.ownerId.equals(2002L)));
    }
}
```

And a second test asserting global availability rules + date overrides are owner-scoped (FIX 2):

```java
package com.calit.domain;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class AvailabilityRuleOwnerScopeTest {

    private AvailabilityRule globalRule(Long owner, DayOfWeek dow) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = owner; r.meetingTypeId = null; r.dayOfWeek = dow;
        r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(17, 0);
        r.persist();
        return r;
    }

    @Test
    @TestTransaction
    void globalForOwnerReturnsOnlyThatOwnersGlobalRules() {
        globalRule(4001L, DayOfWeek.MONDAY);
        globalRule(4002L, DayOfWeek.MONDAY); // other owner, same day
        // A per-type rule for owner A must NOT show up in the global list.
        AvailabilityRule typed = new AvailabilityRule();
        typed.ownerId = 4001L; typed.meetingTypeId = 7777L; typed.dayOfWeek = DayOfWeek.MONDAY;
        typed.startTime = LocalTime.of(8, 0); typed.endTime = LocalTime.of(9, 0);
        typed.persist();

        assertEquals(1, AvailabilityRule.globalForOwner(4001L, DayOfWeek.MONDAY).size());
        assertEquals(1, AvailabilityRule.globalForOwner(4002L, DayOfWeek.MONDAY).size());
        assertEquals(0, AvailabilityRule.globalForOwner(4001L, DayOfWeek.TUESDAY).size());
    }

    @Test
    @TestTransaction
    void dateOverrideGlobalResolveIsOwnerScoped() {
        LocalDate day = LocalDate.of(2026, 7, 1);
        DateOverride a = new DateOverride();
        a.ownerId = 4001L; a.meetingTypeId = null; a.overrideDate = day; a.persist();
        DateOverride b = new DateOverride();
        b.ownerId = 4002L; b.meetingTypeId = null; b.overrideDate = day; b.persist();

        // No per-type override -> falls back to the OWNER's global override, never the other owner's.
        assertEquals(a.id, DateOverride.resolve(4001L, 9999L, day).id);
        assertEquals(b.id, DateOverride.resolve(4002L, 9999L, day).id);
        assertNull(DateOverride.resolve(4003L, 9999L, day), "owner with no override -> null");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=MeetingTypeOwnerScopeTest`
then `./mvnw test -Dtest=AvailabilityRuleOwnerScopeTest`
Expected: FAIL — `ownerId` / new static signatures (`MeetingType`, `AvailabilityRule.globalForOwner`, `DateOverride.resolve(Long,Long,LocalDate)`) do not exist (compile error).

- [ ] **Step 3a: Edit MeetingType**

Add the field (after `id`) and replace the three statics. The new fields/methods:

```java
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
```

(insert directly below the `public Long id;` block; keep the `@Column(nullable = false)` on `slug` but REMOVE `unique = true` since uniqueness is now the composite DB constraint:)

```java
    @Column(nullable = false)
    public String slug;
```

Replace the existing `findBySlug` / `listPublic` with:

```java
    public static MeetingType findBySlug(Long ownerId, String slug) {
        return find("ownerId = ?1 and slug = ?2", ownerId, slug).firstResult();
    }

    /** Active, non-secret types for this owner — what their public landing page lists. */
    public static List<MeetingType> listPublic(Long ownerId) {
        return list("ownerId = ?1 and active = true and secret = false", ownerId);
    }

    /** Every type for this owner, including secret/inactive — the management listing. */
    public static List<MeetingType> listForOwner(Long ownerId) {
        return list("ownerId", ownerId);
    }
```

- [ ] **Step 3b: Edit BookingField**

Add the `ownerId` field and an owner-aware `formFor` + a `globalForOwner` helper. Replace the existing `formFor`:

```java
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
```

(insert below `public Long id;`)

```java
    /** This owner's global default form fields (meeting_type_id IS NULL), ordered by position. */
    public static List<BookingField> globalForOwner(Long ownerId) {
        return list("ownerId = ?1 and meetingTypeId is null order by position", ownerId);
    }

    /**
     * Per-type fields if the meeting type defines any (still scoped to this owner); otherwise the
     * owner's global default form. The owner scope is defence-in-depth: meeting-type ids are already
     * the owner's, but the global fallback MUST be the owner's globals, never another owner's.
     */
    public static List<BookingField> formFor(Long ownerId, Long meetingTypeId) {
        List<BookingField> typed = list(
                "ownerId = ?1 and meetingTypeId = ?2 order by position", ownerId, meetingTypeId);
        return typed.isEmpty() ? globalForOwner(ownerId) : typed;
    }
```

> Delete the old single-arg `formFor(Long meetingTypeId)`. Its callers — `BookingService.validateRequiredFields`, `EmailService.buildAnswerLines`, and the deleted `MeetingTypeResource.form` — are updated in Tasks 6 and 8.

- [ ] **Step 3c: Edit Slugs**

The slug uniqueness check must be owner-scoped. Replace `uniqueMeetingTypeSlug` + `slugTaken`:

```java
    /**
     * Returns {@code base} (or "meeting" if blank) made unique against this OWNER's existing
     * meeting_type slugs by appending -2, -3, ... A row with id {@code excludeId} is ignored, so
     * re-saving a type with its own current slug is allowed.
     */
    public static String uniqueMeetingTypeSlug(Long ownerId, String base, Long excludeId) {
        String root = (base == null || base.isBlank()) ? "meeting" : base;
        String candidate = root;
        int n = 1;
        while (slugTaken(ownerId, candidate, excludeId)) {
            n++;
            candidate = root + "-" + n;
        }
        return candidate;
    }

    private static boolean slugTaken(Long ownerId, String slug, Long excludeId) {
        MeetingType existing = MeetingType.findBySlug(ownerId, slug);
        return existing != null && !existing.id.equals(excludeId);
    }
```

- [ ] **Step 3d: Edit AvailabilityRule (FIX 2)**

Add the `ownerId` field (below `public Long id;`), update the field doc, and owner-scope both finders mirroring the `BookingField` pattern. Replace the field block + the two statics:

```java
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
```

(insert directly below `public Long id;`)

Update the `meetingTypeId` doc and replace `forMeetingType` / `globalFor`:

```java
    /**
     * Null = this owner's global default rule (applies to all of their meeting types).
     * Otherwise this rule overrides for that meeting type. Either way it carries {@link #ownerId}.
     */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    /** This owner's per-type rules for a weekday. (meetingTypeId is already the owner's; ownerId is
     *  defence-in-depth and keeps every query uniformly owner-filtered.) */
    public static List<AvailabilityRule> forMeetingType(Long ownerId, Long meetingTypeId, DayOfWeek dow) {
        return list("ownerId = ?1 and meetingTypeId = ?2 and dayOfWeek = ?3", ownerId, meetingTypeId, dow);
    }

    /** This owner's GLOBAL default rules (meetingTypeId IS NULL) for a weekday. */
    public static List<AvailabilityRule> globalForOwner(Long ownerId, DayOfWeek dow) {
        return list("ownerId = ?1 and meetingTypeId is null and dayOfWeek = ?2", ownerId, dow);
    }
```

- [ ] **Step 3e: Edit DateOverride (FIX 2)**

Add the `ownerId` field (below `public Long id;`), update the `meetingTypeId` doc, and owner-scope `resolve` so the global fallback is THIS owner's global override. Add the field:

```java
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
```

(insert directly below `public Long id;`)

Update the `meetingTypeId` doc and replace `resolve` (add a leading `ownerId` param + `ownerId = ?1` predicates):

```java
    /** Null = this owner's global override (all their types); otherwise scoped to this meeting type.
     *  Either way it carries {@link #ownerId}. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;
```

```java
    /**
     * This owner's per-type override for (meetingTypeId, date) if present; else this owner's global
     * (meeting_type_id IS NULL) override for the date; else null. Owner-scoped: another owner's
     * global override never leaks into this owner's resolution.
     */
    public static DateOverride resolve(Long ownerId, Long meetingTypeId, LocalDate date) {
        DateOverride typed = find(
                "ownerId = ?1 and meetingTypeId = ?2 and overrideDate = ?3",
                ownerId, meetingTypeId, date).firstResult();
        if (typed != null) {
            typed.windows = DateOverrideWindow
                    .list("dateOverrideId = ?1 order by startTime asc", typed.id);
            return typed;
        }
        DateOverride global = find(
                "ownerId = ?1 and meetingTypeId is null and overrideDate = ?2", ownerId, date).firstResult();
        if (global != null) {
            global.windows = DateOverrideWindow
                    .list("dateOverrideId = ?1 order by startTime asc", global.id);
        }
        return global;
    }
```

(Add the `import jakarta.persistence.Column;` — already present — and keep `getWindows()` unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=MeetingTypeOwnerScopeTest`
then `./mvnw test -Dtest=AvailabilityRuleOwnerScopeTest`
Expected: PASS.

> Full build still does not compile (AdminResource etc. call the old signatures) — fixed in Task 5/6/8.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/MeetingType.java src/main/java/com/calit/domain/BookingField.java src/main/java/com/calit/domain/AvailabilityRule.java src/main/java/com/calit/domain/DateOverride.java src/main/java/com/calit/domain/Slugs.java src/test/java/com/calit/domain/MeetingTypeOwnerScopeTest.java src/test/java/com/calit/domain/AvailabilityRuleOwnerScopeTest.java
git commit -m "feat: owner-scope MeetingType/BookingField/AvailabilityRule/DateOverride/Slugs statics

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: owner_id + per-owner statics on GoogleCredential & GoogleCalendar

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleCredential.java`
- Modify: `src/main/java/com/calit/google/GoogleCalendar.java`
- Modify: `src/main/resources/db/migration/V8__owner_scoping.sql` (add the `google_credential` identity line)
- Test: `src/test/java/com/calit/google/GoogleEntitiesOwnerScopeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class GoogleEntitiesOwnerScopeTest {

    @Test
    @TestTransaction
    void credentialForOwnerIsScoped() {
        GoogleCredential a = new GoogleCredential();
        a.ownerId = 3001L; a.refreshToken = "ra"; a.persist();
        GoogleCredential b = new GoogleCredential();
        b.ownerId = 3002L; b.refreshToken = "rb"; b.persist();

        assertEquals("ra", GoogleCredential.forOwner(3001L).refreshToken);
        assertEquals("rb", GoogleCredential.forOwner(3002L).refreshToken);
        assertNull(GoogleCredential.forOwner(9999L));
    }

    @Test
    @TestTransaction
    void calendarReadAndWriteTargetsAreScoped() {
        GoogleCalendar a = new GoogleCalendar();
        a.ownerId = 3001L; a.googleCalendarId = "cal-a"; a.summary = "A";
        a.readForBusy = true; a.writeTarget = true; a.persist();
        GoogleCalendar b = new GoogleCalendar();
        b.ownerId = 3002L; b.googleCalendarId = "cal-b"; b.summary = "B";
        b.readForBusy = true; b.writeTarget = true; b.persist();

        assertEquals(1, GoogleCalendar.readForBusy(3001L).size());
        assertEquals("cal-a", GoogleCalendar.writeTarget(3001L).googleCalendarId);
        assertEquals("cal-b", GoogleCalendar.writeTarget(3002L).googleCalendarId);
        assertEquals("cal-a", GoogleCalendar.findByGoogleId(3001L, "cal-a").googleCalendarId);
        assertNull(GoogleCalendar.findByGoogleId(3001L, "cal-b"), "other owner's calendar id -> null");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=GoogleEntitiesOwnerScopeTest`
Expected: FAIL — `ownerId` / scoped statics do not exist (compile error).

- [ ] **Step 3a: Edit GoogleCredential**

```java
package com.calit.google;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "google_credential")
public class GoogleCredential extends PanacheEntityBase {

    /** Refresh the access token this long before its real expiry to avoid edge-of-expiry failures. */
    public static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** Long-lived offline refresh token. Obtained once during the consent flow. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    public String refreshToken;

    /** Short-lived access token, refreshed on demand. Null until first refresh. */
    @Column(name = "access_token", columnDefinition = "text")
    public String accessToken;

    /** Instant the current access token stops being valid. Null when no access token is cached. */
    @Column(name = "access_token_expiry")
    public Instant accessTokenExpiry;

    /** This owner's credential row, or null if Google is not yet connected for them. */
    public static GoogleCredential forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
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

- [ ] **Step 3b: Add the google_credential identity line to V8**

Append to `src/main/resources/db/migration/V8__owner_scoping.sql`:

```sql
-- google_credential.id was a fixed singleton (id = 1); make it identity now that rows are per-owner.
ALTER TABLE google_credential ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
```

- [ ] **Step 3c: Edit GoogleCalendar**

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

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** The Google-side calendar id (often an email address or an opaque id). */
    @Column(name = "google_calendar_id", nullable = false)
    public String googleCalendarId;

    @Column(nullable = false)
    public String summary;

    /** Include this calendar's busy blocks when computing free/busy. */
    @Column(name = "read_for_busy", nullable = false)
    public boolean readForBusy = false;

    /** Create new booking events on this calendar. At most one row per owner may have this true. */
    @Column(name = "write_target", nullable = false)
    public boolean writeTarget = false;

    /** This owner's calendars whose busy time should be subtracted from availability. */
    public static List<GoogleCalendar> readForBusy(Long ownerId) {
        return list("ownerId = ?1 and readForBusy = true", ownerId);
    }

    /** This owner's single write-target calendar, or null if none selected yet. */
    public static GoogleCalendar writeTarget(Long ownerId) {
        return find("ownerId = ?1 and writeTarget = true", ownerId).firstResult();
    }

    /** This owner's calendar with the given Google id, or null. */
    public static GoogleCalendar findByGoogleId(Long ownerId, String googleCalendarId) {
        return find("ownerId = ?1 and googleCalendarId = ?2", ownerId, googleCalendarId).firstResult();
    }

    /** Remove all of this owner's calendar selections (used before re-saving). */
    public static long deleteForOwner(Long ownerId) {
        return delete("ownerId", ownerId);
    }
}
```

> The `unique = true` is removed from `googleCalendarId` (now the composite DB constraint).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=GoogleEntitiesOwnerScopeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/GoogleCredential.java src/main/java/com/calit/google/GoogleCalendar.java src/main/resources/db/migration/V8__owner_scoping.sql src/test/java/com/calit/google/GoogleEntitiesOwnerScopeTest.java
git commit -m "feat: owner-scope GoogleCredential/GoogleCalendar statics

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Thread the owner through the service layer

This task makes the service layer compile against the new owner-scoped statics. `Booking` gains `ownerId` (set at creation, denormalized from the meeting type). `SlotService`, `BookingService`, `EmailService` resolve the owner via `meetingType.ownerId`. `GoogleTokenService` takes a `Long ownerId`. `GoogleCalendarPort` resolves the owner via `CurrentOwner`.

**Files:**
- Modify: `src/main/java/com/calit/booking/Booking.java`
- Modify: `src/main/java/com/calit/availability/SlotService.java`
- Modify: `src/main/java/com/calit/booking/BookingService.java`
- Modify: `src/main/java/com/calit/email/EmailService.java`
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java`
- Modify: `src/main/java/com/calit/google/GoogleCalendarPort.java`
- Modify: `src/main/java/com/calit/availability/DefaultAvailabilitySeeder.java`
- Test: `src/test/java/com/calit/booking/BookingOwnerStampTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BookingOwnerStampTest {

    @Inject
    BookingService bookingService;

    /** Seeds an owner with settings, a meeting type, and a wide weekly availability window. */
    private MeetingType seedOwnerAndType(String username) {
        AppUser u = new AppUser();
        u.username = username; u.passwordHash = "x"; u.enabled = true; u.isAdmin = false;
        u.createdAt = java.time.Instant.now();
        u.persist();

        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id; s.ownerName = username; s.ownerEmail = username + "@x.com";
        s.timezone = "UTC";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = u.id; t.name = "Intro"; t.slug = "intro-" + username;
        t.durationMinutes = 30;
        t.persist();

        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = u.id; r.meetingTypeId = t.id; r.dayOfWeek = d;
            r.startTime = LocalTime.of(0, 0); r.endTime = LocalTime.of(23, 30);
            r.persist();
        }
        return t;
    }

    @Test
    @TestTransaction
    void bookingIsStampedWithItsMeetingTypesOwner() {
        MeetingType t = seedOwnerAndType("ownerstamp");
        // Pick a slot far enough out to clear min-notice (0) and inside the horizon.
        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("UTC"))
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);

        Booking b = bookingService.book(t.ownerId, t.slug, slot.toInstant(),
                "Invitee", "invitee@x.com", Map.of(), null, null);

        assertEquals(t.ownerId, b.ownerId, "booking.ownerId must equal the meeting type's owner");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BookingOwnerStampTest`
Expected: FAIL — `book(...)` does not take an `ownerId` arg and `Booking.ownerId` does not exist (compile error).

- [ ] **Step 3a: Edit Booking — add ownerId field and owner-scope heldOverlapping**

Add directly below `public Long id;`:

```java
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
```

`heldOverlapping` must be owner-scoped: owners are isolated (each connects their own Google account), so one owner's HELD booking must NOT block another owner's availability. Add a leading `ownerId` parameter and an `ownerId = ?1` predicate, shifting the existing positional params. Replace the method:

```java
    /**
     * This owner's HELD (PENDING or CONFIRMED) bookings whose [startUtc, endUtc) overlaps the
     * window [from, to). These are the bookings that block THIS OWNER's calendar (a pending
     * approval request holds its slot too — feature 14). CANCELLED/DECLINED are excluded.
     * Owner-scoped: owners are isolated, so another owner's bookings are never in this set.
     * Overlap predicate: startUtc < to AND from < endUtc.
     */
    public static List<Booking> heldOverlapping(Long ownerId, Instant from, Instant to) {
        return list("ownerId = ?1 and status in ?2 and startUtc < ?3 and ?4 < endUtc",
                ownerId,
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED), to, from);
    }
```

(`findByManageToken`, `countByEmailCreatedBetween` are UNCHANGED.)

- [ ] **Step 3b: Edit SlotService (FIX 2: owner-scope global rules/overrides)**

`generateRawSlots` must resolve settings by the type's owner AND thread `type.ownerId` into the
window/rule resolution so the GLOBAL fallback is read via `globalForOwner(ownerId)` /
the owner-scoped `DateOverride.resolve(ownerId, ...)` instead of an unscoped `meetingTypeId is null`
query. Replace the top of `generateRawSlots` and the inner-loop call:

```java
    public List<TimeSlot> generateRawSlots(MeetingType type, LocalDate from, LocalDate to) {
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            throw new IllegalStateException(
                    "Owner settings not configured for owner " + type.ownerId
                    + "; set them via /me/settings before generating slots.");
        }
        ZoneId zone = ZoneId.of(settings.timezone);
        List<TimeSlot> slots = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Window window : windowsFor(type.ownerId, type.id, date)) {
```

(Keep the slot-stepping body inside the loop exactly as-is.)

Now replace `windowsFor` and `rulesFor` to take and forward `ownerId`:

```java
    List<Window> windowsFor(Long ownerId, Long meetingTypeId, LocalDate date) {
        DateOverride override = DateOverride.resolve(ownerId, meetingTypeId, date);
        if (override != null) {
            return override.windows.stream()
                    .map(w -> new Window(w.startTime, w.endTime))
                    .toList();
        }
        return rulesFor(ownerId, meetingTypeId, date.getDayOfWeek()).stream()
                .map(r -> new Window(r.startTime, r.endTime))
                .toList();
    }

    /** Per-meeting-type rules win for a given day; otherwise fall back to THIS OWNER's global rules. */
    List<AvailabilityRule> rulesFor(Long ownerId, Long meetingTypeId, DayOfWeek dow) {
        List<AvailabilityRule> override = AvailabilityRule.forMeetingType(ownerId, meetingTypeId, dow);
        return override.isEmpty() ? AvailabilityRule.globalForOwner(ownerId, dow) : override;
    }
```

- [ ] **Step 3c: Edit BookingService**

Three changes: `availableSlots` resolves zone via `type.ownerId`; `book` takes a leading `Long ownerId` and stamps `booking.ownerId`; the private helpers that read `OwnerSettings.get()` now read `OwnerSettings.forOwner(type.ownerId)`; `validateRequiredFields` calls the owner-aware `formFor`.

In `availableSlots(MeetingType type, LocalDate from, LocalDate to, Long excludeBookingId)` replace the zone line:

```java
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
```

and pass the type's owner into the busy-set computation (replace the `busyIntervals(...)` call):

```java
        List<Interval> busy = busyIntervals(type.ownerId, fromInstant, toInstant, excludeBookingId);
```

`busyIntervals` must take and forward the `ownerId` to the now owner-scoped `heldOverlapping`. Replace the method:

```java
    List<Interval> busyIntervals(Long ownerId, Instant from, Instant to, Long excludeBookingId) {
        List<Interval> busy = new ArrayList<>();
        if (calendarPort.isConnected()) {
            for (BusyInterval bi : calendarPort.freeBusy(from, to)) {
                busy.add(new Interval(bi.start(), bi.end()));
            }
        }
        for (Booking b : Booking.<Booking>heldOverlapping(ownerId, from, to)) {
            if (excludeBookingId != null && excludeBookingId.equals(b.id)) {
                continue;
            }
            busy.add(new Interval(b.startUtc, b.endUtc));
        }
        return busy;
    }
```

In `createGoogleEvent`, replace `OwnerSettings.get()`:

```java
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
```

In `assertSlotAvailable`, replace:

```java
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
```

`enforcePerEmailDailyCap` currently reads `OwnerSettings.get().timezone` with no `type` in scope. Change its signature to take the type and use its owner. Replace the method and its call:

```java
    private void enforcePerEmailDailyCap(MeetingType type, String inviteeEmail) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(type.ownerId).timezone);
        LocalDate today = Instant.now().atZone(zone).toLocalDate();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        if (Booking.countByEmailCreatedBetween(inviteeEmail, dayStart, dayEnd) >= perEmailDailyCap) {
            throw new RateLimitException(
                    "Daily booking cap reached for " + inviteeEmail);
        }
    }
```

`validateRequiredFields` must use the owner-aware `formFor`:

```java
    private void validateRequiredFields(MeetingType type, Map<String, String> answers) {
        for (BookingField field : BookingField.formFor(type.ownerId, type.id)) {
            if (field.required) {
                String value = answers.get(field.fieldKey);
                if (value == null || value.isBlank()) {
                    throw new BookingValidationException(
                            "Required field '" + field.fieldKey + "' is missing or blank");
                }
            }
        }
    }
```

Now rewrite the `book` method signature + the owner-resolution + the cap call + the `ownerId` stamp. Replace from the `@Transactional public Booking book(...)` declaration down to (and including) the `booking.meetingTypeId = type.id;` line:

```java
    @Transactional
    public Booking book(Long ownerId, String meetingTypeSlug, Instant startUtc,
                        String inviteeName, String inviteeEmail,
                        Map<String, String> answers, String turnstileToken, String honeypot) {
        MeetingType type = MeetingType.findBySlug(ownerId, meetingTypeSlug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug
                    + " for owner " + ownerId);
        }

        // Feature 16: all three abuse guards run first, inside book(). The web layer
        // just forwards the cf-turnstile-response (turnstileToken) and website (honeypot) form values.
        turnstileVerifier.verify(turnstileToken);          // -> AbuseException (400) when enabled & invalid
        if (honeypot != null && !honeypot.isBlank()) {     // a bot filled the hidden field
            throw new AbuseException("Honeypot field was filled.");  // -> AbuseException (400)
        }
        enforcePerEmailDailyCap(type, inviteeEmail);       // -> RateLimitException (429) over cap

        Map<String, String> submitted = answers == null ? Map.of() : answers;

        // Feature 10: every required custom field must have a non-blank value.
        validateRequiredFields(type, submitted);

        Instant endUtc = startUtc.plusSeconds(60L * type.durationMinutes);

        // App-level availability re-check (buffer/min-notice/horizon).
        assertSlotAvailable(type, startUtc, null);

        Booking booking = new Booking();
        booking.ownerId = type.ownerId;
        booking.meetingTypeId = type.id;
```

(Leave the remainder of `book` — from `booking.inviteeName = inviteeName;` onward — unchanged.)

- [ ] **Step 3d: Edit EmailService**

`load(...)` resolves the owner via the meeting type and uses owner-aware `formFor`. Replace the `load` method's `OwnerSettings.get()` line:

```java
            OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
```

And in `buildAnswerLines`, the loop must use the owner-aware `formFor`. Change its signature to take the meeting type so the owner is available:

```java
    private static List<AnswerLine> buildAnswerLines(Booking booking, MeetingType type) {
        List<AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(type.ownerId, booking.meetingTypeId)) {
            String value = answers.get(field.fieldKey);
            if (value != null && !value.isBlank()) {
                lines.add(new AnswerLine(field.label, value));
            }
        }
        return lines;
    }
```

Update its caller inside `load`:

```java
            List<AnswerLine> answers = buildAnswerLines(booking, type);
```

- [ ] **Step 3e: Edit GoogleTokenService**

`exchangeCode` and `validAccessToken` take a `Long ownerId` and use `forOwner`. Replace `exchangeCode`:

```java
    @Transactional
    public void exchangeCode(Long ownerId, String code, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        GoogleCredential c = GoogleCredential.forOwner(ownerId);
        if (c == null) {
            c = new GoogleCredential();
            c.ownerId = ownerId;
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.persist();
    }
```

Replace `validAccessToken`:

```java
    @Transactional
    public String validAccessToken(Long ownerId, Instant now) {
        GoogleCredential c = GoogleCredential.forOwner(ownerId);
        if (c == null) {
            throw new IllegalStateException(
                    "Google is not connected for owner " + ownerId + ". Run /api/google/connect.");
        }
        if (!c.isAccessTokenExpired(now)) {
            return c.accessToken;
        }
        TokenResponse resp = requestToken("refresh_token", c.refreshToken, now);
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.persist();
        return c.accessToken;
    }
```

- [ ] **Step 3f: Edit GoogleCalendarPort**

Inject `CurrentOwner` and thread `currentOwner.id()` into every credential/calendar/token lookup. Replace the constructor + the four touch points.

Add the field + constructor injection (replace the existing constructor block):

```java
    private final GoogleTokenService tokens;
    private final GoogleCalendarClientFactory clientFactory;
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleCalendarPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory,
                              com.calit.user.CurrentOwner currentOwner) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
        this.currentOwner = currentOwner;
    }

    private Calendar client() {
        return clientFactory.build(tokens.validAccessToken(currentOwner.id(), Instant.now()));
    }
```

`isConnected`:

```java
    @Override
    @Transactional
    public boolean isConnected() {
        return GoogleCredential.forOwner(currentOwner.id()) != null;
    }
```

`freeBusy` readers:

```java
        List<GoogleCalendar> readers = GoogleCalendar.readForBusy(currentOwner.id());
```

`requireWriteTarget`:

```java
    private GoogleCalendar requireWriteTarget() {
        GoogleCalendar target = GoogleCalendar.writeTarget(currentOwner.id());
        if (target == null) {
            throw new IllegalStateException(
                    "No write-target Google calendar selected. POST /api/google/calendars.");
        }
        return target;
    }
```

`eventTime` is `static` and reads `OwnerSettings.get()`. Make it an instance method using the owner. Replace the method + update both call sites (`createEvent`'s `.setStart(eventTime(start))`/`.setEnd(eventTime(end))` and `updateEvent`'s — they call `eventTime(...)` which now resolves on the instance, no signature change needed at call sites):

```java
    private EventDateTime eventTime(Instant instant) {
        String ownerZoneId = OwnerSettings.forOwner(currentOwner.id()).timezone;
        return new EventDateTime()
                .setDateTime(new DateTime(instant.toEpochMilli()))
                .setTimeZone(ownerZoneId);
    }
```

(Remove the `static` keyword; the import of `OwnerSettings` stays.)

- [ ] **Step 3g: Neuter DefaultAvailabilitySeeder (FIX 2)**

`DefaultAvailabilitySeeder` seeds owner-less GLOBAL rules at boot (`meetingTypeId == null`, no
`ownerId`). Under owner scoping a global rule needs an `owner_id`, and at boot no `app_user` may
exist yet — so boot-time global seeding is no longer valid. Default-availability seeding becomes a
per-user concern triggered by Phase 4's first-login wizard (when the user's `owner_settings` row is
created); it is NOT built here. Remove the boot-time seeding so it cannot persist invalid rows.
Replace the body of `onStart` (or delete the `@Observes StartupEvent` method) with a no-op + note,
keeping `weekdayDefaults()` for Phase 4 to reuse:

```java
    /**
     * Phase 2: boot-time GLOBAL seeding is disabled — a rule now needs an owner_id and at boot no
     * app_user may exist. Phase 4's first-login wizard seeds each new owner's default availability
     * (it knows the owner id and stamps it), reusing {@link #weekdayDefaults()}.
     */
    void onStart(@Observes StartupEvent ev) {
        // intentionally no-op until Phase 4 wires per-owner seeding
    }
```

(Drop the `@Transactional` and the `AvailabilityRule` import if they become unused; keep
`weekdayDefaults()` as a static helper for Phase 4. The `@UnlessBuildProfile("test")` annotation may
stay or be removed — the method is now a no-op regardless. The existing `DefaultAvailabilitySeederTest`
only exercises `weekdayDefaults()`, so it stays green unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BookingOwnerStampTest`
Expected: PASS.

> The full suite still won't compile because `PublicResource`, `AdminResource`, `GoogleOAuthResource`, and several existing tests call old signatures. PublicResource is Phase 3's owner-resolution concern, but it must still COMPILE now. Apply the minimal bridge below so the module builds; Phase 3 replaces it.

- [ ] **Step 4b: Minimal PublicResource bridge (compile-only, Phase 3 will rework)**

`PublicResource` currently uses `MeetingType.findBySlug(slug)`, `OwnerSettings.get()`, `BookingField.formFor(type.id)`, and `bookingService.book(slug, ...)`. Until Phase 3 adds `/{user}/{slug}` routing, resolve the single existing owner from the meeting type itself. Replace each:

- `book(@PathParam ... slug)`: after `MeetingType type = MeetingType.find("slug", slug).firstResult();` (a temporary global lookup — there is still effectively one owner) replace the `OwnerSettings.get()` reads with `OwnerSettings.forOwner(type.ownerId)`, `BookingField.formFor(type.id)` with `BookingField.formFor(type.ownerId, type.id)`.
- `submitBooking`: same `MeetingType.find("slug", slug).firstResult()` lookup; call `bookingService.book(type.ownerId, slug, ...)`; `OwnerSettings.forOwner(type.ownerId)`; `BookingField.formFor(type.ownerId, type.id)`.
- `manage` / `confirmationPage` / `daySlots`: replace `OwnerSettings.get()` with `OwnerSettings.forOwner(type.ownerId)` (in `manage`, `type` is loaded from `booking.meetingTypeId`; `daySlots(type)` already has `type`).
- `landing()`: replace `MeetingType.listPublic()` with a global active+non-secret list for now: `MeetingType.list("active = true and secret = false")`.

> This is a deliberate temporary bridge so the build is green. Add a `// PHASE 3: resolve owner from /{user} path segment` comment at each site. Do NOT add tests for it here; Phase 3 owns public routing + its tests. The existing `BookPageTest`/`BookingPostTest` are updated in Task 10 to seed an owner-stamped meeting type so these still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/booking/Booking.java src/main/java/com/calit/availability/SlotService.java src/main/java/com/calit/booking/BookingService.java src/main/java/com/calit/email/EmailService.java src/main/java/com/calit/google/GoogleTokenService.java src/main/java/com/calit/google/GoogleCalendarPort.java src/main/java/com/calit/availability/DefaultAvailabilitySeeder.java src/main/java/com/calit/web/PublicResource.java src/test/java/com/calit/booking/BookingOwnerStampTest.java
git commit -m "feat: thread owner through booking/slot/email/google services

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: MeOwnerFilter + scope AdminResource to /me and CurrentOwner

This is the biggest task: rename `/admin` → `/me`, switch the role to `user`, install the `MeOwnerFilter`, and scope **every** query in `AdminResource` to `currentOwner.id()`. Update `adminBase.html` nav and `application.properties`.

**Files:**
- Create: `src/main/java/com/calit/web/MeOwnerFilter.java`
- Modify: `src/main/java/com/calit/web/AdminResource.java`
- Modify: `src/main/resources/templates/adminBase.html`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/calit/web/MeOwnerFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class MeOwnerFilterTest {

    @Test
    void meDashboardRequiresAuthAndResolvesOwner() {
        // Unauthenticated -> redirected to the form login page (302), never 200.
        given().redirects().follow(false)
            .when().get("/me")
            .then().statusCode(302);

        // Authenticated -> the filter resolves the owner and the dashboard renders.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me")
            .then().statusCode(200);
    }

    @Test
    void oldAdminPathIsGone() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin")
            .then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=MeOwnerFilterTest`
Expected: FAIL — `/me` does not exist yet (404 instead of 200/302), and `/admin` still serves 200.

- [ ] **Step 3a: Create MeOwnerFilter**

```java
package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.CurrentOwner;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * Resolves the request's owner for /me and /me/* from the authenticated SecurityIdentity principal
 * and stashes it in {@link CurrentOwner}. Security (the `user` role requirement) is enforced
 * separately by the HTTP permission policy + @RolesAllowed; by the time this filter runs the
 * identity is already authenticated. Phase 4 adds the first-login wizard redirect here.
 */
@Provider
public class MeOwnerFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    CurrentOwner currentOwner;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!matchesMe(ctx.getUriInfo())) {
            return;
        }
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return; // unauthenticated; the permission policy already rejects/redirects this request
        }
        AppUser user = AppUser.findByUsername(identity.getPrincipal().getName());
        if (user != null) {
            currentOwner.set(user);
        }
    }

    /** True for the exact path "me" and anything under "me/". */
    private static boolean matchesMe(UriInfo uriInfo) {
        String path = uriInfo.getPath(); // no leading slash, e.g. "me" or "me/settings"
        return path.equals("me") || path.startsWith("me/");
    }
}
```

- [ ] **Step 3b: Rewrite AdminResource — path, role, owner scoping**

Apply the following edits to `src/main/java/com/calit/web/AdminResource.java`. Inject `CurrentOwner` and scope every query.

Class annotations + new injection:

```java
@Path("/me")
@RolesAllowed("user")
public class AdminResource {
```

Add the injected owner (below the existing `@Inject BookingService bookingService;`):

```java
    @Inject
    com.calit.user.CurrentOwner currentOwner;
```

`pendingCount()` — scope to owner:

```java
    private long pendingCount() {
        return Booking.count("ownerId = ?1 and status = ?2",
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
    }
```

`dashboard()` — scope the upcoming + pending count:

```java
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        List<Booking> upcoming = Booking.list(
                "ownerId = ?1 and status = ?2 and startUtc >= ?3 order by startUtc",
                currentOwner.id(), com.calit.booking.BookingStatus.CONFIRMED, java.time.Instant.now());
        long pendingCount = pendingCount();
        return Templates.dashboard(upcoming, pendingCount, Layout.TZ_SCRIPT);
    }
```

`meetingTypes()` listing (GET) and all four re-renders that call `MeetingType.listAll()` — replace every `MeetingType.listAll()` in this file with `MeetingType.listForOwner(currentOwner.id())`. (Occurs in `meetingTypes`, `createMeetingType`, `toggleActive`, `deleteMeetingType`, `availability` GET, `createRule`, `deleteRule`, `dateOverrides` GET, `createOverride`, `deleteOverride`.)

`createMeetingType` — stamp owner + owner-scoped slug. Replace the `MeetingType t = new MeetingType();` block head:

```java
        MeetingType t = new MeetingType();
        t.ownerId = currentOwner.id();
        t.name = name;
        String slugBase = (slug == null || slug.isBlank()) ? Slugs.slugify(name) : Slugs.slugify(slug);
        t.slug = Slugs.uniqueMeetingTypeSlug(currentOwner.id(), slugBase, null);
```

`editMeetingType` — owner-scoped slug:

```java
        t.slug = Slugs.uniqueMeetingTypeSlug(currentOwner.id(), slugBase, id);
```

`requireType(Long id)` — must 404 cross-owner rows:

```java
    private MeetingType requireType(Long id) {
        MeetingType t = MeetingType.findById(id);
        if (t == null || !t.ownerId.equals(currentOwner.id())) {
            throw new jakarta.ws.rs.NotFoundException("No meeting type " + id);
        }
        return t;
    }
```

`meetingTypeDetail` + `detailInstance` — `detailInstance(Long id)` re-loads `MeetingType.findById(id)`; replace it to reuse `requireType` so child rows are always for an owned type:

```java
    private TemplateInstance detailInstance(Long id) {
        MeetingType t = requireType(id);
        List<BookingField> fields = BookingField.list("meetingTypeId = ?1 order by position", id);
        List<AvailabilityRule> rules = AvailabilityRule.list("meetingTypeId = ?1 order by dayOfWeek", id);
        List<DateOverride> overrides = overridesForType(id);
        return Templates.meetingTypeDetail(t, fields, rules, overrides,
                LocationType.values(), BookingField.FieldType.values(),
                DayOfWeek.values(), pendingCount());
    }
```

(Child queries `meetingTypeId = ?1` stay correct because `requireType` already proved the type is the owner's — children inherit the scope through the parent FK. Every detail-scoped POST handler — `addTypeField`, `deleteTypeField`, `addTypeRule`, `deleteTypeRule`, `addTypeOverride`, `deleteTypeOverride`, `editMeetingType`, `meetingTypeDetail`, `toggleActive`, `deleteMeetingType` — must call `requireType(id)` first. `toggleActive`/`deleteMeetingType` currently call `MeetingType.findById(id)` / `deleteById(id)` directly — change them to use `requireType(id)`:)

```java
    @Transactional
    public TemplateInstance toggleActive(@PathParam("id") Long id) {
        MeetingType t = requireType(id);
        t.active = !t.active;
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), LocationType.values(),
                DayOfWeek.values(), pendingCount());
    }
```

```java
    @Transactional
    public TemplateInstance deleteMeetingType(@PathParam("id") Long id) {
        requireType(id);
        MeetingType.deleteById(id);
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), LocationType.values(),
                DayOfWeek.values(), pendingCount());
    }
```

`availability()` GET and `createRule`/`deleteRule` — the standalone availability page lists THIS OWNER's rules: their global defaults (`ownerId = ? and meetingTypeId is null`) plus their per-type rules. **FIX 2 reverses the earlier "drop globals / require a meeting type" decision:** `availability_rule` now carries its own `owner_id` (Task 1 + Task 4), so global rows ARE owner-attributed and the page keeps full global-rule support. Every rule (global or per-type) is created with `ownerId = currentOwner.id()`. Add a helper that loads this owner's rules and replace `availability()`:

```java
    /** This owner's availability rules — global defaults + per-type — ordered for display. */
    private List<AvailabilityRule> ownerRules() {
        return AvailabilityRule.list("ownerId = ?1 order by meetingTypeId nulls first, dayOfWeek",
                currentOwner.id());
    }

    @GET
    @Path("/availability")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance availability() {
        return Templates.availability(ownerRules(),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount());
    }
```

`createRule` — a blank `meetingTypeId` means a GLOBAL rule for this owner (preserved); a non-blank one must be one of the owner's types. Stamp `ownerId` either way. Replace:

```java
    @POST
    @Path("/availability")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createRule(@RestForm String dayOfWeek,
                                       @RestForm String startTime,
                                       @RestForm String endTime,
                                       @RestForm String meetingTypeId) {
        // Blank meetingTypeId = this owner's GLOBAL default rule. A non-blank id must be owned.
        Long typeId = (meetingTypeId == null || meetingTypeId.isBlank()) ? null : Long.valueOf(meetingTypeId);
        if (typeId != null) {
            requireType(typeId); // 404 a cross-owner type
        }
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = currentOwner.id();
        r.meetingTypeId = typeId; // null = global default
        r.dayOfWeek = DayOfWeek.valueOf(dayOfWeek);
        r.startTime = LocalTime.parse(startTime);
        r.endTime = LocalTime.parse(endTime);
        r.persist();
        return Templates.availability(ownerRules(),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount());
    }
```

`deleteRule` — only delete a rule owned by the current owner (covers both global and per-type rows):

```java
    @POST
    @Path("/availability/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteRule(@PathParam("id") Long id) {
        AvailabilityRule r = AvailabilityRule.findById(id);
        if (r != null && currentOwner.id().equals(r.ownerId)) {
            AvailabilityRule.deleteById(id);
        }
        return Templates.availability(ownerRules(),
                MeetingType.listForOwner(currentOwner.id()), DayOfWeek.values(), pendingCount());
    }
```

`settings()` GET + `updateSettings` — owner-scoped `forOwner` + stamp `ownerId`:

```java
    @GET
    @Path("/settings")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        return Templates.settings(OwnerSettings.forOwner(currentOwner.id()),
                reminderLeadMinutes, pendingCount(), zoneIds());
    }

    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance updateSettings(@RestForm String ownerName,
                                           @RestForm String ownerEmail,
                                           @RestForm String timezone,
                                           @RestForm String ownerNotificationsEnabled) {
        OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
        if (s == null) { s = new OwnerSettings(); s.ownerId = currentOwner.id(); }
        s.ownerName = ownerName;
        s.ownerEmail = ownerEmail;
        s.timezone = timezone;
        s.ownerNotificationsEnabled = "on".equals(ownerNotificationsEnabled);
        s.persist();
        return Templates.settings(s, reminderLeadMinutes, pendingCount(), zoneIds());
    }
```

`google()` GET — pending count via the scoped helper:

```java
    @GET
    @Path("/google")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance google() {
        return Templates.google(pendingCount());
    }
```

`bookingFields()` GET + `createBookingField` + `deleteBookingField` — owner-scoped global form:

```java
    @GET
    @Path("/booking-fields")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance bookingFields() {
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()), FieldType.values(), pendingCount());
    }

    @POST
    @Path("/booking-fields")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createBookingField(@RestForm String label,
                                               @RestForm String fieldKey,
                                               @RestForm String type,
                                               @RestForm String required,
                                               @RestForm int position) {
        BookingField f = new BookingField();
        f.ownerId = currentOwner.id();
        f.label = label;
        f.fieldKey = fieldKey;
        f.type = FieldType.valueOf(type);
        f.required = "on".equals(required);
        f.position = position;
        f.meetingTypeId = null; // standalone page manages this owner's global defaults
        f.persist();
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()), FieldType.values(), pendingCount());
    }

    @POST
    @Path("/booking-fields/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteBookingField(@PathParam("id") Long id) {
        BookingField f = BookingField.findById(id);
        if (f != null && currentOwner.id().equals(f.ownerId)) {
            BookingField.deleteById(id);
        }
        return Templates.bookingFields(
                BookingField.globalForOwner(currentOwner.id()), FieldType.values(), pendingCount());
    }
```

For the per-type `addTypeField`/`addTypeRule`/`addTypeOverride` — stamp the owner too (they live under `requireType`-guarded paths). In `addTypeField`, after `requireType(id);`:

```java
        BookingField f = new BookingField();
        f.ownerId = currentOwner.id();
        f.meetingTypeId = id;
```

In `addTypeRule`, after `requireType(id);`, stamp `r.ownerId`:

```java
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = currentOwner.id();
        r.meetingTypeId = id;
```

In `addTypeOverride`, after `requireType(id);`, stamp `o.ownerId`:

```java
        DateOverride o = new DateOverride();
        o.ownerId = currentOwner.id();
        o.meetingTypeId = id;
```

(`createMeetingType`'s `createInitialWorkingHours`/`createInitialDateOverride` helpers also create
`AvailabilityRule`/`DateOverride` rows — stamp `r.ownerId = t.ownerId` / `o.ownerId = t.ownerId` in
each, since `t.ownerId` is already set to `currentOwner.id()`.)

`deleteTypeField` — guard the parent type then delete (a field id belongs to an owned type):

```java
    @Transactional
    public TemplateInstance deleteTypeField(@PathParam("id") Long id, @PathParam("fid") Long fid) {
        requireType(id);
        BookingField f = BookingField.findById(fid);
        if (f != null && id.equals(f.meetingTypeId)) {
            BookingField.deleteById(fid);
        }
        return detailInstance(id);
    }
```

`overridesWithWindows()` + `dateOverrides()` GET + `createOverride` + `deleteOverride` — mirror availability: list THIS OWNER's overrides (global defaults + per-type), preserving globals. **FIX 2 reverses the earlier "drop globals / require a meeting type" decision** — `date_override` now carries `owner_id`, so global overrides are owner-attributed. Replace `overridesWithWindows`:

```java
    private List<DateOverride> overridesWithWindows() {
        List<DateOverride> all = DateOverride.list(
                "ownerId = ?1 order by meetingTypeId nulls first, overrideDate", currentOwner.id());
        for (DateOverride o : all) {
            o.windows = DateOverrideWindow.list("dateOverrideId = ?1 order by startTime asc", o.id);
        }
        return all;
    }
```

`createOverride` — a blank `meetingTypeId` means a GLOBAL override for this owner (preserved); a non-blank one must be owned. Stamp `ownerId` either way:

```java
    @POST
    @Path("/date-overrides")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance createOverride(@RestForm String date,
                                           @RestForm String meetingTypeId,
                                           MultivaluedMap<String, String> form) {
        // Blank meetingTypeId = this owner's GLOBAL override. A non-blank id must be owned.
        Long typeId = (meetingTypeId == null || meetingTypeId.isBlank()) ? null : Long.valueOf(meetingTypeId);
        if (typeId != null) {
            requireType(typeId); // 404 a cross-owner type
        }
        DateOverride o = new DateOverride();
        o.ownerId = currentOwner.id();
        o.overrideDate = LocalDate.parse(date);
        o.meetingTypeId = typeId; // null = global override
        o.persist();
        List<String> starts = form.getOrDefault("windowStart", List.of());
        List<String> ends = form.getOrDefault("windowEnd", List.of());
        for (int i = 0; i < starts.size() && i < ends.size(); i++) {
            if (starts.get(i).isBlank() || ends.get(i).isBlank()) { continue; }
            DateOverrideWindow w = new DateOverrideWindow();
            w.dateOverrideId = o.id;
            w.startTime = LocalTime.parse(starts.get(i));
            w.endTime = LocalTime.parse(ends.get(i));
            w.persist();
        }
        return Templates.dateOverrides(
                overridesWithWindows(), MeetingType.listForOwner(currentOwner.id()), pendingCount());
    }
```

`deleteOverride` — only delete an override owned by the current owner (covers global + per-type):

```java
    @POST
    @Path("/date-overrides/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteOverride(@PathParam("id") Long id) {
        DateOverride o = DateOverride.findById(id);
        if (o != null && currentOwner.id().equals(o.ownerId)) {
            DateOverrideWindow.delete("dateOverrideId = ?1", id);
            DateOverride.deleteById(id);
        }
        return Templates.dateOverrides(
                overridesWithWindows(), MeetingType.listForOwner(currentOwner.id()), pendingCount());
    }
```

`dateOverrides()` GET re-render — replace `MeetingType.listAll()` with `MeetingType.listForOwner(currentOwner.id())`.

`pending()` GET + `approveBooking` + `declineBooking` — scope the pending list to the owner and 404 cross-owner approve/decline:

```java
    @GET
    @Path("/pending")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pending() {
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT);
    }

    /** Load a PENDING-eligible booking owned by the current owner, or 404. */
    private Booking requireOwnedBooking(Long id) {
        Booking b = Booking.findById(id);
        if (b == null || !currentOwner.id().equals(b.ownerId)) {
            throw new jakarta.ws.rs.NotFoundException("No booking " + id);
        }
        return b;
    }

    @POST
    @Path("/bookings/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance approveBooking(@PathParam("id") Long id) {
        requireOwnedBooking(id);
        bookingService.approve(id);
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT);
    }

    @POST
    @Path("/bookings/{id}/decline")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance declineBooking(@PathParam("id") Long id) {
        requireOwnedBooking(id);
        bookingService.decline(id);
        List<Booking> pending = Booking.list(
                "ownerId = ?1 and status = ?2 order by startUtc",
                currentOwner.id(), com.calit.booking.BookingStatus.PENDING);
        return Templates.pending(pending, Layout.TZ_SCRIPT);
    }
```

> Add the `jakarta.ws.rs.BadRequestException` import (or fully-qualify as shown). `meetingTypes()` GET re-render: `MeetingType.listForOwner(currentOwner.id())`. Double-check every remaining `MeetingType.listAll()`, `OwnerSettings.get()` is gone from this file.

- [ ] **Step 3c: Update adminBase.html nav**

Replace every `href="/admin..."` with `/me...`. The eight occurrences:
- `href="/admin/meeting-types"` (Create button) → `/me/meeting-types`
- `href="/admin"` (Dashboard) → `/me`
- `href="/admin/pending"` → `/me/pending`
- `href="/admin/meeting-types"` (nav) → `/me/meeting-types`
- `href="/admin/availability"` → `/me/availability`
- `href="/admin/date-overrides"` → `/me/date-overrides`
- `href="/admin/booking-fields"` → `/me/booking-fields`
- `href="/admin/settings"` → `/me/settings`
- `href="/admin/google"` → `/me/google`

(`/logout` stays.)

- [ ] **Step 3d: Update application.properties**

Change the permission path and the form landing page:

```properties
quarkus.http.auth.form.landing-page=/me
```

```properties
# Only /me/* requires the user role; everything else stays public.
quarkus.http.auth.permission.authenticated.paths=/me,/me/*
quarkus.http.auth.permission.authenticated.policy=authenticated-policy
quarkus.http.auth.policy.authenticated-policy.roles-allowed=user
```

> Remove the old `quarkus.http.auth.permission.admin.*` + `quarkus.http.auth.policy.admin-policy.*` lines. Leave the `quarkus.security.users.embedded.*` lines as Phase 1 left them (Phase 1 replaced embedded auth with security-jpa; if those lines are still present in your tree, Phase 1 is not actually done — STOP and resolve Phase 1 first).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=MeOwnerFilterTest`
Expected: PASS (302 unauth, 200 authed at `/me`, 404 at `/admin`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/MeOwnerFilter.java src/main/java/com/calit/web/AdminResource.java src/main/resources/templates/adminBase.html src/main/resources/application.properties src/test/java/com/calit/web/MeOwnerFilterTest.java
git commit -m "feat: /me owner-scoped management UI + MeOwnerFilter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Scope Google web flow; delete /api CRUD resources

Scope `GoogleCalendarResource` and `GoogleOAuthResource` to `CurrentOwner`, and DELETE the four unused, unauthenticated `/api/*` CRUD resources (keep `/api/google/*`). After this task the whole module compiles and the full suite (minus the still-pending test updates in Task 10) runs.

> **Auth note for `/api/google/*`:** these resolve the owner from `CurrentOwner`, but the HTTP permission policy in Task 7 only guards `/me`. Add `/api/google` + `/api/google/*` to the same `authenticated` permission so `currentOwner.id()` is never null there, and extend `MeOwnerFilter` to also match those paths.

**Files:**
- Modify: `src/main/java/com/calit/web/MeOwnerFilter.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/calit/google/GoogleCalendarResource.java`
- Modify: `src/main/java/com/calit/google/GoogleOAuthResource.java`
- Delete: `src/main/java/com/calit/api/MeetingTypeResource.java`, `SettingsResource.java`, `BookingFieldResource.java`, `AvailabilityResource.java`
- Delete: `src/test/java/com/calit/api/MeetingTypeResourceTest.java`, `BookingFieldResourceTest.java`
- Test: `src/test/java/com/calit/api/DeletedApiResourcesGoneTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.calit.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/** The unauthenticated JSON CRUD resources are removed; their paths must 404. /api/google stays. */
@QuarkusTest
class DeletedApiResourcesGoneTest {

    @Test
    void meetingTypesCrudGone() {
        given().when().get("/api/meeting-types").then().statusCode(404);
    }

    @Test
    void settingsCrudGone() {
        given().contentType("application/json").body("{}")
            .when().put("/api/settings").then().statusCode(404);
    }

    @Test
    void availabilityCrudGone() {
        given().contentType("application/json").body("{}")
            .when().post("/api/availability").then().statusCode(404);
    }

    @Test
    void bookingFieldsCrudGone() {
        given().contentType("application/json").body("{}")
            .when().post("/api/booking-fields").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=DeletedApiResourcesGoneTest`
Expected: FAIL — these endpoints still respond (not 404) because the resources still exist.

- [ ] **Step 3a: Delete the four resources and their two tests**

```bash
git rm src/main/java/com/calit/api/MeetingTypeResource.java \
       src/main/java/com/calit/api/SettingsResource.java \
       src/main/java/com/calit/api/BookingFieldResource.java \
       src/main/java/com/calit/api/AvailabilityResource.java \
       src/test/java/com/calit/api/MeetingTypeResourceTest.java \
       src/test/java/com/calit/api/BookingFieldResourceTest.java
```

- [ ] **Step 3b: Extend MeOwnerFilter to match /api/google/***

Replace `matchesMe`:

```java
    /** True for /me, /me/*, /api/google, and /api/google/* (all owner-scoped, authenticated routes). */
    private static boolean matchesMe(UriInfo uriInfo) {
        String path = uriInfo.getPath();
        return path.equals("me") || path.startsWith("me/")
                || path.equals("api/google") || path.startsWith("api/google/");
    }
```

- [ ] **Step 3c: Guard /api/google in application.properties**

Extend the authenticated permission paths added in Task 7:

```properties
quarkus.http.auth.permission.authenticated.paths=/me,/me/*,/api/google,/api/google/*
```

- [ ] **Step 3d: Scope GoogleCalendarResource**

Inject `CurrentOwner`; stamp/scope every calendar op. Replace the constructor + the two handlers:

```java
    private final CalendarListPort calendarListPort;
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleCalendarResource(CalendarListPort calendarListPort,
                                  com.calit.user.CurrentOwner currentOwner) {
        this.calendarListPort = calendarListPort;
        this.currentOwner = currentOwner;
    }
```

```java
    @POST
    @Transactional
    public Response save(SaveSelectionRequest req) {
        long writeTargets = req.calendars().stream().filter(CalendarSelection::writeTarget).count();
        if (writeTargets > 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("At most one write-target calendar is allowed").build();
        }
        GoogleCalendar.deleteForOwner(currentOwner.id());
        for (CalendarSelection sel : req.calendars()) {
            GoogleCalendar c = new GoogleCalendar();
            c.ownerId = currentOwner.id();
            c.googleCalendarId = sel.googleCalendarId();
            c.summary = sel.summary();
            c.readForBusy = sel.readForBusy();
            c.writeTarget = sel.writeTarget();
            c.persist();
        }
        return Response.ok().build();
    }

    @GET
    @Path("/write-target")
    public GoogleCalendar writeTarget() {
        GoogleCalendar target = GoogleCalendar.writeTarget(currentOwner.id());
        if (target == null) {
            throw new NotFoundException("No write-target calendar selected");
        }
        return target;
    }
```

(`list()` delegates to `calendarListPort.listCalendars()` which goes through `GoogleCalendarPort.client()` → already owner-scoped via `CurrentOwner` from Task 6 — no change.)

- [ ] **Step 3e: Scope GoogleOAuthResource**

Inject `CurrentOwner`, pass its id into `exchangeCode`, redirect to `/me`:

```java
    private final GoogleTokenService tokenService;
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleOAuthResource(GoogleTokenService tokenService,
                               com.calit.user.CurrentOwner currentOwner) {
        this.tokenService = tokenService;
        this.currentOwner = currentOwner;
    }
```

In `callback`, replace the exchange + redirect:

```java
        tokenService.exchangeCode(currentOwner.id(), code, now);
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/me"))
                .build();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=DeletedApiResourcesGoneTest`
Expected: PASS (all four 404).

> Now run the whole compile to confirm the module builds clean: `./mvnw -q test-compile`. Expected: BUILD SUCCESS (no references to deleted classes or old signatures remain).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: scope /api/google to owner; delete unused /api CRUD resources

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Cross-owner isolation tests

Prove the security guarantee end-to-end: with owner A logged in, owner B's meeting type / availability / booking / settings are invisible in A's lists and 404 on direct access. `FormAuth.login()` (Phase 1) authenticates as the `admin` user — that is owner A. Seed a second `AppUser` (owner B) plus B's data, then assert isolation. This task also covers the two service-level isolation guarantees from the FIXES: (FIX 1) owner B's HELD booking is absent from owner A's busy-set so A's overlapping slot stays free, and (FIX 2) owner A's GLOBAL availability rule does not affect owner B's slots.

**Files:**
- Create: `src/test/java/com/calit/web/TestOwners.java` (the helper from the convention box above)
- Create: `src/test/java/com/calit/web/CrossOwnerIsolationTest.java`

- [ ] **Step 1: Write the failing test**

First create `TestOwners.java` exactly as shown in the convention box near the top of this plan. Then:

```java
package com.calit.web;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import com.calit.availability.SlotService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CrossOwnerIsolationTest {

    @Inject
    SlotService slotService;

    @Inject
    com.calit.booking.BookingService bookingService;

    // The busy-set test calls availableSlots() outside an HTTP request, where the real
    // GoogleCalendarPort.isConnected() would touch the @RequestScoped CurrentOwner. Mock the port
    // to the degraded (not-connected) path so the busy-set is just the owner's HELD bookings.
    @io.quarkus.test.InjectMock
    com.calit.google.CalendarPort calendarPort;

    /** Seeds owner B (a second AppUser) with a meeting type, a rule, a pending booking, and settings. */
    @Transactional
    long[] seedOwnerB() {
        AppUser b = new AppUser();
        b.username = "ownerb"; b.passwordHash = "x"; b.enabled = true; b.isAdmin = false;
        b.createdAt = Instant.now();
        b.persist();

        OwnerSettings s = new OwnerSettings();
        s.ownerId = b.id; s.ownerName = "Owner B"; s.ownerEmail = "ownerb@x.com"; s.timezone = "UTC";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = b.id; t.name = "B Secret Strategy"; t.slug = "b-strategy";
        t.durationMinutes = 30;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = b.id; r.meetingTypeId = t.id; r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(17, 0);
        r.persist();

        Booking bk = new Booking();
        bk.ownerId = b.id; bk.meetingTypeId = t.id;
        bk.inviteeName = "B Invitee"; bk.inviteeEmail = "binvitee@x.com";
        bk.startUtc = Instant.now().plusSeconds(86400);
        bk.endUtc = bk.startUtc.plusSeconds(1800);
        bk.createdAt = Instant.now();
        bk.manageToken = java.util.UUID.randomUUID().toString();
        bk.status = BookingStatus.PENDING;
        bk.persist();

        return new long[]{t.id, bk.id};
    }

    @Test
    void ownerBMeetingTypeAbsentFromOwnerAList() {
        seedOwnerB();
        given()
            .cookie("quarkus-credential", FormAuth.login()) // owner A
            .when().get("/me/meeting-types")
            .then().statusCode(200)
                .body(not(containsString("B Secret Strategy")));
    }

    @Test
    void ownerADirectGetOfOwnerBTypeIs404() {
        long typeId = seedOwnerB()[0];
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types/" + typeId)
            .then().statusCode(404);
    }

    @Test
    void ownerACannotEditOwnerBType() {
        long typeId = seedOwnerB()[0];
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Hijacked").formParam("slug", "hijacked")
            .formParam("durationMinutes", "15")
            .formParam("minNoticeMinutes", "0").formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET").formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/me/meeting-types/" + typeId + "/edit")
            .then().statusCode(404);
    }

    @Test
    void ownerACannotDeleteOwnerBType() {
        long typeId = seedOwnerB()[0];
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().post("/me/meeting-types/" + typeId + "/delete")
            .then().statusCode(404);
    }

    @Test
    void ownerBPendingBookingAbsentAndApproveIs404() {
        long bookingId = seedOwnerB()[1];
        // B's pending booking never appears in A's pending queue ...
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/pending")
            .then().statusCode(200).body(not(containsString("B Invitee")));
        // ... and A cannot approve it.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().post("/me/bookings/" + bookingId + "/approve")
            .then().statusCode(404);
    }

    @Test
    void ownerASettingsAreOwnersOwnNotOwnerBs() {
        seedOwnerB();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/settings")
            .then().statusCode(200).body(not(containsString("ownerb@x.com")));
    }

    /**
     * FIX 1 — owner-scoped busy-set: owner B's HELD booking must NOT appear in owner A's busy-set,
     * so A still sees the overlapping slot as FREE. Each owner has their own Google calendar; one
     * owner's bookings never block another's availability.
     */
    @Test
    @TestTransaction
    void ownerBHeldBookingDoesNotBlockOwnerAsSlots() {
        org.mockito.Mockito.when(calendarPort.isConnected()).thenReturn(false); // degraded: no Google
        AppUser a = new AppUser();
        a.username = "ownera-busy"; a.passwordHash = "x"; a.enabled = true; a.isAdmin = false;
        a.createdAt = Instant.now(); a.persist();
        OwnerSettings sa = new OwnerSettings();
        sa.ownerId = a.id; sa.ownerName = "A"; sa.ownerEmail = "a@x.com"; sa.timezone = "UTC"; sa.persist();
        MeetingType ta = new MeetingType();
        ta.ownerId = a.id; ta.name = "A Intro"; ta.slug = "a-intro"; ta.durationMinutes = 30; ta.persist();

        // A wide weekly rule for A's type on the target weekday.
        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3);
        AvailabilityRule ra = new AvailabilityRule();
        ra.ownerId = a.id; ra.meetingTypeId = ta.id; ra.dayOfWeek = day.getDayOfWeek();
        ra.startTime = LocalTime.of(9, 0); ra.endTime = LocalTime.of(17, 0); ra.persist();

        // Owner B holds a booking at 10:00-10:30 on that same day (against B's own meeting type).
        AppUser b = new AppUser();
        b.username = "ownerb-busy"; b.passwordHash = "x"; b.enabled = true; b.isAdmin = false;
        b.createdAt = Instant.now(); b.persist();
        MeetingType tb = new MeetingType();
        tb.ownerId = b.id; tb.name = "B Intro"; tb.slug = "b-intro-busy"; tb.durationMinutes = 30; tb.persist();
        Instant bStart = day.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC);
        Booking bk = new Booking();
        bk.ownerId = b.id; bk.meetingTypeId = tb.id; // B's real type (FK-valid); not under test
        bk.inviteeName = "B"; bk.inviteeEmail = "b@x.com";
        bk.startUtc = bStart; bk.endUtc = bStart.plusSeconds(1800);
        bk.createdAt = Instant.now(); bk.manageToken = java.util.UUID.randomUUID().toString();
        bk.status = BookingStatus.CONFIRMED; bk.persist();

        // availableSlots subtracts the OWNER-SCOPED busy-set (Google free/busy is skipped — not
        // connected — leaving only owner A's HELD bookings, of which there are none). If the busy-set
        // were still instance-wide, B's 10:00 hold would remove A's 10:00 slot. Assert it survives.
        boolean tenAmBookableForA = bookingService.availableSlots(ta, day, day).stream()
                .anyMatch(s -> s.start().toInstant().equals(bStart));
        org.junit.jupiter.api.Assertions.assertTrue(tenAmBookableForA,
                "A's 10:00 slot must stay free — owner B's held booking is not in A's busy-set");
    }

    /**
     * FIX 2 — owner-scoped GLOBAL availability rules: owner A's global rule (meetingTypeId is null)
     * must not affect owner B's slots. B with no rules of their own produces no slots even though A
     * has a wide global rule for the same weekday.
     */
    @Test
    @TestTransaction
    void ownerAGlobalRuleDoesNotAffectOwnerBSlots() {
        AppUser a = new AppUser();
        a.username = "ownera-global"; a.passwordHash = "x"; a.enabled = true; a.isAdmin = false;
        a.createdAt = Instant.now(); a.persist();
        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3);
        AvailabilityRule ga = new AvailabilityRule();
        ga.ownerId = a.id; ga.meetingTypeId = null; ga.dayOfWeek = day.getDayOfWeek();
        ga.startTime = LocalTime.of(9, 0); ga.endTime = LocalTime.of(17, 0); ga.persist();

        AppUser b = new AppUser();
        b.username = "ownerb-global"; b.passwordHash = "x"; b.enabled = true; b.isAdmin = false;
        b.createdAt = Instant.now(); b.persist();
        OwnerSettings sb = new OwnerSettings();
        sb.ownerId = b.id; sb.ownerName = "B"; sb.ownerEmail = "b@x.com"; sb.timezone = "UTC"; sb.persist();
        MeetingType tb = new MeetingType();
        tb.ownerId = b.id; tb.name = "B Intro"; tb.slug = "b-intro"; tb.durationMinutes = 30; tb.persist();

        // B has NO global rule of their own; A's global rule must not leak into B's resolution.
        assertEquals(0, slotService.generateRawSlots(tb, day, day).size(),
                "owner B has no availability; owner A's global rule must not apply");
    }

    /** Sanity: the seeded admin login user really is a distinct owner from B. */
    @Test
    @TestTransaction
    void seededOwnersAreDistinct() {
        long[] ignored = seedOwnerB();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                AppUser.findByUsername("admin").id, AppUser.findByUsername("ownerb").id);
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or passes)**

Run: `./mvnw test -Dtest=CrossOwnerIsolationTest`
Expected: Most assertions PASS already (Task 7 scoped everything). If any FAIL, that is a real isolation leak — fix the offending handler in `AdminResource` (it is missing an owner filter or a `requireType`/`requireOwnedBooking` guard) before proceeding. Do NOT weaken the test.

> This task is also a verification gate: a failure here means a Task-7 query slipped through unscoped.

- [ ] **Step 3: (No new production code expected.)** If a leak was found, the fix lives in `AdminResource` per the failing assertion. Re-run until green.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CrossOwnerIsolationTest`
Expected: PASS (all assertions green).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/calit/web/TestOwners.java src/test/java/com/calit/web/CrossOwnerIsolationTest.java
git commit -m "test: cross-owner isolation for /me management UI

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Update existing tests for /admin → /me and owner-stamped seeds

The existing web + booking + email + google tests still POST/GET `/admin*`, call `OwnerSettings.get()`/`GoogleCredential.get()`/`MeetingType.findBySlug(String)`, or seed un-owned rows. Update them. Work file-by-file; run each as you go.

**Files (modify):**
- `src/test/java/com/calit/web/AdminAuthTest.java`
- `src/test/java/com/calit/web/AdminAvailabilityTest.java`
- `src/test/java/com/calit/web/AdminBookingFieldsTest.java`
- `src/test/java/com/calit/web/AdminDateOverridesTest.java`
- `src/test/java/com/calit/web/AdminGoogleTest.java`
- `src/test/java/com/calit/web/AdminMeetingTypeDetailTest.java`
- `src/test/java/com/calit/web/AdminMeetingTypeFormTest.java`
- `src/test/java/com/calit/web/AdminMeetingTypesTest.java`
- `src/test/java/com/calit/web/AdminPendingTest.java`
- `src/test/java/com/calit/web/AdminSettingsTest.java`
- `src/test/java/com/calit/web/LogoutTest.java`
- `src/test/java/com/calit/web/RememberMeTest.java`
- `src/test/java/com/calit/web/BookPageTest.java`
- `src/test/java/com/calit/web/BookingPostTest.java`
- `src/test/java/com/calit/web/BookingSettingsGuardTest.java`
- `src/test/java/com/calit/web/BookPageTurnstileEnabledTest.java`
- `src/test/java/com/calit/web/ManageBookingTest.java`
- `src/test/java/com/calit/web/PublicLandingTest.java`
- `src/test/java/com/calit/domain/AvailabilityRuleTest.java` (calls `globalFor`/`forMeetingType` — new owner-scoped signatures + `.ownerId` seeds)
- `src/test/java/com/calit/domain/DateOverrideTest.java` (calls `resolve(typeId, date)` — new `resolve(ownerId, typeId, date)` + `.ownerId` seeds)
- `src/test/java/com/calit/availability/*Test.java` (SlotService tests: `windowsFor`/`rulesFor` gained a leading `ownerId`; stamp `.ownerId` on seeded rules/overrides and seed `OwnerSettings(ownerId)`)
- `src/test/java/com/calit/booking/*Test.java` (incl. `BookingTest` — `heldOverlapping` now takes a leading `ownerId`), `src/test/java/com/calit/email/*Test.java`,
  `src/test/java/com/calit/google/*Test.java`, `src/test/java/com/calit/scheduler/*Test.java`
  (any that seed `OwnerSettings`/`MeetingType`/`GoogleCredential`/`Booking`/`AvailabilityRule`/`DateOverride` or call the old singletons)

- [ ] **Step 1: Find every stale reference**

Run:

```bash
git grep -n "/admin" src/test
git grep -n "OwnerSettings.get()\|GoogleCredential.get()\|MeetingType.findBySlug(\|MeetingType.listAll\|MeetingType.listPublic()\|BookingField.formFor(" src/test
git grep -n "bookingService.book(\|Booking.heldOverlapping(\|AvailabilityRule.globalFor(\|DateOverride.resolve(" src/test
```

Each hit is a required edit. The mechanical rules:

1. **Path:** every `"/admin"` / `"/admin/..."` string → `"/me"` / `"/me/..."`.
2. **Singleton reads:** `OwnerSettings.get()` → `OwnerSettings.forOwner(ownerId)`; `GoogleCredential.get()` → `GoogleCredential.forOwner(ownerId)`, where `ownerId` is the seeded owner's id (use `TestOwners.loginOwnerId()` for the `admin` login user, or the id of an `AppUser` the test itself seeds).
3. **Owner-stamp seeds:** every `new OwnerSettings()` must set `.ownerId`; every `new MeetingType()` must set `.ownerId`; every `new GoogleCredential()` must set `.ownerId` (and NOT set `.id = SINGLETON_ID`); every `new GoogleCalendar()` must set `.ownerId`; every `new Booking()` must set `.ownerId`; every `new AvailabilityRule()` and `new DateOverride()` must set `.ownerId` (including global rows, `meetingTypeId == null`). Seed these to `TestOwners.loginOwnerId()` so the `admin`-logged-in tests see them.
4. **Scoped statics:** `MeetingType.findBySlug(slug)` → `MeetingType.findBySlug(ownerId, slug)`; `MeetingType.listPublic()` → `MeetingType.listPublic(ownerId)`; `BookingField.formFor(typeId)` → `BookingField.formFor(ownerId, typeId)`.
5. **book(...) signature:** `bookingService.book(slug, ...)` → `bookingService.book(ownerId, slug, ...)`.
6. **Owner-scoped statics:** `Booking.heldOverlapping(from, to)` → `Booking.heldOverlapping(ownerId, from, to)` (stamp the seeded bookings' `.ownerId` to that same `ownerId` so the window query finds them); `AvailabilityRule.globalFor(dow)` → `AvailabilityRule.globalForOwner(ownerId, dow)`; `DateOverride.resolve(typeId, date)` → `DateOverride.resolve(ownerId, typeId, date)`. Stamp `.ownerId` on every seeded `AvailabilityRule`/`DateOverride` (rule 3 extended).
7. **Settings/credential tests that asserted singleton id = 1** must drop that assertion.

Example — `AdminSettingsTest.readNotificationsEnabled()` currently calls `OwnerSettings.get()`. Replace its body:

```java
    @Transactional
    boolean readNotificationsEnabled() {
        em.clear();
        return com.calit.domain.OwnerSettings.forOwner(
                com.calit.user.AppUser.findByUsername("admin").id).ownerNotificationsEnabled;
    }
```

and every `/admin/settings` → `/me/settings` in that file.

Example — `AdminMeetingTypesTest.seedSecret()` must stamp the owner:

```java
    @Transactional
    void seedSecret() {
        MeetingType secret = new MeetingType();
        secret.ownerId = com.calit.user.AppUser.findByUsername("admin").id;
        secret.name = "Admin Visible Secret"; secret.slug = "admin-secret";
        secret.durationMinutes = 30; secret.secret = true;
        secret.persist();
    }
```

plus `/admin/meeting-types` → `/me/meeting-types`, and `MeetingType.findBySlug(slug)` → `MeetingType.findBySlug(AppUser.findByUsername("admin").id, slug)` in the three assertions.

> For booking/email/google/scheduler tests that seed a full owner graph, prefer seeding their OWN `AppUser` + `OwnerSettings(ownerId)` + owner-stamped `MeetingType` (the pattern in `BookingOwnerStampTest.seedOwnerAndType`) rather than relying on the login user, unless the test also exercises the `/me` UI.

- [ ] **Step 2: Edit each file per the rules above, running its test immediately after**

For each modified test class, run it before moving on, e.g.:

```bash
./mvnw test -Dtest=AdminSettingsTest
./mvnw test -Dtest=AdminMeetingTypesTest
./mvnw test -Dtest=AdminAvailabilityTest
./mvnw test -Dtest=AdminPendingTest
./mvnw test -Dtest=AdminMeetingTypeDetailTest
./mvnw test -Dtest=AdminMeetingTypeFormTest
./mvnw test -Dtest=AdminBookingFieldsTest
./mvnw test -Dtest=AdminDateOverridesTest
./mvnw test -Dtest=AdminGoogleTest
./mvnw test -Dtest=AdminAuthTest
./mvnw test -Dtest=LogoutTest
./mvnw test -Dtest=RememberMeTest
./mvnw test -Dtest=BookPageTest
./mvnw test -Dtest=BookingPostTest
./mvnw test -Dtest=BookingSettingsGuardTest
./mvnw test -Dtest=ManageBookingTest
./mvnw test -Dtest=PublicLandingTest
```

Expected: each PASS once its `/admin`→`/me` paths and owner-stamped seeds are applied.

> **AdminAuthTest** likely asserted `/admin` requires the `admin` role. Phase 2 management requires the `user` role at `/me`; update its expectations: a logged-in `user` reaches `/me` (200), an anonymous request to `/me` is 302 to login. If it asserted a 403 for a non-admin, that scenario belongs to Phase 4 (`/me/users` admin-only) — remove or relax it here.
>
> **DateOverride / availability tests** that created GLOBAL rows (`meetingTypeId is null`) via `/me/availability` or `/me/date-overrides` still work: FIX 2 keeps owner-attributed globals (a blank `meetingTypeId` now creates a global rule/override stamped with the current owner). Just ensure the seeded/asserted rows carry `.ownerId = TestOwners.loginOwnerId()`, and update `globalFor`/`forMeetingType`/`resolve` calls to their new owner-scoped signatures.

- [ ] **Step 3: Run the FULL suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS — all tests green (Dev Services Postgres up; Docker running).

> If a non-web test (booking/email/google/scheduler) fails on a missing `owner_id` (NOT NULL violation) or a null `OwnerSettings`, it is seeding an un-owned row — apply rule 3. If a scheduler test (`ReminderScheduler`, `PendingExpiryScheduler`) fails, confirm those schedulers were NOT changed (they iterate due rows instance-wide and need no owner) and that the failure is only a seed-stamping issue.

- [ ] **Step 4: Verify no stale references remain**

Run:

```bash
git grep -n "/admin" src/test src/main || echo "no /admin references remain"
git grep -n "SINGLETON_ID\|OwnerSettings.get()\|GoogleCredential.get()" src/ || echo "no singleton references remain"
```

Expected: both print their "no ... remain" message (the only allowed `/admin` hit is none; any remaining is a miss to fix).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: migrate existing tests to /me + owner-stamped seeds

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] Run the full suite once more with a clean build: `./mvnw clean test` — Expected: BUILD SUCCESS.
- [ ] Confirm the security guarantee holds by re-running `./mvnw test -Dtest=CrossOwnerIsolationTest` — Expected: PASS.
- [ ] Spot-check there are no remaining `listAll()` calls on owned entities in `src/main`: `git grep -n "MeetingType.listAll\|OwnerSettings.get\|GoogleCredential.get" src/main` — Expected: no output.
