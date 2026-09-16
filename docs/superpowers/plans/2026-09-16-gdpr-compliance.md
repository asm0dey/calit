# GDPR Compliance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a calit operator the mechanics to answer a data-subject request and hold a defensible retention policy, plus the paperwork kit on the docs site that turns those mechanics into compliance.

**Architecture:** One `privacy` package holding a hand-written data inventory (`PersonalData`) and a `PrivacyService` with explicit Panache queries per table — no registry interface, no JPA-metadata reflection. A `@QuarkusTest` schema guard reads `information_schema` and fails the build when a new migration adds an unclassified table or column; that test is the compliance property, everything else is plumbing. Subject-rights surfaces hang off keys that already exist (the invitee's `manage_token`, the owner's `/me` session). Retention runs as one more `SELECT … FOR UPDATE SKIP LOCKED` scheduler, no leader election.

**Tech Stack:** Quarkus 3.38 / Java 25, Panache + Hibernate (validate-only), Flyway, Qute `@CheckedTemplate`, Tailwind v4 + daisyUI 5, JUnit 5 + RestAssured under `@QuarkusTest`, Jackson for JSON export.

**Spec:** `docs/superpowers/specs/2026-09-12-gdpr-compliance-design.md`

**Bean:** `calit-l3fk` (epic). Create one child bean per task below before starting it; keep its checkboxes current and commit the bean file with the code.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Build JDK.** `export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca` before any Maven command. The default `mvn` on PATH is JDK 21 and fails with `release version 25 not supported`.
- **Docker must be running.** Dev Services provisions the throwaway Postgres for every `@QuarkusTest`. There is no H2 fallback.
- **Run the whole suite, not just your class, before opening a PR.** `mvn test` must be `BUILD SUCCESS`, 0 failures, 0 errors. A failure you did not cause is still a failure you fix first, in its own commit.
- **Migration numbering starts at V34.** The spec says "V33"; `V33__notification_channel_default_enabled.sql` already exists on `main`. Never edit an applied migration — Flyway checksum validation fails on even a comment change.
- **Owner scoping is a hard invariant.** Every `/me` query filters by `currentOwner.id()`. One user must never read or write another's data.
- **Every new POST form carries `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">`.** CSRF is off in `%test` and on in `%prod`; a missing token is a 400 in production that tests will not catch.
- **Every new or changed user-facing string ships with its German translation in the same change.** Add the key to the bundle interface (`AppMessages` = `{msg:…}`, `AdminMessages` = `{adm:…}`) with the English text as the `@Message` default, and the `de` value to `src/main/resources/messages/{msg,adm}_de.properties`. Hebrew is deliberately deferred for this epic — see Task 12; it ships with the English default and a tracked issue, never silently.
- **Formatting gate.** `mvn spotless:apply` (or `bun run format`) before committing; `verify` fails on unformatted Java. Qute `.html` is deliberately not Prettier-formatted.
- **Commit style.** Conventional commits (`feat:`, `fix:`, `docs:`, `chore:`). Branch from `origin/main`, never from local `main` (local `main` may hold unpushed commits that ride into the PR).
- **Branch + PR always.** Never push to `main`.
- **Coverage.** `quarkus-jacoco` only instruments code reached through a booted `@QuarkusTest`. Plain JUnit tests that construct objects directly contribute nothing. Every test in this plan is a `@QuarkusTest`.
- **Test DB invariant.** `DatabaseResetCallback` truncates and reseeds per test; the admin user is always `id 1`.

### Branch and PR layout

One branch, `gdpr-compliance`, cut from `origin/main`. Four PRs at the seams below; each is independently green and independently shippable.

| PR | Tasks | Title |
|---|---|---|
| 1 | 1–3 | `feat(privacy): data inventory, schema guard and outbox links` |
| 2 | 4–8 | `feat(privacy): invitee erasure, export and account deletion` |
| 3 | 9–10 | `feat(privacy): retention and hygiene purges` |
| 4 | 11–12 | `feat(privacy): fact-driven policy copy and compliance docs` |

Add the `## Unreleased` changelog bullet on the `docs-site` branch as each PR merges — not at release time.

### Deviations from the spec, decided here

Three things the spec assumes are not true of `main` as it stands. Each is built the way described below; flag them in the PR body.

1. **V33 is taken.** The migration is `V34__privacy.sql`.
2. **There is no Google OAuth revoke to reuse.** `GooglePageResource.disconnect` (`src/main/java/site/asm0dey/calit/web/GooglePageResource.java:206`) deletes the `google_credential` row; it never calls Google's `/revoke` endpoint. Account deletion therefore deletes the stored tokens (via the `owner_id` cascade) and does **not** revoke the grant at Google. The operator guide says so, and the privacy copy says so. Adding a real revoke call is out of scope for this epic — file it as a follow-up bean.
3. **`booking.title` and `booking.description` are invitee-writable** (`POST /booking/{manageToken}/edit-details`) and hold free text, so they are personal columns the spec's table omits. They are classified as personal and nulled on erasure; nulling falls back to the meeting type's own name and description, so the owner's record stays readable.

---

## File Structure

New files, and what each is responsible for.

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V34__privacy.sql` | `booking.erased_at`, `owner_settings.booking_retention_days`, `email_outbox.booking_id`/`owner_id`, explicit cascade on `booking.meeting_type_id` |
| `src/main/java/site/asm0dey/calit/privacy/PersonalData.java` | The hand-written inventory: every table, every column, which are personal, whose they are, what erasure does. Plus the outbound-destination list. |
| `src/main/java/site/asm0dey/calit/privacy/PrivacyService.java` | Every read and write that touches personal data as personal data: anonymise, export, delete account, purge. Explicit Panache queries, one per table. |
| `src/main/java/site/asm0dey/calit/privacy/ErasureReport.java` | Per-destination outcome of one erasure, rendered on the done page. |
| `src/main/java/site/asm0dey/calit/privacy/PrivacyConfig.java` | `calit.privacy.invitee-erasure`, `calit.retention.booking-days` as an injectable bean. |
| `src/main/java/site/asm0dey/calit/privacy/PrivacyFacts.java` | `{inject:privacy.*}` — what this deployment actually does, read at render time. |
| `src/main/java/site/asm0dey/calit/email/MailTag.java` | The `(bookingId, ownerId)` pair an outbox row is keyed by. |
| `src/main/java/site/asm0dey/calit/scheduler/RetentionScheduler.java` | Daily booking anonymisation past the retention window. |
| `src/main/java/site/asm0dey/calit/scheduler/PurgeScheduler.java` | Daily non-configurable hygiene purges: outbox at 30 days, auth tokens 24h past expiry. |
| `src/main/resources/templates/PublicResource/eraseConfirm.html` | Invitee erase confirm page, with the boundary statement. |
| `src/main/resources/templates/PublicResource/erased.html` | Done page, per-destination outcome. |
| `src/main/resources/templates/AdminResource/deleteAccount.html` | Owner self-serve account deletion confirm. |

Modified files are named per task.

---

## Task 1: V34 migration

**Files:**
- Create: `src/main/resources/db/migration/V34__privacy.sql`
- Create: `src/test/java/site/asm0dey/calit/migration/V34MigrationTest.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java`
- Modify: `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailOutbox.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Booking.erasedAt` (`Instant`, nullable) and `Booking.isErased()` (`boolean`); `OwnerSettings.bookingRetentionDays` (`Integer`, nullable); `EmailOutbox.bookingId` and `EmailOutbox.ownerId` (both `Long`, nullable). Hibernate runs `validate`-only, so an entity field without its column is a boot failure — migration and entities land in the same commit.

- [ ] **Step 1: Write the failing migration test**

Create `src/test/java/site/asm0dey/calit/migration/V34MigrationTest.java`:

```java
package site.asm0dey.calit.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

/** V34 adds the privacy columns and makes booking -> meeting_type cascade explicit. */
@QuarkusTest
class V34MigrationTest {

    @Inject
    EntityManager em;

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private long column(String table, String col) {
        return scalar("select count(*) from information_schema.columns where table_name='" + table
                + "' and column_name='" + col + "'");
    }

    @Test
    @Transactional
    void privacyColumnsExist() {
        assertEquals(1L, column("booking", "erased_at"), "booking.erased_at must exist");
        assertEquals(
                1L,
                column("owner_settings", "booking_retention_days"),
                "owner_settings.booking_retention_days must exist");
        assertEquals(1L, column("email_outbox", "booking_id"), "email_outbox.booking_id must exist");
        assertEquals(1L, column("email_outbox", "owner_id"), "email_outbox.owner_id must exist");
    }

    @Test
    @Transactional
    void newColumnsAreNullable() {
        var notNullable = scalar("select count(*) from information_schema.columns "
                + "where table_name in ('booking','owner_settings','email_outbox') "
                + "and column_name in ('erased_at','booking_retention_days','booking_id','owner_id') "
                + "and is_nullable='NO'");
        assertEquals(0L, notNullable, "every V34 column must be nullable — no backfill");
    }

    @Test
    @Transactional
    void bookingMeetingTypeCascadesExplicitly() {
        var cascade = scalar("select count(*) from information_schema.referential_constraints rc "
                + "join information_schema.key_column_usage k on k.constraint_name = rc.constraint_name "
                + "where k.table_name='booking' and k.column_name='meeting_type_id' "
                + "and rc.delete_rule='CASCADE'");
        assertEquals(1L, cascade, "booking.meeting_type_id must declare ON DELETE CASCADE");
    }

    @Test
    @Transactional
    void outboxLinksCascade() {
        var cascades = scalar("select count(*) from information_schema.referential_constraints rc "
                + "join information_schema.key_column_usage k on k.constraint_name = rc.constraint_name "
                + "where k.table_name='email_outbox' and k.column_name in ('booking_id','owner_id') "
                + "and rc.delete_rule='CASCADE'");
        assertEquals(2L, cascades, "both email_outbox links must cascade");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=V34MigrationTest
```

Expected: FAIL — `booking.erased_at must exist ==> expected: <1> but was: <0>`.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V34__privacy.sql`:

```sql
-- GDPR epic. Four independent changes, no backfill: existing rows are not erased and
-- retention stays off until an operator opts in, so an upgrade changes nothing by itself.

-- 1. Invitee erasure marker. NULL = not erased. The booking ROW survives erasure so the owner
--    keeps a "someone booked 14:00-14:30" record; this stamp is what makes the invitee-facing
--    routes 404 and what the owner's list renders as a placeholder instead of a blank name.
ALTER TABLE booking ADD COLUMN erased_at TIMESTAMPTZ;

-- 2. Per-owner retention override. NULL = fall back to the instance default
--    (calit.retention.booking-days), which is itself unset by default = keep forever.
ALTER TABLE owner_settings ADD COLUMN booking_retention_days INT;

-- 3. email_outbox was orphan personal data: it holds the rendered HTML of every booking mail
--    with the recipient address, and nothing cascaded to it, so it survived account deletion.
--    Matching on recipient alone is NOT a fix -- two owners can mail the same invitee, and
--    erasing one booking would take the other owner's mail with it. Link the rows instead.
--    Both nullable: rows enqueued before V34 have no link and are cleared by the age purge alone.
ALTER TABLE email_outbox ADD COLUMN booking_id BIGINT REFERENCES booking(id)  ON DELETE CASCADE;
ALTER TABLE email_outbox ADD COLUMN owner_id   BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
CREATE INDEX idx_email_outbox_booking ON email_outbox (booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX idx_email_outbox_owner   ON email_outbox (owner_id)   WHERE owner_id IS NOT NULL;

-- 4. V4 declared booking.meeting_type_id REFERENCES meeting_type(id) with no ON DELETE clause.
--    Owner deletion resolves today only because meeting_type and booking each cascade separately
--    from app_user. Make the intent explicit rather than leave it as an accident of ordering.
ALTER TABLE booking DROP CONSTRAINT booking_meeting_type_id_fkey;
ALTER TABLE booking ADD CONSTRAINT booking_meeting_type_id_fkey
    FOREIGN KEY (meeting_type_id) REFERENCES meeting_type(id) ON DELETE CASCADE;
```

Note on the constraint name: V4 created it unnamed, so Postgres auto-named it `booking_meeting_type_id_fkey`. Verify before running the migration for the first time:

```bash
docker exec -i $(docker ps -qf name=postgres) psql -U quarkus -d quarkus \
  -c "\d booking" | grep meeting_type_id
```

If the name differs, use the actual name in the `DROP CONSTRAINT` line.

- [ ] **Step 4: Add the entity fields**

In `src/main/java/site/asm0dey/calit/booking/Booking.java`, after the `icsSequence` field:

```java
    /**
     * When this booking's invitee data was anonymised (Art. 17 erasure, or the retention window
     * elapsing). NULL = not erased. The row survives so the owner keeps the slot record; this stamp
     * 404s every invitee-facing route on it and switches the owner's list to a placeholder.
     */
    @Column(name = "erased_at")
    public Instant erasedAt;
```

and, beside `effectiveTitle`:

```java
    /** True once the invitee's data has been anonymised — see {@link #erasedAt}. */
    public boolean isErased() {
        return erasedAt != null;
    }
```

In `src/main/java/site/asm0dey/calit/domain/OwnerSettings.java`, after `ownerNotificationsEnabled`:

```java
    /**
     * This owner's booking-retention window in days. NULL = fall back to the instance default
     * ({@code calit.retention.booking-days}), which is itself unset by default = keep forever.
     */
    @Column(name = "booking_retention_days")
    public Integer bookingRetentionDays;
```

In `src/main/java/site/asm0dey/calit/email/EmailOutbox.java`, after `createdAt`:

```java
    /** The booking this mail is about, when there is one. Lets erasure purge it by key, not by address. */
    @Column(name = "booking_id")
    public Long bookingId;

    /** The owner this mail belongs to, when known. Cascades away with the account. */
    @Column(name = "owner_id")
    public Long ownerId;
```

- [ ] **Step 5: Run the test and the full suite**

```bash
./mvnw -o test -Dtest=V34MigrationTest
./mvnw -o test
```

Expected: `V34MigrationTest` PASS, then `BUILD SUCCESS` for the whole suite.

`quarkus.flyway.clean-at-start=true` in `src/test/resources/application.properties` drops and re-migrates on every `%test` boot, so the reused Dev Services container picks V34 up with no manual step.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply
git add src/main/resources/db/migration/V34__privacy.sql \
        src/test/java/site/asm0dey/calit/migration/V34MigrationTest.java \
        src/main/java/site/asm0dey/calit/booking/Booking.java \
        src/main/java/site/asm0dey/calit/domain/OwnerSettings.java \
        src/main/java/site/asm0dey/calit/email/EmailOutbox.java \
        .beans/
git commit -m "feat(privacy): V34 adds erasure, retention and outbox-link columns"
```

---

## Task 2: Personal-data inventory and the schema guard

The compliance property of this whole epic. A future migration that adds a personal column breaks the build until someone writes down what erasure does with it.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/privacy/PersonalData.java`
- Create: `src/test/java/site/asm0dey/calit/privacy/PersonalDataInventoryTest.java`

**Interfaces:**
- Consumes: the V34 schema from Task 1.
- Produces:
  - `PersonalData.TABLES` — `List<PersonalData.Classified>`, where
    `Classified(String table, Set<String> columns, Set<String> personalColumns, Subject subject, EraseRoute route)`.
  - `PersonalData.Subject` — enum `INVITEE, GUEST, OWNER, NONE`.
  - `PersonalData.EraseRoute` — enum `ANONYMISE_IN_PLACE, DELETE_ROWS, CASCADES, AGE_PURGE, NOT_PERSONAL`.
  - `PersonalData.OUTBOUND` — `List<PersonalData.Destination>`, where
    `Destination(String name, String when, boolean reachableByErasure)`.
  - `PersonalData.personalColumnsOf(String table)` — `Set<String>`, empty for an unknown table.

- [ ] **Step 1: Write the failing guard test**

Create `src/test/java/site/asm0dey/calit/privacy/PersonalDataInventoryTest.java`:

```java
package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The compliance property. Every table and every column in the live schema must appear in
 * {@link PersonalData}, classified as personal or not. A migration that adds a column fails this
 * test until someone decides what erasure does with it — which is exactly the judgement a
 * reflection-over-JPA-metadata approach would have hidden.
 */
@QuarkusTest
class PersonalDataInventoryTest {

    /** Flyway's own bookkeeping table is infrastructure, not application data. */
    private static final Set<String> IGNORED = Set.of("flyway_schema_history");

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    private List<String> strings(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    private Set<String> liveTables() {
        return new TreeSet<>(
                strings("select table_name from information_schema.tables "
                                + "where table_schema='public' and table_type='BASE TABLE'")
                        .stream()
                        .filter(t -> !IGNORED.contains(t))
                        .collect(Collectors.toSet()));
    }

    private Set<String> liveColumns(String table) {
        return new TreeSet<>(strings("select column_name from information_schema.columns "
                + "where table_schema='public' and table_name='" + table + "'"));
    }

    @Test
    @Transactional
    void everyLiveTableIsClassified() {
        var classified =
                new TreeSet<>(PersonalData.TABLES.stream().map(PersonalData.Classified::table).toList());
        assertEquals(
                liveTables(),
                classified,
                "PersonalData.TABLES must name exactly the live application tables — "
                        + "add the new table to the inventory and say what erasure does with it");
    }

    @Test
    @Transactional
    void everyLiveColumnIsClassified() {
        for (PersonalData.Classified c : PersonalData.TABLES) {
            assertEquals(
                    liveColumns(c.table()),
                    new TreeSet<>(c.columns()),
                    "PersonalData column list for '" + c.table() + "' is out of date — "
                            + "classify the new column as personal or not");
        }
    }

    @Test
    @Transactional
    void personalColumnsAreASubsetOfKnownColumns() {
        for (PersonalData.Classified c : PersonalData.TABLES) {
            assertTrue(
                    c.columns().containsAll(c.personalColumns()),
                    "personal columns of '" + c.table() + "' must all be real columns");
        }
    }

    @Test
    void everyPersonalTableDeclaresANonTrivialEraseRoute() {
        for (PersonalData.Classified c : PersonalData.TABLES) {
            if (c.personalColumns().isEmpty()) {
                continue;
            }
            assertTrue(
                    c.route() != PersonalData.EraseRoute.NOT_PERSONAL,
                    "'" + c.table() + "' carries personal columns but declares no erase route");
            assertTrue(
                    c.subject() != PersonalData.Subject.NONE,
                    "'" + c.table() + "' carries personal columns but names no data subject");
        }
    }

    @Test
    void outboundDestinationsAreRecorded() {
        assertTrue(PersonalData.OUTBOUND.size() >= 4, "the four known outbound destinations must be listed");
        assertTrue(
                PersonalData.OUTBOUND.stream().anyMatch(d -> !d.reachableByErasure()),
                "at least one destination is known to be beyond erasure — say so");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=PersonalDataInventoryTest
```

Expected: compile error — `PersonalData` does not exist.

- [ ] **Step 3: Write the inventory**

Create `src/main/java/site/asm0dey/calit/privacy/PersonalData.java`. The skeleton and the classification decisions are below; **generate the exact column sets from the live schema** rather than typing them from memory:

```bash
docker exec -i $(docker ps -qf name=postgres) psql -U quarkus -d quarkus -At -F',' -c \
"select table_name, string_agg(column_name, ''',''' order by column_name)
 from information_schema.columns
 where table_schema='public' and table_name <> 'flyway_schema_history'
 group by table_name order by table_name"
```

Each line becomes one `Classified` entry. Paste the column list verbatim into `Set.of(...)`; the guard test is what proves you did not drop one.

```java
package site.asm0dey.calit.privacy;

import java.util.List;
import java.util.Set;

/**
 * What personal data this deployment holds, table by table, and what erasure does with each piece.
 *
 * <p>Hand-written on purpose. A registry interface would buy extensibility that has no second
 * consumer, and reflection over JPA metadata would hide exactly the judgement that matters: which
 * columns are personal, and what erasure does with them. The safety property is
 * {@code PersonalDataInventoryTest}, which reads the live {@code information_schema} and fails the
 * build when this file falls behind a migration.
 *
 * <p>ponytail: a flat list of records, no lookup indices. Twenty-odd entries walked a handful of
 * times per request at most.
 */
public final class PersonalData {

    private PersonalData() {}

    /** Whose data a table's personal columns are. */
    public enum Subject {
        INVITEE,
        GUEST,
        OWNER,
        NONE
    }

    /** What an erasure or account deletion does with a table. */
    public enum EraseRoute {
        /** Columns are blanked; the row survives so the owner keeps the slot record. */
        ANONYMISE_IN_PLACE,
        /** Rows are deleted outright. */
        DELETE_ROWS,
        /** Nothing explicit: an FK ON DELETE CASCADE removes these rows with their parent. */
        CASCADES,
        /** Removed on a fixed schedule regardless of any request. */
        AGE_PURGE,
        /** No personal data. */
        NOT_PERSONAL
    }

    /**
     * One table's classification. {@code columns} is EVERY column the table has — that total is
     * what the guard test diffs against the live schema, so a new column cannot slip past
     * unclassified. {@code personalColumns} is the subset carrying personal data.
     */
    public record Classified(
            String table, Set<String> columns, Set<String> personalColumns, Subject subject, EraseRoute route) {}

    /** A place calit sends personal data that it may not be able to reach back into. */
    public record Destination(String name, String when, boolean reachableByErasure) {}

    public static final List<Classified> TABLES = List.of(
            // --- invitee data -------------------------------------------------------------------
            new Classified(
                    "booking",
                    Set.of(/* paste every booking column here */ ),
                    // title/description are invitee-writable via POST /booking/{t}/edit-details, so
                    // they are free text about the invitee -- classified personal even though the
                    // design spec's table omitted them. Nulling them falls back to the meeting
                    // type's own name/description, so the owner's record stays readable.
                    Set.of("invitee_name", "invitee_email", "answers", "meet_link", "title", "description"),
                    Subject.INVITEE,
                    EraseRoute.ANONYMISE_IN_PLACE),
            new Classified(
                    "booking_guest",
                    Set.of(/* ... */ ),
                    Set.of("email"),
                    Subject.GUEST,
                    EraseRoute.DELETE_ROWS),
            new Classified("reminder", Set.of(/* ... */ ), Set.of(), Subject.NONE, EraseRoute.CASCADES),
            new Classified(
                    "email_outbox",
                    Set.of(/* ... incl. booking_id, owner_id from V34 */ ),
                    Set.of("recipient", "subject", "html_body", "ics_bytes"),
                    Subject.INVITEE,
                    EraseRoute.AGE_PURGE),
            // --- owner data ---------------------------------------------------------------------
            new Classified(
                    "app_user",
                    Set.of(/* ... */ ),
                    Set.of("username", "password_hash", "google_sub", "oidc_sub"),
                    Subject.OWNER,
                    EraseRoute.DELETE_ROWS),
            new Classified(
                    "owner_settings",
                    Set.of(/* ... incl. booking_retention_days from V34 */ ),
                    Set.of("owner_name", "owner_email", "timezone"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "google_credential",
                    Set.of(/* ... */ ),
                    Set.of("email", "google_sub", "access_token", "refresh_token"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "google_calendar",
                    Set.of(/* ... */ ),
                    Set.of("calendar_id", "summary"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "notification_channel",
                    Set.of(/* ... */ ),
                    Set.of("url", "label"),
                    Subject.OWNER,
                    EraseRoute.CASCADES),
            new Classified(
                    "password_reset_token",
                    Set.of(/* ... */ ),
                    Set.of("user_id", "token_hash"),
                    Subject.OWNER,
                    EraseRoute.AGE_PURGE),
            new Classified(
                    "login_ticket", Set.of(/* ... */ ), Set.of("user_id"), Subject.OWNER, EraseRoute.AGE_PURGE)
            // --- remaining owner-configuration tables: free text the owner wrote about themselves,
            //     all removed by the app_user cascade. One Classified entry each, personalColumns
            //     naming the free-text columns, route CASCADES:
            //     meeting_type, meeting_type_host, booking_field, availability_rule,
            //     date_override, date_override_window, notification_channel_meeting_type
            );

    /**
     * Where calit sends invitee data that it cannot always reach back into. The guard test cannot
     * check this list against anything — it exists so that a NEW outbound integration lands next to
     * the line recording whether erasure reaches it, and so the erase confirm page can state the
     * limits before the invitee clicks.
     */
    public static final List<Destination> OUTBOUND = List.of(
            new Destination(
                    "Google Calendar event",
                    "the owner has Google connected",
                    // Reachable via the API -- but Google keeps the event in trash for roughly 30
                    // days, and the call cannot run at all if the grant was since disconnected.
                    true),
            new Destination("Notification channel message", "the owner configured a channel", false),
            new Destination("Email already delivered over SMTP", "every booking", false),
            new Destination("The .ics in the invitee's and guests' own calendars", "every booking", false));

    /** Personal columns of {@code table}, or an empty set when the table is unknown. */
    public static Set<String> personalColumnsOf(String table) {
        return TABLES.stream()
                .filter(c -> c.table().equals(table))
                .findFirst()
                .map(Classified::personalColumns)
                .orElse(Set.of());
    }
}
```

- [ ] **Step 4: Iterate until the guard passes**

```bash
./mvnw -o test -Dtest=PersonalDataInventoryTest
```

The failure messages name the exact table and the exact set difference. Fix the inventory, rerun. Expected: PASS.

- [ ] **Step 5: Run the full suite**

```bash
./mvnw -o test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/privacy/PersonalData.java \
        src/test/java/site/asm0dey/calit/privacy/PersonalDataInventoryTest.java .beans/
git commit -m "feat(privacy): personal-data inventory with a schema guard test"
```

---

## Task 3: Tag outbox rows with their booking and owner

Erasure must clear the rendered mail immediately, not leave the invitee's name in `email_outbox.html_body` for thirty days. Matching on the recipient address is not an option — two owners can mail the same person.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/email/MailTag.java`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailOutbox.java`
- Modify: `src/main/java/site/asm0dey/calit/email/MailSender.java`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Create: `src/test/java/site/asm0dey/calit/email/OutboxTagTest.java`

**Interfaces:**
- Consumes: `EmailOutbox.bookingId` / `EmailOutbox.ownerId` from Task 1.
- Produces:
  - `MailTag` — `record MailTag(Long bookingId, Long ownerId)` with `static MailTag none()`, `static MailTag forBooking(Long bookingId, Long ownerId)`, `static MailTag forOwner(Long ownerId)`.
  - `EmailOutbox.enqueue(String recipient, String subject, String htmlBody, byte[] icsBytes, Instant notAfter, String error, MailTag tag)` — the existing six-arg form is kept, delegating with `MailTag.none()`.
  - `MailSender.send(String fromName, String to, String subject, String html, byte[] ics, Instant notAfter, MailTag tag)` — existing overloads delegate with `MailTag.none()`.
  - `EmailOutbox.deleteForBooking(Long bookingId)` and `EmailOutbox.deleteForOwner(Long ownerId)` — both `long`, rows deleted.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/OutboxTagTest.java`:

```java
package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OutboxTagTest {

    @Test
    @Transactional
    void enqueueStoresTheTag() {
        Long id = EmailOutbox.enqueue(
                "a@example.com", "s", "<p>hi</p>", null, null, "test", MailTag.forBooking(7L, 1L));
        EmailOutbox row = EmailOutbox.findById(id);
        assertEquals(7L, row.bookingId);
        assertEquals(1L, row.ownerId);
    }

    @Test
    @Transactional
    void untaggedEnqueueLeavesBothLinksNull() {
        Long id = EmailOutbox.enqueue("a@example.com", "s", "<p>hi</p>", null, null, "test");
        EmailOutbox row = EmailOutbox.findById(id);
        assertNull(row.bookingId);
        assertNull(row.ownerId);
    }

    @Test
    @Transactional
    void deleteForBookingTakesOnlyThatBookingsRows() {
        EmailOutbox.enqueue("a@example.com", "s", "<p>a</p>", null, null, "t", MailTag.forBooking(7L, 1L));
        EmailOutbox.enqueue("a@example.com", "s", "<p>b</p>", null, null, "t", MailTag.forBooking(8L, 2L));
        assertEquals(1L, EmailOutbox.deleteForBooking(7L));
        assertEquals(0L, EmailOutbox.count("bookingId", 7L));
        assertEquals(1L, EmailOutbox.count("bookingId", 8L));
    }

    @Test
    @Transactional
    void deleteForOwnerTakesOnlyThatOwnersRows() {
        EmailOutbox.enqueue("a@example.com", "s", "<p>a</p>", null, null, "t", MailTag.forOwner(1L));
        EmailOutbox.enqueue("a@example.com", "s", "<p>b</p>", null, null, "t", MailTag.forOwner(2L));
        assertEquals(1L, EmailOutbox.deleteForOwner(1L));
        assertEquals(1L, EmailOutbox.count("ownerId", 2L));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=OutboxTagTest
```

Expected: compile error — `MailTag` does not exist.

- [ ] **Step 3: Write `MailTag`**

Create `src/main/java/site/asm0dey/calit/email/MailTag.java`:

```java
package site.asm0dey.calit.email;

/**
 * What an outbox row is about, so erasure and account deletion can purge it by key instead of by
 * recipient address. Matching on the address is wrong: two owners can mail the same invitee, and
 * erasing one booking would take the other owner's mail with it.
 *
 * <p>Both fields nullable — a password-reset mail has an owner but no booking, and a mail parked
 * before V34 has neither.
 */
public record MailTag(Long bookingId, Long ownerId) {

    private static final MailTag NONE = new MailTag(null, null);

    /** No link recorded — the age purge is the only thing that clears these. */
    public static MailTag none() {
        return NONE;
    }

    public static MailTag forBooking(Long bookingId, Long ownerId) {
        return new MailTag(bookingId, ownerId);
    }

    public static MailTag forOwner(Long ownerId) {
        return new MailTag(null, ownerId);
    }
}
```

- [ ] **Step 4: Thread it through `EmailOutbox`**

In `src/main/java/site/asm0dey/calit/email/EmailOutbox.java`, replace the existing `enqueue` with an overload pair and add the two delete helpers:

```java
    /** Parks a failed send with no booking/owner link — see {@link #enqueue(String, String, String, byte[], Instant, String, MailTag)}. */
    public static Long enqueue(
            String recipient, String subject, String htmlBody, byte[] icsBytes, Instant notAfter, String error) {
        return enqueue(recipient, subject, htmlBody, icsBytes, notAfter, error, MailTag.none());
    }

    /**
     * Parks a failed send. Must run inside a transaction (caller opens requiringNew). Returns the new id.
     * {@code notAfter} null = no usefulness deadline; non-null = stop retrying once that instant passes
     * (so a time-limited mail like a reset link isn't delivered dead). {@code tag} records what the mail
     * is about so erasure can clear it without waiting for the 30-day age purge.
     */
    public static Long enqueue(
            String recipient,
            String subject,
            String htmlBody,
            byte[] icsBytes,
            Instant notAfter,
            String error,
            MailTag tag) {
        var r = new EmailOutbox();
        r.recipient = recipient;
        r.subject = subject;
        r.htmlBody = htmlBody;
        r.icsBytes = icsBytes;
        r.attempts = 0;
        r.lastError = error;
        r.notAfter = notAfter;
        r.nextAttemptAt = Instant.now(); // due immediately
        r.sentAt = null;
        r.createdAt = Instant.now();
        r.bookingId = tag.bookingId();
        r.ownerId = tag.ownerId();
        r.persist();
        return r.id;
    }

    /** Drops every parked mail about one booking. Returns the row count. */
    public static long deleteForBooking(Long bookingId) {
        return delete("bookingId", bookingId);
    }

    /** Drops every parked mail belonging to one owner. Returns the row count. */
    public static long deleteForOwner(Long ownerId) {
        return delete("ownerId", ownerId);
    }
```

- [ ] **Step 5: Thread it through `MailSender`**

In `src/main/java/site/asm0dey/calit/email/MailSender.java`, keep both existing `send` overloads delegating, and add the tagged form:

```java
    /** Try direct; on any failure, durably queue to the outbox for retry (no usefulness deadline). */
    public void send(String fromName, String to, String subject, String html, byte[] ics) {
        send(fromName, to, subject, html, ics, null, MailTag.none());
    }

    public void send(String fromName, String to, String subject, String html, byte[] ics, Instant notAfter) {
        send(fromName, to, subject, html, ics, notAfter, MailTag.none());
    }

    public void send(String fromName, String to, String subject, String html, byte[] ics, MailTag tag) {
        send(fromName, to, subject, html, ics, null, tag);
    }

    /**
     * Try direct; on any failure, durably queue to the outbox for retry. Never throws.
     * {@code notAfter} non-null bounds retry: a queued mail is dropped undelivered once that instant
     * passes (e.g. a reset link whose token has expired — delivering it would only hand over a dead
     * link). {@code tag} records the booking/owner the mail is about so erasure can purge it by key.
     * ponytail: the outbox does not persist {@code fromName}; a retried mail sends with the
     * config-default From (cosmetic only).
     */
    public void send(
            String fromName, String to, String subject, String html, byte[] ics, Instant notAfter, MailTag tag) {
        try {
            sendNow(fromName, to, subject, html, ics);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew()
                    .run(() -> EmailOutbox.enqueue(to, subject, html, ics, notAfter, e.getMessage(), tag));
            Log.warnf(e, "SMTP send failed, queued to outbox: to=%s subject=%s", to, subject);
        }
    }
```

- [ ] **Step 6: Tag the booking mails in `EmailService`**

`MailSink` (`src/main/java/site/asm0dey/calit/email/EmailService.java:274`) is the seam every booking mail goes through, and the `BookingSnapshot` the callers already hold carries both ids. Widen the interface by one parameter:

```java
    /** Where a rendered mail goes: either a direct SMTP send or an outbox enqueue. */
    @FunctionalInterface
    private interface MailSink {
        void deliver(String fromName, String to, String subject, String html, byte[] ics, MailTag tag);
    }

    /**
     * In-transaction sink: persist the rendered mail to the outbox (a fast INSERT, no SMTP) so it
     * commits atomically with the caller's transaction. OutboxScheduler delivers it with retry/backoff.
     * Static so it can be used as a method reference with no captured state.
     */
    private static void enqueueToOutbox(
            String fromName, String to, String subject, String html, byte[] ics, MailTag tag) {
        EmailOutbox.enqueue(to, subject, html, ics, null, "scheduled dispatch (transactional outbox)", tag);
    }
```

Then, at each `sink.deliver(...)` call site (lines ~805, ~818, ~833) and each direct `mailSender.send(...)` for a booking mail (lines ~620, ~663, ~693, ~709), append
`MailTag.forBooking(l.booking().id, l.booking().ownerId)` — `l` is the `BookingSnapshot` already in scope. For the three non-booking mails, pass the owner when it is known and `MailTag.none()` when it is not:

| Call site | Tag |
|---|---|
| `sendPasswordReset` (line ~81) | `MailTag.none()` — the service only has the address, not the user id |
| `sendInvite` (line ~96) | `MailTag.none()` — same |
| `sendGoogleDisconnected` (line ~111) | `MailTag.none()` — same |

These three keep the untagged overload and are cleared by the 30-day age purge, which is the right answer for a mail whose whole content is a one-time link. Do not widen their signatures to chase an owner id.

- [ ] **Step 7: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=OutboxTagTest
./mvnw -o test
```

Expected: both PASS / `BUILD SUCCESS`.

- [ ] **Step 8: Commit and open PR 1**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/email/ src/test/java/site/asm0dey/calit/email/OutboxTagTest.java .beans/
git commit -m "feat(privacy): key parked mails to their booking and owner"
git push -u origin gdpr-compliance
gh pr create --title "feat(privacy): data inventory, schema guard and outbox links" --body "..."
```

Include the `show-me` diagram of the inventory → guard-test relationship in the PR body.

---

## Task 4: `PrivacyService.anonymise` and the erasure boundary report

**Files:**
- Create: `src/main/java/site/asm0dey/calit/privacy/PrivacyService.java`
- Create: `src/main/java/site/asm0dey/calit/privacy/ErasureReport.java`
- Create: `src/main/java/site/asm0dey/calit/privacy/PrivacyConfig.java`
- Create: `src/test/java/site/asm0dey/calit/privacy/BookingErasureTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `.env.example`

**Interfaces:**
- Consumes: `Booking.erasedAt` (Task 1), `EmailOutbox.deleteForBooking` (Task 3), `PersonalData` (Task 2), existing `BookingService.cancel(String manageToken)` and `CalendarPort.isConnected(Long ownerId)`.
- Produces:
  - `ErasureReport` — `record ErasureReport(GoogleOutcome google, boolean channelsWereUsed, boolean mailWasDelivered)` with nested `enum GoogleOutcome { NOT_APPLICABLE, REMOVED, UNREACHABLE }`.
  - `PrivacyService.anonymise(Long bookingId)` — `void`, `@Transactional`. The retention path. Blanks the personal columns, deletes guests, deletes unsent reminders, deletes parked mail, stamps `erasedAt`. Idempotent: a second call on an already-erased booking is a no-op.
  - `PrivacyService.eraseByManageToken(String manageToken)` — `ErasureReport`. The invitee path: cancels first when the booking is still upcoming (so Google and the owner's mail follow the normal cancel route), then anonymises.
  - `PrivacyConfig.inviteeErasureEnabled()` — `boolean`.
  - `PrivacyConfig.bookingRetentionDays()` — `Optional<Integer>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/privacy/BookingErasureTest.java`:

```java
package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailTag;
import site.asm0dey.calit.scheduler.Reminder;

@QuarkusTest
class BookingErasureTest {

    /** The seeded admin owner — DatabaseResetCallback guarantees id 1. */
    private static final Long OWNER = 1L;

    @Inject
    PrivacyService privacy;

    /** A CONFIRMED booking in the past, with a guest, a parked mail and an unsent reminder. */
    private Long seedPastBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var b = new Booking();
            b.ownerId = OWNER;
            b.meetingTypeId = firstMeetingTypeId();
            b.inviteeName = "Dana Vogel";
            b.inviteeEmail = "dana@example.com";
            b.answers = new java.util.HashMap<>(Map.of("why", "annual review"));
            b.title = "Dana's slot";
            b.description = "notes from Dana";
            b.meetLink = "https://meet.google.com/abc-defg-hij";
            b.startUtc = Instant.now().minus(30, ChronoUnit.DAYS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now().minus(31, ChronoUnit.DAYS);
            b.manageToken = UUID.randomUUID().toString();
            b.persist();

            var g = new BookingGuest();
            g.ownerId = OWNER;
            g.bookingId = b.id;
            g.email = "guest@example.com";
            g.status = GuestStatus.INVITED;
            g.declineToken = UUID.randomUUID().toString();
            g.createdAt = Instant.now();
            g.persist();

            var r = new Reminder();
            r.bookingId = b.id;
            r.sendAt = Instant.now().plus(1, ChronoUnit.DAYS);
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();

            EmailOutbox.enqueue(
                    "dana@example.com",
                    "Your booking",
                    "<p>Hi Dana Vogel</p>",
                    null,
                    null,
                    "seed",
                    MailTag.forBooking(b.id, OWNER));
            return b.id;
        });
    }

    private static Long firstMeetingTypeId() {
        return site.asm0dey.calit.domain.MeetingType.<site.asm0dey.calit.domain.MeetingType>find(
                        "ownerId", OWNER)
                .firstResult()
                .id;
    }

    @Test
    void anonymiseBlanksEveryPersonalColumn() {
        Long id = seedPastBooking();
        privacy.anonymise(id);

        Booking b = QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findById(id));
        assertEquals("", b.inviteeName);
        assertEquals("", b.inviteeEmail, "invitee_email is NOT NULL, so erasure blanks it");
        assertTrue(b.answers.isEmpty());
        assertNull(b.meetLink);
        assertNull(b.title);
        assertNull(b.description);
        assertNotNull(b.erasedAt);
        assertEquals(BookingStatus.CONFIRMED, b.status, "the slot record survives erasure");
    }

    @Test
    void anonymiseRemovesGuestsRemindersAndParkedMail() {
        Long id = seedPastBooking();
        privacy.anonymise(id);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0L, BookingGuest.count("bookingId", id));
            assertEquals(0L, Reminder.count("bookingId = ?1 and sentAt is null", id));
            assertEquals(0L, EmailOutbox.count("bookingId", id));
        });
    }

    @Test
    void anonymiseIsIdempotent() {
        Long id = seedPastBooking();
        privacy.anonymise(id);
        Instant first = QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findById(id).erasedAt);
        privacy.anonymise(id);
        Instant second = QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findById(id).erasedAt);
        assertEquals(first, second, "a second erasure must not restamp the row");
    }

    @Test
    void erasingAPastBookingDoesNotTouchGoogle() {
        Long id = seedPastBooking();
        String token = QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findById(id).manageToken);

        ErasureReport report = privacy.eraseByManageToken(token);
        assertEquals(
                ErasureReport.GoogleOutcome.NOT_APPLICABLE,
                report.google(),
                "no Google event id on this row, and Google is disabled in %test");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=BookingErasureTest
```

Expected: compile error — `PrivacyService` does not exist.

- [ ] **Step 3: Write `ErasureReport`**

Create `src/main/java/site/asm0dey/calit/privacy/ErasureReport.java`:

```java
package site.asm0dey.calit.privacy;

/**
 * Per-destination outcome of one erasure. The done page reports this rather than a blanket
 * success: calit sends invitee data to places it cannot reach back into, and the invitee is owed
 * an honest answer about which copies are actually gone.
 */
public record ErasureReport(GoogleOutcome google, boolean channelsWereUsed, boolean mailWasDelivered) {

    public enum GoogleOutcome {
        /** No Google event existed for this booking (degraded mode, or already cancelled). */
        NOT_APPLICABLE,
        /**
         * The delete call was made. Google keeps the event in the calendar's trash for roughly
         * 30 days afterwards — "removed" is not "unrecoverable".
         */
        REMOVED,
        /** An event id was recorded but the grant is gone, so the remote copy could not be touched. */
        UNREACHABLE
    }

    /** True when at least one copy is known to be beyond calit's reach — the done page says so. */
    public boolean hasUnreachableCopies() {
        return google == GoogleOutcome.UNREACHABLE || channelsWereUsed || mailWasDelivered;
    }
}
```

- [ ] **Step 4: Write `PrivacyConfig`**

Create `src/main/java/site/asm0dey/calit/privacy/PrivacyConfig.java`:

```java
package site.asm0dey.calit.privacy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The two privacy knobs an operator can turn. There is deliberately no global "GDPR mode" switch:
 * the regulation's territorial scope follows the data subject, not the server, so a switch would
 * remove the tools without removing the obligation.
 */
@ApplicationScoped
public class PrivacyConfig {

    final boolean inviteeErasure;

    final Optional<Integer> retentionDays;

    @Inject
    public PrivacyConfig(
            @ConfigProperty(name = "calit.privacy.invitee-erasure", defaultValue = "true") boolean inviteeErasure,
            @ConfigProperty(name = "calit.retention.booking-days") Optional<Integer> retentionDays) {
        this.inviteeErasure = inviteeErasure;
        this.retentionDays = retentionDays;
    }

    /** When false, the manage page shows the operator's contact address instead of the erase button. */
    public boolean inviteeErasureEnabled() {
        return inviteeErasure;
    }

    /** Instance-wide retention window. Empty = keep bookings forever, which is the default. */
    public Optional<Integer> bookingRetentionDays() {
        return retentionDays.filter(d -> d > 0);
    }
}
```

- [ ] **Step 5: Write `PrivacyService`**

Create `src/main/java/site/asm0dey/calit/privacy/PrivacyService.java`:

```java
package site.asm0dey.calit.privacy;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.HashMap;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.notify.NotificationChannel;
import site.asm0dey.calit.scheduler.Reminder;

/**
 * Every read and write that treats personal data AS personal data: erasure, export, account
 * deletion, retention. Explicit Panache queries per table, deliberately — see {@link PersonalData}
 * for why there is no registry interface and no reflection here.
 */
@ApplicationScoped
public class PrivacyService {

    final BookingService bookingService;

    final CalendarPort calendarPort;

    @Inject
    public PrivacyService(BookingService bookingService, CalendarPort calendarPort) {
        this.bookingService = bookingService;
        this.calendarPort = calendarPort;
    }

    /**
     * Blanks one booking's invitee data in place. The ROW survives: the owner keeps a
     * "someone booked 14:00-14:30" record, which is the legitimate-interest half of the balance,
     * and {@code erasedAt} is what 404s the invitee-facing routes afterwards.
     *
     * <p>{@code invitee_email} is NOT NULL, so it becomes an empty string rather than null. That
     * also means the per-email abuse cap ({@code idx_booking_email_created}) can never match an
     * erased row against a real address.
     *
     * <p>Idempotent: an already-erased booking is left exactly as it was, so the retention
     * scheduler cannot restamp a row the invitee erased last week.
     */
    @Transactional
    public void anonymise(Long bookingId) {
        Booking b = Booking.findById(bookingId);
        if (b == null || b.isErased()) {
            return;
        }
        b.inviteeName = "";
        b.inviteeEmail = "";
        b.answers = new HashMap<>();
        b.meetLink = null;
        b.title = null; // invitee-writable free text; falls back to the meeting type's name
        b.description = null;
        b.erasedAt = Instant.now();
        BookingGuest.delete("bookingId", bookingId);
        Reminder.delete("bookingId = ?1 and sentAt is null", bookingId);
        EmailOutbox.deleteForBooking(bookingId);
        Log.infof("PRIVACY erasure booking=%d", bookingId);
    }

    /**
     * The invitee's own erasure, keyed by the manage token that already proves control of the
     * booking — no new identity verification is introduced.
     *
     * <p>An UPCOMING booking is cancelled first, through the ordinary cancel path, so the Google
     * event is deleted and the owner gets the normal cancellation mail. A past booking is
     * anonymised directly: there is nothing left to cancel.
     */
    public ErasureReport eraseByManageToken(String manageToken) {
        Booking b = QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findByManageToken(manageToken));
        if (b == null || b.isErased()) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        boolean hadGoogleEvent = b.googleEventId != null;
        boolean googleReachable = hadGoogleEvent && calendarPort.isConnected(b.ownerId);

        if (isUpcomingAndHeld(b)) {
            bookingService.cancel(manageToken); // deletes the Google event, mails the owner
        }
        anonymise(b.id);

        var google = !hadGoogleEvent
                ? ErasureReport.GoogleOutcome.NOT_APPLICABLE
                : googleReachable ? ErasureReport.GoogleOutcome.REMOVED : ErasureReport.GoogleOutcome.UNREACHABLE;
        var report = new ErasureReport(
                google, NotificationChannel.count("ownerId", b.ownerId) > 0, /* mail already delivered */ true);
        // Proof of handling: one log line, booking id plus per-destination outcome. No erasure_log
        // table until an operator actually needs an audit trail (ponytail).
        Log.infof(
                "PRIVACY erasure booking=%d google=%s channels=%s mail=%s",
                b.id, report.google(), report.channelsWereUsed(), report.mailWasDelivered());
        return report;
    }

    private static boolean isUpcomingAndHeld(Booking b) {
        return b.endUtc.isAfter(Instant.now())
                && (b.status == BookingStatus.PENDING || b.status == BookingStatus.CONFIRMED);
    }
}
```

- [ ] **Step 6: Add the config properties**

In `src/main/resources/application.properties`, beside the other `calit.*` blocks:

```properties
# --- Privacy / GDPR -----------------------------------------------------------------------------
# Invitee self-service erasure on the manage link. Default ON: calit ships the tool because the
# operator owes the response either way -- switching this off does not remove the obligation, it
# only makes the operator answer requests by hand (the manage page then shows their contact address).
calit.privacy.invitee-erasure=${INVITEE_ERASURE:true}

# Instance-wide booking retention window in days. UNSET = keep bookings forever, so upgrading
# changes nothing until an operator opts in. Each owner may tighten it in /me/settings.
calit.retention.booking-days=${BOOKING_RETENTION_DAYS:}
```

In `.env.example`, in the same order the README documents:

```bash
# Privacy / GDPR
# INVITEE_ERASURE=true            # invitee self-service erasure on the manage link (default true)
# BOOKING_RETENTION_DAYS=730      # anonymise bookings this many days after they end; unset = keep forever
```

- [ ] **Step 7: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=BookingErasureTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply
git add src/main/java/site/asm0dey/calit/privacy/ \
        src/test/java/site/asm0dey/calit/privacy/BookingErasureTest.java \
        src/main/resources/application.properties .env.example .beans/
git commit -m "feat(privacy): anonymise a booking and report the erasure boundary"
```

---

## Task 5: Invitee erasure routes, pages and copy

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java`
- Create: `src/main/resources/templates/PublicResource/eraseConfirm.html`
- Create: `src/main/resources/templates/PublicResource/erased.html`
- Modify: `src/main/resources/templates/PublicResource/manage.html`
- Modify: `src/main/resources/templates/AdminResource/dashboard.html`
- Modify: `src/main/resources/templates/AdminResource/pending.html`
- Modify: `src/main/resources/templates/AdminResource/manageBooking.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`
- Modify: `src/main/resources/messages/msg_de.properties`, `adm_de.properties`
- Create: `src/test/java/site/asm0dey/calit/privacy/InviteeErasureRouteTest.java`
- Create: `src/test/java/site/asm0dey/calit/privacy/InviteeErasureDisabledTest.java`

**Interfaces:**
- Consumes: `PrivacyService.eraseByManageToken` and `PrivacyConfig.inviteeErasureEnabled()` (Task 4), `Booking.isErased()` (Task 1), `SiteInfo.getContactEmail()`.
- Produces: routes `GET /booking/{manageToken}/erase` and `POST /booking/{manageToken}/erase`; the invariant that **every** invitee-facing route on an erased booking returns 404.

- [ ] **Step 1: Write the failing route test**

Create `src/test/java/site/asm0dey/calit/privacy/InviteeErasureRouteTest.java`:

```java
package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * CSRF is disabled in %test, so these POSTs carry no token; production forms must still render
 * {inject:csrf.token} or they 400 (see the global constraints).
 */
@QuarkusTest
class InviteeErasureRouteTest {

    /** Seeds a past booking and returns its manage token. Mirrors BookingErasureTest's fixture. */
    private String seedToken() {
        return ErasureFixtures.seedPastBooking();
    }

    @Test
    void confirmPageStatesTheBoundaryBeforeTheClick() {
        String token = seedToken();
        given().when()
                .get("/booking/" + token + "/erase")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_ERASE_CONFIRM"));
    }

    @Test
    void manageHubOffersErasure() {
        String token = seedToken();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/booking/" + token + "/erase"));
    }

    @Test
    void erasingRendersTheDonePageAndDropsTheName() {
        String token = seedToken();
        given().when()
                .post("/booking/" + token + "/erase")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_ERASED"))
                .body(not(containsString("Dana Vogel")));
    }

    @Test
    void everyInviteeRouteIs404AfterErasure() {
        String token = seedToken();
        given().when().post("/booking/" + token + "/erase").then().statusCode(200);

        for (String path : new String[] {"/manage", "/invite.ics", "/cancel", "/erase"}) {
            given().when().get("/booking/" + token + path).then().statusCode(404);
        }
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/erase").then().statusCode(404);
    }
}
```

Create the shared fixture `src/test/java/site/asm0dey/calit/privacy/ErasureFixtures.java` by lifting `seedPastBooking()` out of `BookingErasureTest` (Task 4) into a package-private static helper, and have `BookingErasureTest` call it too. Do this as part of this step so the two tests cannot drift.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=InviteeErasureRouteTest
```

Expected: FAIL — `expected status code <200> but was <404>` on the confirm page.

- [ ] **Step 3: Add the message keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, after the `pub_cancel_keep_btn()` block:

```java
    // ---- Invitee erasure (eraseConfirm.html / erased.html) ----

    @Message("Erase your data")
    String pub_erase_confirm_title();

    @Message("Erase your data from this booking?")
    String pub_erase_confirm_h1();

    @Message(
            "This removes your name, email address, answers and guest list from this booking. "
                    + "The host keeps a record that the slot was taken, with no personal details. "
                    + "It can't be undone.")
    String pub_erase_confirm_desc();

    @Message("An upcoming booking is cancelled first, so the host is notified and the slot is freed.")
    String pub_erase_confirm_cancels_first();

    @Message("What erasure cannot reach")
    String pub_erase_boundary_h2();

    @Message("A calendar event on the host's Google Calendar stays in their trash for about 30 days.")
    String pub_erase_boundary_google();

    @Message("A chat notification the host already received (Telegram, Slack, Discord) cannot be recalled.")
    String pub_erase_boundary_channels();

    @Message("Emails already delivered, and the calendar entry in your own calendar, stay where they are.")
    String pub_erase_boundary_mail();

    @Message("Erase my data")
    String pub_erase_confirm_btn();

    @Message("Keep my data")
    String pub_erase_keep_btn();

    @Message("Your data has been erased")
    String pub_erased_title();

    @Message("Your data has been erased")
    String pub_erased_h1();

    @Message("Here is what happened to each copy:")
    String pub_erased_desc();

    @Message("Removed from this site")
    String pub_erased_local_ok();

    @Message("The host's calendar event was deleted (Google keeps it in trash for about 30 days).")
    String pub_erased_google_removed();

    @Message("The host's calendar event could not be reached — ask the operator to remove it.")
    String pub_erased_google_unreachable();

    @Message("No calendar event existed for this booking.")
    String pub_erased_google_none();

    @Message("A chat notification the host already received cannot be recalled.")
    String pub_erased_channels();

    @Message("Emails already delivered cannot be recalled.")
    String pub_erased_mail();

    @Message("For the copies calit cannot reach, contact the operator at {0}.")
    String pub_erased_contact(String contactEmail);

    @Message("Back to the booking page")
    String pub_erased_btn();

    @Message("To have your data erased, contact the operator at {0}.")
    String pub_erase_disabled_notice(String contactEmail);

    @Message("To have your data erased, contact the operator of this site.")
    String pub_erase_disabled_notice_no_contact();
```

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, one key for the owner's list placeholder:

```java
    @Message("(erased at the invitee's request)")
    String adm_booking_invitee_erased();
```

German, in `src/main/resources/messages/msg_de.properties` (append at the position matching the interface order):

```properties
pub_erase_confirm_title=Ihre Daten löschen
pub_erase_confirm_h1=Ihre Daten aus dieser Buchung löschen?
pub_erase_confirm_desc=Dadurch werden Ihr Name, Ihre E-Mail-Adresse, Ihre Antworten und die Gästeliste aus dieser Buchung entfernt. Der Gastgeber behält einen Eintrag darüber, dass der Termin belegt war, ohne persönliche Angaben. Dies kann nicht rückgängig gemacht werden.
pub_erase_confirm_cancels_first=Eine bevorstehende Buchung wird zuerst storniert, damit der Gastgeber benachrichtigt und der Termin freigegeben wird.
pub_erase_boundary_h2=Was die Löschung nicht erreichen kann
pub_erase_boundary_google=Ein Kalendereintrag im Google Kalender des Gastgebers bleibt etwa 30 Tage im Papierkorb.
pub_erase_boundary_channels=Eine Chat-Benachrichtigung, die der Gastgeber bereits erhalten hat (Telegram, Slack, Discord), kann nicht zurückgerufen werden.
pub_erase_boundary_mail=Bereits zugestellte E-Mails und der Kalendereintrag in Ihrem eigenen Kalender bleiben bestehen.
pub_erase_confirm_btn=Meine Daten löschen
pub_erase_keep_btn=Meine Daten behalten
pub_erased_title=Ihre Daten wurden gelöscht
pub_erased_h1=Ihre Daten wurden gelöscht
pub_erased_desc=Das ist mit jeder Kopie geschehen:
pub_erased_local_ok=Von dieser Website entfernt
pub_erased_google_removed=Der Kalendereintrag des Gastgebers wurde gelöscht (Google behält ihn etwa 30 Tage im Papierkorb).
pub_erased_google_unreachable=Der Kalendereintrag des Gastgebers konnte nicht erreicht werden – bitten Sie den Betreiber, ihn zu entfernen.
pub_erased_google_none=Für diese Buchung gab es keinen Kalendereintrag.
pub_erased_channels=Eine Chat-Benachrichtigung, die der Gastgeber bereits erhalten hat, kann nicht zurückgerufen werden.
pub_erased_mail=Bereits zugestellte E-Mails können nicht zurückgerufen werden.
pub_erased_contact=Für die Kopien, die calit nicht erreichen kann, wenden Sie sich an den Betreiber unter {0}.
pub_erased_btn=Zurück zur Buchungsseite
pub_erase_disabled_notice=Wenden Sie sich zur Löschung Ihrer Daten an den Betreiber unter {0}.
pub_erase_disabled_notice_no_contact=Wenden Sie sich zur Löschung Ihrer Daten an den Betreiber dieser Website.
```

German, in `src/main/resources/messages/adm_de.properties`:

```properties
adm_booking_invitee_erased=(auf Wunsch der eingeladenen Person gelöscht)
```

Hebrew is **not** added here — Task 12 files the tracking issue. Leave `msg_he.properties` and `adm_he.properties` untouched for these keys; the English `@Message` default is what a Hebrew viewer sees, and the issue records the gap.

- [ ] **Step 4: Write the two templates**

Create `src/main/resources/templates/PublicResource/eraseConfirm.html`:

```html
{@java.lang.String title}
{@site.asm0dey.calit.booking.Booking booking}
{@site.asm0dey.calit.domain.MeetingType type}
{@java.lang.String meetingName}
{@java.lang.Boolean upcoming}
{@java.lang.String tzScript}
{#include base title=title}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <!-- CALIT_ERASE_CONFIRM -->
    <div class="card-body items-start gap-3">
      <h1 class="text-2xl font-bold">{msg:pub_erase_confirm_h1}</h1>
      <p class="text-base-content/70">{msg:pub_erase_confirm_desc}</p>
      {#if upcoming}<p class="text-base-content/70">{msg:pub_erase_confirm_cancels_first}</p>{/if}
      <ul class="list-disc ms-5">
        <li><strong>{msg:pub_booking_meeting_label}</strong> {meetingName}</li>
        <li><strong>{msg:pub_booking_when_label}</strong> <time data-utc="{booking.startUtc}">{booking.startUtc} UTC</time></li>
      </ul>
      <h2 class="text-lg font-semibold mt-2">{msg:pub_erase_boundary_h2}</h2>
      <ul class="list-disc ms-5 text-base-content/70">
        <li>{msg:pub_erase_boundary_google}</li>
        <li>{msg:pub_erase_boundary_channels}</li>
        <li>{msg:pub_erase_boundary_mail}</li>
      </ul>
      <div class="flex gap-2">
        <form method="post" action="/booking/{booking.manageToken}/erase"><input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}"><button type="submit" class="btn btn-error">{msg:pub_erase_confirm_btn}</button></form>
        <a class="btn btn-ghost" href="/booking/{booking.manageToken}/manage">{msg:pub_erase_keep_btn}</a>
      </div>
    </div>
  </div>
  {tzScript.raw}
{/include}
```

Create `src/main/resources/templates/PublicResource/erased.html`:

```html
{@java.lang.String title}
{@site.asm0dey.calit.privacy.ErasureReport report}
{@java.lang.String contactEmail}
{#include base title=title}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <!-- CALIT_ERASED -->
    <div class="card-body items-start gap-2">
      <h1 class="text-2xl font-bold">{msg:pub_erased_h1}</h1>
      <p class="text-base-content/70">{msg:pub_erased_desc}</p>
      <ul class="list-disc ms-5">
        <li>{msg:pub_erased_local_ok}</li>
        {#if report.google.name == 'REMOVED'}<li>{msg:pub_erased_google_removed}</li>{/if}
        {#if report.google.name == 'UNREACHABLE'}<li class="text-warning">{msg:pub_erased_google_unreachable}</li>{/if}
        {#if report.google.name == 'NOT_APPLICABLE'}<li>{msg:pub_erased_google_none}</li>{/if}
        {#if report.channelsWereUsed}<li>{msg:pub_erased_channels}</li>{/if}
        {#if report.mailWasDelivered}<li>{msg:pub_erased_mail}</li>{/if}
      </ul>
      {#if contactEmail && report.hasUnreachableCopies}
      <p class="mt-2">{msg:pub_erased_contact(contactEmail)}</p>
      {/if}
      <a class="btn btn-primary mt-2" href="/">{msg:pub_erased_btn}</a>
    </div>
  </div>
{/include}
```

- [ ] **Step 5: Add the routes**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`, add the two template declarations to the inner `Templates` class:

```java
        public static native TemplateInstance eraseConfirm(
                String title, Booking booking, MeetingType type, String meetingName, boolean upcoming, String tzScript);

        public static native TemplateInstance erased(String title, ErasureReport report, String contactEmail);
```

Inject `PrivacyService privacy`, `PrivacyConfig privacyConfig` and `SiteInfo siteInfo` through the existing constructor, then add beside the cancel pair:

```java
    /**
     * Art. 17 erasure, keyed by the manage token that already proves control of this booking — the
     * same authorization every other invitee route uses, so no new identity check is introduced.
     * 404 when the toggle is off: the manage page then shows the operator's contact address instead
     * of the button, and this route must not remain as a way around that.
     */
    @GET
    @Path("/booking/{manageToken}/erase")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance eraseConfirmPage(@PathParam("manageToken") String manageToken) {
        var m = messages.forLocale(activeLocale.current());
        Booking booking = requireErasableBooking(manageToken);
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        boolean upcoming = booking.endUtc.isAfter(Instant.now())
                && (booking.status == BookingStatus.PENDING || booking.status == BookingStatus.CONFIRMED);
        return Templates.eraseConfirm(
                m.pub_erase_confirm_title(),
                booking,
                type,
                booking.effectiveTitle(type),
                upcoming,
                Layout.TZ_SCRIPT);
    }

    @POST
    @Path("/booking/{manageToken}/erase")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance erase(@PathParam("manageToken") String manageToken) {
        var m = messages.forLocale(activeLocale.current());
        requireErasableBooking(manageToken);
        ErasureReport report = privacy.eraseByManageToken(manageToken);
        return Templates.erased(m.pub_erased_title(), report, siteInfo.getContactEmail());
    }

    /**
     * The booking behind an erasure request, or 404. Covers three cases with one answer: an unknown
     * token, an already-erased booking (its data is gone, so there is nothing to confirm or repeat),
     * and the operator having switched invitee erasure off.
     */
    private Booking requireErasableBooking(String manageToken) {
        if (!privacyConfig.inviteeErasureEnabled()) {
            throw new NotFoundException("Invitee erasure is disabled on this deployment");
        }
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null || booking.isErased()) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        return booking;
    }
```

- [ ] **Step 6: 404 the other invitee routes on an erased booking**

`manage`, `inviteIcs`, `cancelConfirmPage`, `cancelBooking`, `rescheduleBooking` and `editDetails` all load by manage token. Extend each null check — the erased row must behave exactly like a token that was never issued:

```java
        if (booking == null || booking.isErased()) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
```

In `cancelBooking` and `rescheduleBooking`, which call `bookingService` by token rather than loading the row first, add the lookup and the same guard before the service call.

- [ ] **Step 7: Put the button on the manage hub**

In `src/main/resources/templates/PublicResource/manage.html`, add a parameter line at the top:

```html
{@java.lang.Boolean erasureEnabled}
{@java.lang.String contactEmail}
```

and, next to the existing cancel link:

```html
      {#if erasureEnabled}
      <a class="btn btn-ghost btn-sm text-error" href="/booking/{booking.manageToken}/erase">{msg:pub_erase_confirm_btn}</a>
      {#else}
      <p class="text-sm text-base-content/70">{#if contactEmail}{msg:pub_erase_disabled_notice(contactEmail)}{#else}{msg:pub_erase_disabled_notice_no_contact}{/if}</p>
      {/if}
```

Add both parameters to `Templates.manage(...)` and to the `renderManage` call in `PublicResource`, passing `privacyConfig.inviteeErasureEnabled()` and `siteInfo.getContactEmail()`.

- [ ] **Step 8: Render the owner-side placeholder**

An erased booking must not show as a blank name in `/me`. In each of the three owner templates, wrap the invitee line:

`AdminResource/dashboard.html:49` and `AdminResource/pending.html:15`:

```html
        <p>{#if b.erased}<em class="text-base-content/60">{adm:adm_booking_invitee_erased}</em>{#else}<strong>{b.inviteeName}</strong> ({b.inviteeEmail}){/if}</p>
```

`AdminResource/manageBooking.html:24`:

```html
        <p><strong>{msg:pub_manage_for_label}</strong> {#if booking.erased}<em class="text-base-content/60">{adm:adm_booking_invitee_erased}</em>{#else}{booking.inviteeName} ({booking.inviteeEmail}){/if}</p>
```

Qute resolves `{b.erased}` to the `isErased()` getter added in Task 1.

- [ ] **Step 9: Write the toggle-off test**

Create `src/test/java/site/asm0dey/calit/privacy/InviteeErasureDisabledTest.java`:

```java
package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** INVITEE_ERASURE=false: the button is replaced by the operator's contact, and the route is gone. */
@QuarkusTest
@TestProfile(InviteeErasureDisabledTest.Off.class)
class InviteeErasureDisabledTest {

    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.privacy.invitee-erasure", "false",
                    "app.privacy-contact", "privacy@example.com");
        }
    }

    @Test
    void manageHubShowsTheOperatorContactInsteadOfTheButton() {
        String token = ErasureFixtures.seedPastBooking();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("privacy@example.com"))
                .body(not(containsString("/erase")));
    }

    @Test
    void theRouteIsGone() {
        String token = ErasureFixtures.seedPastBooking();
        given().when().get("/booking/" + token + "/erase").then().statusCode(404);
        given().when().post("/booking/" + token + "/erase").then().statusCode(404);
    }
}
```

A `@TestProfile` triggers an in-JVM Quarkus restart — expect this class to add a few seconds to the suite.

- [ ] **Step 10: Run both tests, then the full suite**

```bash
./mvnw -o test -Dtest='InviteeErasure*Test'
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 11: Commit**

```bash
mvn spotless:apply && bun run format:fe
git add src/main/java src/main/resources src/test/java .beans/
git commit -m "feat(privacy): invitee self-service erasure on the manage link"
```

---

## Task 6: Per-booking JSON export on the manage link

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/privacy/PrivacyService.java`
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java`
- Modify: `src/main/resources/templates/PublicResource/manage.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, `messages/msg_de.properties`
- Create: `src/test/java/site/asm0dey/calit/privacy/BookingExportTest.java`

**Interfaces:**
- Consumes: `Booking.findByManageToken`, `Booking.isErased()`, `BookingGuest.allForBooking`.
- Produces:
  - `PrivacyService.exportBooking(String manageToken)` — returns `Map<String, Object>`, JSON-serialisable by the default Jackson provider. Keys: `exportedAt`, `booking` (`{id, meetingType, title, description, startUtc, endUtc, status, locale, createdAt}`), `invitee` (`{name, email, answers}`), `guests` (list of `{email, status}`).
  - Route `GET /booking/{manageToken}/data` — `application/json`, `Content-Disposition: attachment`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/privacy/BookingExportTest.java`:

```java
package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BookingExportTest {

    @Test
    void exportCarriesTheInviteesOwnData() {
        String token = ErasureFixtures.seedPastBooking();
        given().when()
                .get("/booking/" + token + "/data")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Content-Disposition", containsString("attachment"))
                .body("invitee.name", equalTo("Dana Vogel"))
                .body("invitee.email", equalTo("dana@example.com"))
                .body("invitee.answers.why", equalTo("annual review"))
                .body("guests", hasSize(1))
                .body("guests[0].email", equalTo("guest@example.com"))
                .body("booking.startUtc", notNullValue())
                .body("exportedAt", notNullValue());
    }

    @Test
    void erasedBookingHasNothingToExport() {
        String token = ErasureFixtures.seedPastBooking();
        given().when().post("/booking/" + token + "/erase").then().statusCode(200);
        given().when().get("/booking/" + token + "/data").then().statusCode(404);
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/data").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=BookingExportTest
```

Expected: FAIL — `expected status code <200> but was <404>`.

- [ ] **Step 3: Add the export method**

In `PrivacyService`:

```java
    /**
     * Everything calit holds about ONE booking, from the invitee's side of it: the times, the
     * meeting, their own name/email/answers, and their guest list. Art. 15 access, scoped to what
     * the manage token authorizes — it proves control of this booking and nothing else, so this is
     * not a search across every booking that shares an address.
     */
    public Map<String, Object> exportBooking(String manageToken) {
        Booking b = Booking.findByManageToken(manageToken);
        if (b == null || b.isErased()) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        MeetingType type = MeetingType.findById(b.meetingTypeId);
        var booking = new LinkedHashMap<String, Object>();
        booking.put("id", b.id);
        booking.put("meetingType", b.effectiveTitle(type));
        booking.put("title", b.title);
        booking.put("description", b.description);
        booking.put("startUtc", b.startUtc.toString());
        booking.put("endUtc", b.endUtc.toString());
        booking.put("status", b.status.name());
        booking.put("locale", b.locale);
        booking.put("createdAt", b.createdAt.toString());

        var invitee = new LinkedHashMap<String, Object>();
        invitee.put("name", b.inviteeName);
        invitee.put("email", b.inviteeEmail);
        invitee.put("answers", b.answers);

        var guests = BookingGuest.<BookingGuest>allForBooking(b.id).stream()
                .map(g -> Map.<String, Object>of("email", g.email, "status", g.status.name()))
                .toList();

        var out = new LinkedHashMap<String, Object>();
        out.put("exportedAt", Instant.now().toString());
        out.put("booking", booking);
        out.put("invitee", invitee);
        out.put("guests", guests);
        return out;
    }
```

- [ ] **Step 4: Add the route**

In `PublicResource`, beside `inviteIcs`:

```java
    /**
     * Art. 15 access for the invitee: everything calit holds about THIS booking, as a JSON
     * attachment. Keyed by the manage token, same as every other invitee route.
     */
    @GET
    @Path("/booking/{manageToken}/data")
    @Produces(MediaType.APPLICATION_JSON)
    public Response bookingData(@PathParam("manageToken") String manageToken) {
        return Response.ok(privacy.exportBooking(manageToken))
                .header("Content-Disposition", "attachment; filename=\"booking-data.json\"")
                .build();
    }
```

- [ ] **Step 5: Link it from the manage hub**

Add to `AppMessages`:

```java
    @Message("Download my data")
    String pub_manage_download_data();
```

`msg_de.properties`:

```properties
pub_manage_download_data=Meine Daten herunterladen
```

In `manage.html`, beside the erase link:

```html
      <a class="btn btn-ghost btn-sm" href="/booking/{booking.manageToken}/data">{msg:pub_manage_download_data}</a>
```

This link is **not** gated on `INVITEE_ERASURE`. The toggle governs erasure; access is not something an operator opts out of.

- [ ] **Step 6: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=BookingExportTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply && bun run format:fe
git add src/main src/test .beans/
git commit -m "feat(privacy): per-booking JSON export on the manage link"
```

---

## Task 7: Account deletion — self-serve and admin

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/privacy/PrivacyService.java`
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java`
- Modify: `src/main/java/site/asm0dey/calit/web/UsersResource.java`
- Create: `src/main/resources/templates/AdminResource/deleteAccount.html`
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Modify: `src/main/resources/templates/UsersResource/users.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, `messages/adm_de.properties`
- Create: `src/test/java/site/asm0dey/calit/privacy/AccountDeletionTest.java`

**Interfaces:**
- Consumes: `PersonalData.TABLES` (Task 2), `EmailOutbox.deleteForOwner` (Task 3), `PasswordHasher`, `AppUser`, the `owner_id` cascades already in the schema.
- Produces:
  - `PrivacyService.deleteAccount(Long userId)` — `void`, `@Transactional`. Purges `email_outbox` by `owner_id`, then deletes the `app_user` row; every other table follows by cascade. Throws `IllegalStateException("last-admin")` when the account is the last enabled admin.
  - `PrivacyService.isLastEnabledAdmin(Long userId)` — `boolean`.
  - Routes `GET /me/settings/delete`, `POST /me/settings/delete`, `POST /me/users/{id}/delete`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/privacy/AccountDeletionTest.java`:

```java
package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailTag;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class AccountDeletionTest {

    @Inject
    PrivacyService privacy;

    @Inject
    EntityManager em;

    /** A second, non-admin account with a settings row, so deleting it is legal. */
    private Long seedSecondUser() {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create("deletable", "x", false);
            u.persist();
            site.asm0dey.calit.domain.OwnerSettings.seed(u.id, "deletable@example.com");
            EmailOutbox.enqueue(
                    "deletable@example.com", "s", "<p>hi</p>", null, null, "seed", MailTag.forOwner(u.id));
            return u.id;
        });
    }

    private long rowsFor(String table, Long ownerId) {
        return ((Number) em.createNativeQuery(
                        "select count(*) from " + table + " where owner_id = :o")
                .setParameter("o", ownerId)
                .getSingleResult())
                .longValue();
    }

    @Test
    void deletionReachesEveryOwnerScopedTable() {
        Long id = seedSecondUser();
        privacy.deleteAccount(id);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0L, AppUser.count("id", id), "the app_user row is gone");
            for (PersonalData.Classified c : PersonalData.TABLES) {
                if (c.columns().contains("owner_id")) {
                    assertEquals(0L, rowsFor(c.table(), id), c.table() + " must not survive account deletion");
                }
            }
        });
    }

    @Test
    void deletionClearsParkedMailForThatOwner() {
        Long id = seedSecondUser();
        privacy.deleteAccount(id);
        QuarkusTransaction.requiringNew()
                .run(() -> assertEquals(0L, EmailOutbox.count("ownerId", id)));
    }

    @Test
    void theLastEnabledAdminCannotBeDeleted() {
        // DatabaseResetCallback seeds exactly one admin, always id 1.
        assertTrue(privacy.isLastEnabledAdmin(1L));
        assertThrows(IllegalStateException.class, () -> privacy.deleteAccount(1L));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(1L, AppUser.count("id", 1L)));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=AccountDeletionTest
```

Expected: compile error — `deleteAccount` does not exist.

- [ ] **Step 3: Add the service methods**

In `PrivacyService`:

```java
    /** Admins who can still log in. Driving this to zero locks everyone out with no in-app recovery. */
    public boolean isLastEnabledAdmin(Long userId) {
        AppUser u = AppUser.findById(userId);
        return u != null && u.isAdmin && u.enabled && AppUser.count("isAdmin = true and enabled = true") <= 1;
    }

    /**
     * Art. 17 for an owner: the account row goes, and every {@code owner_id} cascade takes the
     * subtree with it — settings, meeting types, availability, bookings, guests, Google credentials
     * and calendars, notification channels, reset tokens, login tickets.
     *
     * <p>{@code email_outbox} is purged explicitly first even though V34 gave it a cascading
     * {@code owner_id}: rows enqueued BEFORE V34 carry a null link and would otherwise outlive the
     * account. The explicit delete is a no-op for those, so the 30-day age purge remains their only
     * route — which is why the operator guide names that window.
     *
     * <p>No Google revoke: {@code GooglePageResource.disconnect} never called Google's revoke
     * endpoint either, so deleting the credential rows removes calit's copy of the tokens without
     * withdrawing the grant at Google. The privacy copy and the operator guide both say so.
     *
     * <p>No "your account was deleted" email — the mailbox may be the thing being erased.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        if (isLastEnabledAdmin(userId)) {
            throw new IllegalStateException("last-admin");
        }
        AppUser u = AppUser.findById(userId);
        if (u == null) {
            return;
        }
        EmailOutbox.deleteForOwner(userId);
        u.delete();
        Log.infof("PRIVACY account-deleted user=%d", userId);
    }
```

- [ ] **Step 4: Add the message keys**

`AdminMessages`:

```java
    @Message("Delete my account")
    String adm_delete_account_link();

    @Message("Delete your account?")
    String adm_delete_account_title();

    @Message(
            "This permanently removes your account, your meeting types and availability, every booking "
                    + "on your calendar, your connected Google accounts and your notification channels. "
                    + "It can't be undone.")
    String adm_delete_account_desc();

    @Message("Disconnecting Google here removes calit's copy of the tokens. Revoke calit's access in your Google account to withdraw the grant itself.")
    String adm_delete_account_google_note();

    @Message("Enter your password to confirm")
    String adm_delete_account_password_label();

    @Message("Type your username to confirm")
    String adm_delete_account_username_label();

    @Message("Delete my account permanently")
    String adm_delete_account_btn();

    @Message("Cancel")
    String adm_delete_account_cancel();

    @Message("That didn't match. Your account was not deleted.")
    String adm_delete_account_error_mismatch();

    @Message("You are the last enabled admin. Grant admin to another account first.")
    String adm_delete_account_error_last_admin();

    @Message("Delete")
    String adm_users_delete();

    @Message("Cannot delete the last enabled admin.")
    String adm_users_error_last_admin_delete();
```

`adm_de.properties`:

```properties
adm_delete_account_link=Mein Konto löschen
adm_delete_account_title=Ihr Konto löschen?
adm_delete_account_desc=Dadurch werden Ihr Konto, Ihre Meeting-Typen und Verfügbarkeiten, alle Buchungen in Ihrem Kalender, Ihre verbundenen Google-Konten und Ihre Benachrichtigungskanäle dauerhaft entfernt. Dies kann nicht rückgängig gemacht werden.
adm_delete_account_google_note=Das Trennen von Google entfernt hier die von calit gespeicherten Tokens. Widerrufen Sie den Zugriff von calit in Ihrem Google-Konto, um die Berechtigung selbst zu entziehen.
adm_delete_account_password_label=Geben Sie zur Bestätigung Ihr Passwort ein
adm_delete_account_username_label=Geben Sie zur Bestätigung Ihren Benutzernamen ein
adm_delete_account_btn=Mein Konto endgültig löschen
adm_delete_account_cancel=Abbrechen
adm_delete_account_error_mismatch=Das stimmt nicht überein. Ihr Konto wurde nicht gelöscht.
adm_delete_account_error_last_admin=Sie sind der letzte aktive Administrator. Vergeben Sie zuerst Administratorrechte an ein anderes Konto.
adm_users_delete=Löschen
adm_users_error_last_admin_delete=Der letzte aktive Administrator kann nicht gelöscht werden.
```

- [ ] **Step 5: Write the confirm template**

Create `src/main/resources/templates/AdminResource/deleteAccount.html`:

```html
{@java.lang.String title}
{@java.lang.Long pendingCount}
{@java.lang.Boolean isAdmin}
{@java.lang.Boolean hasPassword}
{@java.lang.String username}
{@java.lang.String error}
{#include adminBase title=title pendingCount=pendingCount active="settings" isAdmin=isAdmin}
  <!-- CALIT_DELETE_ACCOUNT -->
  <div class="card bg-base-100 border border-error/40 shadow-sm max-w-2xl">
    <div class="card-body items-start gap-3">
      <h1 class="text-2xl font-bold text-error">{adm:adm_delete_account_title}</h1>
      <p class="text-base-content/70">{adm:adm_delete_account_desc}</p>
      <p class="text-base-content/70">{adm:adm_delete_account_google_note}</p>
      {#if error}<div class="alert alert-error">{error}</div>{/if}
      <form method="post" action="/me/settings/delete" class="w-full">
        <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
        {#if hasPassword}
        <label class="label" for="del-confirm">{adm:adm_delete_account_password_label}</label>
        <input id="del-confirm" class="input w-full" type="password" name="confirmation" required autocomplete="current-password">
        {#else}
        <label class="label" for="del-confirm">{adm:adm_delete_account_username_label}</label>
        <input id="del-confirm" class="input w-full" type="text" name="confirmation" required placeholder="{username}">
        {/if}
        <div class="flex gap-2 mt-3">
          <button type="submit" class="btn btn-error">{adm:adm_delete_account_btn}</button>
          <a class="btn btn-ghost" href="/me/settings">{adm:adm_delete_account_cancel}</a>
        </div>
      </form>
    </div>
  </div>
{/include}
```

- [ ] **Step 6: Add the owner routes**

In `AdminResource.Templates`:

```java
        public static native TemplateInstance deleteAccount(
                String title, Long pendingCount, boolean isAdmin, boolean hasPassword, String username, String error);
```

Inject `PrivacyService privacy` and `PasswordHasher passwordHasher`, then:

```java
    @GET
    @Path("/settings/delete")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance deleteAccountConfirm() {
        return deleteAccountPage(null);
    }

    /**
     * Art. 17 for the owner. Re-authentication is deliberate: a session left open on a shared
     * machine must not be one click away from destroying an account. An OIDC- or Google-only
     * account has no password to re-enter, so it types its username instead — the same friction
     * without asking for a credential it does not have.
     */
    @POST
    @Path("/settings/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response deleteAccount(@RestForm String confirmation) {
        AppUser me = AppUser.findById(currentOwner.id());
        boolean ok = me.passwordHash != null
                ? passwordHasher.verify(confirmation, me.passwordHash)
                : me.username.equals(Usernames.normalize(confirmation == null ? "" : confirmation));
        if (!ok) {
            return Response.ok(deleteAccountPage(m().adm_delete_account_error_mismatch()))
                    .build();
        }
        try {
            privacy.deleteAccount(me.id);
        } catch (IllegalStateException e) {
            return Response.ok(deleteAccountPage(m().adm_delete_account_error_last_admin()))
                    .build();
        }
        audit.event(me.username, "delete-account", "user:" + me.id, null);
        // The session now points at a row that no longer exists; send the browser through logout
        // so the credential cookie is cleared rather than left dangling.
        return Response.seeOther(URI.create("/logout")).build();
    }

    private TemplateInstance deleteAccountPage(String error) {
        AppUser me = AppUser.findById(currentOwner.id());
        return Templates.deleteAccount(
                m().adm_delete_account_title(),
                pendingCount(),
                isAdmin(),
                me.passwordHash != null,
                me.username,
                error);
    }
```

Check `PasswordHasher`'s actual verify method name before writing this — use whatever `AppUserIdentityProvider` calls.

- [ ] **Step 7: Link it from settings**

At the end of `src/main/resources/templates/AdminResource/settings.html`, before `{/include}`:

```html
  <div class="mt-10 pt-4 border-t border-base-300 max-w-2xl">
    <a class="link link-error text-sm" href="/me/settings/delete">{adm:adm_delete_account_link}</a>
  </div>
```

- [ ] **Step 8: Add the admin route**

In `UsersResource`, beside `lock`/`unlock`:

```java
    /**
     * Site-admin deletion of another account — the operator's route for an Art. 17 request that
     * arrives by mail. Same last-admin guard as revoke/lock: there is no in-app recovery from zero
     * enabled admins (SEC-AUTHZ-01).
     */
    @POST
    @Path("/{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance deleteUser(@PathParam("id") Long id) {
        var m = adminMsgs.forLocale(activeLocale.current());
        try {
            privacy.deleteAccount(id);
        } catch (IllegalStateException e) {
            return render(m.adm_users_error_last_admin_delete());
        }
        audit.event(identity.getPrincipal().getName(), "delete-user", USER_TARGET + id, null);
        return render(null);
    }
```

Inject `PrivacyService privacy` through the constructor. In `src/main/resources/templates/UsersResource/users.html`, add beside the existing lock/unlock buttons, inside the same per-row form:

```html
        <button type="submit" formaction="/me/users/{u.id}/delete" class="btn btn-ghost btn-xs text-error">{adm:adm_users_delete}</button>
```

- [ ] **Step 9: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=AccountDeletionTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
mvn spotless:apply && bun run format:fe
git add src/main src/test .beans/
git commit -m "feat(privacy): account deletion, self-serve and admin"
```

---

## Task 8: Owner data export

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/privacy/PrivacyService.java`
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java`
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, `messages/adm_de.properties`
- Create: `src/test/java/site/asm0dey/calit/privacy/OwnerExportTest.java`

**Interfaces:**
- Consumes: `CurrentOwner.id()`, the owner-scoped entities.
- Produces:
  - `PrivacyService.exportOwner(Long ownerId)` — `Map<String, Object>` with keys `exportedAt`, `account`, `settings`, `meetingTypes`, `availability`, `dateOverrides`, `bookings`, `notificationChannels`, `googleAccounts`.
  - Route `GET /me/export` — `application/json` attachment.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/privacy/OwnerExportTest.java`:

```java
package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OwnerExportTest {

    @Test
    void anonymousCannotExport() {
        given().when().get("/me/export").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void exportIsAJsonAttachmentCoveringTheOwnersSubtree() {
        given().when()
                .get("/me/export")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Content-Disposition", containsString("attachment"))
                .body("exportedAt", notNullValue())
                .body("account", notNullValue())
                .body("settings", notNullValue())
                .body("meetingTypes", notNullValue())
                .body("availability", notNullValue())
                .body("bookings", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void noSecretsLeaveTheBuilding() {
        String body = given().when().get("/me/export").then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("passwordHash") || body.contains("password_hash"),
                "the argon2id hash must never appear in an export");
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("accessToken") || body.contains("refreshToken"),
                "Google OAuth tokens must never appear in an export");
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void notificationChannelUrlsAreRedacted() {
        ChannelFixtures.seedChannel(1L, "ntfy://ntfy.sh/secret-topic");
        given().when()
                .get("/me/export")
                .then()
                .statusCode(200)
                .body(not(containsString("secret-topic")))
                .body("notificationChannels", everyItem(hasKey("url")))
                .body("notificationChannels[0].url", containsString("redacted"));
    }
}
```

Check the existing `@TestSecurity` usage in `src/test/java/site/asm0dey/calit/web/` for the exact user/roles this codebase authenticates `/me` with, and match it. Add the small `ChannelFixtures.seedChannel` helper beside `ErasureFixtures`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=OwnerExportTest
```

Expected: FAIL — 404 on `/me/export`.

- [ ] **Step 3: Add the export method**

In `PrivacyService`:

```java
    /** What a redacted secret reads as in an export — present, so its existence is disclosed; useless. */
    private static final String REDACTED = "[redacted]";

    /**
     * Art. 15/20 for the owner: one JSON file covering their whole subtree. Bookings include the
     * invitee data — the OWNER is the controller for it, so an export that hid it would be useless
     * for the Art. 30 records they have to keep.
     *
     * <p>Two things are deliberately withheld. The argon2id password hash is a credential, not
     * personal data the subject needs back. Notification-channel URLs are bearer secrets — the URL
     * IS the authorization to post into that chat, so it is disclosed as present and redacted as a
     * value. Google OAuth tokens are withheld for the same reason and the row reports only the
     * account email.
     */
    public Map<String, Object> exportOwner(Long ownerId) {
        var out = new LinkedHashMap<String, Object>();
        out.put("exportedAt", Instant.now().toString());

        AppUser u = AppUser.findById(ownerId);
        out.put(
                "account",
                Map.of(
                        "username", u.username,
                        "isAdmin", u.isAdmin,
                        "enabled", u.enabled,
                        "hasPassword", u.passwordHash != null,
                        "linkedGoogle", u.googleSub != null,
                        "linkedOidc", u.oidcSub != null,
                        "createdAt", u.createdAt.toString()));

        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        out.put(
                "settings",
                s == null
                        ? Map.of()
                        : Map.of(
                                "ownerName", s.ownerName,
                                "ownerEmail", s.ownerEmail,
                                "timezone", s.timezone,
                                "locale", s.locale,
                                "timeFormat", s.timeFormat,
                                "ownerNotificationsEnabled", s.ownerNotificationsEnabled,
                                "bookingRetentionDays", s.bookingRetentionDays));

        out.put(
                "meetingTypes",
                MeetingType.<MeetingType>list("ownerId", ownerId).stream()
                        .map(t -> Map.<String, Object>of(
                                "id", t.id, "name", t.name, "slug", t.slug,
                                "description", t.description == null ? "" : t.description))
                        .toList());

        out.put(
                "availability",
                AvailabilityRule.<AvailabilityRule>list("ownerId", ownerId).stream()
                        .map(r -> Map.<String, Object>of(
                                "dayOfWeek", String.valueOf(r.dayOfWeek),
                                "startTime", String.valueOf(r.startTime),
                                "endTime", String.valueOf(r.endTime)))
                        .toList());

        out.put(
                "dateOverrides",
                DateOverride.<DateOverride>list("ownerId", ownerId).stream()
                        .map(d -> Map.<String, Object>of("date", String.valueOf(d.date)))
                        .toList());

        out.put(
                "bookings",
                Booking.<Booking>list("ownerId", ownerId).stream()
                        .map(b -> {
                            var row = new LinkedHashMap<String, Object>();
                            row.put("id", b.id);
                            row.put("startUtc", b.startUtc.toString());
                            row.put("endUtc", b.endUtc.toString());
                            row.put("status", b.status.name());
                            row.put("erasedAt", b.erasedAt == null ? null : b.erasedAt.toString());
                            row.put("inviteeName", b.inviteeName);
                            row.put("inviteeEmail", b.inviteeEmail);
                            row.put("answers", b.answers);
                            row.put(
                                    "guests",
                                    BookingGuest.<BookingGuest>allForBooking(b.id).stream()
                                            .map(g -> g.email)
                                            .toList());
                            return row;
                        })
                        .toList());

        out.put(
                "notificationChannels",
                NotificationChannel.<NotificationChannel>list("ownerId", ownerId).stream()
                        .map(c -> Map.<String, Object>of(
                                "label", c.label == null ? "" : c.label, "url", REDACTED))
                        .toList());

        out.put(
                "googleAccounts",
                GoogleCredential.<GoogleCredential>list("ownerId", ownerId).stream()
                        .map(g -> Map.<String, Object>of("email", g.email == null ? "" : g.email))
                        .toList());

        return out;
    }
```

Verify each entity's actual field names before writing (`AvailabilityRule`, `DateOverride`, `GoogleCredential`, `NotificationChannel`) — the shapes above assume the obvious names and the compiler is the check.

- [ ] **Step 4: Add the route and the link**

In `AdminResource`:

```java
    /**
     * Art. 15/20 for the owner: their whole subtree as one JSON file. Owner-scoped by
     * {@code currentOwner.id()} like every other /me query — never a parameter.
     */
    @GET
    @Path("/export")
    @Produces(MediaType.APPLICATION_JSON)
    public Response export() {
        return Response.ok(privacy.exportOwner(currentOwner.id()))
                .header("Content-Disposition", "attachment; filename=\"calit-export.json\"")
                .build();
    }
```

`AdminMessages` + `adm_de.properties`:

```java
    @Message("Download all my data")
    String adm_settings_export_link();
```

```properties
adm_settings_export_link=Alle meine Daten herunterladen
```

In `settings.html`, in the same footer block as the delete link:

```html
    <a class="link text-sm me-4" href="/me/export">{adm:adm_settings_export_link}</a>
```

- [ ] **Step 5: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=OwnerExportTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 6: Commit and open PR 2**

```bash
mvn spotless:apply && bun run format:fe
git add src/main src/test .beans/
git commit -m "feat(privacy): owner data export with secrets redacted"
git push
gh pr create --title "feat(privacy): invitee erasure, export and account deletion" --body "..."
```

---

## Task 9: Retention scheduler

**Files:**
- Create: `src/main/java/site/asm0dey/calit/scheduler/RetentionScheduler.java`
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java`
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, `messages/adm_de.properties`
- Create: `src/test/java/site/asm0dey/calit/scheduler/RetentionSchedulerTest.java`

**Interfaces:**
- Consumes: `PrivacyService.anonymise(Long)` (Task 4), `PrivacyConfig.bookingRetentionDays()` (Task 4), `OwnerSettings.bookingRetentionDays` (Task 1).
- Produces:
  - `RetentionScheduler.sweep()` — package-private, `void`. The tick body, called directly by the test rather than waiting on the cron.
  - `OwnerSettings.retentionDaysOrDefault(Integer instanceDefault)` — `Integer`, null meaning keep forever.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/scheduler/RetentionSchedulerTest.java`:

```java
package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.ErasureFixtures;

@QuarkusTest
class RetentionSchedulerTest {

    @Inject
    RetentionScheduler scheduler;

    private static boolean erased(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findById(id).isErased());
    }

    /** Default config: calit.retention.booking-days unset. */
    @Test
    void unsetInstanceDefaultIsANoOp() {
        Long id = ErasureFixtures.seedPastBookingId(); // ends 30 days ago
        scheduler.sweep();
        assertFalse(erased(id), "an unset retention window must keep bookings forever");
    }

    @Test
    void perOwnerOverrideAppliesWithoutAnInstanceDefault() {
        Long id = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 7;
        });
        scheduler.sweep();
        assertTrue(erased(id), "a 7-day owner window must catch a booking that ended 30 days ago");
    }

    @Test
    void aFutureBookingIsNeverTouched() {
        Long id = ErasureFixtures.seedUpcomingBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 1;
        });
        scheduler.sweep();
        assertFalse(erased(id), "retention measures from end_utc; a future booking has not ended");
    }

    @Nested
    @QuarkusTest
    @TestProfile(WithInstanceDefault.class)
    class InstanceDefaultApplies {

        @Inject
        RetentionScheduler scheduler;

        @Test
        void instanceDefaultCatchesAnOldBooking() {
            Long id = ErasureFixtures.seedPastBookingId();
            scheduler.sweep();
            assertTrue(erased(id), "a 14-day instance default must catch a booking that ended 30 days ago");
        }

        @Test
        void aLongerOwnerOverrideWins() {
            Long id = ErasureFixtures.seedPastBookingId();
            QuarkusTransaction.requiringNew().run(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                s.bookingRetentionDays = 365;
            });
            scheduler.sweep();
            assertFalse(erased(id), "the owner's own window overrides the instance default in both directions");
        }
    }

    public static class WithInstanceDefault implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.retention.booking-days", "14");
        }
    }
}
```

Add `seedPastBookingId()` and `seedUpcomingBookingId()` to `ErasureFixtures`, and make the class `public` so the scheduler test package can reach it.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=RetentionSchedulerTest
```

Expected: compile error — `RetentionScheduler` does not exist.

- [ ] **Step 3: Add the settings helper**

In `OwnerSettings`:

```java
    /**
     * This owner's effective retention window in days, or null for "keep forever". The owner's own
     * value wins in BOTH directions — a longer window is as legitimate a choice as a shorter one,
     * and silently capping it at the instance default would be a deletion the operator did not ask
     * for.
     */
    public Integer retentionDaysOrDefault(Integer instanceDefault) {
        return bookingRetentionDays != null ? bookingRetentionDays : instanceDefault;
    }
```

- [ ] **Step 4: Write the scheduler**

Create `src/main/java/site/asm0dey/calit/scheduler/RetentionScheduler.java`:

```java
package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.PrivacyConfig;
import site.asm0dey.calit.privacy.PrivacyService;

/**
 * Anonymises bookings once their retention window has elapsed. Runs on EVERY replica, daily, with
 * no leader: each tick claims rows with SELECT ... FOR UPDATE SKIP LOCKED, the same pattern
 * {@link ReminderScheduler} uses.
 *
 * <p>The window is per owner ({@code owner_settings.booking_retention_days}) falling back to the
 * instance default ({@code calit.retention.booking-days}). Both unset means keep forever, which is
 * the shipped default — an upgrade changes nothing until an operator opts in.
 *
 * <p>These bookings are all in the past, so there is nothing to cancel: this calls the same
 * anonymise the erase button calls, minus the cancel step.
 */
@ApplicationScoped
public class RetentionScheduler {

    /** One tick's ceiling. A backlog drains over successive days rather than in one long transaction. */
    private static final int BATCH = 200;

    final EntityManager em;

    final PrivacyService privacy;

    final PrivacyConfig config;

    @Inject
    public RetentionScheduler(EntityManager em, PrivacyService privacy, PrivacyConfig config) {
        this.em = em;
        this.privacy = privacy;
        this.config = config;
    }

    @Scheduled(cron = "0 17 3 * * ?")
    void dailySweep() {
        sweep();
    }

    /**
     * One pass. The window arithmetic runs in SQL because it is per-row: each booking is measured
     * against ITS OWNER's window, so a single Java-side cutoff instant would be wrong the moment two
     * owners disagree. COALESCE picks the owner's value, then the instance default; a NULL result
     * means "keep forever" and the row is not selected at all.
     */
    void sweep() {
        Integer instanceDefault = config.bookingRetentionDays().orElse(null);
        List<Long> claimed = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery("SELECT b.id FROM booking b "
                            + "JOIN owner_settings os ON os.owner_id = b.owner_id "
                            + "WHERE b.erased_at IS NULL "
                            + "  AND COALESCE(os.booking_retention_days, :instanceDefault) IS NOT NULL "
                            + "  AND b.end_utc < now() - make_interval("
                            + "        days => COALESCE(os.booking_retention_days, :instanceDefault)) "
                            + "ORDER BY b.end_utc "
                            + "FOR UPDATE OF b SKIP LOCKED "
                            + "LIMIT :batch")
                    .setParameter("instanceDefault", instanceDefault)
                    .setParameter("batch", BATCH)
                    .getResultList();
            ids.forEach(n -> claimed.add(n.longValue()));
        });
        // anonymise() opens its own transaction per booking: one poison row cannot roll back the
        // whole sweep, and an interrupted sweep leaves every already-anonymised booking committed.
        for (Long id : claimed) {
            privacy.anonymise(id);
        }
        if (!claimed.isEmpty()) {
            Log.infof("PRIVACY retention sweep anonymised %d booking(s)", claimed.size());
        }
    }
}
```

Two details worth keeping: `FOR UPDATE OF b` (a bare `FOR UPDATE` would try to lock the joined `owner_settings` row too, and Postgres rejects `SKIP LOCKED` across an outer join shape as readily as it accepts this), and `COALESCE(...) IS NOT NULL` before the interval arithmetic so a null window is excluded rather than compared.

- [ ] **Step 5: Add the per-owner setting to the UI**

`AdminMessages` + `adm_de.properties`:

```java
    @Message("Delete booking details after (days)")
    String adm_settings_label_retention();

    @Message("Leave blank to keep bookings indefinitely. Invitee name, email and answers are removed; the time slot record stays.")
    String adm_settings_retention_hint();
```

```properties
adm_settings_label_retention=Buchungsdetails löschen nach (Tagen)
adm_settings_retention_hint=Leer lassen, um Buchungen unbegrenzt aufzubewahren. Name, E-Mail-Adresse und Antworten der eingeladenen Person werden entfernt; der Termineintrag bleibt erhalten.
```

In `settings.html`, inside the main settings form after the notifications checkbox:

```html
    <label class="label" for="set-retention">{adm:adm_settings_label_retention}</label>
    <input id="set-retention" class="input w-full" type="number" min="1" name="bookingRetentionDays" value="{#if settings && settings.bookingRetentionDays}{settings.bookingRetentionDays}{/if}">
    <p class="text-sm text-base-content/70">{adm:adm_settings_retention_hint}</p>
```

In `AdminResource.updateSettings`, add the form parameter and parse it. A blank field, a zero, a negative number and an unparseable string all mean the same thing — no override:

```java
            @RestForm String bookingRetentionDays,
```

```java
            row.bookingRetentionDays = parseRetentionDays(bookingRetentionDays);
```

```java
    /** Blank, zero, negative and unparseable all mean "no override" — fall back to the instance default. */
    private static Integer parseRetentionDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int days = Integer.parseInt(raw.trim());
            return days > 0 ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 6: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=RetentionSchedulerTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply && bun run format:fe
git add src/main src/test .beans/
git commit -m "feat(privacy): retention sweep with a per-owner window"
```

---

## Task 10: Hygiene purges

Nothing configurable here. None of this should have been kept in the first place; `email_outbox` is the largest single reduction in stored personal data in the epic.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/scheduler/PurgeScheduler.java`
- Create: `src/test/java/site/asm0dey/calit/scheduler/PurgeSchedulerTest.java`

**Interfaces:**
- Consumes: `EmailOutbox`, `PasswordResetToken`, `LoginTicket`.
- Produces: `PurgeScheduler.purge()` — package-private `void`, callable directly by the test.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/scheduler/PurgeSchedulerTest.java`:

```java
package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;

@QuarkusTest
class PurgeSchedulerTest {

    @Inject
    PurgeScheduler purger;

    private Long outbox(Instant createdAt, Instant sentAt, Instant nextAttemptAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var r = new EmailOutbox();
            r.recipient = "a@example.com";
            r.subject = "s";
            r.htmlBody = "<p>personal</p>";
            r.attempts = 0;
            r.createdAt = createdAt;
            r.sentAt = sentAt;
            r.nextAttemptAt = nextAttemptAt;
            r.persist();
            return r.id;
        });
    }

    private static long count(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count("id", id));
    }

    private static Instant daysAgo(int d) {
        return Instant.now().minus(d, ChronoUnit.DAYS);
    }

    @Test
    void sentMailOlderThanThirtyDaysGoes() {
        Long id = outbox(daysAgo(40), daysAgo(31), null);
        purger.purge();
        assertEquals(0L, count(id));
    }

    @Test
    void recentlySentMailStays() {
        Long id = outbox(daysAgo(10), daysAgo(3), null);
        purger.purge();
        assertEquals(1L, count(id));
    }

    @Test
    void deadMailOlderThanThirtyDaysGoes() {
        Long id = outbox(daysAgo(31), null, null); // next_attempt_at null = dead
        purger.purge();
        assertEquals(0L, count(id));
    }

    @Test
    void mailStillInItsRetryWindowIsNeverTouched() {
        Long id = outbox(daysAgo(60), null, Instant.now().plusSeconds(60));
        purger.purge();
        assertEquals(1L, count(id), "a row still due for retry must survive regardless of age");
    }

    @Test
    void expiredAuthTokensGoADayAfterTheyDie() {
        Long fresh = TokenFixtures.seedResetToken(Instant.now().plusSeconds(3600));
        Long stale = TokenFixtures.seedResetToken(daysAgo(2));
        purger.purge();
        assertEquals(1L, TokenFixtures.countResetToken(fresh));
        assertEquals(0L, TokenFixtures.countResetToken(stale));
    }
}
```

Add `TokenFixtures` beside the other fixtures, matching `PasswordResetToken`'s real field names.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=PurgeSchedulerTest
```

Expected: compile error — `PurgeScheduler` does not exist.

- [ ] **Step 3: Write the scheduler**

Create `src/main/java/site/asm0dey/calit/scheduler/PurgeScheduler.java`:

```java
package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.user.LoginTicket;
import site.asm0dey.calit.user.PasswordResetToken;

/**
 * Data nothing should have been keeping. Not configurable, and deliberately so: there is no
 * deployment for which holding the rendered HTML of a year-old booking mail, complete with the
 * recipient's address, is the right answer.
 *
 * <p>ponytail: two hardcoded constants, documented in the config reference. Add env vars only if an
 * operator actually asks.
 */
@ApplicationScoped
public class PurgeScheduler {

    /** How long a delivered or dead mail is kept for operational inspection before it goes. */
    private static final Duration MAIL_RETENTION = Duration.ofDays(30);

    /** Grace past an auth token's own expiry, so a just-expired link still explains itself. */
    private static final Duration TOKEN_GRACE = Duration.ofDays(1);

    @Scheduled(cron = "0 37 3 * * ?")
    void dailyPurge() {
        purge();
    }

    /**
     * One pass. A row still inside its retry window ({@code next_attempt_at IS NOT NULL} and
     * unsent) is never touched regardless of age — dropping it would lose a mail calit still
     * intends to deliver.
     */
    @Transactional
    void purge() {
        var mailCutoff = Instant.now().minus(MAIL_RETENTION);
        long sent = EmailOutbox.delete("sentAt is not null and sentAt < ?1", mailCutoff);
        long dead = EmailOutbox.delete(
                "sentAt is null and nextAttemptAt is null and createdAt < ?1", mailCutoff);

        var tokenCutoff = Instant.now().minus(TOKEN_GRACE);
        long resets = PasswordResetToken.delete("expiresAt < ?1", tokenCutoff);
        long tickets = LoginTicket.delete("expiresAt < ?1", tokenCutoff);

        if (sent + dead + resets + tickets > 0) {
            Log.infof(
                    "PRIVACY purge: outbox sent=%d dead=%d, reset-tokens=%d, login-tickets=%d",
                    sent, dead, resets, tickets);
        }
    }
}
```

Check `PasswordResetToken` and `LoginTicket` for their real expiry field names before writing the queries; both may use `expiresAt` or a column-mapped variant.

Sent `reminder` rows are deliberately left alone: a booking id and a timestamp, no personal data, and they cascade with the booking.

- [ ] **Step 4: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=PurgeSchedulerTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 5: Commit and open PR 3**

```bash
mvn spotless:apply
git add src/main src/test .beans/
git commit -m "feat(privacy): purge stale parked mail and expired auth tokens"
git push
gh pr create --title "feat(privacy): retention and hygiene purges" --body "..."
```

---

## Task 11: Fact-driven policy copy and operator overrides

The shipped privacy policy currently states that "deleting a user account removes that user's scheduling data" — which was false until Task 7 and is now true. It also claims Google processing on deployments that never configured Google. Both are fixed here.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/privacy/PrivacyFacts.java`
- Modify: `src/main/java/site/asm0dey/calit/web/LegalResource.java`
- Modify: `src/main/resources/templates/LegalResource/privacy.html`
- Modify: `src/main/resources/templates/LegalResource/terms.html`
- Modify: `src/main/resources/templates/AdminResource/bookingFields.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, `messages/adm_de.properties`
- Modify: `src/main/resources/application.properties`, `.env.example`
- Create: `src/test/java/site/asm0dey/calit/privacy/PrivacyPolicyRenderTest.java`

**Interfaces:**
- Consumes: `PrivacyConfig` (Task 4), `GoogleOAuthConfig`, `NotificationChannel`, `OwnerSettings`.
- Produces:
  - `PrivacyFacts` — `@Named("privacy") @ApplicationScoped`, exposed to Qute as `{inject:privacy.*}`. Getters: `isGoogleConfigured()`, `isOidcConfigured()`, `getSmtpHost()`, `isSignupOpen()`, `isInviteeErasureEnabled()`, `getRetentionDays()` (`Integer`, null = forever), `isAnyChannelConfigured()`.
  - `LegalResource` serves `PRIVACY_POLICY_PATH` / `TERMS_PATH` fragments when set.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/privacy/PrivacyPolicyRenderTest.java`:

```java
package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PrivacyPolicyRenderTest {

    /** Google is disabled by default in %test, so the Google sections must not render. */
    @Test
    void googleSectionsAreAbsentWhenGoogleIsUnconfigured() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_LEGAL_PRIVACY"))
                .body(not(containsString("Limited Use disclosure")));
    }

    @Test
    void deletionClaimIsBackedByTheDeleteRoute() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("/me/settings/delete"));
    }

    @Test
    void retentionSectionSaysForeverWhenUnset() {
        given().when().get("/privacy").then().statusCode(200).body(containsString("CALIT_RETENTION_FOREVER"));
    }

    @QuarkusTest
    @TestProfile(WithOverride.class)
    static class OverrideFile {

        @Test
        void theOperatorFragmentReplacesTheShippedBody() {
            given().when()
                    .get("/privacy")
                    .then()
                    .statusCode(200)
                    .body(containsString("OPERATOR SUPPLIED POLICY"))
                    .body(not(containsString("CALIT_LEGAL_PRIVACY")));
        }
    }

    public static class WithOverride implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path f = Files.createTempFile("policy", ".html");
                Files.writeString(f, "<h1>OPERATOR SUPPLIED POLICY</h1>");
                return Map.of("app.privacy-policy-path", f.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -o test -Dtest=PrivacyPolicyRenderTest
```

Expected: FAIL — `Limited Use disclosure` is present, because the shipped copy is unconditional.

- [ ] **Step 3: Write `PrivacyFacts`**

Create `src/main/java/site/asm0dey/calit/privacy/PrivacyFacts.java`:

```java
package site.asm0dey.calit.privacy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.notify.NotificationChannel;

/**
 * What THIS deployment actually does, exposed to Qute as {@code {inject:privacy.*}} so the shipped
 * policy describes the running software rather than the feature set. A deployment without Google
 * configured stops claiming it talks to Google; one with retention unset says so plainly instead of
 * implying a schedule it does not run.
 */
@Named("privacy")
@ApplicationScoped
public class PrivacyFacts {

    final boolean googleConfigured;

    final boolean oidcConfigured;

    final Optional<String> smtpHost;

    final boolean signupOpen;

    final PrivacyConfig config;

    @Inject
    public PrivacyFacts(
            @ConfigProperty(name = "google.oauth.client-id") Optional<String> googleClientId,
            @ConfigProperty(name = "calit.oidc.enabled", defaultValue = "false") boolean oidcEnabled,
            @ConfigProperty(name = "quarkus.mailer.host") Optional<String> smtpHost,
            @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false") boolean signupOpen,
            PrivacyConfig config) {
        this.googleConfigured = googleClientId.filter(s -> !s.isBlank()).isPresent();
        this.oidcConfigured = oidcEnabled;
        this.smtpHost = smtpHost;
        this.signupOpen = signupOpen;
        this.config = config;
    }

    public boolean isGoogleConfigured() {
        return googleConfigured;
    }

    public boolean isOidcConfigured() {
        return oidcConfigured;
    }

    /** The SMTP relay this deployment hands mail to — a sub-processor the operator must name. */
    public String getSmtpHost() {
        return smtpHost.filter(s -> !s.isBlank()).orElse(null);
    }

    public boolean isSignupOpen() {
        return signupOpen;
    }

    public boolean isInviteeErasureEnabled() {
        return config.inviteeErasureEnabled();
    }

    /** Instance retention window in days, or null when bookings are kept indefinitely. */
    public Integer getRetentionDays() {
        return config.bookingRetentionDays().orElse(null);
    }

    /**
     * Whether ANY owner on this instance has an outbound notification channel. A live count, not
     * config: channels are per-owner rows, and the policy has to disclose the category of recipient
     * as soon as one exists.
     */
    public boolean isAnyChannelConfigured() {
        return NotificationChannel.count() > 0;
    }
}
```

Confirm the exact config key for the Google client id (`google.oauth.client-id` in `application.properties`) and for the mailer host before writing the constructor.

- [ ] **Step 4: Rewrite the policy body**

In `src/main/resources/templates/LegalResource/privacy.html`, make the Google, sharing, and retention sections conditional, and correct the deletion claim. The three sections to change:

```html
  {#if inject:privacy.googleConfigured}
  <h2 class="text-xl font-semibold mt-6">How Google user data is used</h2>
  <p class="mt-2">... unchanged text ...</p>

  <h3 class="text-lg font-semibold mt-4">Limited Use disclosure</h3>
  <p class="mt-2">... unchanged text ...</p>
  {/if}

  <h2 class="text-xl font-semibold mt-6">Data sharing</h2>
  <p class="mt-2">calit does not sell or share your data. This deployment sends data to:</p>
  <ul class="list-disc ps-6 mt-2">
    {#if inject:privacy.googleConfigured}<li>Google, for the calendar operations above.</li>{/if}
    {#if inject:privacy.smtpHost}<li>The SMTP server <code>{inject:privacy.smtpHost}</code>, which delivers booking and notification emails.</li>{/if}
    {#if inject:privacy.anyChannelConfigured}<li>The chat or push services hosts have configured as notification channels (for example Telegram, Slack, Discord or ntfy). Messages already delivered there cannot be recalled.</li>{/if}
  </ul>

  <h2 class="text-xl font-semibold mt-6">Retention and deletion</h2>
  <ul class="list-disc ps-6 mt-2">
    {#if inject:privacy.googleConfigured}<li>Disconnecting a Google account in <a class="link" href="/me/google">Settings → Google</a> deletes that account's stored tokens and calendar selections from this site. It does not withdraw the grant at Google — revoke calit's access in your Google account to do that.</li>{/if}
    <li>Deleting your account at <a class="link" href="/me/settings/delete">Settings → Delete my account</a> removes your account, settings, meeting types, availability, bookings, connected Google accounts and notification channels.</li>
    {#if inject:privacy.inviteeErasureEnabled}<li>If you booked a meeting here, the manage link in your confirmation email lets you download your data or erase it from that booking.</li>{/if}
    {#if inject:privacy.retentionDays}
    <li>Booking details are anonymised {inject:privacy.retentionDays} days after the meeting ends. Individual hosts may set a shorter or longer window.</li>
    {#else}
    <!-- CALIT_RETENTION_FOREVER -->
    <li>Booking details are kept until removed by the host or the operator; this deployment sets no automatic retention window. Individual hosts may set their own.</li>
    {/if}
    <li>Emails parked for retry are deleted 30 days after they are sent or abandoned. Password-reset and sign-in links are deleted a day after they expire.</li>
  </ul>
```

Keep the `<!-- CALIT_LEGAL_PRIVACY -->` marker — the render test keys on it, and RestAssured cannot run scripts so marker comments are how this codebase asserts on templates.

Also replace the template's `{! ponytail: English-only legal copy, ported from
docs-site/src/content/docs/privacy.md — keep the two in sync. !}` comment. That mirror is what this
task ends: the app page is now deployment-conditional and the markdown cannot be. Say so instead:

```html
{! ponytail: English-only legal copy. THIS page is authoritative for a running deployment -- it
   renders from PrivacyFacts, so it describes what this instance actually does. The reference copy
   at docs-site/src/content/docs/privacy.md describes the software with every option enabled and is
   NOT a mirror of this file; shared prose is synced by hand, conditional sections are not. !}
```

Task 12 Step 4a does the docs-site half.

- [ ] **Step 5: Add the fragment overrides**

`application.properties`:

```properties
# Operator-supplied replacements for the shipped /privacy and /terms bodies. HTML FRAGMENTS (no
# <html>/<body>), served inside the normal layout. Rendered unescaped, at the same trust level as
# any other operator-supplied configuration -- this is the operator's own file, not user input.
app.privacy-policy-path=${PRIVACY_POLICY_PATH:}
app.terms-path=${TERMS_PATH:}
```

`.env.example`:

```bash
# PRIVACY_POLICY_PATH=/etc/calit/privacy.html   # HTML fragment replacing the shipped /privacy body
# TERMS_PATH=/etc/calit/terms.html              # HTML fragment replacing the shipped /terms body
```

In `LegalResource`, add an `override` parameter to both templates and read the file at render time:

```java
        public static native TemplateInstance privacy(String title, OgCard og, RawString override);

        public static native TemplateInstance terms(String title, OgCard og, RawString override);
```

```java
    /**
     * The operator's replacement body for a legal page, or null to render the shipped one. Read on
     * every request rather than cached at startup: an operator editing the file should see the
     * change without a restart, and these two pages are not hot. An unreadable path logs and falls
     * back to the shipped copy — a missing file must never take /privacy down, because the Google
     * consent screen links it.
     */
    private RawString fragment(Optional<String> path) {
        return path.filter(p -> !p.isBlank())
                .map(p -> {
                    try {
                        return new RawString(Files.readString(Path.of(p)));
                    } catch (IOException e) {
                        Log.warnf(e, "Could not read legal fragment %s; serving the shipped copy", p);
                        return null;
                    }
                })
                .orElse(null);
    }
```

The fragment is inserted as a `RawString`, so it is **not** Qute-parsed: an operator's file is
static HTML. `{inject:site.operatorName}`, `{msg:…}` and every other template expression are
unavailable inside it, and a stray `{` in their prose cannot blow up the render. That is the safe
direction, and the docs must say so — an operator who wants the controller name in their own copy
types it, because they know it and the template only guessed it from the base URL.

**Scope check on `terms.html`:** it needs the override wrapper and nothing else. It is four short
generic sections with no Google content, no retention claim and no factual statement this epic
falsifies, so it gets no `{inject:privacy.*}` conditionals. One honest addition, since Task 7 makes
it true — after the "Acceptable use" section:

```html
  <h2 class="text-xl font-semibold mt-6">Your account</h2>
  <p class="mt-2">You may delete your account at any time from <a class="link" href="/me/settings">Settings</a>. Deleting it removes your scheduling data from this instance.</p>
```

and in both templates, wrap the shipped body:

```html
{@io.quarkus.qute.RawString override}
{#include base title=title og=og}
{#if override}
<div class="max-w-3xl mx-auto">{override}</div>
{#else}
... the entire shipped body, unchanged ...
{/if}
{/include}
```

- [ ] **Step 6: Warn about special-category answers**

An owner can ask any question in a custom booking field, including Art. 9 special-category data, and calit stores the reply as plain text. Not fixable in code — say so where the field is created.

`AdminMessages` + `adm_de.properties`:

```java
    @Message("Answers are stored as plain text and shown to you and anyone with access to this site's database. Don't ask for health, biometric, religious, political or other sensitive details unless you have a lawful basis for holding them.")
    String adm_fields_sensitive_warning();
```

```properties
adm_fields_sensitive_warning=Antworten werden als Klartext gespeichert und sind für Sie und alle mit Zugriff auf die Datenbank dieser Website sichtbar. Fragen Sie nicht nach Gesundheits-, biometrischen, religiösen, politischen oder anderen sensiblen Angaben, sofern Sie keine Rechtsgrundlage für deren Speicherung haben.
```

In `src/main/resources/templates/AdminResource/bookingFields.html`, above the add-field form:

```html
  <div class="alert alert-warning max-w-2xl mb-4">
    <span>{adm:adm_fields_sensitive_warning}</span>
  </div>
```

- [ ] **Step 7: Run the test, then the full suite**

```bash
./mvnw -o test -Dtest=PrivacyPolicyRenderTest
./mvnw -o test
```

Expected: PASS / `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply && bun run format:fe
git add src/main src/test .env.example .beans/
git commit -m "feat(privacy): render the policy from what the deployment actually does"
```

---

## Task 12: Docs kit, config reference, changelog, translation issue

Docs are part of "done" here, not follow-up. This work lands on the **`docs-site`** branch, which is a separate Astro Starlight project — do not add these pages to `main`.

**Files (on `docs-site`):**
- Create: `docs-site/src/content/docs/compliance/operator-guide.md`
- Create: `docs-site/src/content/docs/compliance/records-of-processing.md`
- Create: `docs-site/src/content/docs/compliance/sub-processors.md`
- Create: `docs-site/src/content/docs/compliance/dpa-template.md`
- Create: `docs-site/src/content/docs/compliance/breach-checklist.md`
- Modify: `docs-site/src/content/docs/privacy.md` — the second hand-maintained copy of the policy; it stops claiming to be canonical (see Step 4a)
- Create: `docs-site/src/content/docs/compliance/custom-legal-pages.md`
- Modify: `docs-site/astro.config.mjs` — the sidebar is an explicit array, not autogenerated; a new directory is invisible until it has a group here
- Modify: `docs-site/src/content/docs/installation/configuration.md` (the config reference)
- Modify: `docs-site/src/content/docs/releases/changelog.md`

**Files (on `main`):**
- Modify: `README.md` (env-var reference table)

**Interfaces:**
- Consumes: `PersonalData.TABLES` (Task 2) as the source for the Art. 30 starter — the records page and the guard test must describe the same tables.
- Produces: nothing code depends on.

- [ ] **Step 1: File the Hebrew translation issue first**

Every string this epic added ships with English and German. Hebrew is deferred deliberately — the copy is legal-adjacent, nobody in the loop can review it, and a wrong nuance costs the operator. The gap is tracked, never silent.

```bash
gh issue create \
  --title "i18n(he): Hebrew translations for the GDPR/privacy copy" \
  --label translation \
  --body "$(cat <<'EOF'
The GDPR epic (calit-l3fk) added user-facing copy in two bundles. English and German shipped with
each PR; Hebrew was deliberately deferred — the copy is legal-adjacent and nobody in the review loop
can check the nuance. The keys below currently fall back to their English `@Message` defaults for
Hebrew viewers.

**AppMessages (`src/main/resources/messages/msg_he.properties`)**
`pub_erase_confirm_*`, `pub_erase_boundary_*`, `pub_erase_keep_btn`, `pub_erased_*`,
`pub_erase_disabled_notice*`, `pub_manage_download_data`

**AdminMessages (`src/main/resources/messages/adm_he.properties`)**
`adm_booking_invitee_erased`, `adm_delete_account_*`, `adm_users_delete`,
`adm_users_error_last_admin_delete`, `adm_settings_export_link`, `adm_settings_label_retention`,
`adm_settings_retention_hint`, `adm_fields_sensitive_warning`

Placeholder names (`{0}`) must stay identical across locales. Parity check: every `String key();`
in the bundle interface has a matching `key=` line in each locale file.
EOF
)"
```

Reference the issue number in every PR of this epic that added a key.

- [ ] **Step 2: Write the operator guide**

`docs-site/src/content/docs/compliance/operator-guide.md`. Open with the not-legal-advice note, then cover, in this order:

1. **You are the controller; the maintainers are not.** calit is software you host. The project's maintainers never see your deployment's data and cannot answer a request on your behalf.
2. **What calit does for you** — the invitee erase and export on the manage link, `/me/export`, account deletion (self-serve and admin), the retention sweep, the automatic purges of parked mail and expired auth tokens.
3. **What only you can do** — set `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL` so `/privacy` names a real controller and a real contact; decide your lawful basis; answer requests that arrive by email rather than through the manage link; maintain the Art. 30 records.
4. **The erasure boundary, verbatim from the spec's table** — Google's ~30-day trash, notification-channel messages that cannot be recalled, delivered SMTP, the `.ics` already in someone's calendar. State that the erase done page tells the invitee this too.
5. **Google grants are not revoked by account deletion.** Deleting an account removes calit's copy of the OAuth tokens. It does not withdraw the grant at Google; the user does that in their own Google account settings. (See Deviation 2.)
6. **The `answers` warning.** Custom booking fields store free text as plain text. An owner can ask for Art. 9 special-category data and calit will store it. There is no technical fix — the field editor carries the warning and this guide repeats it.
7. **Rows enqueued before V34** have no booking/owner link and are cleared only by the 30-day age purge, not by an erasure request. If you upgraded from before V34 and need those gone now, wait 30 days or clear `email_outbox` by hand.

- [ ] **Step 3: Write the Art. 30 starter**

`docs-site/src/content/docs/compliance/records-of-processing.md`. Fill the categories from `PersonalData.TABLES` so the document and the guard test describe the same schema — for each of *invitee*, *invitee's guests* and *host/owner*: categories of data, purpose, lawful basis (a blank for the operator), recipients, retention, and the erasure route. Leave the operator-specific fields as explicit blanks, not as invented examples.

- [ ] **Step 4: Write the remaining three pages**

- `sub-processors.md` — a table with Google (when configured), the operator's SMTP provider, and each notification-channel provider in use, each with blank columns for purpose, location and DPA link.
- `dpa-template.md` — for an operator hosting on someone else's behalf. Note in one line that this is a starting point for a lawyer, not a document to sign as-is.
- `breach-checklist.md` — the 72-hour clock, which tables hold what (from the inventory), which logs to pull (the `audit` logger category, and the `PRIVACY …` lines this epic emits), and who to notify.

- [ ] **Step 4a: Demote the docs-site privacy copy**

`docs-site/src/content/docs/privacy.md` is a SECOND hand-maintained copy of the same policy. Its
header comment calls itself canonical and tells the editor to mirror changes into
`LegalResource/privacy.html`; the landing page footer links it (`src/pages/index.astro:128`).

Task 11 breaks that arrangement on purpose. The app's copy now renders per deployment — no Google
sections without Google, a real retention statement, a deletion claim the software backs — and a
static markdown page cannot do any of that. Keeping the "same text" promise would mean either
freezing the app page or shipping a docs page that lies about deployments it does not describe.

So stop pretending they are one document, and give each a job:

- **The app page is authoritative for a running instance.** It is the URL an operator submits for
  Google OAuth verification, and the one a data subject is entitled to read.
- **The markdown page becomes the REFERENCE copy** — what the software does when everything is
  configured, for someone evaluating calit before installing it.

Concretely:

1. Replace the header comment with one that says the app page is authoritative and this page is the
   reference copy, so the next editor does not re-establish the mirror by hand.
2. Add a note directly under the frontmatter, above the existing `:::note[For operators]`:

```markdown
:::caution[This is the reference copy]
Your deployment's actual privacy policy is served by your own instance at
`${APP_BASE_URL}/privacy`. It describes only what that deployment really does — a deployment
without Google connected does not claim Google processing, and the retention section reflects the
window you configured. This page describes the software with every optional feature enabled, for
evaluation. Link the instance URL, not this one, from your OAuth consent screen.
:::
```

3. Mark each optional section in the prose — Google, notification channels, retention — with a
   short "when configured" qualifier, so the page is not read as a description of every deployment.
4. Correct the same falsehood Task 11 corrects in the template: account deletion now exists
   (`/me/settings/delete`), Google grants are NOT revoked by it, and the invitee manage link carries
   download and erase. Add the 30-day parked-mail and 24-hour token purges.
5. Bump `_Last updated:_` to the merge date.

ponytail: no generator, no shared partial, no build step that renders both from one source. Two
files with two different jobs and a note in each is less machinery than a cross-branch templating
scheme, and the drift it prevents is drift of prose a human has to approve anyway.

- [ ] **Step 4b: Write the custom-legal-pages page**

`docs-site/src/content/docs/compliance/custom-legal-pages.md`. This is where an operator who wants
their own policy starts — without it, `PRIVACY_POLICY_PATH` hands them a config knob and a blank
file. Cover, in this order:

1. **You do not need this.** calit serves a complete policy and terms with no configuration; set
   `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL` and the shipped pages name you as the controller.
   Override only when your legal position differs from what the software describes.

2. **What the file is.** An HTML *fragment* — no `<!DOCTYPE>`, `<html>`, `<head>` or `<body>`. It is
   served inside calit's normal layout, so it inherits the site nav, footer and Tailwind classes.

3. **It is not a template.** The file is inserted verbatim, not Qute-parsed. `{inject:site.*}`,
   `{msg:…}` and every other expression are inert inside it, and a stray `{` is harmless. Write your
   operator name and contact address as literal text.

4. **Where to start.** Not from a copy pasted into this page — that copy would go stale against the
   template the same way `privacy.md` did. Start from what your own instance already serves:

```bash
curl -s "$APP_BASE_URL/privacy" \
  | sed -n '/<!-- CALIT_LEGAL_PRIVACY -->/,/<\/div>/p' > /srv/calit/privacy.html
```

   That gives you your deployment's own rendered policy — Google sections present only if you use
   Google, your real retention window — as the starting point to edit. `/terms` has the matching
   `<!-- CALIT_LEGAL_TERMS -->` marker.

5. **Storing them on your filesystem, with Docker.** The path is read *inside the container*, so a
   host path needs a bind mount. Read-only is enough — calit never writes these:

```yaml
services:
  calit:
    image: ghcr.io/asm0dey/calit:1.25.1
    environment:
      PRIVACY_POLICY_PATH: /etc/calit/legal/privacy.html
      TERMS_PATH: /etc/calit/legal/terms.html
    volumes:
      - /srv/calit/legal:/etc/calit/legal:ro
```

   Running calit directly from the jar instead? Point the variables at any absolute path the
   process can read. A relative path resolves against the working directory, which is rarely what
   you meant — use absolute paths.

6. **Edits are live.** The file is read on each request, so a change shows up on the next page load
   with no restart. That also means the file must stay readable: if it disappears or the permissions
   break, calit logs a warning and falls back to the shipped copy rather than 500ing. `/privacy` is
   linked from the Google OAuth consent screen and must never go down.

7. **The path is trusted configuration**, at the same level as your database password — whatever you
   point it at is served to the public, so do not point it at a file you did not intend to publish.

8. **Multi-replica deployments:** every replica reads its own copy of the path. Mount the same file
   into all of them, or they will serve different policies.

- [ ] **Step 5: Register the section in the sidebar**

Starlight's sidebar in `docs-site/astro.config.mjs` is an explicit array — pages in a new directory do not appear on their own. Add a **Compliance** group between `Usage` and `Releases`, so it reads as "here is how you run this lawfully" after "here is how you use it":

```js
        {
          label: 'Compliance',
          items: [
            { label: 'GDPR operator guide', slug: 'compliance/operator-guide' },
            { label: 'Records of processing (Art. 30)', slug: 'compliance/records-of-processing' },
            { label: 'Sub-processors', slug: 'compliance/sub-processors' },
            { label: 'Customising the legal pages', slug: 'compliance/custom-legal-pages' },
            { label: 'DPA template', slug: 'compliance/dpa-template' },
            { label: 'Breach checklist', slug: 'compliance/breach-checklist' },
          ],
        },
```

Each `slug` must match the file path under `src/content/docs/` exactly, minus the extension — a typo is a build-time error, not a silent 404.

`starlightLinksValidator` is in the plugin list, so **every internal link in the new pages is validated at build time** and a wrong path fails `bun run build`. Cross-link the operator guide to `installation/configuration` and `usage/bookings` with real slugs, not guesses.

- [ ] **Step 6: Extend the config reference**

Add to the configuration reference page, in the table's existing style:

| Variable | Default | Meaning |
|---|---|---|
| `INVITEE_ERASURE` | `true` | Invitee self-service erasure on the manage link. Off = the manage page shows `PRIVACY_CONTACT_EMAIL` instead; the obligation to respond does not go away. |
| `BOOKING_RETENTION_DAYS` | unset | Anonymise bookings this many days after they end. Unset = keep forever. Each host may override it in their own settings. |
| `PRIVACY_POLICY_PATH` | unset | Path to an HTML fragment replacing the shipped `/privacy` body. |
| `TERMS_PATH` | unset | Path to an HTML fragment replacing the shipped `/terms` body. |

Document the two non-configurable constants in prose beneath the table: parked mail is deleted 30 days after it is sent or abandoned; password-reset and sign-in tokens are deleted a day after they expire.

Mirror the same four rows into the env-var reference in `README.md` on `main`, in the same commit as PR 4.

- [ ] **Step 7: Write the changelog bullets**

Under `## Unreleased` in `docs-site/src/content/docs/releases/changelog.md` (create the section with its standing subtitle if absent). House style: one flat statement per change, roughly 20–35 words, no bold lead-in, no before/after narrative, ending with the PR link.

```markdown
- Migration `V34` adds `booking.erased_at`, `owner_settings.booking_retention_days` and
  booking/owner links on `email_outbox`. No backfill; existing rows are untouched.
  ([#N](https://github.com/asm0dey/calit/pull/N))
- Invitees can download or erase their data from the manage link. Erasure cancels an upcoming
  booking first, then anonymises; `INVITEE_ERASURE=false` replaces the button with the operator's
  contact address. ([#N](https://github.com/asm0dey/calit/pull/N))
- Erasure cannot reach a delivered email, a chat notification or an `.ics` already in someone's
  calendar; the confirm and done pages say which copies survived.
  ([#N](https://github.com/asm0dey/calit/pull/N))
- Accounts can be deleted: `/me/settings/delete` for your own, `/me/users` for a site admin. The
  last enabled admin is refused. Google grants are not revoked — do that in your Google account.
  ([#N](https://github.com/asm0dey/calit/pull/N))
- `GET /me/export` returns your whole account as JSON. Notification-channel URLs and OAuth tokens
  are redacted. ([#N](https://github.com/asm0dey/calit/pull/N))
- `BOOKING_RETENTION_DAYS` anonymises bookings that many days after they end; unset keeps them
  forever, and each host may override it. ([#N](https://github.com/asm0dey/calit/pull/N))
- Parked emails are deleted 30 days after sending or abandonment, and expired password-reset and
  sign-in tokens a day after they lapse. Neither is configurable.
  ([#N](https://github.com/asm0dey/calit/pull/N))
- `/privacy` now describes what the deployment actually runs: no Google sections without Google, a
  real retention statement, and a deletion claim the software backs.
  ([#N](https://github.com/asm0dey/calit/pull/N))
- `PRIVACY_POLICY_PATH` and `TERMS_PATH` serve an operator's own HTML fragment in place of the
  shipped legal body. ([#N](https://github.com/asm0dey/calit/pull/N))
- New German copy ships with every string above; Hebrew is tracked in
  [#M](https://github.com/asm0dey/calit/issues/M) and falls back to English until then.
  ([#N](https://github.com/asm0dey/calit/pull/N))

Upgrade: nothing to do. Retention stays off until you set `BOOKING_RETENTION_DAYS` or a per-host
window. Emails parked before `V34` carry no booking link, so an erasure request does not clear
them — the 30-day purge does.
```

- [ ] **Step 8: Verify the docs build and commit**

```bash
git switch docs-site
# ... write the five pages, the sidebar group, the config rows and the changelog ...
cd docs-site && bun install && bun run build && cd ..
git add docs-site/
git commit -m "docs(compliance): GDPR operator guide, Art. 30 starter, sub-processors, DPA, breach checklist"
git push
```

`bun run build` is the gate, not a formality: `starlightLinksValidator` fails it on any broken internal
link, and a `slug` in the sidebar group that does not match a real file fails it too. Then open
`https://asm0dey.github.io/calit/` after the Pages deploy finishes and confirm **Compliance** is in
the sidebar with all five pages under it — a group that builds is not necessarily a group that shows
where you meant it to.

Then back on the feature branch for the README rows:

```bash
git switch gdpr-compliance
git add README.md .beans/
git commit -m "docs: document the four privacy env vars"
git push
gh pr create --title "feat(privacy): fact-driven policy copy and compliance docs" --body "..."
```

- [ ] **Step 9: Close out the epic**

Mark every child bean completed, add a `## Summary of Changes` section to `calit-l3fk`, and record the decisions in the precedent graph — the inventory-plus-guard-test choice over a registry or reflection, and the no-global-GDPR-switch choice, both with their rejected alternatives and rationale:

```bash
precedent check --topic "personal data inventory" --chose "hand-written inventory + schema guard test"
precedent record --topic "personal data inventory" \
  --chose "hand-written PersonalData inventory validated by an information_schema guard test" \
  --rejected "PersonalDataSource interface per module; reflection over JPA metadata" \
  --rationale "A registry buys extensibility with no second consumer; reflection hides the judgement that matters (which columns are personal, what erasure does with each). The safety property is the guard test, identical under all three."
precedent record --topic "GDPR mode switch" --chose "no global switch; features default on, two individually configurable" \
  --rejected "a single GDPR_MODE flag" \
  --rationale "Territorial scope follows the data subject, not the server. A switch removes the tools without removing the obligation."
```

Also record the ADR if this epic's approach belongs in `docs/adr/` — the inventory-and-guard-test decision does.

---

## Self-Review

Checked against `docs/superpowers/specs/2026-09-12-gdpr-compliance-design.md`.

**Spec coverage.** Every section maps to a task:

| Spec section | Task |
|---|---|
| §1 inventory + guard test | 2 |
| §1 defects (outbox orphan, missing cascade, `answers` warning) | 1, 3, 11 |
| §2 invitee `/data`, `/erase` pair, `INVITEE_ERASURE` | 5, 6 |
| §2 owner `/me/export`, `/me/settings/delete`, `/me/users/{id}/delete`, last-admin guard | 7, 8 |
| §2 erasure boundary, per-destination done page, log line | 4, 5 |
| §3 `BOOKING_RETENTION_DAYS`, per-owner override, `RetentionScheduler` | 9 |
| §3 outbox 30d, tokens 24h | 10 |
| §4 `PrivacyFacts`, conditional sections, `PRIVACY_POLICY_PATH`/`TERMS_PATH`, false deletion claim | 11 |
| §5 docs kit, config reference, changelog | 12 |
| §6 V34 migration, no backfill | 1 |
| §7 all six named tests | 2, 4, 5, 7, 8, 9, 11 (plus `V34MigrationTest`, `OutboxTagTest`, `PurgeSchedulerTest`) |
| §8 German in-change, Hebrew tracked | every task; issue filed in 12 |
| "Deliberately not built" | nothing in this plan builds any of them |

**Placeholders.** One deliberate ellipsis remains: the `Set.of(...)` column lists in `PersonalData` (Task 2, Step 3). Typing thirty column lists from memory would be guessing; the step gives the exact `psql` query that generates them and the guard test is what proves the result is complete. Every other code block is literal.

**Type consistency.** `MailTag` is introduced in Task 3 and used by name in Tasks 4 and 7. `ErasureReport.GoogleOutcome` is defined in Task 4 and referenced by the `erased.html` template in Task 5 via `report.google.name`. `PrivacyService.anonymise(Long)` is written in Task 4 and called by `RetentionScheduler` in Task 9 under the same signature. `Booking.isErased()` from Task 1 is what Qute's `{b.erased}` resolves against in Task 5. `PersonalData.Classified.columns()` is what both the guard test (Task 2) and `AccountDeletionTest` (Task 7) walk.

Three assumptions the implementer must verify against the code before writing, each flagged at its step: the auto-generated FK constraint name in Task 1, `PasswordHasher`'s verify method name in Task 7, and the real field names on `AvailabilityRule` / `DateOverride` / `GoogleCredential` / `NotificationChannel` / `PasswordResetToken` / `LoginTicket` in Tasks 8 and 10.
