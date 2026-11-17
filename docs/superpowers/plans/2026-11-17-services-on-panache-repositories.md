# Services on Panache Repositories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `BookingService` and `MeetingHosts` off Panache active-record statics onto injected repositories, so both can be built with `new` and unit-tested with mocks, and add an ArchUnit guard that stops new DB access outside repositories.

**Architecture:** One `@ApplicationScoped` `<Entity>Repository implements PanacheRepositoryBase<E, ID>` per entity, next to the entity. Entities stay `PanacheEntityBase` with public fields. A frozen ArchUnit rule records today's ~400 out-of-repository DB calls in a committed store. New violations fail the build. Each migration PR shrinks the store. Migrated classes are also on a strict, unfrozen list.

**Tech Stack:** Quarkus 3.40.1.2 Hibernate ORM Panache, ArchUnit 1.5.1 (`archunit-junit5`), JUnit 5, Mockito (`quarkus-junit5-mockito`), AssertJ.

**Spec:** GitHub issue #343 ("Services on Panache repositories, so their wiring is unit-testable without a database"), plus the maintainer's addition: ArchUnit must ensure no SQL/DB interaction outside repositories. Rule M and the "must stay on Postgres" list come from `docs/superpowers/plans/2026-11-15-tests-off-real-stack.md`. Task 2 writes the ADR.

## Global Constraints

- One service per PR. This PR covers `BookingService` and `MeetingHosts` only. The other ~350 calls stay frozen in the ArchUnit store.
- Entities stay `PanacheEntityBase` with public fields. Only query and persist entry points move.
- Repositories: `@ApplicationScoped`, constructor-injected into services, never `@Inject` fields.
- No `@QuarkusComponentTest`, no `quarkus-panache-mock`. Wiring tests are plain JUnit: `new BookingService(mocks…)`.
- Dirty checking stays as it is: no explicit `update` calls added.
- Keep one real-stack end-to-end test per feature (#276 practice).
- Repository queries keep Postgres tests: owner scoping, constraints, locks, `FOR UPDATE SKIP LOCKED`, distinct counts.
- AssertJ only. Hamcrest only inside RestAssured `.body(...)`.
- JDK imports as `import module java.base;`.
- No user-facing change: no `docs-site`, no changelog entry, no i18n keys.
- `mvn test` must be fully green, whole suite, before the PR (CLAUDE.md).
- ADR in `docs/adr/` plus a precedent-graph entry (issue #343 and CLAUDE.md).

## Review Focus

1. **Unmanaged entities after the move.** A booking loaded through `BookingRepository` inside `@Transactional` must still be managed: `booking.status = CONFIRMED` reaches the DB at commit. A mock test cannot prove this. Task 4 keeps `ApproveDeclineTest#approveConfirmsPendingBooking` (or the equivalent real-stack approve test) on Postgres and checks the status in a fresh transaction.
2. **Lock order in `lockForDecision` / `declineSetGuest`.** The flush-before-refresh step and "lock the set lead first" must survive the move. `BookingRepositoryTest` pins both: unflushed changes survive `lockForDecision`, and a set row locks the lead.
3. **Mixing active record and repository on one entity.** The Quarkus docs show each pattern alone, not both on one entity. Task 3 Step 1 proves a `PanacheRepositoryBase<Booking, Long>` works for a `PanacheEntityBase` entity before anything is built on top of it.
4. **ArchUnit false negatives.** A finder such as `Booking.group(...)` is a static on the entity, not a `PanacheEntityBase` member. A rule that only checks `PanacheEntityBase` members would let new code call it. `PersistenceAccessTest` pins the transitive check with fixtures.
5. **A forgotten store shrink.** When a PR removes violations, ArchUnit rewrites the store locally. If the shrunk store is not committed, the next PR re-adds those lines silently. CI fails when the test run leaves `src/test/resources/archunit_store` dirty (Task 1).

---

### Task 1: ArchUnit persistence-boundary guard (frozen) and baseline timing

**Files:**
- Modify: `pom.xml` (test dependency `com.tngtech.archunit:archunit-junit5:1.5.1`, version as a `<archunit.version>` property next to `assertj.version`)
- Create: `src/test/resources/archunit.properties`
- Create: `src/test/java/site/asm0dey/calit/arch/PersistenceAccess.java`
- Create: `src/test/java/site/asm0dey/calit/arch/PersistenceBoundaryTest.java`
- Create: `src/test/java/site/asm0dey/calit/arch/PersistenceAccessTest.java`
- Create: `src/test/java/site/asm0dey/calit/arch/fixtures/*.java` (fixtures for the self-test)
- Create (generated, committed): `src/test/resources/archunit_store/**`
- Modify: `.github/workflows/ci.yml` (step after `verify`)

**Interfaces:**
- Produces: `PersistenceAccess.ACCESS_PERSISTENCE: ArchCondition<JavaClass>` and `PersistenceAccess.IS_REPOSITORY: DescribedPredicate<JavaClass>`. `PersistenceBoundaryTest.STRICT: List<Class<?>>` is the list later tasks append to.

- [ ] **Step 0: Baseline timing.** Before any change, on `origin/main`: `/usr/bin/time -v ./mvnw test 2>&1 | tee $SCRATCH/before.log`. Record `Tests run:` totals, wall clock, and per-class times of the `booking/` tests (`grep 'Time elapsed' target/surefire-reports/*.txt`). Task 6 compares against these numbers.

- [ ] **Step 1: Write the failing self-test `PersistenceAccessTest`** (plain JUnit). It imports fixture classes with `new ClassFileImporter().importClasses(...)` and evaluates `noClasses().that(not(IS_REPOSITORY)).should(ACCESS_PERSISTENCE)`. Fixtures are tiny classes in `arch/fixtures` that call real calit entities:

| Test | Fixture does | Expected |
|---|---|---|
| `flagsInheritedPanacheStatic` | `Booking.findById(1L)` | violation |
| `flagsEntityInstancePersist` | `new Booking().persist()` | violation |
| `flagsEntityFinderTransitively` | `Booking.group(uuid)` | violation |
| `flagsEntityManagerCall` | `em.flush()` on an `EntityManager` param | violation |
| `flagsRepositoryQueryString` | `bookingRepo.find("x")` on a `PanacheRepositoryBase` param | violation |
| `allowsRepositoryById` | `repo.findById(1L)`, `repo.persist(b)`, `repo.delete(b)`, `repo.flush()` | none |
| `allowsPureEntityMethods` | `b.isErased()`, `OwnerSettings.coerceZone("UTC")`, `MeetingTypeHost.of(...)` | none |
| `allowsCatchingPersistenceException` | `catch (PersistenceException e) { e.getCause(); }` | none |
| `exemptsRepositories` | a fixture `implements PanacheRepositoryBase<Booking, Long>` calling `find("x")` and `getEntityManager()` | none |

Assert on `result.getFailureReport().getDetails()`: `isEmpty()` for the allowed cases, `anySatisfy(d -> assertThat(d).contains("<target method name>"))` for the violation cases.

- [ ] **Step 2: Run it.** `./mvnw test -Dtest=PersistenceAccessTest`. Expected: compile failure (`PersistenceAccess` missing).

- [ ] **Step 3: Implement `PersistenceAccess`.** A `JavaCall` from the class "accesses persistence" when one of these holds:
  1. The target owner is assignable to `jakarta.persistence.EntityManager`, `jakarta.persistence.EntityManagerFactory`, `jakarta.persistence.Query`, `org.hibernate.Session`, `io.quarkus.hibernate.orm.panache.Panache`, `io.quarkus.hibernate.orm.panache.PanacheQuery`, `javax.sql.DataSource`, `java.sql.Connection`, `java.sql.Statement` or `java.sql.ResultSet`.
  2. `target.resolveMember()` is declared in `PanacheEntityBase`. This covers `Booking.findById`, `b.persist()` and `getEntityManager()`.
  3. The resolved member is declared in `PanacheRepositoryBase` and is not in the allowlist: `findById`, `findByIdOptional`, `persist`, `persistAndFlush`, `flush`, `isPersistent`, and `delete` with exactly one parameter.
  4. The target is a static method declared in a `PanacheEntityBase` subclass whose own calls, followed transitively through static methods of the same class (keep a visited set), satisfy 1–3. This covers `Booking.group` and `Booking.findLiveForPrivacy`.

  `IS_REPOSITORY` = `assignableTo(PanacheRepositoryBase.class)`. Nested and anonymous classes of a repository count as the repository: check `getEnclosingClass()` up the chain.
  The violation message is `"<origin full name> accesses persistence via <target full name>"`. ArchUnit appends the source location, and its default line matcher ignores line numbers in the frozen store.

- [ ] **Step 4: Run.** `./mvnw test -Dtest=PersistenceAccessTest`. Expected: PASS, 9 tests.

- [ ] **Step 5: `archunit.properties` and `PersistenceBoundaryTest`.**
  - `archunit.properties`: `freeze.store.default.path=src/test/resources/archunit_store` and `freeze.store.default.allowStoreCreation=false`. Leave `allowStoreUpdate` at its default `true`, so a fixed violation shrinks the store locally.
  - `PersistenceBoundaryTest` (plain JUnit, `@AnalyzeClasses(packages = "site.asm0dey.calit", importOptions = ImportOption.DoNotIncludeTests.class)`):
    - `@ArchTest static final ArchRule noPersistenceOutsideRepositories = FreezingArchRule.freeze(noClasses().that(not(IS_REPOSITORY)).should(ACCESS_PERSISTENCE).as("persistence access lives in repositories"))`
    - `@ArchTest static final ArchRule migratedClassesAreClean`: same condition, not frozen, `that().belongToAnyOf(STRICT…)`. `STRICT` starts empty; while it is empty the rule uses `.allowEmptyShould(true)`.

- [ ] **Step 6: Create the store.** `./mvnw test -Dtest=PersistenceBoundaryTest -Darchunit.freeze.store.default.allowStoreCreation=true`. Expected: PASS, and `src/test/resources/archunit_store/` holds `stored.rules` plus one violations file. Sanity check: `grep -c 'accesses persistence' src/test/resources/archunit_store/*-*` is in the 400s. Run once more with no flag: PASS, `git status` shows no store change (the store is stable).

- [ ] **Step 7: CI gate on a dirty store.** In `ci.yml`, right after the `./mvnw … verify` step of the test job, add a step: `git diff --exit-code -- src/test/resources/archunit_store || { echo "::error::ArchUnit store shrank; commit src/test/resources/archunit_store"; exit 1; }`. Run `actionlint`. Expected: clean.

- [ ] **Step 8: Commit.** `test(arch): freeze DB access outside repositories with ArchUnit (#343)`.

### Task 2: ADR, CLAUDE.md, precedent

**Files:**
- Create: `docs/adr/0015-queries-live-in-injected-panache-repositories.md` (follow the format of `0014-…`)
- Modify: `CLAUDE.md`, Architecture `domain/` bullet and Tests section

- [ ] **Step 1: Write ADR 0015.**
  - Decision: entities keep public fields and stay `PanacheEntityBase`. Queries, persists, deletes and locking live in `<Entity>Repository` (`@ApplicationScoped`, `implements PanacheRepositoryBase<E, ID>`, same package as the entity). Services get repositories through the constructor.
  - Rejected: `@QuarkusComponentTest` + `@MockPanacheEntities` (about 2 s container build per class, no cross-class cache, about 0.1 s per method, so slower than the shared booted app); `quarkus-panache-mock`; leaving the statics and testing only on Postgres; plain JPA DAOs without Panache.
  - Migration rule: while an entity static still has callers outside repositories, the repository method delegates to it. The PR that removes the last outside caller moves the body into the repository and deletes the static.
  - Guard: the frozen ArchUnit rule from Task 1. The store only shrinks, CI fails on an uncommitted shrink, and migrated classes go on `STRICT`.
  - Consequence: a mock test proves the decision and the in-memory mutation, not that the entity was managed, so one real-stack test per feature stays.
- [ ] **Step 2: CLAUDE.md.** In the `domain/` bullet, add: "queries and writes go through `<Entity>Repository` beans (ADR 0015); active-record statics remain only in unmigrated code, frozen by `PersistenceBoundaryTest`". Tests section, one bullet: "A service on repositories is tested with `new Service(mock(XRepository.class), …)` in plain JUnit. A new DB call outside a repository fails `PersistenceBoundaryTest`; when you remove one, commit the shrunk `src/test/resources/archunit_store`."
- [ ] **Step 3: Precedent.** First `uv run <precedent plugin>/scripts/precedent.py check --topic persistence-access --chose panache-repositories`. If nothing matches, `record` with `--rejected` (the four alternatives above) and `--rationale` (wiring unit-testable in milliseconds, no Quarkus bytecode rewrite needed, ArchUnit-frozen incremental migration).
- [ ] **Step 4: Commit.** `docs(adr): queries live in injected Panache repositories (#343)`.

### Task 3: Repositories for the entities `BookingService` and `MeetingHosts` touch

**Files (create, each `@ApplicationScoped`, same package as the entity):**
- `booking/BookingRepository.java`: `PanacheRepositoryBase<Booking, Long>`
- `booking/BookingGuestRepository.java`, `booking/BookingInviteRepository.java`
- `domain/MeetingTypeRepository.java`, `domain/MeetingTypeHostRepository.java`, `domain/OwnerSettingsRepository.java`, `domain/BookingFieldRepository.java`
- `domain/MeetingTypeDurationRepository.java`: `PanacheRepositoryBase<MeetingTypeDuration, MeetingTypeDuration.Key>`
- `user/AppUserRepository.java`
- Test: `src/test/java/site/asm0dey/calit/booking/BookingRepositoryTest.java`, `.../domain/MeetingTypeHostRepositoryTest.java` (`@QuarkusTest`, default profile)

**Interfaces (Produces).** Names mirror the entity static they replace. Each one delegates or carries the body as the ADR migration rule says.
- `BookingRepository`: `List<Booking> heldOverlapping(Long ownerId, Instant from, Instant to)`, `Booking findByManageToken(String)`, `List<Booking> group(UUID)`, `Booking leadOfGroup(UUID, Long creatorOwnerId)`, `List<Booking> set(UUID)`, `Booking leadOfSet(UUID, Long creatorOwnerId)`, `long countDistinctBookingsByEmailBetween(String, Instant, Instant)`, `List<Booking> futureHeldGroupRows(Long hostOwnerId, Long meetingTypeId, Instant now)` (replaces the `Booking.list` in `cancelFutureGroupBookingsForHost`), `long countFutureHeldGroupRows(Long hostOwnerId, Long meetingTypeId, Instant now)` (replaces `Booking.count` in `MeetingHosts.countFutureBookings`; the two share one query string constant), `Booking lockForDecision(Long bookingId)` (null when absent; body moves from `BookingService.lockForDecision` unchanged: flush, then refresh `PESSIMISTIC_WRITE` on the row or on the set lead, then re-read the set), `Booking lockSetLead(UUID setId, Long creatorOwnerId)` (flush, then lead under `PESSIMISTIC_WRITE`; used by `lockForDecision` and `declineSetGuest`), `void flush()` (inherited).
- `BookingGuestRepository`: `List<BookingGuest> activeForBooking(Long)`, `BookingGuest findInBooking(Long bookingId, String email)`, `BookingGuest findByDeclineToken(String)`, `void reread(BookingGuest)` (`getEntityManager().refresh(g)`).
- `BookingInviteRepository`: `long consume(String tokenHash, Long meetingTypeId, Instant now)`.
- `MeetingTypeRepository`: `MeetingType findBySlug(Long ownerId, String slug)`, `boolean slugUsedByOwner(Long ownerId, String slug, Long excludeTypeId)`.
- `MeetingTypeHostRepository`: `List<MeetingTypeHost> forType(Long)`, `List<MeetingTypeHost> acceptedForType(Long)`, `MeetingTypeHost findHost(Long meetingTypeId, Long ownerId)`, `List<MeetingTypeHost> cohostedTypesFor(Long)`, `boolean isMultiHost(Long)`, `long countForType(Long)`, `long countCohosts(Long)`, `long deleteHost(Long meetingTypeId, Long ownerId)`, `long deleteCreatorRow(Long meetingTypeId)`.
- `MeetingTypeDurationRepository`: `List<Integer> allowedDurations(MeetingType)`, `boolean isAllowed(MeetingType, int)`, `MeetingTypeDuration findRow(Long, int)`.
- `OwnerSettingsRepository`: `OwnerSettings forOwner(Long ownerId)`.
- `BookingFieldRepository`: `List<BookingField> formFor(Long ownerId, Long meetingTypeId)`.
- `AppUserRepository`: `Map<Long, Boolean> enabledByIds(Collection<Long> ids)` (the `AppUser.list("id in ?1")` in `MeetingHosts` line ~89), plus inherited `findById`.

- [ ] **Step 1: Prove the mix works (failing test first).** `BookingRepositoryTest#repositoryLoadsActiveRecordEntity`: persist a `Booking` through the existing test fixture helpers, then `assertThat(bookings.findById(id)).extracting(b -> b.manageToken).isEqualTo(token)`. Run `./mvnw test -Dtest=BookingRepositoryTest`. Expected: FAIL (`BookingRepository` missing). Create the class, run again. Expected: PASS. If Quarkus rejects a repository on an active-record entity, stop and report. The ADR's premise depends on this.
- [ ] **Step 2: Repository tests for what no remaining Postgres test pins.** In `BookingRepositoryTest`:
  - `lockForDecisionKeepsUnflushedChanges`: in one `QuarkusTransaction`, set a field, call `lockForDecision`, then assert the field from a fresh transaction after commit.
  - `lockForDecisionOnSetRowReturnsClickedRow`: returns the clicked row, not the lead.
  - `lockForDecisionReturnsNullForUnknownId`.
  - `countDistinctCountsGroupAndSetOnce`: one standalone row, one group of 2 rows, one set of 3 rows, same email, same day → `isEqualTo(3L)`.
  - `futureHeldGroupRowsExcludesPastCancelledAndStandalone`.
  - `heldOverlappingIsOwnerScoped`: another owner's overlapping booking is absent.

  In `MeetingTypeHostRepositoryTest`: `deleteCreatorRowLeavesCohosts` and `isMultiHostIgnoresCreatorRow`.
- [ ] **Step 3: Implement all repositories** to the Interfaces block.
- [ ] **Step 4: Run** `./mvnw test -Dtest='*RepositoryTest,PersistenceBoundaryTest'`. Expected: PASS, store unchanged (repositories are exempt, and no call site moved yet).
- [ ] **Step 5: Commit.** `feat(persistence): repositories for booking and host entities (#343)`.

### Task 4: `MeetingHosts` on repositories, with wiring tests

**Files:**
- Modify: `booking/MeetingHosts.java` (constructor gains `MeetingTypeRepository`, `MeetingTypeHostRepository`, `MeetingTypeDurationRepository`, `BookingRepository`, `AppUserRepository`)
- Modify: test classes that construct `MeetingHosts` by hand, if any (`grep -rn "new MeetingHosts(" src/test`)
- Create: `src/test/java/site/asm0dey/calit/booking/MeetingHostsWiringTest.java` (plain JUnit, Mockito)
- Modify/trim: `MeetingHostsTest`, `MeetingHostsMutationTest`, `MeetingHostsBufferTest` (Rule M)
- Modify: `arch/PersistenceBoundaryTest.java` (`STRICT += MeetingHosts.class`), `src/test/resources/archunit_store/**`

**Interfaces:**
- Consumes: Task 3 repositories.
- Produces: `MeetingHosts(CalendarPort, Event<HostConsentRequested>, MeetingTypeRepository, MeetingTypeHostRepository, MeetingTypeDurationRepository, BookingRepository, AppUserRepository)`. Public method signatures do not change.

- [ ] **Step 1: Add `MeetingHosts.class` to `STRICT`.** Run `./mvnw test -Dtest=PersistenceBoundaryTest`. Expected: FAIL, listing every `MeetingHosts` call (≈23).
- [ ] **Step 2: Replace each call** with the repository method named in Task 3. Replace `h.persist()` with `hosts.persist(h)`. If an entity static now has no caller outside repositories (`grep -rn "MeetingTypeHost\.forType(" src/main`), move its body into the repository and delete the static (ADR rule).
- [ ] **Step 3: Run** `./mvnw test -Dtest='PersistenceBoundaryTest,MeetingHosts*'`. Expected: PASS. `git diff --stat src/test/resources/archunit_store` shows only deletions.
- [ ] **Step 4: Move tests by Rule M.** For each method in the three `MeetingHosts*Test` classes, check its assertions. If every one is about a port call, a fired event, a return value or exception, or an in-memory field, the DB is setup only. Re-create that method in `MeetingHostsWiringTest` under the same name and with the same assertions, built on `new MeetingHosts(mock(...)…)` with stubbed repositories. Delete the original in the same commit. The rest stays (owner scoping, constraints, queries). The commit body lists `Old#method → MeetingHostsWiringTest#method`.
- [ ] **Step 5: Run** `./mvnw -Paffected test-order:affected test` (re-learn the index first if it predates the merge-base, per CLAUDE.md). Expected: green.
- [ ] **Step 6: Commit.** `refactor(hosts): MeetingHosts on repositories, wiring tests in plain JUnit (#343)`, including the shrunk store.

### Task 5: `BookingService` on repositories, with wiring tests

**Files:**
- Modify: `booking/BookingService.java` (constructor gains `BookingRepository`, `BookingGuestRepository`, `BookingInviteRepository`, `MeetingTypeRepository`, `MeetingTypeHostRepository`, `MeetingTypeDurationRepository`, `OwnerSettingsRepository`, `BookingFieldRepository`)
- Modify: `web/AdminResource.java:~2349`, `web/PublicResource.java:~1516` (`cancellableOfSet` becomes an instance call)
- Modify: test classes that construct `BookingService` by hand (`grep -rn "new BookingService(" src/test`)
- Create: `src/test/java/site/asm0dey/calit/booking/BookingServiceWiringTest.java` (plain JUnit, Mockito)
- Modify/trim: the `booking/` `@QuarkusTest` classes listed under Step 5
- Modify: `PersistenceBoundaryTest` (`STRICT += BookingService.class`), `archunit_store/**`

**Interfaces:**
- Consumes: Task 3 repositories, `MeetingHosts` from Task 4 (mocked in wiring tests).
- Produces: `public List<Booking> cancellableOfSet(UUID setId, Long creatorOwnerId)`, now an instance method, same filter. `lengthOf` and `decisionOpen` stay static (pure). Other public signatures do not change.

- [ ] **Step 1: Add `BookingService.class` to `STRICT`** and run `PersistenceBoundaryTest`. Expected: FAIL. The list is the worklist. It includes multi-line `Booking\n.<Booking>group(...)` calls that grep misses.
- [ ] **Step 2: Replace each call.**
  - `Booking.getEntityManager().flush()` in `writeOne` becomes `bookings.flush()`. Keep the `PersistenceException` / `isNoOverlapViolation` handling exactly as it is.
  - Delete the private `lockForDecision`. Callers do `var b = bookings.lockForDecision(id); if (b == null) throw new NotFoundException("No booking " + id);`.
  - In `declineSetGuest`, use `bookings.lockSetLead(...)` and `guests.reread(g)`.
  - Instance `persist()` becomes `<repo>.persist(entity)`.
  - `consumeInvite` and `cancellableOfSet` become instance methods.
  - Apply the ADR move-or-delegate rule per static.
- [ ] **Step 3: Run** `./mvnw test -Dtest='PersistenceBoundaryTest'`, then `./mvnw -Paffected test-order:affected test`. Expected: PASS. The store only loses lines.
- [ ] **Step 4: Managed-entity guard (Review Focus 1).** Make sure a real-stack approve test still asserts the persisted status from a fresh read, for example in `ApproveDeclineTest`. If none does after Step 5's trimming, keep or add `approvePersistsConfirmedStatus`: book with approval, `approve(id)`, then `assertThat(QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findById(id).status)).isEqualTo(BookingStatus.CONFIRMED)`. Test code is outside the ArchUnit import.
- [ ] **Step 5: Move tests by Rule M**, one source class per commit, as in Task 4 Step 4. Candidates: `BookServiceTest`, `ApproveDeclineTest`, `GroupApprovalTest`, `SetApprovalTest`, `RescheduleCancelTest`, `GroupCancelRescheduleTest`, `BookingServiceGuestTest`, `GroupEditDetailsTest`, `UpdateDetailsTest`, `BookingDurationTest`, `RescheduleLengthTest`, `SetCancelTest`, `BookingWriteTargetOverrideTest`, `NoWriteCalendarBookingTest`, `BookingCalendarAddressTest`, `BookingLocaleTest`. The following stay whole on Postgres: anything asserting `booking_no_overlap_held`, locks or concurrency, owner scoping, `countDistinct…`, AFTER_SUCCESS mail observers, HTTP or Qute. Commit each as `test(booking): <Class> wiring tests in plain JUnit (#343)` with the `Old#method → New#method` list.
- [ ] **Step 6: Run** `./mvnw -Paffected test-order:affected test`. Expected: green.
- [ ] **Step 7: Commit** the service change (if not already in Step 3's commit) with the shrunk store: `refactor(booking): BookingService on repositories (#343)`.

### Task 6: Verify, measure, PR

- [ ] **Step 1:** `./mvnw spotless:apply`, then `/usr/bin/time -v ./mvnw verify 2>&1 | tee $SCRATCH/after.log`. Expected: `BUILD SUCCESS`, 0 failures, 0 errors, `git status` clean for `archunit_store`.
- [ ] **Step 2:** Compare with Task 1 Step 0: total wall clock, test count, the `booking/` class times, and the store line count before and after (`grep -c 'accesses persistence'`). Put the table in the PR body.
- [ ] **Step 3:** Tick the #343 todo boxes for design, `BookingService` + `MeetingHosts`, and measure. Leave "follow-ups per service" open.
- [ ] **Step 4:** Open the PR with the `mattpocock-skills:pr` skill. Include a show-me diagram (memory), an AI Disclosure section, and `Closes` nothing (`Part of #343`).
