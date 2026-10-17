# Migrations stay compatible with the previous release through views and paired-column triggers

The release after a schema change must not break the previous one: replicas roll, so both versions
run against the new schema at once. V37 therefore renames `google_credential` to `calendar_account`
and `google_calendar` to `calendar`, then creates **compatibility views** under the old names,
filtered `WHERE provider = 'GOOGLE'` and exposing every column under its old name and type. Being
single-table, they are auto-updatable in PostgreSQL, so old replicas read and write through them and
never see CalDAV rows. On tables that stay (`booking`, `meeting_type`), new neutral columns sit next
to the old ones and a `BEFORE INSERT OR UPDATE` trigger keeps each pair equal. The next release drops
the views, the old columns and the triggers together. `calendar_account` stays one table with a
`provider` discriminator.

## Considered options

**Rename in place** — rejected: the previous release's SQL fails the moment the migration runs.

**Parallel per-provider tables** — rejected: every query that spans accounts (busy time, health,
export, erasure) must union them, and the Google path would be duplicated for no gain.

**Per-provider tables now (`JOINED`)** — rejected for now: more migration for a second provider that
needs only a handful of nullable columns. The split is deferred to the Microsoft 365 bean
(`calit-qj4b`), after the shims are gone.

## Consequences

- Compatibility is asserted by tests: the previous release's Flyway validates, the views keep old
  names and types, and its native SQL still runs.
- Until the shims are dropped, schema changes to these tables must keep both sides of each pair in
  step.
