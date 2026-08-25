# Selectable Booking Duration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A meeting type can offer several lengths; the Invitee picks one on the public page and the slot grid re-renders for it.

**Architecture:** A new `meeting_type_duration` table holds the extra lengths, each with optional per-duration buffers. The meeting type's existing `duration_minutes` stays the default and is an implicit member of the set, so no backfill. The chosen length threads explicitly through `SlotService` → `BookingService` → `MeetingHosts` as an `int` parameter with defaulting overloads, so every existing caller keeps compiling. The public page carries the choice as a `?duration=` query param on the existing GET, which keeps it working without JavaScript. The same grid line also gains a fix for a pre-existing multi-host bug: hosts in different timezones currently intersect to zero slots.

**Tech Stack:** Quarkus 3.38, Java 25, Panache/Hibernate ORM, Flyway, Qute `@CheckedTemplate`, Tailwind v4 + daisyUI 5, JUnit 5 + RestAssured.

**Spec:** `docs/superpowers/specs/2026-08-25-selectable-booking-duration-design.md`

**Beans:** `calit-p5xm` (the feature), `calit-io9y` (the lattice bug, child of p5xm)

**ADRs:** [0002](../../adr/0002-buffers-are-constraints-not-settings.md) buffers are constraints · [0003](../../adr/0003-a-meeting-types-duration-doubles-as-its-default.md) duration doubles as default · [0008](../../adr/0008-the-slot-lattice-is-anchored-to-the-creators-clock.md) lattice anchored to the Creator's clock

## Global Constraints

- **Docker must be running** for `mvn test` and `mvn quarkus:dev` — Quarkus Dev Services provisions Postgres. No H2 fallback.
- **Build JDK is 26.** Run `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` before `./mvnw`, or the build fails with "release 25 not supported".
- **Never edit an applied Flyway migration.** Latest applied is `V28`. This plan adds `V29` only.
- **Every query must be owner-scoped.** `MeetingTypeDuration` rows are reached through a `meeting_type_id` that the caller has already owner-checked via `requireType(id)` / `CurrentOwner`. Never load them by duration alone.
- **Progressive enhancement is mandatory.** Every feature here works with JavaScript disabled. The duration picker is anchor links; the durations editor is a plain form.
- **Every new or changed user-facing string ships with its `de` AND `he` translation** in `src/main/resources/messages/{msg,adm}_{de,he}.properties`, keyed by the bundle method name. Placeholder names identical across locales.
- **`reuseForks=true`:** one JVM and one Dev Services Postgres are shared across same-profile `@QuarkusTest` classes. `DatabaseResetCallback` truncates and reseeds per test; **the admin user is always id 1**.
- **Only one `mvn test` at a time** against the reused container — `clean-at-start` drops the schema at boot.
- **`mvn test` must be fully green before this branch becomes a PR.** 0 failures, 0 errors, `BUILD SUCCESS`, whole suite.
- **Formatting:** `bun run format` before committing, or let the lefthook pre-commit hook run `spotless:apply` on staged Java.
- **RestAssured cannot execute JavaScript.** Assert on rendered HTML, never on script behaviour.

---

### Task 1: Group `Templates.book`'s chrome and captcha parameters

`Templates.book` takes 14 positional arguments including a bare `null` and a bare `""`, and is called from two places that must stay in lockstep. This task is mechanical and changes no behaviour — it exists so the feature's diff lands on a signature a reviewer can read.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java:38-58` (`Templates.book` declaration), `:219-244` (GET `book`), `:355-370` (POST error re-render)
- Modify: `src/main/resources/templates/PublicResource/book.html`
- Test: no new test. The proof of no-behaviour-change is that the existing renderers of `book.html` still pass untouched — `BookPageTest`, `BookPageTurnstileEnabledTest`, `BookPageAltchaEnabledTest`, `BookingPostTest`, `GuestBookingFlowTest`, `CsrfEnforcementTest`. Do not edit any of them.

**Interfaces:**
- Consumes: nothing
- Produces: `PublicResource.Chrome(String tzBar, String tzScript, String calScript)`, `PublicResource.Captcha(String provider, String siteKey)`, and a `Templates.book` whose 5 chrome/captcha params are replaced by those two records.

- [ ] **Step 1: Run the existing public-page tests to establish a green baseline**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest='Public*Test'
```

Expected: PASS. This task must leave these tests passing without editing them — that is the proof it changed no behaviour.

- [ ] **Step 2: Add the two records next to the existing view-model records**

In `PublicResource.java`, beside `public record LandingType(MeetingType type, String bookUrl) {}` at line 163:

```java
/** The page furniture every booking-page render passes identically: timezone bar + its scripts. */
public record Chrome(String tzBar, String tzScript, String calScript) {}

/** Captcha wiring for the booking form; siteKey is public and rendered into the page. */
public record Captcha(String provider, String siteKey) {}
```

- [ ] **Step 3: Change the template declaration**

Replace the `book` declaration in the `Templates` class (lines 44-58) with:

```java
public static native TemplateInstance book(
        String title,
        String user,
        MeetingType type,
        List<DaySlots> days,
        List<BookingField> fields,
        String error,
        Chrome chrome,
        Captcha captcha,
        boolean googleConnected,
        String ownerName,
        String initialGuests);
```

- [ ] **Step 4: Update both call sites**

In the GET handler (`book`, around line 231):

```java
return Templates.book(
        bookTitle,
        urlUser.username,
        type,
        byDate,
        fields,
        null,
        new Chrome(Layout.tzBar(m), Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT),
        new Captcha(captchaProviderConfig.provider(), turnstileSiteKey()),
        calendarPort.isConnected(type.ownerId),
        settings.ownerName,
        "");
```

In the POST error re-render (around line 358), identically but with `daySlots(type)`, `BookingField.formFor(...)` and `be.getMessage()` in place of `byDate`, `fields` and `null`.

- [ ] **Step 5: Update the template's references**

In `src/main/resources/templates/PublicResource/book.html`, replace:

| Old | New |
|---|---|
| `{tzBar.raw}` | `{chrome.tzBar.raw}` |
| `{tzScript.raw}` | `{chrome.tzScript.raw}` |
| `{calScript.raw}` | `{chrome.calScript.raw}` |
| `{captchaProvider}` | `{captcha.provider}` |
| `{turnstileSiteKey}` | `{captcha.siteKey}` |

Find every occurrence first — do not assume one each:

```bash
grep -n 'tzBar\|tzScript\|calScript\|captchaProvider\|turnstileSiteKey' src/main/resources/templates/PublicResource/book.html
```

- [ ] **Step 6: Run the tests**

```bash
./mvnw test -Dtest='Public*Test'
```

Expected: PASS, with no test file edited. A Qute compile error here means a template reference was missed in Step 5 — the build fails at compile time, not at render time, because `@CheckedTemplate` is checked.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/PublicResource.java src/main/resources/templates/PublicResource/book.html
git commit -m "refactor(web): group the booking page's chrome and captcha params

Templates.book took 14 positional arguments including a bare null and
a bare empty string, from two call sites that must stay in lockstep.
No behaviour change."
```

---

### Task 2: The `meeting_type_duration` table and its entity

**Files:**
- Create: `src/main/resources/db/migration/V29__meeting_type_duration.sql`
- Create: `src/main/java/site/asm0dey/calit/domain/MeetingTypeDuration.java`
- Test: `src/test/java/site/asm0dey/calit/domain/MeetingTypeDurationTest.java`

**Interfaces:**
- Consumes: `MeetingType` (fields `id`, `durationMinutes`, `bufferBeforeMinutes`, `bufferAfterMinutes`)
- Produces:
  - `MeetingTypeDuration` entity, public fields `meetingTypeId: Long`, `durationMinutes: int`, `bufferBeforeMinutes: Integer`, `bufferAfterMinutes: Integer`
  - `static List<Integer> allowedDurations(MeetingType type)` — sorted ascending, always contains `type.durationMinutes`
  - `static int shortestAllowed(MeetingType type)`
  - `static MeetingTypeDuration findRow(Long meetingTypeId, int durationMinutes)` — null when absent
  - `static boolean isAllowed(MeetingType type, int durationMinutes)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/domain/MeetingTypeDurationTest.java`:

```java
package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MeetingTypeDurationTest {

    /** Admin is always owner id 1 (DatabaseResetCallback reseeds it per test). */
    private static final Long OWNER = 1L;

    @Transactional
    MeetingType seedType(String slug, int durationMinutes) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = durationMinutes;
        t.persist();
        return t;
    }

    @Transactional
    void seedDuration(Long typeId, int minutes, Integer before, Integer after) {
        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = typeId;
        d.durationMinutes = minutes;
        d.bufferBeforeMinutes = before;
        d.bufferAfterMinutes = after;
        d.persist();
    }

    @Test
    void emptyTableMeansTheSetIsExactlyTheDefault() {
        MeetingType t = seedType("empty-set", 30);
        assertEquals(List.of(30), MeetingTypeDuration.allowedDurations(t));
        assertEquals(30, MeetingTypeDuration.shortestAllowed(t));
    }

    @Test
    void theDefaultIsAnImplicitMemberEvenWhenTheTableOmitsIt() {
        MeetingType t = seedType("implicit-default", 60);
        seedDuration(t.id, 30, null, null);
        seedDuration(t.id, 120, 45, 45);
        assertEquals(List.of(30, 60, 120), MeetingTypeDuration.allowedDurations(t));
        assertEquals(30, MeetingTypeDuration.shortestAllowed(t));
    }

    @Test
    void aRowForTheDefaultDoesNotDuplicateIt() {
        MeetingType t = seedType("default-row", 60);
        seedDuration(t.id, 60, 15, 15);
        assertEquals(List.of(60), MeetingTypeDuration.allowedDurations(t));
    }

    @Test
    void isAllowedAcceptsTheDefaultAndConfiguredLengthsOnly() {
        MeetingType t = seedType("allowed-check", 60);
        seedDuration(t.id, 120, null, null);
        assertTrue(MeetingTypeDuration.isAllowed(t, 60));
        assertTrue(MeetingTypeDuration.isAllowed(t, 120));
        assertFalse(MeetingTypeDuration.isAllowed(t, 45));
    }

    @Test
    void findRowReturnsTheBufferOverridesOrNull() {
        MeetingType t = seedType("find-row", 30);
        seedDuration(t.id, 120, 45, 50);
        MeetingTypeDuration row = MeetingTypeDuration.findRow(t.id, 120);
        assertNotNull(row);
        assertEquals(45, row.bufferBeforeMinutes);
        assertEquals(50, row.bufferAfterMinutes);
        assertNull(MeetingTypeDuration.findRow(t.id, 30));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=MeetingTypeDurationTest
```

Expected: compile failure — `MeetingTypeDuration` does not exist.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V29__meeting_type_duration.sql`:

```sql
-- The set of lengths a meeting type may be booked at, beyond its own duration_minutes,
-- which is an implicit member (ADR-0003). DDL only: an empty table means the set is
-- exactly {duration_minutes}, so every existing type is already valid.
create table meeting_type_duration (
    meeting_type_id       bigint not null references meeting_type (id) on delete cascade,
    duration_minutes      int    not null check (duration_minutes > 0),
    buffer_before_minutes int    null check (buffer_before_minutes >= 0),
    buffer_after_minutes  int    null check (buffer_after_minutes >= 0),
    primary key (meeting_type_id, duration_minutes)
);
```

The composite primary key is the natural key: it rules out a duplicate length for free, so there is no surrogate `id`, no `position` column and no ordering UI.

- [ ] **Step 4: Write the entity**

Create `src/main/java/site/asm0dey/calit/domain/MeetingTypeDuration.java`:

```java
package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One length a meeting type may be booked at, with optional buffer overrides for that length.
 *
 * <p>The meeting type's own {@code durationMinutes} is an IMPLICIT member of the set — see
 * {@link #allowedDurations(MeetingType)} and
 * {@code docs/adr/0003-a-meeting-types-duration-doubles-as-its-default.md}. A row whose
 * {@code durationMinutes} equals the default therefore carries only that length's buffers;
 * deleting it drops the buffers, never the duration.
 */
@Entity
@Table(name = "meeting_type_duration")
@IdClass(MeetingTypeDuration.Key.class)
public class MeetingTypeDuration extends PanacheEntityBase {

    /** Composite key mirroring the table's natural primary key. */
    public static class Key implements Serializable {
        public Long meetingTypeId;
        public int durationMinutes;

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(meetingTypeId, k.meetingTypeId)
                    && durationMinutes == k.durationMinutes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(meetingTypeId, durationMinutes);
        }
    }

    @Id
    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    @Id
    @Column(name = "duration_minutes", nullable = false)
    public int durationMinutes;

    /** Null = this length imposes no buffer of its own; see ADR-0002 for how it combines. */
    @Column(name = "buffer_before_minutes")
    public Integer bufferBeforeMinutes;

    /** Null = this length imposes no buffer of its own; see ADR-0002 for how it combines. */
    @Column(name = "buffer_after_minutes")
    public Integer bufferAfterMinutes;

    /** Configured rows for a type, shortest first. Does NOT include the implicit default. */
    public static List<MeetingTypeDuration> rowsFor(Long meetingTypeId) {
        return list("meetingTypeId = ?1 order by durationMinutes", meetingTypeId);
    }

    /**
     * Every length this type may be booked at, shortest first: the configured rows unioned with the
     * type's own {@code durationMinutes}, which is why the set can never omit its default.
     */
    public static List<Integer> allowedDurations(MeetingType type) {
        List<Integer> all = new ArrayList<>();
        all.add(type.durationMinutes);
        for (MeetingTypeDuration d : rowsFor(type.id)) {
            if (d.durationMinutes != type.durationMinutes) {
                all.add(d.durationMinutes);
            }
        }
        all.sort(Integer::compareTo);
        return all;
    }

    /** The cadence anchor: the shortest length on offer, which is NOT necessarily the default. */
    public static int shortestAllowed(MeetingType type) {
        return allowedDurations(type).getFirst();
    }

    public static boolean isAllowed(MeetingType type, int durationMinutes) {
        return allowedDurations(type).contains(durationMinutes);
    }

    /** The buffer-override row for one length, or null when that length has none. */
    public static MeetingTypeDuration findRow(Long meetingTypeId, int durationMinutes) {
        return find("meetingTypeId = ?1 and durationMinutes = ?2", meetingTypeId, durationMinutes)
                .firstResult();
    }
}
```

- [ ] **Step 5: Run the test**

```bash
./mvnw test -Dtest=MeetingTypeDurationTest
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V29__meeting_type_duration.sql \
        src/main/java/site/asm0dey/calit/domain/MeetingTypeDuration.java \
        src/test/java/site/asm0dey/calit/domain/MeetingTypeDurationTest.java
git commit -m "feat(domain): a meeting type's allowed durations

New meeting_type_duration table, one row per extra length with
optional per-duration buffers. The type's own duration_minutes is an
implicit member of the set (ADR-0003), so the migration is DDL only
and every existing type is already valid."
```

---

### Task 3: Parameterise slot generation by the chosen duration

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/availability/SlotService.java:26-81`
- Test: `src/test/java/site/asm0dey/calit/availability/SlotServiceDurationTest.java` (create)

**Interfaces:**
- Consumes: `MeetingTypeDuration.shortestAllowed(MeetingType)` from Task 2
- Produces: `SlotService.generateRawSlots(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to, boolean dayAnchoredGrid, int durationMinutes)`. The pre-existing 5-arg overload delegates with `type.durationMinutes`, so no existing caller changes.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/availability/SlotServiceDurationTest.java`:

```java
package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SlotServiceDurationTest {

    private static final Long OWNER = 1L;

    @Inject
    SlotService slotService;

    /** A Monday well clear of "now" so min-notice/horizon filters (applied elsewhere) never bite. */
    private static final LocalDate MONDAY = LocalDate.of(2027, 3, 1);

    @Transactional
    MeetingType seed(String slug, int defaultMinutes, List<Integer> extraLengths) {
        return seed(slug, defaultMinutes, extraLengths, null);
    }

    /** Seeds OwnerSettings too — generateRawSlots reads the host's timezone and throws without it. */
    @Transactional
    MeetingType seed(String slug, int defaultMinutes, List<Integer> extraLengths, Integer cadence) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = defaultMinutes;
        t.slotIntervalMinutes = cadence;
        t.persist();
        for (int len : extraLengths) {
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = len;
            d.persist();
        }
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = OWNER;
        r.meetingTypeId = t.id;
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(12, 0);
        r.persist();
        return t;
    }

    private List<LocalTime> startsFor(MeetingType t, int duration) {
        return slotService.generateRawSlots(t, OWNER, MONDAY, MONDAY, false, duration).stream()
                .map(s -> s.start().toLocalTime())
                .toList();
    }

    @Test
    void theLatticeIsAnchoredToTheShortestLengthNotTheChosenOne() {
        MeetingType t = seed("lattice", 60, List.of(30, 120));

        // Shortest allowed is 30, so candidate starts are every 30 minutes for BOTH picks.
        assertEquals(
                List.of(
                        LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0),
                        LocalTime.of(10, 30), LocalTime.of(11, 0), LocalTime.of(11, 30)),
                startsFor(t, 30));

        // 120 keeps the same lattice and simply drops the starts that run past 12:00.
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)), startsFor(t, 120));
    }

    @Test
    void aSingleDurationTypeIsUnchangedByTheNewParameter() {
        MeetingType t = seed("single", 45, List.of());
        // Cadence falls back to the shortest allowed, which for a single-duration type IS the duration.
        // 11:15 belongs: its body ends exactly at 12:00, and the window end is inclusive
        // (`s + duration <= endMin`), as SlotServiceTest#generatesBackToBackSlotsWithinGlobalWindow shows.
        assertEquals(
                List.of(LocalTime.of(9, 0), LocalTime.of(9, 45), LocalTime.of(10, 30), LocalTime.of(11, 15)),
                startsFor(t, 45));
        // and the old overload agrees with the explicit one
        assertEquals(
                startsFor(t, 45),
                slotService.generateRawSlots(t, OWNER, MONDAY, MONDAY, false).stream()
                        .map(s -> s.start().toLocalTime())
                        .toList());
    }

    @Test
    void anExplicitCadenceStillWins() {
        // slotIntervalMinutes is set INSIDE the seeding transaction — assigning it to a detached
        // entity afterwards would never reach the row SlotService reads back.
        MeetingType t = seed("explicit-cadence", 60, List.of(30), 60);
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0)), startsFor(t, 30));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=SlotServiceDurationTest
```

Expected: compile failure — no 6-arg `generateRawSlots`.

- [ ] **Step 3: Add the overload and change the two grid inputs**

In `SlotService.java`, keep every existing overload and add the duration to the deepest one. Replace the existing 5-arg method (line 51 onward) with a delegating overload plus the new 6-arg body:

```java
public List<TimeSlot> generateRawSlots(
        MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to, boolean dayAnchoredGrid) {
    return generateRawSlots(type, hostOwnerId, from, to, dayAnchoredGrid, type.durationMinutes);
}

/**
 * Same as {@link #generateRawSlots(MeetingType, Long, LocalDate, LocalDate, boolean)} for a chosen
 * length. The grid STEP comes from the type's shortest allowed length, not from
 * {@code durationMinutes}: the lattice of candidate starts must not move when an Invitee switches
 * length (ADR-0003). Only the slot BODY varies.
 */
public List<TimeSlot> generateRawSlots(
        MeetingType type,
        Long hostOwnerId,
        LocalDate from,
        LocalDate to,
        boolean dayAnchoredGrid,
        int durationMinutes) {
    // ... existing body, with the two lines inside the window loop replaced:
    //     int step = ...;  int duration = ...;
}
```

Inside the window loop, replace:

```java
int step = type.effectiveSlotIntervalMinutes();
int duration = type.durationMinutes;
```

with:

```java
// Cadence: an explicit interval wins; otherwise the SHORTEST allowed length, so the lattice
// stays put when the Invitee switches. Not the chosen length, and not the default.
int step = (type.slotIntervalMinutes != null && type.slotIntervalMinutes > 0)
        ? type.slotIntervalMinutes
        : MeetingTypeDuration.shortestAllowed(type);
int duration = durationMinutes;
```

Hoist both out of the per-window loop to the top of the method — they do not vary per window, and `shortestAllowed` issues a query.

- [ ] **Step 4: Deprecate the now-partial entity helper**

`MeetingType.effectiveSlotIntervalMinutes()` still returns `durationMinutes` as its fallback, which is now wrong for a multi-duration type. It cannot simply be fixed in place — the correct fallback needs a query, and an entity method should not issue one. Leave the method, and add to its javadoc:

```java
/**
 * Cadence (minutes) between consecutive slot starts; falls back to the duration when unset.
 *
 * <p><strong>Not the slot lattice.</strong> {@code SlotService} falls back to the type's SHORTEST
 * allowed length instead, because the lattice must not move when an Invitee switches length. This
 * method is kept for callers that only want the configured value.
 */
```

Then check whether anything else still calls it:

```bash
grep -rn 'effectiveSlotIntervalMinutes' src/main src/test
```

If nothing outside `MeetingType` and its own tests calls it, delete it in this commit and drop this step's javadoc edit.

- [ ] **Step 5: Run the test**

```bash
./mvnw test -Dtest=SlotServiceDurationTest
```

Expected: PASS, 3 tests.

- [ ] **Step 6: Run the whole availability and booking suite to prove nothing moved**

```bash
./mvnw test -Dtest='SlotService*Test,Booking*Test'
```

Expected: PASS. Any failure here means an existing type's slot set changed, which it must not.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/availability/SlotService.java \
        src/main/java/site/asm0dey/calit/domain/MeetingType.java \
        src/test/java/site/asm0dey/calit/availability/SlotServiceDurationTest.java
git commit -m "feat(availability): generate slots for a chosen duration

The grid step now falls back to the type's shortest allowed length
rather than its duration, so the lattice of candidate starts does not
move when an invitee switches length. Only the slot body varies.
Single-duration types are unaffected: shortest == duration."
```

---

### Task 4: One shared lattice per type, defined in the Creator's clock (`calit-io9y`)

A pre-existing bug, fixed on the line Task 3 just touched. Multi-host slots are anchored to 00:00 in **each host's own** timezone, and `BookingService:145` intersects the per-host free sets by exact start instant. Host-local midnight is a different instant per host, so two hosts' grids coincide only when their UTC offsets differ by a whole number of `step`. London and Berlin on a 45-minute cadence differ by 60, which is not a multiple of 45 — the intersection is empty every day, forever, and the page renders the ordinary "no times available" state.

**A first attempt at this task landed as `2a59d0f` and is superseded.** It used a fixed origin instant (`LocalDate.EPOCH.atStartOfDay(creatorZone)`) plus multiples of the step. That is correct about host agreement but freezes the zone's 1970 rules, so it shifted the start times of teams that never had the bug — `Asia/Kathmandu` was `+05:30` until 1986 and `+05:45` since, putting an all-Kathmandu team on `09:15`/`09:45` where they see `09:00`/`09:30` today. Read [ADR-0008](../../adr/0008-the-slot-lattice-is-anchored-to-the-creators-clock.md) before starting; it records both the rejected shape and the current one.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/availability/SlotService.java` — replace the `Instant gridAnchor` parameter and `gridAnchorFor` introduced by `2a59d0f`
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:129-152` (`availableSlots`), `:164-194` (`hostFreeSlots`)
- Modify: `src/test/java/site/asm0dey/calit/availability/SlotServiceDurationTest.java` — Task 3 wrote it against the `boolean`; `2a59d0f` changed those to `null`, which stays valid
- Modify: `src/test/java/site/asm0dey/calit/availability/SlotServiceLatticeTest.java` — rewritten by `2a59d0f`; this task replaces its anchor-shaped assertions
- Modify: `src/test/java/site/asm0dey/calit/availability/SlotServiceTest.java` — add the DST-window case below

**Interfaces:**
- Consumes: `generateRawSlots(..., Instant gridAnchor, int durationMinutes)` as `2a59d0f` left it
- Produces: `generateRawSlots(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to, ZoneId latticeZone, int durationMinutes)` — nullable `ZoneId` replaces the `Instant`. Null means window-anchored (single-host, historical behaviour). Also `SlotService.latticeZoneFor(MeetingType type)` returning the Creator's zone; `gridAnchorFor` is deleted.

- [ ] **Step 1: Rewrite the lattice test class**

Replace `SlotServiceLatticeTest`'s anchor-shaped helpers and assertions. Keep the seeding fixes `2a59d0f` made (they were real: `AppUser` has no `email` field, `roles`/`createdAt`/`ownerEmail` are NOT NULL, and the Creator's `OwnerSettings` row must exist even when the Creator is not a Host). The class must contain exactly these cases:

1. `hostsAnHourApartShareALatticeOnAFortyFiveMinuteCadence` — London + Berlin, cadence 45, both 09:00–17:00 local. The intersection must be non-empty.
2. `aQuarterHourOffsetZoneStillShares` — Berlin + Kathmandu, cadence 30. Non-empty.
3. `aTwentyNineMinuteCadenceStillIntersectsAcrossAFourHourFortyFiveOffset` — Berlin 09:00–17:00 against Kathmandu 11:00–19:00, cadence 29, on `2027-03-01` (a Monday, before EU DST starts on the 28th). Assert the intersection is non-empty; that consecutive shared starts are **exactly** 29 minutes apart, which is what proves a single comb rather than two that happen to touch; that no shared start precedes Berlin's open and none has a body running past Kathmandu's close; and the exact count the arithmetic determines.
4. `anAllKathmanduTeamKeepsRoundLocalTimes` — **the case the previous design failed.** Creator and both hosts in `Asia/Kathmandu`, window 09:00–17:00 local, cadence 30. Every start's local time must be `:00` or `:30` — assert the first start is exactly 09:00 local. A fixed-epoch-origin implementation yields 09:15 here and must fail this test.
5. `theLatticeIsRoundInTheCreatorsZoneNotTheHosts` — Creator in `Asia/Kolkata` (`+05:30`, a half-hour zone), single Host in `Europe/Berlin`, cadence 30. Every start must be `:00`/`:30` in **Kolkata** and therefore `:00`/`:30` in Berlin too (their offsets differ by a multiple of 30); then repeat with the Host in `Asia/Kathmandu` and assert the Host's local minutes are `:15`/`:45` while Kolkata's remain `:00`/`:30`. This is what fails if the implementation uses the Host's zone or plain UTC.
6. `theLatticeDoesNotMoveWithTheRequestedRange` — cadence 50 (which does not divide 1440), one host. The starts produced for day `D` when the requested range is `D-3 … D+3` must equal those produced when the range is `D … D`. This is what fails if the lattice is derived from the request's own start date.
7. `aNullLatticeZoneKeepsWindowAnchoringForSingleHost` — the first slot IS the window start.

Cases 4, 5 and 6 are the point of this rewrite: the previous attempt's tests passed even for a plain-UTC implementation and even for one anchored to `LocalDate.now()`, because every one of them compared a lattice only against itself.

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=SlotServiceLatticeTest
```

Expected: compile failure (no `latticeZoneFor`), and once that is stubbed, cases 4 and 5 fail against the `2a59d0f` implementation. Confirm case 4 fails with 09:15 before you change the production code — that failure is the whole justification for this task.

- [ ] **Step 3: Replace `gridAnchorFor` with `latticeZoneFor`**

```java
/**
 * The zone whose clock defines this type's lattice of candidate start times: the CREATOR's
 * (ADR-0008). Start times come out round on the clock of whoever defined the meeting type, and
 * every Host tests the same predicate against the same instants, so Hosts cannot disagree about
 * which instants are candidates.
 *
 * <p>The zone's rules are consulted at each candidate instant rather than frozen at some origin
 * date. That is deliberate: {@code Asia/Kathmandu} was {@code +05:30} until 1986 and {@code +05:45}
 * since, so an origin-based lattice would move an all-Kathmandu team off the round local times they
 * have today, to fix a cross-timezone problem they do not have.
 */
public ZoneId latticeZoneFor(MeetingType type) {
    OwnerSettings creator = OwnerSettings.forOwner(type.ownerId);
    if (creator == null) {
        throw new IllegalStateException("Owner settings not configured for owner " + type.ownerId
                + "; set them via /me/settings before generating slots.");
    }
    return ZoneId.of(OwnerSettings.coerceZone(creator.timezone));
}
```

The null guard matches the one `generateRawSlots` already applies to the host's settings twenty lines below; without it a multi-host type whose Creator is not among the accepted hosts NPEs before any host is touched, instead of producing that actionable message. Use `coerceZone` here **and** make the neighbouring `generateRawSlots` read of `settings.timezone` use it too — a row written before the save-time guard existed can hold an unparseable zone.

- [ ] **Step 4: Walk candidate starts in local time**

Replace the per-window loop body:

```java
for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
    for (Window window : availability.windowsFor(date)) {
        Instant windowStart = date.atTime(window.start()).atZone(zone).toInstant();
        Instant windowEnd = date.atTime(window.end()).atZone(zone).toInstant();
        long bodySeconds = duration * 60L;
        if (latticeZone == null) {
            // Window-anchored (single-host): the first slot IS the window start, byte-identical to
            // the historical behaviour, including per-window anchoring on a multi-window day.
            for (Instant s = windowStart;
                    !s.plusSeconds(bodySeconds).isAfter(windowEnd);
                    s = s.plusSeconds(step * 60L)) {
                slots.add(new TimeSlot(s.atZone(zone), s.plusSeconds(bodySeconds).atZone(zone)));
            }
        } else {
            // Lattice-anchored (multi-host): candidate starts are the instants whose local
            // time-of-day in the CREATOR's zone is a whole number of steps past midnight.
            LocalDateTime local = windowStart.atZone(latticeZone).toLocalDateTime().withSecond(0).withNano(0);
            int intoDay = local.getHour() * 60 + local.getMinute();
            int over = intoDay % step;
            if (over != 0) {
                local = local.plusMinutes(step - (long) over);
            }
            while (true) {
                // Step in LOCAL terms and re-resolve. ZonedDateTime.plusMinutes works on the
                // instant time-line, so it would drift an hour off round after a fall-back.
                Instant s = local.atZone(latticeZone).toInstant();
                if (s.plusSeconds(bodySeconds).isAfter(windowEnd)) {
                    break;
                }
                if (!s.isBefore(windowStart)) {
                    slots.add(new TimeSlot(s.atZone(zone), s.plusSeconds(bodySeconds).atZone(zone)));
                }
                local = local.plusMinutes(step);
            }
        }
    }
}
```

The window end stays **inclusive** — a slot whose body ends exactly at the window end is offered, as `SlotServiceTest#generatesBackToBackSlotsWithinGlobalWindow` has always asserted. A window ending at 00:00 still yields nothing, because `windowEnd` then precedes `windowStart`.

- [ ] **Step 5: Pass the zone from `BookingService`**

```java
var singleHost = hostIds.size() == 1;
// Single-host stays window-anchored (null). Multi-host shares one lattice (ADR-0008).
ZoneId latticeZone = singleHost ? null : slotService.latticeZoneFor(type);
```

Thread `latticeZone` through `hostFreeSlots` in place of the anchor. Keep the `singleHost` boolean itself — it also selects the exception-handling contract. `hostFreeSlots` does not yet take a duration; pass `type.durationMinutes` until Task 6 replaces it.

- [ ] **Step 6: Pin the DST-in-window behaviour**

The old code walked host-local minute-of-day; this one walks instants on the null-anchor path. For a window straddling a DST transition they differ: on fall-back the instant walk covers the full elapsed time, including both passes of the ambiguous hour, where minute-of-day walked wall time once. The new behaviour is the correct one — a host available 01:00–05:00 local on a fall-back day genuinely has five hours — but it is a single-host production change, so pin it. Add to `SlotServiceTest`:

`aWindowStraddlingAFallBackTransitionCoversTheFullElapsedTime` — a `Europe/Berlin` host, a window containing the October fall-back, a cadence that divides the window, asserting the slot count matches the elapsed real time rather than the wall-clock span, with a comment saying this is deliberate and why.

- [ ] **Step 7: Run the lattice and duration tests**

```bash
./mvnw test -Dtest='SlotService*Test'
```

Expected: PASS, including cases 4, 5 and 6 which failed in Step 2.

- [ ] **Step 8: Run the full suite — this task changes shared behaviour**

```bash
./mvnw test
```

Expected: `BUILD SUCCESS`. A multi-host test whose hosts share a timezone must produce the same slots as before; if one fails, the lattice's phase is wrong, not the test.

- [ ] **Step 9: Update the bean and commit**

Check off `calit-io9y`'s items. The 50-minute `assertSlotAvailable` item is satisfied by case 6 — a 50-minute cadence is settable today via `slotIntervalMinutes`, so that item was never blocked on a later task; correct the note if it says otherwise. Leave only the docs-site changelog item open, and set the bean's status accordingly.

```bash
git add src/main/java/site/asm0dey/calit/availability/SlotService.java \
        src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/availability/ \
        docs/adr/0008-the-slot-lattice-is-anchored-to-the-creators-clock.md \
        .beans/
git commit -m "fix(availability): define the slot lattice in the creator's clock

Multi-host slots were anchored to midnight in each host's own timezone
and then intersected by exact instant, so two hosts' grids coincided
only when their UTC offsets differed by a whole number of the cadence.
London and Berlin on a 45-minute cadence never did: the intersection
was empty every day and the page rendered the ordinary 'no times
available' state.

The lattice is now the instants whose local time-of-day in the
creator's zone is a whole number of steps past midnight (ADR-0008).
One definition, one zone, so hosts agree by construction, and the
zone's rules are read at each instant rather than frozen at an origin
date -- an all-Kathmandu team keeps the round times it has today,
which a fixed epoch origin would have moved by 15 minutes because
Kathmandu shifted +05:30 to +05:45 in 1986.

Supersedes the origin-based lattice in 2a59d0f.

Fixes calit-io9y."
```

### Task 5: Duration-aware buffers

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java:124-131`
- Test: `src/test/java/site/asm0dey/calit/booking/MeetingHostsBufferTest.java` (create)

**Interfaces:**
- Consumes: `MeetingTypeDuration.findRow(Long, int)` from Task 2
- Produces: `MeetingHosts.effectiveBufferBefore(MeetingType type, Long hostOwnerId, int durationMinutes)` and `effectiveBufferAfter(...)`. The existing 2-arg forms remain, delegating with `type.durationMinutes`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/MeetingHostsBufferTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.MeetingTypeHost;

@QuarkusTest
class MeetingHostsBufferTest {

    private static final Long OWNER = 1L;

    @Inject
    MeetingHosts meetingHosts;

    @Transactional
    MeetingType seed(String slug, Integer hostOverride, Integer durationOverride) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.bufferBeforeMinutes = 10;
        t.bufferAfterMinutes = 10;
        t.persist();

        MeetingTypeHost h = MeetingTypeHost.of(t.id, OWNER, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED);
        h.bufferBeforeMinutes = hostOverride;
        h.bufferAfterMinutes = hostOverride;
        h.persist();

        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = t.id;
        d.durationMinutes = 120;
        d.bufferBeforeMinutes = durationOverride;
        d.bufferAfterMinutes = durationOverride;
        d.persist();
        return t;
    }

    /** ADR-0002: the max is over the overrides actually SET; an unset one is not a 10-minute floor. */
    @Test
    void neitherSetFallsBackToTheTypeBuffer() {
        MeetingType t = seed("buf-none", null, null);
        assertEquals(10, meetingHosts.effectiveBufferBefore(t, OWNER, 120));
        assertEquals(10, meetingHosts.effectiveBufferAfter(t, OWNER, 120));
    }

    @Test
    void aHostOverrideBelowTheTypeDefaultIsNotRaised() {
        MeetingType t = seed("buf-host-low", 5, null);
        assertEquals(5, meetingHosts.effectiveBufferBefore(t, OWNER, 120));
    }

    @Test
    void aDurationOverrideAppliesWhenTheHostHasNone() {
        MeetingType t = seed("buf-duration", null, 45);
        assertEquals(45, meetingHosts.effectiveBufferBefore(t, OWNER, 120));
    }

    @Test
    void theLargerOfTwoSetOverridesWins() {
        MeetingType t = seed("buf-both-duration-wins", 5, 45);
        assertEquals(45, meetingHosts.effectiveBufferBefore(t, OWNER, 120));

        MeetingType u = seed("buf-both-host-wins", 90, 45);
        assertEquals(90, meetingHosts.effectiveBufferBefore(u, OWNER, 120));
    }

    @Test
    void aLengthWithNoRowUsesOnlyTheHostOverride() {
        MeetingType t = seed("buf-other-length", 5, 45);
        // 30 has no meeting_type_duration row, so only the host's 5 is set.
        assertEquals(5, meetingHosts.effectiveBufferBefore(t, OWNER, 30));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=MeetingHostsBufferTest
```

Expected: compile failure — no 3-arg `effectiveBufferBefore`.

- [ ] **Step 3: Implement**

Replace lines 124-131 of `MeetingHosts.java`:

```java
public int effectiveBufferBefore(MeetingType type, Long hostOwnerId) {
    return effectiveBufferBefore(type, hostOwnerId, type.durationMinutes);
}

public int effectiveBufferAfter(MeetingType type, Long hostOwnerId) {
    return effectiveBufferAfter(type, hostOwnerId, type.durationMinutes);
}

public int effectiveBufferBefore(MeetingType type, Long hostOwnerId, int durationMinutes) {
    MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
    MeetingTypeDuration d = MeetingTypeDuration.findRow(type.id, durationMinutes);
    return strictest(
            h == null ? null : h.bufferBeforeMinutes,
            d == null ? null : d.bufferBeforeMinutes,
            type.bufferBeforeMinutes);
}

public int effectiveBufferAfter(MeetingType type, Long hostOwnerId, int durationMinutes) {
    MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
    MeetingTypeDuration d = MeetingTypeDuration.findRow(type.id, durationMinutes);
    return strictest(
            h == null ? null : h.bufferAfterMinutes,
            d == null ? null : d.bufferAfterMinutes,
            type.bufferAfterMinutes);
}

/**
 * ADR-0002: a buffer is a constraint, so where several apply to one host the strictest governs.
 * The maximum is taken over the overrides actually SET — a null is the ABSENCE of a requirement,
 * not a requirement equal to {@code typeDefault}. Letting a null fall back inside the maximum would
 * raise a host's deliberate 5 back to the type's 10.
 */
private static int strictest(Integer hostOverride, Integer durationOverride, int typeDefault) {
    if (hostOverride == null && durationOverride == null) {
        return typeDefault;
    }
    if (hostOverride == null) {
        return durationOverride;
    }
    if (durationOverride == null) {
        return hostOverride;
    }
    return Math.max(hostOverride, durationOverride);
}
```

Add `import site.asm0dey.calit.domain.MeetingTypeDuration;`.

- [ ] **Step 4: Run the test**

```bash
./mvnw test -Dtest=MeetingHostsBufferTest
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/MeetingHosts.java \
        src/test/java/site/asm0dey/calit/booking/MeetingHostsBufferTest.java
git commit -m "feat(booking): per-duration buffer overrides

A host override and a chosen length's override can both apply; per
ADR-0002 the larger of those actually SET governs, and an unset one is
the absence of a requirement rather than a floor at the type's buffer.
Every existing row has a null duration override, so its effective
buffer is unchanged."
```

---

### Task 6: Thread the chosen duration through booking

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` — `availableSlots` (all 4 overloads, `:104-152`), `hostFreeSlots` (`:164-194`), `assertSlotAvailable` (`:664-680`), `book` (`:227-296`)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingDurationTest.java` (create)

**Interfaces:**
- Consumes: Tasks 2, 3, 4, 5
- Produces:
  - `availableSlots(MeetingType type, LocalDate from, LocalDate to, Set<Long> excludeBookingIds, int durationMinutes)` plus the existing overloads delegating with `type.durationMinutes`
  - `book(..., List<String> guestEmails, int durationMinutes)` — a 12-arg overload; the existing 11-arg form delegates with `type.durationMinutes`
  - `BookingService.assertDurationAllowed(MeetingType, int)` — package-private, throws `BookingConflictException`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/BookingDurationTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class BookingDurationTest {

    private static final Long OWNER = 1L;

    @Inject
    BookingService bookingService;

    @Transactional
    MeetingType seed(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.horizonDays = 60;
        t.persist();
        for (int len : new int[] {60, 120}) {
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = len;
            d.persist();
        }
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = OWNER;
            r.meetingTypeId = t.id;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.persist();
        }
        return t;
    }

    @Test
    void bookingAtAChosenLengthSetsTheEndAccordingly() {
        MeetingType t = seed("dur-book");
        var slot = bookingService
                .availableSlots(t, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.util.Set.of(), 120)
                .getFirst();

        // 12-arg order: ownerId, slug, startUtc, name, email, answers, turnstileToken,
        // altchaSolution, honeypot, locale, guestEmails, durationMinutes.
        Booking b = bookingService.book(
                OWNER, t.slug, slot.start().toInstant(), "Ada", "ada@example.test",
                Map.of(), null, null, null, "en", List.of(), 120);

        assertEquals(120, Duration.between(b.startUtc, b.endUtc).toMinutes());
    }

    @Test
    void aLengthOutsideTheAllowedSetIsRejected() {
        MeetingType t = seed("dur-reject");
        var slot = bookingService
                .availableSlots(t, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.util.Set.of(), 30)
                .getFirst();

        long before = Booking.count();
        assertThrows(
                BookingConflictException.class,
                () -> bookingService.book(
                        OWNER, t.slug, slot.start().toInstant(), "Ada", "ada@example.test",
                        Map.of(), null, null, null, "en", List.of(), 45));
        assertEquals(before, Booking.count(), "a rejected duration must write no row");
    }

    @Test
    void theDefaultingOverloadStillBooksTheTypesOwnLength() {
        MeetingType t = seed("dur-default");
        var slot = bookingService
                .availableSlots(t, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.util.Set.of())
                .getFirst();

        Booking b = bookingService.book(
                OWNER, t.slug, slot.start().toInstant(), "Ada", "ada@example.test",
                Map.of(), null, null, null, "en", List.of());

        assertEquals(30, Duration.between(b.startUtc, b.endUtc).toMinutes());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=BookingDurationTest
```

Expected: compile failure — no duration-bearing `availableSlots` or `book`.

- [ ] **Step 3: Add the duration parameter to the slot chain**

Add a 5-arg `availableSlots` carrying `int durationMinutes`; have the existing 3-arg and 4-arg forms delegate with `type.durationMinutes`. Inside, pass it to `hostFreeSlots`, which passes it to both the buffer lookups and `generateRawSlots`:

```java
int bufBefore = meetingHosts.effectiveBufferBefore(type, hostId, durationMinutes);
int bufAfter = meetingHosts.effectiveBufferAfter(type, hostId, durationMinutes);
Map<Instant, TimeSlot> hostFree = new LinkedHashMap<>();
for (TimeSlot slot : slotService.generateRawSlots(type, hostId, from, to, gridAnchor, durationMinutes)) {
```

Add a matching `assertSlotAvailable(MeetingType type, Instant startUtc, Set<Long> excludeBookingIds, int durationMinutes)` that forwards the duration into `availableSlots`.

- [ ] **Step 4: Add the allowed-set guard and use the chosen length for the end**

In `BookingService`:

```java
/**
 * The submitted length must be one the type actually offers. Not optional: without this a POST
 * carrying an arbitrary duration builds a self-consistent lattice of its own that passes every
 * downstream check. Rejected as the same 409 an unavailable slot produces.
 */
void assertDurationAllowed(MeetingType type, int durationMinutes) {
    if (!MeetingTypeDuration.isAllowed(type, durationMinutes)) {
        throw new BookingConflictException(
                "Duration " + durationMinutes + " is not offered by " + type.slug);
    }
}
```

In the 12-arg `book`, immediately after the type is resolved and before any other work:

```java
assertDurationAllowed(type, durationMinutes);
```

and replace line 290:

```java
Instant endUtc = startUtc.plus(durationMinutes, ChronoUnit.MINUTES);
```

then pass the duration into `assertSlotAvailable`. `bookGroup` already takes `endUtc` as a parameter, so multi-host needs no further change.

Keep the existing 11-arg `book` as an overload delegating with the type's own duration — but note it resolves the type *inside* the method, so the overload must look the type up itself:

```java
@Transactional
public Booking book(
        Long ownerId, String meetingTypeSlug, Instant startUtc, String inviteeName, String inviteeEmail,
        Map<String, String> answers, String turnstileToken, String altchaSolution, String honeypot,
        String locale, List<String> guestEmails) {
    MeetingType type = MeetingType.findBySlug(ownerId, meetingTypeSlug);
    int duration = type == null ? 0 : type.durationMinutes;
    return book(ownerId, meetingTypeSlug, startUtc, inviteeName, inviteeEmail, answers,
            turnstileToken, altchaSolution, honeypot, locale, guestEmails, duration);
}
```

A null type yields `0`, and the 12-arg body throws its existing `NotFoundException` for the unknown slug before the duration is ever used — the lookup order matters, so do not reorder it.

- [ ] **Step 5: Run the test**

```bash
./mvnw test -Dtest=BookingDurationTest
```

Expected: PASS, 3 tests.

- [ ] **Step 6: Run the whole booking suite**

```bash
./mvnw test -Dtest='Booking*Test,*BookingTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/BookingDurationTest.java
git commit -m "feat(booking): book at a chosen allowed duration

availableSlots and book carry the chosen length; the submitted value
is checked against the type's allowed set before anything else, since
an arbitrary duration would otherwise build a self-consistent lattice
that passes every downstream check."
```

---

### Task 7: Reschedule preserves the booked length

Independent of whether anyone configures a second duration, `reschedule` and `rescheduleGroup` recompute the end from `type.durationMinutes`. The moment a type offers a second length, rescheduling a 120-minute booking silently shrinks it to the default.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java:792`, `:890`
- Test: `src/test/java/site/asm0dey/calit/booking/RescheduleLengthTest.java` (create)

**Interfaces:**
- Consumes: Task 6's duration-bearing `availableSlots` / `assertSlotAvailable`
- Produces: `public static int lengthOf(Booking booking)` on `BookingService` — public, not package-private: Task 8 consumes it from `site.asm0dey.calit.email`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/RescheduleLengthTest.java`:

```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class RescheduleLengthTest {

    private static final Long OWNER = 1L;

    @Inject
    BookingService bookingService;

    @Transactional
    MeetingType seed(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.horizonDays = 60;
        t.persist();
        MeetingTypeDuration d = new MeetingTypeDuration();
        d.meetingTypeId = t.id;
        d.durationMinutes = 120;
        d.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = OWNER;
            r.meetingTypeId = t.id;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.persist();
        }
        return t;
    }

    @Test
    void reschedulingA120MinuteBookingKeepsIt120() {
        MeetingType t = seed("resched-len");
        var slots = bookingService.availableSlots(t, LocalDate.now(), LocalDate.now().plusDays(7), Set.of(), 120);

        Booking b = bookingService.book(
                OWNER, t.slug, slots.getFirst().start().toInstant(), "Ada", "ada@example.test",
                Map.of(), null, null, null, "en", List.of(), 120);
        assertEquals(120, Duration.between(b.startUtc, b.endUtc).toMinutes());

        var target = slots.stream()
                .filter(s -> !s.start().toInstant().equals(b.startUtc))
                .findFirst()
                .orElseThrow();
        bookingService.reschedule(b.manageToken, target.start().toInstant());

        Booking moved = Booking.findById(b.id);
        assertEquals(
                120,
                Duration.between(moved.startUtc, moved.endUtc).toMinutes(),
                "reschedule moves a booking; it must never resize it");
    }
}
```

Check `reschedule`'s actual signature before running — if it takes `(String manageToken, Instant newStartUtc)` this compiles as written; if it differs, adapt the call and keep the assertion.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=RescheduleLengthTest
```

Expected: FAIL — `expected: <120> but was: <30>`.

- [ ] **Step 3: Implement**

Add to `BookingService`:

```java
/**
 * A booking carries its own length; reschedule moves it, never resizes it.
 *
 * <p>Public because {@code EmailService} — a different package — displays it.
 */
public static int lengthOf(Booking booking) {
    return (int) Duration.between(booking.startUtc, booking.endUtc).toMinutes();
}
```

Replace both `newEnd` computations (`:792`, `:890`):

```java
Instant newEnd = newStartUtc.plus(lengthOf(booking), ChronoUnit.MINUTES);
```

and pass `lengthOf(booking)` to the `assertSlotAvailable` call in each reschedule path, so the re-check uses slots of the booking's own length.

- [ ] **Step 4: Run the test**

```bash
./mvnw test -Dtest=RescheduleLengthTest
```

Expected: PASS.

- [ ] **Step 5: Run the reschedule suite**

```bash
./mvnw test -Dtest='*Reschedul*Test'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/RescheduleLengthTest.java
git commit -m "fix(booking): reschedule keeps the booked length

Both reschedule paths recomputed the end from the meeting type's
duration, so a booking made at a non-default length would silently
shrink when moved. A booking carries its own length."
```

---

### Task 8: Emails show the booked length

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` — every `l.meetingType.durationMinutes` argument (lines 344, 375, 407, 447, 480, 513, 545, 572, 610)
- Test: `src/test/java/site/asm0dey/calit/email/EmailDurationTest.java` (create)

**Interfaces:**
- Consumes: `BookingService.lengthOf(Booking)` from Task 7
- Produces: nothing new

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/EmailDurationTest.java`. Model it on the existing email tests — find the closest one first:

```bash
ls src/test/java/site/asm0dey/calit/email/
```

Assert that a confirmation rendered for a 120-minute booking on a 30-minute-default type contains `120` and not `30` in its duration line. Use the same mock-mailer inspection the existing tests use; do not invent a new mechanism.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=EmailDurationTest
```

Expected: FAIL — the rendered body says 30.

- [ ] **Step 3: Implement**

Replace every `l.meetingType.durationMinutes` argument with the booking's own length:

```bash
grep -n 'l\.meetingType\.durationMinutes' src/main/java/site/asm0dey/calit/email/EmailService.java
```

For each hit, substitute `BookingService.lengthOf(l.booking)`. `l.booking` is already in scope at every one. ICS and Google need no change — `IcsEvent.end(l.booking.endUtc)` (`:730`, `:802`) and the Google event already read the booking.

- [ ] **Step 4: Run the test**

```bash
./mvnw test -Dtest=EmailDurationTest
```

Expected: PASS.

- [ ] **Step 5: Run the email suite**

```bash
./mvnw test -Dtest='Email*Test,*MailTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/test/java/site/asm0dey/calit/email/EmailDurationTest.java
git commit -m "fix(email): print the booked length, not the type's default

A 120-minute booking on a 30-minute-default type announced itself as
30 minutes in its own confirmation."
```

---

### Task 9: The public duration picker

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` — `book` GET (`:206-244`), `submitBooking` POST (`:302-372`), `daySlots` (`:599`), `LandingType` (`:163`), `Templates.book` declaration
- Modify: `src/main/resources/templates/PublicResource/book.html`, `landing.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Test: `src/test/java/site/asm0dey/calit/web/PublicDurationPickerTest.java` (create)

**Interfaces:**
- Consumes: `MeetingTypeDuration.allowedDurations`, `isAllowed`; `BookingService.availableSlots(..., int)`; `Chrome`/`Captcha` from Task 1
- Produces: `PublicResource.DurationChoice(int chosen, List<Integer> allowed)` with `boolean multiple()`; `Templates.book` gaining a `DurationChoice duration` parameter; `LandingType` gaining `List<Integer> durations`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/PublicDurationPickerTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class PublicDurationPickerTest {

    private static final Long OWNER = 1L;

    @Transactional
    void seed(String slug, int defaultMinutes, int... extras) {
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = defaultMinutes;
        t.persist();
        for (int len : extras) {
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = len;
            d.persist();
        }
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = OWNER;
            r.meetingTypeId = t.id;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.persist();
        }
    }

    private String username() {
        return ((site.asm0dey.calit.user.AppUser) site.asm0dey.calit.user.AppUser.findById(OWNER)).username;
    }

    @Test
    void aMultiDurationTypeRendersOneLinkPerLength() {
        seed("picker-multi", 30, 60, 120);
        given().when()
                .get("/" + username() + "/picker-multi")
                .then()
                .statusCode(200)
                .body(containsString("?duration=30"))
                .body(containsString("?duration=60"))
                .body(containsString("?duration=120"));
    }

    @Test
    void aSingleDurationTypeRendersNoPicker() {
        seed("picker-single", 30);
        given().when()
                .get("/" + username() + "/picker-single")
                .then()
                .statusCode(200)
                .body(not(containsString("?duration=")));
    }

    @Test
    void theChosenLengthIsCarriedIntoTheFormAsAHiddenField() {
        seed("picker-hidden", 30, 120);
        given().when()
                .get("/" + username() + "/picker-hidden?duration=120")
                .then()
                .statusCode(200)
                .body(containsString("name=\"durationMinutes\""))
                .body(containsString("value=\"120\""));
    }

    @Test
    void anUnknownOrMalformedDurationFallsBackToTheDefault() {
        seed("picker-fallback", 30, 120);
        for (String bad : new String[] {"45", "abc", ""}) {
            given().when()
                    .get("/" + username() + "/picker-fallback?duration=" + bad)
                    .then()
                    .statusCode(200)
                    .body(containsString("value=\"30\""));
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=PublicDurationPickerTest
```

Expected: FAIL — no `?duration=` links rendered.

- [ ] **Step 3: Add the view-model record and resolve the choice**

In `PublicResource.java`, beside `Chrome` and `Captcha`:

```java
/** The lengths this type offers and the one currently rendered. */
public record DurationChoice(int chosen, List<Integer> allowed) {
    /** Only a type offering more than one length shows a picker. */
    public boolean multiple() {
        return allowed.size() > 1;
    }
}
```

In the GET handler, add `@RestQuery Integer duration` to the signature and resolve it after the target is known:

```java
List<Integer> allowed = MeetingTypeDuration.allowedDurations(type);
// Absent, malformed, or not-allowed all fall back to the default: a `?duration=` in a URL is
// something a human may have shared or edited, so it must never 404.
int chosen = (duration != null && allowed.contains(duration)) ? duration : type.durationMinutes;
var durationChoice = new DurationChoice(chosen, allowed);
```

A non-numeric `?duration=abc` never reaches the resolution — RESTEasy binds an unparseable `Integer` `@RestQuery` as null. Verify that with the `abc` case in the test; if the binding 404s instead, add `@DefaultValue("")` and parse manually.

Change `daySlots(MeetingType type)` to `daySlots(MeetingType type, int durationMinutes)` and forward the length to `bookingService.availableSlots(type, from, to, Set.of(), durationMinutes)`.

- [ ] **Step 4: Add the parameter to the template and both call sites**

`Templates.book` gains `DurationChoice duration` after `fields`. Both call sites pass it — the POST error re-render passes the submitted value so a bounce does not reset the Invitee to the default.

- [ ] **Step 5: Render the picker**

In `book.html`, replace the hardcoded header line:

```html
{type.durationMinutes} min
```

with:

```html
{duration.chosen} {msg:pub_book_minutes_short}
```

Insert the picker immediately after the `<h2>{msg:pub_book_select_datetime}</h2>` line and **before** the `{#if days.isEmpty()}` block — an Invitee who picks 120 and finds nothing must still be able to switch back:

```html
{#if duration.multiple}
  <p class="label mb-2">{msg:pub_book_duration_label}</p>
  <nav class="join mb-4">
    {#for d in duration.allowed}
      <a class="join-item btn btn-sm {#if d == duration.chosen}btn-active{/if}"
         href="/{user}/{type.slug}?duration={d}">{d} {msg:pub_book_minutes_short}</a>
    {/for}
  </nav>
{/if}
```

Inside the `<form>`, beside the CSRF input:

```html
<input type="hidden" name="durationMinutes" value="{duration.chosen}">
```

- [ ] **Step 6: Accept the submitted length**

Add `@RestForm @DefaultValue("0") int durationMinutes` to `submitBooking` and pass it to `bookingService.book(...)`'s 12-arg form. A `0` — the field absent — must resolve to the type's default rather than reaching `assertDurationAllowed`:

```java
int submitted = durationMinutes > 0 ? durationMinutes : type.durationMinutes;
```

`assertDurationAllowed` then rejects anything genuinely out of the set.

- [ ] **Step 7: Add the messages**

In `AppMessages.java`:

```java
@Message("Meeting length")
String pub_book_duration_label();

@Message("min")
String pub_book_minutes_short();
```

- [ ] **Step 8: Show the set on the landing page**

Change the record to `public record LandingType(MeetingType type, String bookUrl, List<Integer> durations) {}` and populate it at `:200` with `MeetingTypeDuration.allowedDurations(t)`. In `landing.html`, replace the `{t.type.durationMinutes} min` line with:

```html
{#for d in t.durations}{#if !d_isFirst} / {/if}{d}{/for} {msg:pub_book_minutes_short}
```

Confirm the loop variable's actual name in `landing.html` before editing — it may be `t` or `type`.

- [ ] **Step 9: Run the test**

```bash
./mvnw test -Dtest=PublicDurationPickerTest
```

Expected: PASS, 4 tests.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/templates/PublicResource/book.html \
        src/main/resources/templates/PublicResource/landing.html \
        src/test/java/site/asm0dey/calit/web/PublicDurationPickerTest.java
git commit -m "feat(web): let the invitee pick a meeting length

A ?duration= link per allowed length above the slot grid, rendered
only when a type offers more than one. Plain anchors, so it works with
JavaScript disabled, and a shared link with a preselected length falls
out for free. An unknown value falls back to the default rather than
404ing."
```

---

### Task 10: The owner's durations editor

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` — `Templates.meetingTypeDetail` declaration (`:61-79`), `detailInstance` (`:700-732`), new POST route
- Modify: `src/main/resources/templates/AdminResource/meetingTypeDetail.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`
- Test: `src/test/java/site/asm0dey/calit/web/AdminDurationsFormTest.java` (create)

**Interfaces:**
- Consumes: `MeetingTypeDuration` from Task 2
- Produces: `AdminResource.DurationRow(int minutes, Integer before, Integer after, boolean isDefault)`; `POST /me/meeting-types/{id}/durations`; `Templates.meetingTypeDetail` gaining `List<DurationRow> durations`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminDurationsFormTest.java`. Follow the authentication idiom of the existing admin form tests — read one first:

```bash
ls src/test/java/site/asm0dey/calit/web/ | grep -i admin
```

Cover exactly these four behaviours:

1. Posting `d.duration=30&d.before=10&d.after=10&d.duration=120&d.before=45&d.after=45` to a type whose default is 60 leaves `allowedDurations` equal to `[30, 60, 120]`.
2. Re-posting with the `120` row's duration blank removes that row: `allowedDurations` becomes `[30, 60]`.
3. Posting a row for the default (60) with buffers, then re-posting it blank, leaves `allowedDurations` still containing 60 — clearing the default's row drops its buffers, never the duration.
4. The rendered detail page contains one filled row per member of the union plus one empty spare.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=AdminDurationsFormTest
```

Expected: FAIL — 404 on the POST route.

- [ ] **Step 3: Add the view-model record and populate it**

In `AdminResource.java`, beside the other view-model records:

```java
/** One editable row of the allowed-durations table; {@code isDefault} marks the type's own length. */
public record DurationRow(int minutes, Integer before, Integer after, boolean isDefault) {}
```

In `detailInstance`, build the rows from the union so the default always appears even with an empty table:

```java
List<DurationRow> durationRows = new ArrayList<>();
for (int minutes : MeetingTypeDuration.allowedDurations(t)) {
    MeetingTypeDuration row = MeetingTypeDuration.findRow(t.id, minutes);
    durationRows.add(new DurationRow(
            minutes,
            row == null ? null : row.bufferBeforeMinutes,
            row == null ? null : row.bufferAfterMinutes,
            minutes == t.durationMinutes));
}
```

Add `List<DurationRow> durations` to the `meetingTypeDetail` template declaration and pass `durationRows`.

- [ ] **Step 4: Add the save route**

```java
@POST
@Path("/meeting-types/{id}/durations")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.TEXT_HTML)
public TemplateInstance saveDurations(@PathParam("id") Long id, MultivaluedMap<String, String> form) {
    QuarkusTransaction.requiringNew().run(() -> {
        MeetingType t = requireType(id); // owner-scoped; 404s for another owner's type
        // Rows arrive as parallel fields in document order, so index i pairs across the three lists.
        List<String> minutes = form.getOrDefault("d.duration", List.of());
        List<String> before = form.getOrDefault("d.before", List.of());
        List<String> after = form.getOrDefault("d.after", List.of());
        MeetingTypeDuration.delete("meetingTypeId = ?1", t.id);
        for (int i = 0; i < minutes.size(); i++) {
            Integer value = parsePositive(minutes.get(i));
            if (value == null) {
                continue; // a blank duration removes the row; that is how deletion is expressed
            }
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = value;
            d.bufferBeforeMinutes = parseNonNegative(at(before, i));
            d.bufferAfterMinutes = parseNonNegative(at(after, i));
            d.persist();
        }
    });
    return detailInstance(id, null, m().adm_meetingTypeDetail_durations_saved());
}

private static String at(List<String> values, int i) {
    return i < values.size() ? values.get(i) : null;
}

/** Null for blank or unparseable input, so a stray value never becomes a silent 0-minute meeting. */
private static Integer parsePositive(String raw) {
    Integer v = parseNonNegative(raw);
    return (v == null || v <= 0) ? null : v;
}

private static Integer parseNonNegative(String raw) {
    if (raw == null || raw.isBlank()) {
        return null;
    }
    try {
        int v = Integer.parseInt(raw.trim());
        return v < 0 ? null : v;
    } catch (NumberFormatException e) {
        return null;
    }
}
```

Deleting then re-inserting is deliberate: the set is tiny, and it makes "clear a duration to remove it" fall out with no diffing. A row for the default is re-inserted like any other and carries only its buffers — the default's membership comes from the union, so clearing it cannot remove the length.

- [ ] **Step 5: Render the editor**

In `meetingTypeDetail.html`, add a collapse section matching the existing ones (copy the structure from the Buffers section of `SharedMeetingsResource/sharedAvailability.html`):

```html
<div class="collapse collapse-arrow bg-base-100 border border-base-300 mb-4">
  <input type="checkbox">
  <div class="collapse-title font-semibold">{adm:adm_meetingTypeDetail_section_durations}</div>
  <div class="collapse-content">
    <p class="text-sm text-base-content/70 mb-2">{adm:adm_meetingTypeDetail_durations_hint}</p>
    <form method="post" action="/me/meeting-types/{type.id}/durations" class="fieldset">
      <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
      <table class="table table-sm">
        <thead>
          <tr>
            <th>{adm:adm_meetingTypeDetail_duration_col}</th>
            <th>{adm:adm_shared_availability_buffer_before_label}</th>
            <th>{adm:adm_shared_availability_buffer_after_label}</th>
          </tr>
        </thead>
        <tbody>
          {#for row in durations}
            <tr>
              <td>
                <input class="input input-sm" type="number" min="1" name="d.duration" value="{row.minutes}">
                {#if row.isDefault}<span class="badge badge-ghost badge-sm ms-1">{adm:adm_meetingTypeDetail_duration_default}</span>{/if}
              </td>
              <td><input class="input input-sm" type="number" min="0" name="d.before" value="{#if row.before}{row.before}{/if}"></td>
              <td><input class="input input-sm" type="number" min="0" name="d.after" value="{#if row.after}{row.after}{/if}"></td>
            </tr>
          {/for}
          <tr>
            <td><input class="input input-sm" type="number" min="1" name="d.duration" value=""></td>
            <td><input class="input input-sm" type="number" min="0" name="d.before" value=""></td>
            <td><input class="input input-sm" type="number" min="0" name="d.after" value=""></td>
          </tr>
        </tbody>
      </table>
      <button type="submit" class="btn btn-primary mt-2">{adm:adm_meetingTypeDetail_btn_save_durations}</button>
    </form>
  </div>
</div>
```

The CSRF hidden input is mandatory: `quarkus-rest-csrf` is on in production, and a POST form without it 400s there even though the test profile has it off.

- [ ] **Step 6: Add the messages**

In `AdminMessages.java`:

```java
@Message("Allowed durations")
String adm_meetingTypeDetail_section_durations();

@Message(
        "Every length this meeting type may be booked at. Leave a buffer blank to use the meeting type's own; clear a duration to remove that length. The default cannot be removed — change it in Duration above.")
String adm_meetingTypeDetail_durations_hint();

@Message("Duration (minutes)")
String adm_meetingTypeDetail_duration_col();

@Message("default")
String adm_meetingTypeDetail_duration_default();

@Message("Save durations")
String adm_meetingTypeDetail_btn_save_durations();

@Message("Allowed durations saved.")
String adm_meetingTypeDetail_durations_saved();
```

- [ ] **Step 7: Run the test**

```bash
./mvnw test -Dtest=AdminDurationsFormTest
```

Expected: PASS, 4 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/templates/AdminResource/meetingTypeDetail.html \
        src/test/java/site/asm0dey/calit/web/AdminDurationsFormTest.java
git commit -m "feat(admin): edit a meeting type's allowed durations

One row per allowed length plus a blank spare; saving persists every
row with a duration and drops the rest, so clearing a duration is how
a length is removed. No JavaScript, one POST. The default is an
implicit member, so clearing its row drops only its buffers."
```

---

### Task 11: German and Hebrew translations

**Files:**
- Modify: `src/main/resources/messages/msg_de.properties`, `msg_he.properties`, `adm_de.properties`, `adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/i18n/MessageParityTest.java` (create if absent — check first)

**Interfaces:**
- Consumes: the keys added in Tasks 9 and 10
- Produces: nothing new

- [ ] **Step 1: Check whether a parity test already exists**

```bash
ls src/test/java/site/asm0dey/calit/i18n/ 2>/dev/null
grep -rln 'properties' src/test/java/site/asm0dey/calit/i18n/ 2>/dev/null
```

If one exists, run it and let it name the missing keys. If not, create one that reflectively lists every `String xxx()` on `AppMessages` and `AdminMessages` and asserts a matching `xxx=` line in each of the four locale files. That test is worth having permanently — a missing key currently falls back to English silently.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw test -Dtest=MessageParityTest
```

Expected: FAIL, naming the eight new keys.

- [ ] **Step 3: Add the German values**

`msg_de.properties`:

```properties
pub_book_duration_label=Termindauer
pub_book_minutes_short=Min.
```

`adm_de.properties`:

```properties
adm_meetingTypeDetail_section_durations=Erlaubte Dauern
adm_meetingTypeDetail_durations_hint=Jede Länge, mit der dieser Termintyp gebucht werden kann. Puffer leer lassen, um den Puffer des Termintyps zu verwenden; Dauer löschen, um diese Länge zu entfernen. Der Standard kann nicht entfernt werden — ändern Sie ihn oben unter Dauer.
adm_meetingTypeDetail_duration_col=Dauer (Minuten)
adm_meetingTypeDetail_duration_default=Standard
adm_meetingTypeDetail_btn_save_durations=Dauern speichern
adm_meetingTypeDetail_durations_saved=Erlaubte Dauern gespeichert.
```

- [ ] **Step 4: Add the Hebrew values**

`msg_he.properties`:

```properties
pub_book_duration_label=משך הפגישה
pub_book_minutes_short=דק'
```

`adm_he.properties`:

```properties
adm_meetingTypeDetail_section_durations=משכים מותרים
adm_meetingTypeDetail_durations_hint=כל משך שניתן להזמין בו את סוג הפגישה הזה. השאירו חיץ ריק כדי להשתמש בחיץ של סוג הפגישה; מחקו משך כדי להסיר אותו. לא ניתן להסיר את ברירת המחדל — שנו אותה למעלה תחת משך.
adm_meetingTypeDetail_duration_col=משך (דקות)
adm_meetingTypeDetail_duration_default=ברירת מחדל
adm_meetingTypeDetail_btn_save_durations=שמירת משכים
adm_meetingTypeDetail_durations_saved=המשכים המותרים נשמרו.
```

`דק'` matches the existing `adm_meetingTypes_min`, so the abbreviation stays consistent across both bundles.

- [ ] **Step 5: Run the parity test**

```bash
./mvnw test -Dtest=MessageParityTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/messages/ src/test/java/site/asm0dey/calit/i18n/
git commit -m "i18n: German and Hebrew for the duration picker and editor"
```

---

### Task 12: Full verification and documentation

**Files:**
- Modify (on the `docs-site` branch): `docs-site/src/content/docs/releases/changelog.md`, the meeting-type usage page

**Interfaces:**
- Consumes: everything above
- Produces: nothing

- [ ] **Step 1: Run the entire suite**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Nothing below happens until this is true — a PR from a red branch is forbidden, including for a failure that predates this work.

- [ ] **Step 2: Verify formatting**

```bash
bun run format && ./mvnw spotless:check
```

Expected: `BUILD SUCCESS`. `verify` (and therefore CI) fails on unformatted code.

- [ ] **Step 3: Exercise it by hand**

```bash
bun run css:build
mvn quarkus:dev -Dgoogle.oauth.client-id=dummy -Dgoogle.oauth.client-secret=dummy
```

Walk the flow: add 60 and 120 to a 30-minute type with buffers 10/10 and 45/45; confirm the public page shows three links, that 120 offers starts on the 30-minute lattice, that the confirmation email announces 120, and that rescheduling that booking keeps it at 120. Then **disable JavaScript** and repeat — the picker and the form must both still work.

- [ ] **Step 4: Update the beans**

Tick every completed item in `calit-p5xm` and `calit-io9y`, and add a `## Summary of Changes` section to each before marking them completed.

- [ ] **Step 5: Write the changelog on `docs-site`**

Three bullets under `## Unreleased` (create the section, subtitle "Merged but not yet in a tagged release.", if absent):

- **A meeting type can offer several lengths.** Previously an owner running 30-, 60- and 120-minute sessions needed three near-duplicate meeting types. Now one type carries a set of allowed lengths, each with its own optional before/after buffers, and the invitee picks one above the slot grid. Start times stay on the same lattice whichever length is picked, so switching never moves the times on offer.
- **Rescheduling no longer resizes a booking.** A booking made at a non-default length had its end recomputed from the meeting type's duration when moved, silently shrinking it. A booking now carries its own length.
- **Shared meeting types across timezones offer slots again.** Hosts' slot grids were anchored to midnight in each host's own timezone and then intersected by exact start time, so two hosts whose UTC offsets differed by a non-multiple of the slot cadence — London and Berlin on a 45-minute cadence, for instance — matched on nothing and the page showed no available times at all. Every host now shares one grid anchored to the creator's clock.

Close the section with an upgrade note: nothing to do on upgrade, no configuration or database action required, existing meeting types keep exactly the lengths and start times they have. Add the caveat that a shared type whose hosts span timezones may now show start times at unround local minutes for some hosts — that is the fix working, since it previously showed none.

- [ ] **Step 6: Update the usage docs on `docs-site`**

The meeting-type page gains the allowed-durations section: how to add a length, that a blank buffer inherits the type's, that clearing a duration removes it, that the default is set by the Duration field and cannot be removed here, and that the buffer actually applied is the larger of the host's and the length's.

- [ ] **Step 7: Open the PR**

```bash
git push -u origin <branch>
gh pr create --fill
```

State in the body that the full suite is green, and link `calit-p5xm`, `calit-io9y`, and issue #119.

---

## Self-Review

**Spec coverage.** Data model → Task 2. Slot computation → Task 3. Shared lattice → Task 4. Buffers → Task 5. Booking write path → Task 6. Reschedule → Task 7. Email/ICS/Google → Task 8. Public page and template grouping → Tasks 1 and 9. Owner UI → Task 10. i18n → Task 11. Testing and docs → Task 12. No spec section is unimplemented.

**Placeholders.** Task 8's test body and Task 10's test body are described rather than written, because both must follow an existing test's authentication and mock-mailer idiom that this plan should not guess at; each names the file to read first and states the exact assertions required. Every other step carries its literal content.

**Type consistency.** `allowedDurations` / `shortestAllowed` / `isAllowed` / `findRow` / `rowsFor` are used under those names in Tasks 3, 5, 6, 9 and 10. `lengthOf` is defined in Task 7 and used in Task 8. `DurationChoice` / `Chrome` / `Captcha` / `DurationRow` are each defined once and consumed under the same names. `gridAnchorFor` is defined in Task 4 and used only there and in `BookingService`.

**Known ordering hazard.** Task 4 replaces `generateRawSlots`'s `boolean dayAnchoredGrid` with a nullable `ZoneId latticeZone` while Task 6 adds `int durationMinutes` to the same signature. Task 4 must land first; its Step 5 passes `type.durationMinutes` as a placeholder value that Task 6 replaces with the real parameter. Executing them out of order produces a compile error, not silent breakage. Task 4 also owns updating Task 3's `SlotServiceDurationTest`, which was written against the parameter it removes.
