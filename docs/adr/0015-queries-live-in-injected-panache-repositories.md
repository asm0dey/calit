# Queries and writes live in injected Panache repositories, not in entity statics

Services used to call Panache active-record statics directly (`Booking.findById`,
`MeetingType.findBySlug`, `Booking.getEntityManager()`). Those calls only work after Quarkus rewrites
the entity bytecode, so wiring such as calendar-port calls, fired events and mail sent could only be
tested on Postgres. Entities keep their public fields and stay `PanacheEntityBase`. Queries, persists,
deletes and locking move into `<Entity>Repository`: an `@ApplicationScoped` bean in the entity's
package that `implements PanacheRepositoryBase<E, ID>`. Services receive repositories through the
constructor, so a unit test builds them with `new Service(mock(XRepository.class), …)`. Entities
loaded through a repository inside `@Transactional` are still managed, so dirty checking is unchanged.
`lockForDecision` (`flush` + `refresh(PESSIMISTIC_WRITE)`) moves behind a repository method too.

## Migration rule

Migration is incremental, one service per PR. While an entity static still has callers outside
repositories, the repository method delegates to it. The PR that removes the last outside caller moves
the body into the repository and deletes the static.

## Guard

`PersistenceBoundaryTest` (ArchUnit) forbids database access outside repositories. The existing
violations are frozen in `src/test/resources/archunit_store`. The store only shrinks: CI fails when a
run leaves it dirty, so a PR that removes violations commits the shrunk store. Entity constructors are
not flagged. Freezing matches a violation by origin method and target, ignoring line numbers, so a
second identical call inside an already-frozen method is not caught. Migrated classes therefore go on
the `STRICT` list, which allows no violation at all.
Changing the signature of a method that has frozen violations makes its old store line "resolved" and
the call a new violation; fix it by migrating the call to a repository, or by editing that store line's
origin signature by hand in the same commit.

## Considered options

**`@QuarkusComponentTest` + `@MockPanacheEntities`** — rejected: each test class builds its own
container (about 2 s) with no cross-class cache, and a method costs about 0.1 s, so it is slower than
a test in the shared booted app.

**`quarkus-panache-mock`** — rejected: same build-per-class cost, and it still depends on Quarkus
bytecode rewriting.

**Keep the statics and test only on Postgres** — rejected: setup-only wiring tests pay for a database
each, and the call sites stay untestable without one.

**Plain JPA DAOs without Panache** — rejected: drops the Panache query API for no testability gain
over repositories.

## Consequences

- A test with mock repositories proves the decision and the in-memory mutation, not that the entity
  was managed. One real-stack test per feature stays.
- Repository queries keep their own Postgres tests: owner scoping, constraints,
  `FOR UPDATE SKIP LOCKED`, distinct counts.
- Until the last static is gone, the repository layer is a thin delegate in unmigrated areas.
