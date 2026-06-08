# Plan 1 — Core Domain & Availability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap the Quarkus project and build the scheduling core: owner settings, meeting types (with durations + buffers), weekly work hours (global + per-meeting-type), and raw bookable-slot generation.

**Architecture:** Hibernate Panache active-record entities on PostgreSQL (Flyway-managed schema). A `SlotService` resolves the applicable work-hour rules for a meeting type on each day (per-type override falling back to global) and emits back-to-back `TimeSlot`s within those windows, in the owner's timezone. A thin JAX-RS layer exposes settings, meeting-type, availability, and slot-preview endpoints so the subsystem is exercisable on its own. Conflict/busy/buffer subtraction is deliberately **out of scope** — Plan 3 layers it on top of `generateRawSlots`.

**Tech Stack:** Java 25, Quarkus 3.35.3, Hibernate ORM Panache, PostgreSQL, Flyway, quarkus-rest (+jackson), JUnit 5, RestAssured. Tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**.

---

### Task 1: Project bootstrap

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.properties`
- Create: `src/main/java/com/calit/HealthResource.java`
- Test: `src/test/java/com/calit/HealthResourceTest.java`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.calit</groupId>
  <artifactId>calit</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
    <quarkus.platform.version>3.35.3</quarkus.platform.version>
    <surefire-plugin.version>3.5.2</surefire-plugin.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>${quarkus.platform.group-id}</groupId>
        <artifactId>${quarkus.platform.artifact-id}</artifactId>
        <version>${quarkus.platform.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest-jackson</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-hibernate-orm-panache</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-jdbc-postgresql</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-flyway</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-smallrye-health</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-arc</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-junit5</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>${quarkus.platform.group-id}</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
        <version>${quarkus.platform.version}</version>
        <extensions>true</extensions>
        <executions>
          <execution>
            <goals>
              <goal>build</goal>
              <goal>generate-code</goal>
              <goal>generate-code-tests</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire-plugin.version}</version>
        <configuration>
          <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
          </systemPropertyVariables>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create `src/main/resources/application.properties`**

```properties
# Dev Services auto-provisions Postgres in dev/test (Docker required) when no JDBC URL is set.
quarkus.datasource.db-kind=postgresql
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.log.sql=false
quarkus.flyway.migrate-at-start=true

# Production: real datasource via env vars (12-factor; no node-local config).
%prod.quarkus.datasource.jdbc.url=${DB_URL:jdbc:postgresql://localhost:5432/calit}
%prod.quarkus.datasource.username=${DB_USER:calit}
%prod.quarkus.datasource.password=${DB_PASSWORD}
```

> **Horizontal scalability (NFR):** `quarkus-smallrye-health` auto-exposes `/q/health/live` and `/q/health/ready` (the readiness probe includes a datasource check) for the load balancer / orchestrator — no code needed. The app holds **no in-process state**: all state is in shared Postgres, so N identical replicas can run behind a load balancer with no sticky sessions. This constraint is honored by every later plan (DB-stored Google tokens in Plan 2, DB-level double-booking guard in Plan 3, stateless HTTP Basic admin auth in Plan 5). The readiness probe is asserted in the smoke test below.

- [ ] **Step 3: Write the failing smoke test**

`src/test/java/com/calit/HealthResourceTest.java`:

```java
package com.calit;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class HealthResourceTest {
    @Test
    void healthReturnsOk() {
        given().when().get("/api/health").then().statusCode(200).body(is("ok"));
    }

    @Test
    void readinessProbeIsUp() {
        // Horizontal-scalability requirement: the orchestrator / load balancer polls this.
        given().when().get("/q/health/ready").then().statusCode(200);
    }
}
```

- [ ] **Step 4: Run it to confirm it fails**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: FAIL — 404 (no `/api/health` resource yet).

- [ ] **Step 5: Write the minimal resource**

`src/main/java/com/calit/HealthResource.java`:

```java
package com.calit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/health")
public class HealthResource {
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String health() {
        return "ok";
    }
}
```

- [ ] **Step 6: Run it to confirm it passes**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. (First run pulls the Postgres Testcontainer image — slow once.)

- [ ] **Step 7: Add `.gitignore`, init repo, commit**

`.gitignore`:

```gitignore
target/
.idea/
*.iml
.env
```

```bash
git init
git add pom.xml .gitignore src/main/resources/application.properties \
  src/main/java/com/calit/HealthResource.java \
  src/test/java/com/calit/HealthResourceTest.java
git commit -m "chore: bootstrap Quarkus project with health endpoint"
```

---

### Task 2: Database schema (Flyway V1)

**Files:**
- Create: `src/main/resources/db/migration/V1__core_schema.sql`

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V1__core_schema.sql`:

```sql
CREATE TABLE owner_settings (
    id          BIGINT       PRIMARY KEY,
    owner_name  VARCHAR(255) NOT NULL,
    owner_email VARCHAR(255) NOT NULL,
    timezone    VARCHAR(64)  NOT NULL
);

CREATE TABLE meeting_type (
    id                    BIGSERIAL    PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    slug                  VARCHAR(255) NOT NULL UNIQUE,
    duration_minutes      INT          NOT NULL,
    buffer_before_minutes INT          NOT NULL DEFAULT 0,
    buffer_after_minutes  INT          NOT NULL DEFAULT 0,
    description           TEXT,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    secret                BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE availability_rule (
    id              BIGSERIAL   PRIMARY KEY,
    day_of_week     VARCHAR(16) NOT NULL,
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    meeting_type_id BIGINT      REFERENCES meeting_type(id) ON DELETE CASCADE
);

CREATE INDEX idx_availability_lookup ON availability_rule (meeting_type_id, day_of_week);

-- Owner-defined EXTRA booking-form fields. Full name + email are always-present
-- built-ins (Booking.invitee_name / invitee_email), so they are NOT rows here.
CREATE TABLE booking_field (
    id              BIGSERIAL    PRIMARY KEY,
    meeting_type_id BIGINT       REFERENCES meeting_type(id) ON DELETE CASCADE,
    field_key       VARCHAR(64)  NOT NULL,
    label           VARCHAR(255) NOT NULL,
    type            VARCHAR(16)  NOT NULL,
    required        BOOLEAN      NOT NULL DEFAULT FALSE,
    position        INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_booking_field_scope ON booking_field (meeting_type_id, position);

-- Default global form has one optional "description" field out of the box.
INSERT INTO booking_field (meeting_type_id, field_key, label, type, required, position)
VALUES (NULL, 'description', 'Description', 'LONG_TEXT', FALSE, 0);
```

- [ ] **Step 2: Verify the schema applies at startup**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. Flyway runs V1 at boot against the Dev Services DB; no migration errors in the log.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V1__core_schema.sql
git commit -m "feat: add core schema (owner_settings, meeting_type, availability_rule, booking_field)"
```

---

### Task 3: OwnerSettings entity (singleton)

**Files:**
- Create: `src/main/java/com/calit/domain/OwnerSettings.java`
- Test: `src/test/java/com/calit/domain/OwnerSettingsTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/domain/OwnerSettingsTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class OwnerSettingsTest {
    @Test
    @TestTransaction
    void persistsAndReadsSingleton() {
        // Upsert: the RestAssured tests (Task 7) hit the running server over HTTP, so their
        // server-side commits of owner_settings id=1 are NOT rolled back by @TestTransaction
        // and persist in the shared Dev Services DB. Get-or-create tolerates that pre-existing row.
        OwnerSettings s = OwnerSettings.get();
        if (s == null) {
            s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
        }
        s.ownerName = "Pavel";
        s.ownerEmail = "p@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        OwnerSettings loaded = OwnerSettings.get();
        assertNotNull(loaded);
        assertEquals("Europe/Amsterdam", loaded.timezone);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=OwnerSettingsTest`
Expected: FAIL — compilation error, `OwnerSettings` does not exist.

- [ ] **Step 3: Write the entity**

`src/main/java/com/calit/domain/OwnerSettings.java`:

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "owner_settings")
public class OwnerSettings extends PanacheEntityBase {

    public static final long SINGLETON_ID = 1L;

    @Id
    public Long id;

    @Column(name = "owner_name", nullable = false)
    public String ownerName;

    @Column(name = "owner_email", nullable = false)
    public String ownerEmail;

    @Column(nullable = false, length = 64)
    public String timezone;

    /** Returns the single settings row, or null if not yet configured. */
    public static OwnerSettings get() {
        return findById(SINGLETON_ID);
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=OwnerSettingsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/OwnerSettings.java \
  src/test/java/com/calit/domain/OwnerSettingsTest.java
git commit -m "feat: add OwnerSettings singleton entity"
```

---

### Task 4: MeetingType entity

**Files:**
- Create: `src/main/java/com/calit/domain/MeetingType.java`
- Test: `src/test/java/com/calit/domain/MeetingTypeTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/domain/MeetingTypeTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MeetingTypeTest {

    @Test
    @TestTransaction
    void persistsWithDefaultBuffersActiveAndNotSecret() {
        MeetingType t = new MeetingType();
        t.name = "Intro 30";
        t.slug = "intro-30";
        t.durationMinutes = 30;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug("intro-30");
        assertEquals(t.id, loaded.id);
        assertEquals(0, loaded.bufferBeforeMinutes);
        assertEquals(0, loaded.bufferAfterMinutes);
        assertEquals(true, loaded.active);
        assertEquals(false, loaded.secret);
    }

    @Test
    @TestTransaction
    void findBySlugReturnsNullWhenMissing() {
        assertNull(MeetingType.findBySlug("does-not-exist"));
    }

    @Test
    @TestTransaction
    void listPublicExcludesSecretButFindBySlugStillReturnsIt() {
        MeetingType pub = new MeetingType();
        pub.name = "Public"; pub.slug = "pub-listpublic"; pub.durationMinutes = 30;
        pub.persist();

        MeetingType hidden = new MeetingType();
        hidden.name = "Secret"; hidden.slug = "secret-listpublic"; hidden.durationMinutes = 30;
        hidden.secret = true;
        hidden.persist();

        List<MeetingType> publicList = MeetingType.listPublic();
        assertTrue(publicList.stream().anyMatch(m -> "pub-listpublic".equals(m.slug)));
        assertFalse(publicList.stream().anyMatch(m -> "secret-listpublic".equals(m.slug)));
        // Direct slug access bypasses the public filter.
        assertEquals(hidden.id, MeetingType.findBySlug("secret-listpublic").id);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=MeetingTypeTest`
Expected: FAIL — compilation error, `MeetingType` does not exist.

- [ ] **Step 3: Write the entity**

`src/main/java/com/calit/domain/MeetingType.java`:

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "meeting_type")
public class MeetingType extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false, unique = true)
    public String slug;

    @Column(name = "duration_minutes", nullable = false)
    public int durationMinutes;

    @Column(name = "buffer_before_minutes", nullable = false)
    public int bufferBeforeMinutes = 0;

    @Column(name = "buffer_after_minutes", nullable = false)
    public int bufferAfterMinutes = 0;

    @Column(columnDefinition = "text")
    public String description;

    @Column(nullable = false)
    public boolean active = true;

    /** Secret types are hidden from the public list but remain bookable via their direct slug/link. */
    @Column(nullable = false)
    public boolean secret = false;

    public static MeetingType findBySlug(String slug) {
        return find("slug", slug).firstResult();
    }

    /** Active, non-secret types — what the public invitee landing page lists. */
    public static List<MeetingType> listPublic() {
        return list("active = true and secret = false");
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=MeetingTypeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/MeetingType.java \
  src/test/java/com/calit/domain/MeetingTypeTest.java
git commit -m "feat: add MeetingType entity"
```

---

### Task 5: AvailabilityRule entity

**Files:**
- Create: `src/main/java/com/calit/domain/AvailabilityRule.java`
- Test: `src/test/java/com/calit/domain/AvailabilityRuleTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/domain/AvailabilityRuleTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AvailabilityRuleTest {

    @Test
    @TestTransaction
    void separatesGlobalRulesFromMeetingTypeRules() {
        // A typed rule's meeting_type_id is a real FK, so persist a MeetingType first
        // and use its generated id (a literal id would violate the FK constraint).
        MeetingType type = new MeetingType();
        type.name = "Sep Test";
        type.slug = "avail-sep-type";
        type.durationMinutes = 30;
        type.persist();

        AvailabilityRule global = rule(DayOfWeek.MONDAY, "09:00", "12:00", null);
        global.persist();
        AvailabilityRule typed = rule(DayOfWeek.MONDAY, "13:00", "14:00", type.id);
        typed.persist();

        List<AvailabilityRule> globals = AvailabilityRule.globalFor(DayOfWeek.MONDAY);
        List<AvailabilityRule> typedRules = AvailabilityRule.forMeetingType(type.id, DayOfWeek.MONDAY);

        assertEquals(1, globals.size());
        assertEquals(LocalTime.of(9, 0), globals.get(0).startTime);
        assertEquals(1, typedRules.size());
        assertEquals(LocalTime.of(13, 0), typedRules.get(0).startTime);
        assertTrue(AvailabilityRule.forMeetingType(type.id, DayOfWeek.TUESDAY).isEmpty());
    }

    private AvailabilityRule rule(DayOfWeek dow, String start, String end, Long meetingTypeId) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = meetingTypeId;
        return r;
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=AvailabilityRuleTest`
Expected: FAIL — compilation error, `AvailabilityRule` does not exist.

- [ ] **Step 3: Write the entity**

`src/main/java/com/calit/domain/AvailabilityRule.java`:

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

@Entity
@Table(name = "availability_rule")
public class AvailabilityRule extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    public DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    public LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    public LocalTime endTime;

    /** Null = global default rule. Otherwise this rule overrides for that meeting type. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    public static List<AvailabilityRule> forMeetingType(Long meetingTypeId, DayOfWeek dow) {
        return list("meetingTypeId = ?1 and dayOfWeek = ?2", meetingTypeId, dow);
    }

    public static List<AvailabilityRule> globalFor(DayOfWeek dow) {
        return list("meetingTypeId is null and dayOfWeek = ?1", dow);
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=AvailabilityRuleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/AvailabilityRule.java \
  src/test/java/com/calit/domain/AvailabilityRuleTest.java
git commit -m "feat: add AvailabilityRule entity with global/per-type lookups"
```

---

### Task 6: TimeSlot record + SlotService

**Files:**
- Create: `src/main/java/com/calit/availability/TimeSlot.java`
- Create: `src/main/java/com/calit/availability/SlotService.java`
- Test: `src/test/java/com/calit/availability/SlotServiceTest.java`

**Behavior contract for `generateRawSlots(type, from, to)`:**
- For each date in `[from, to]` inclusive, resolve rules: if the meeting type has any rule for that day-of-week, use those; else use the global rules for that day; else no slots.
- Within each rule window, emit back-to-back slots of `durationMinutes`, starting at `startTime`, while `start + duration <= endTime`.
- Each slot's `start`/`end` are `ZonedDateTime` in `OwnerSettings.timezone`.
- Buffers and existing-booking conflicts are NOT applied here (Plan 3). Assumes windows do not cross midnight.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/availability/SlotServiceTest.java`:

```java
package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SlotServiceTest {

    @Inject
    SlotService slotService;

    private static final LocalDate WORKDAY = LocalDate.of(2026, 6, 8);

    @Test
    @TestTransaction
    void generatesBackToBackSlotsWithinGlobalWindow() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(0).end().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(1).start().toLocalTime());
        assertEquals(ZoneId.of("Europe/Amsterdam"), slots.get(0).start().getZone());
    }

    @Test
    @TestTransaction
    void dropsPartialSlotThatDoesNotFit() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "10:30");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void meetingTypeRuleOverridesGlobalForThatDay() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");
        typedRule(t.id, WORKDAY.getDayOfWeek(), "13:00", "14:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(13, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void emitsNothingForDayWithNoRules() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        // no rule for WORKDAY's day-of-week
        globalRule(WORKDAY.getDayOfWeek().plus(1), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertTrue(slots.isEmpty());
    }

    @Test
    @TestTransaction
    void handlesMultipleWindowsSameDay() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "10:00");
        globalRule(WORKDAY.getDayOfWeek(), "14:00", "15:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
    }

    // --- helpers ---

    private void seedSettings(String zone) {
        // Upsert (get-or-create): Task 7's RestAssured tests commit owner_settings id=1 to the
        // shared Dev Services DB (not rolled back), so a plain INSERT here would hit a duplicate PK.
        OwnerSettings s = OwnerSettings.get();
        if (s == null) {
            s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = zone;
        s.persist();
    }

    private MeetingType meetingType(String slug, int minutes) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.persist();
        return t;
    }

    private void globalRule(DayOfWeek dow, String start, String end) {
        rule(dow, start, end, null);
    }

    private void typedRule(Long meetingTypeId, DayOfWeek dow, String start, String end) {
        rule(dow, start, end, meetingTypeId);
    }

    private void rule(DayOfWeek dow, String start, String end, Long meetingTypeId) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = meetingTypeId;
        r.persist();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=SlotServiceTest`
Expected: FAIL — compilation error, `SlotService` / `TimeSlot` do not exist.

- [ ] **Step 3: Write `TimeSlot`**

`src/main/java/com/calit/availability/TimeSlot.java`:

```java
package com.calit.availability;

import java.time.ZonedDateTime;

public record TimeSlot(ZonedDateTime start, ZonedDateTime end) {}
```

- [ ] **Step 4: Write `SlotService`**

`src/main/java/com/calit/availability/SlotService.java`:

```java
package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SlotService {

    /**
     * Raw bookable windows derived from work hours only.
     * Conflict/busy/buffer subtraction is applied by Plan 3 on top of this output.
     */
    public List<TimeSlot> generateRawSlots(MeetingType type, LocalDate from, LocalDate to) {
        OwnerSettings settings = OwnerSettings.get();
        if (settings == null) {
            throw new IllegalStateException(
                    "Owner settings not configured; set them via PUT /api/settings before generating slots.");
        }
        ZoneId zone = ZoneId.of(settings.timezone);
        List<TimeSlot> slots = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (AvailabilityRule rule : rulesFor(type.id, date.getDayOfWeek())) {
                LocalTime start = rule.startTime;
                while (!start.plusMinutes(type.durationMinutes).isAfter(rule.endTime)) {
                    LocalTime end = start.plusMinutes(type.durationMinutes);
                    slots.add(new TimeSlot(
                            date.atTime(start).atZone(zone),
                            date.atTime(end).atZone(zone)));
                    start = end;
                }
            }
        }
        return slots;
    }

    /** Per-meeting-type rules win for a given day; otherwise fall back to global rules. */
    List<AvailabilityRule> rulesFor(Long meetingTypeId, DayOfWeek dow) {
        List<AvailabilityRule> override = AvailabilityRule.forMeetingType(meetingTypeId, dow);
        return override.isEmpty() ? AvailabilityRule.globalFor(dow) : override;
    }
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=SlotServiceTest`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/availability/TimeSlot.java \
  src/main/java/com/calit/availability/SlotService.java \
  src/test/java/com/calit/availability/SlotServiceTest.java
git commit -m "feat: add SlotService raw slot generation with per-type override"
```

---

### Task 7: REST API (settings, meeting types, availability, slot preview)

**Files:**
- Create: `src/main/java/com/calit/api/SettingsResource.java`
- Create: `src/main/java/com/calit/api/MeetingTypeResource.java`
- Create: `src/main/java/com/calit/api/AvailabilityResource.java`
- Test: `src/test/java/com/calit/api/MeetingTypeResourceTest.java`

- [ ] **Step 1: Write the failing end-to-end test**

`src/test/java/com/calit/api/MeetingTypeResourceTest.java`:

```java
package com.calit.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class MeetingTypeResourceTest {

    @Test
    void fullFlowCreatesSettingsTypeRuleAndPreviewsSlots() {
        // 1. Configure owner settings.
        given().contentType("application/json")
                .body("{\"ownerName\":\"Owner\",\"ownerEmail\":\"o@example.com\",\"timezone\":\"Europe/Amsterdam\"}")
                .when().put("/api/settings")
                .then().statusCode(200);

        // 2. Create a 60-minute meeting type (unique slug per run), capturing its id.
        String slug = "api-intro-" + System.nanoTime();
        Integer typeId = given().contentType("application/json")
                .body("{\"name\":\"API Intro\",\"slug\":\"" + slug + "\",\"durationMinutes\":60}")
                .when().post("/api/meeting-types")
                .then().statusCode(201)
                .extract().path("id");

        // 3. Add a PER-TYPE Monday 09:00-11:00 rule (scoped to this type's id). A global rule
        //    (meetingTypeId:null) would commit to the shared Dev Services DB and corrupt the
        //    global-rule counts in AvailabilityRuleTest / SlotServiceTest; a per-type rule does not.
        given().contentType("application/json")
                .body("{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"meetingTypeId\":" + typeId + "}")
                .when().post("/api/availability")
                .then().statusCode(201);

        // 4. Preview slots over a Monday (2026-06-08 is a Monday). The per-type rule overrides
        //    global for this type, so we still expect 2 back-to-back 60-min slots (09:00, 10:00).
        given().when().get("/api/meeting-types/" + slug + "/slots?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200).body("size()", is(2));
    }

    @Test
    void secretTypeIsHiddenFromPublicListButBookableByLink() {
        // Ensure settings exist for slot preview.
        given().contentType("application/json")
                .body("{\"ownerName\":\"Owner\",\"ownerEmail\":\"o@example.com\",\"timezone\":\"Europe/Amsterdam\"}")
                .when().put("/api/settings")
                .then().statusCode(200);

        String slug = "secret-api-" + System.nanoTime();
        given().contentType("application/json")
                .body("{\"name\":\"Secret\",\"slug\":\"" + slug + "\",\"durationMinutes\":60,\"secret\":true}")
                .when().post("/api/meeting-types")
                .then().statusCode(201);

        // Hidden from the public list.
        given().when().get("/api/meeting-types")
                .then().statusCode(200).body("slug", not(hasItem(slug)));

        // Visible in the admin list.
        given().when().get("/api/meeting-types/all")
                .then().statusCode(200).body("slug", hasItem(slug));

        // Still reachable by direct slug (booking link path) — 200, not 404.
        given().when().get("/api/meeting-types/" + slug + "/slots?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200);
    }
}
```

> **Note:** this test writes to the shared Dev Services DB (not rolled back). The unique `slug` and the global Monday rule make it self-contained; reading slots filters by the created type's day-of-week resolution. `2026-06-08` is a Monday.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=MeetingTypeResourceTest`
Expected: FAIL — 404s (no resources yet).

- [ ] **Step 3: Write `SettingsResource`**

`src/main/java/com/calit/api/SettingsResource.java`:

```java
package com.calit.api;

import com.calit.domain.OwnerSettings;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/settings")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SettingsResource {

    public record SettingsRequest(String ownerName, String ownerEmail, String timezone) {}

    @PUT
    @Transactional
    public OwnerSettings update(SettingsRequest req) {
        OwnerSettings s = OwnerSettings.get();
        if (s == null) {
            s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
        }
        s.ownerName = req.ownerName();
        s.ownerEmail = req.ownerEmail();
        s.timezone = req.timezone();
        s.persist();
        return s;
    }
}
```

- [ ] **Step 4: Write `MeetingTypeResource`**

`src/main/java/com/calit/api/MeetingTypeResource.java`:

```java
package com.calit.api;

import com.calit.availability.SlotService;
import com.calit.availability.TimeSlot;
import com.calit.domain.MeetingType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

@Path("/api/meeting-types")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MeetingTypeResource {

    @Inject
    SlotService slotService;

    public record CreateMeetingTypeRequest(
            String name, String slug, int durationMinutes,
            Integer bufferBeforeMinutes, Integer bufferAfterMinutes, String description,
            Boolean secret) {}

    /** Public listing — active, non-secret types only (the invitee landing page). */
    @GET
    public List<MeetingType> list() {
        return MeetingType.listPublic();
    }

    /** Admin listing — every type, including secret/inactive. (Auth-gated in Plan 5.) */
    @GET
    @Path("/all")
    public List<MeetingType> listAllAdmin() {
        return MeetingType.listAll();
    }

    @POST
    @Transactional
    public Response create(CreateMeetingTypeRequest req) {
        MeetingType t = new MeetingType();
        t.name = req.name();
        t.slug = req.slug();
        t.durationMinutes = req.durationMinutes();
        t.bufferBeforeMinutes = req.bufferBeforeMinutes() == null ? 0 : req.bufferBeforeMinutes();
        t.bufferAfterMinutes = req.bufferAfterMinutes() == null ? 0 : req.bufferAfterMinutes();
        t.description = req.description();
        t.secret = req.secret() != null && req.secret();
        t.persist();
        return Response.status(Response.Status.CREATED).entity(t).build();
    }

    @GET
    @Path("/{slug}/slots")
    public List<TimeSlot> slots(@PathParam("slug") String slug,
                                @QueryParam("from") String from,
                                @QueryParam("to") String to) {
        MeetingType t = MeetingType.findBySlug(slug);
        if (t == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        return slotService.generateRawSlots(t, LocalDate.parse(from), LocalDate.parse(to));
    }
}
```

- [ ] **Step 5: Write `AvailabilityResource`**

`src/main/java/com/calit/api/AvailabilityResource.java`:

```java
package com.calit.api;

import com.calit.domain.AvailabilityRule;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Path("/api/availability")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AvailabilityResource {

    public record RuleRequest(DayOfWeek dayOfWeek, String startTime, String endTime, Long meetingTypeId) {}

    @POST
    @Transactional
    public Response create(RuleRequest req) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = req.dayOfWeek();
        r.startTime = LocalTime.parse(req.startTime());
        r.endTime = LocalTime.parse(req.endTime());
        r.meetingTypeId = req.meetingTypeId();
        r.persist();
        return Response.status(Response.Status.CREATED).entity(r).build();
    }
}
```

- [ ] **Step 6: Run it to confirm it passes**

Run: `mvn test -Dtest=MeetingTypeResourceTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `mvn test`
Expected: PASS — all tests (Health, OwnerSettings, MeetingType, AvailabilityRule, SlotService, MeetingTypeResource).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/calit/api/ src/test/java/com/calit/api/MeetingTypeResourceTest.java
git commit -m "feat: add REST API for settings, meeting types, availability, slot preview"
```

---

### Task 8: Custom booking fields (BookingField)

Owner-defined extra fields for the booking form. Full name + email are always-present built-ins (handled by `Booking` in Plan 3), so they are NOT modeled here. The migration (Task 2) already seeded a global optional `description` field.

**Files:**
- Create: `src/main/java/com/calit/domain/BookingField.java`
- Create: `src/main/java/com/calit/api/BookingFieldResource.java`
- Test: `src/test/java/com/calit/domain/BookingFieldTest.java`
- Test: `src/test/java/com/calit/api/BookingFieldResourceTest.java`

- [ ] **Step 1: Write the failing entity test**

`src/test/java/com/calit/domain/BookingFieldTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BookingFieldTest {

    @Test
    @TestTransaction
    void globalFormIncludesSeededDescription() {
        // No per-type fields for this id -> falls back to the global default form.
        List<BookingField> form = BookingField.formFor(999_999L);
        assertTrue(form.stream().anyMatch(f -> "description".equals(f.fieldKey)));
    }

    @Test
    @TestTransaction
    void perTypeFieldsOverrideGlobalAndKeepOrder() {
        // booking_field.meeting_type_id is a real FK, so persist a MeetingType first and use
        // its generated id (a literal id would violate the FK constraint).
        MeetingType type = new MeetingType();
        type.name = "BF Test";
        type.slug = "bookingfield-override-type";
        type.durationMinutes = 30;
        type.persist();

        BookingField company = field(type.id, "company", "Company",
                BookingField.FieldType.SHORT_TEXT, true, 1);
        company.persist();
        BookingField vat = field(type.id, "vat", "VAT ID",
                BookingField.FieldType.SHORT_TEXT, false, 0);
        vat.persist();

        List<BookingField> form = BookingField.formFor(type.id);

        assertEquals(2, form.size());
        assertEquals("vat", form.get(0).fieldKey);     // position 0 first
        assertEquals("company", form.get(1).fieldKey); // global description NOT included
    }

    private BookingField field(Long typeId, String key, String label,
                               BookingField.FieldType type, boolean required, int position) {
        BookingField f = new BookingField();
        f.meetingTypeId = typeId;
        f.fieldKey = key;
        f.label = label;
        f.type = type;
        f.required = required;
        f.position = position;
        return f;
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=BookingFieldTest`
Expected: FAIL — compilation error, `BookingField` does not exist.

- [ ] **Step 3: Write the entity**

`src/main/java/com/calit/domain/BookingField.java`:

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "booking_field")
public class BookingField extends PanacheEntityBase {

    public enum FieldType { SHORT_TEXT, LONG_TEXT, EMAIL, PHONE, NUMBER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Null = part of the global default form. Otherwise overrides the form for that meeting type. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    @Column(name = "field_key", nullable = false, length = 64)
    public String fieldKey;

    @Column(nullable = false)
    public String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public FieldType type;

    @Column(nullable = false)
    public boolean required = false;

    @Column(nullable = false)
    public int position = 0;

    /** Per-type fields if the meeting type defines any; otherwise the global default form. */
    public static List<BookingField> formFor(Long meetingTypeId) {
        List<BookingField> typed = list("meetingTypeId = ?1 order by position", meetingTypeId);
        return typed.isEmpty()
                ? list("meetingTypeId is null order by position")
                : typed;
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=BookingFieldTest`
Expected: PASS.

- [ ] **Step 5: Write the failing resource test**

`src/test/java/com/calit/api/BookingFieldResourceTest.java`:

```java
package com.calit.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class BookingFieldResourceTest {

    @Test
    void createFieldForTypeAndReadResolvedForm() {
        // Create a meeting type to attach fields to.
        String slug = "fields-intro-" + System.nanoTime();
        Integer typeId = given().contentType("application/json")
                .body("{\"name\":\"Fields Intro\",\"slug\":\"" + slug + "\",\"durationMinutes\":30}")
                .when().post("/api/meeting-types")
                .then().statusCode(201)
                .extract().path("id");

        // Add a required custom field for that type.
        given().contentType("application/json")
                .body("{\"meetingTypeId\":" + typeId + ",\"fieldKey\":\"company\",\"label\":\"Company\","
                        + "\"type\":\"SHORT_TEXT\",\"required\":true,\"position\":0}")
                .when().post("/api/booking-fields")
                .then().statusCode(201);

        // Resolved form for that type's slug exposes the custom field.
        given().when().get("/api/meeting-types/" + slug + "/form")
                .then().statusCode(200).body("fieldKey", hasItem("company"));
    }
}
```

- [ ] **Step 6: Run it to confirm it fails**

Run: `mvn test -Dtest=BookingFieldResourceTest`
Expected: FAIL — 404 (no `/api/booking-fields` resource yet).

- [ ] **Step 7a: Add the resolved-form endpoint to `MeetingTypeResource`**

The resolved form lives at `GET /api/meeting-types/{slug}/form`. It MUST be a method on `MeetingTypeResource` (which owns `@Path("/api/meeting-types")`), NOT on `BookingFieldResource`. JAX-RS root-resource selection routes `/api/meeting-types/...` to the resource with the most specific `@Path` (`MeetingTypeResource`) and does NOT fall back to another root resource (e.g. one at `@Path("/api")`) for sub-paths it doesn't match — so a `form` method placed on `BookingFieldResource` would be unreachable (404).

Add this method to `src/main/java/com/calit/api/MeetingTypeResource.java` (alongside the existing `slots` method; `BookingField` is already importable from `com.calit.domain`):

```java
    /** Resolved invitee form (excludes the always-present full name + email built-ins). */
    @GET
    @Path("/{slug}/form")
    public List<com.calit.domain.BookingField> form(@PathParam("slug") String slug) {
        MeetingType t = MeetingType.findBySlug(slug);
        if (t == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        return com.calit.domain.BookingField.formFor(t.id);
    }
```

(Use a normal `import com.calit.domain.BookingField;` at the top instead of the fully-qualified name if you prefer — either is fine.)

- [ ] **Step 7b: Write the `BookingFieldResource` (create + delete only)**

`src/main/java/com/calit/api/BookingFieldResource.java`:

```java
package com.calit.api;

import com.calit.domain.BookingField;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BookingFieldResource {

    public record FieldRequest(Long meetingTypeId, String fieldKey, String label,
                               BookingField.FieldType type, Boolean required, Integer position) {}

    @POST
    @Path("/booking-fields")
    @Transactional
    public Response create(FieldRequest req) {
        BookingField f = new BookingField();
        f.meetingTypeId = req.meetingTypeId();
        f.fieldKey = req.fieldKey();
        f.label = req.label();
        f.type = req.type();
        f.required = req.required() != null && req.required();
        f.position = req.position() == null ? 0 : req.position();
        f.persist();
        return Response.status(Response.Status.CREATED).entity(f).build();
    }

    @DELETE
    @Path("/booking-fields/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = BookingField.deleteById(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }
}
```

- [ ] **Step 8: Run it to confirm it passes**

Run: `mvn test -Dtest=BookingFieldResourceTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/calit/domain/BookingField.java \
  src/main/java/com/calit/api/BookingFieldResource.java \
  src/main/java/com/calit/api/MeetingTypeResource.java \
  src/test/java/com/calit/domain/BookingFieldTest.java \
  src/test/java/com/calit/api/BookingFieldResourceTest.java
git commit -m "feat: add owner-defined custom booking fields (BookingField + resolved form API)"
```

---

## Self-Review against spec

**1. Spec coverage (Plan 1 scope):**
| Requirement | Task |
|---|---|
| Meeting types of a given length (feat 1) | Task 4 (`durationMinutes`), Task 6 (slot gen), Task 7 (create API) |
| Min buffer before/after — stored (feat 6) | Task 4 (`bufferBefore/AfterMinutes`); enforcement deferred to Plan 3 (documented) |
| Work hours global + per-meeting-type (feat 7) | Task 5 (`AvailabilityRule`, global vs typed), Task 6 (override resolution), Task 7 (rule API) |
| Secret meeting types (feat 9) | Task 2 (`secret` column), Task 4 (`MeetingType.secret`, `listPublic()`), Task 7 (public `GET /api/meeting-types` excludes secret; `/all` includes; direct slug still bookable) |
| Custom booking-field definitions (feat 10) | Task 2 (`booking_field` table + seeded `description`), Task 8 (`BookingField` + `FieldType` + `formFor` override resolution + CRUD + resolved `/form` API). Per-booking answer storage + required validation are in Plan 3; full name + email are built-ins |
| Health probes + statelessness (NFR) | Task 1 (`quarkus-smallrye-health`, `/q/health/ready` test, no in-process state, env-driven config) |

Out of scope here (later plans): Google sync (2), Meet links (3), emails (4), reschedule (5), frontends (8), buffer/conflict enforcement.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to" placeholders. All steps show full code and exact commands.

**3. Type consistency:** `MeetingType.id` (Long), `durationMinutes` (int), `MeetingType.secret` (boolean, default false), `MeetingType.listPublic()` (active + non-secret), `AvailabilityRule.meetingTypeId` (Long, null=global), `OwnerSettings.timezone` (String IANA id), `BookingField` (meetingTypeId null=global, fieldKey, label, `FieldType` enum, required, position) with `formFor(Long)` mirroring the availability override pattern, `SlotService.generateRawSlots(MeetingType, LocalDate, LocalDate)`, `TimeSlot(ZonedDateTime, ZonedDateTime)` — names match across Tasks 3–8 and the overview's cross-plan contract. Plan 3 consumes `BookingField.formFor` + `fieldKey` for required-answer validation.

**Known assumptions (carried to Plan 3):** windows do not cross midnight; slot granularity equals duration (no separate granularity setting yet); buffers stored but not yet subtracted.
