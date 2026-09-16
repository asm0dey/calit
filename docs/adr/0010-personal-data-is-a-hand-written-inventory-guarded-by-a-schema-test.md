# Personal data is a hand-written inventory guarded by a schema test

Erasure, export and the Art. 30 records all need the same answer: which tables and columns hold
personal data, whose it is, and what an erasure does with each. calit keeps that answer in one
hand-written file, `PersonalData.TABLES`, with one entry per table. Each entry lists every column
the table has, the subset that is personal, the data subject (invitee, guest, owner, none), and the
erase route (anonymise in place, delete rows, cascade, age purge, retained indefinitely, not
personal).

What keeps the file honest is `PersonalDataInventoryTest`. It reads the live `information_schema`
after Flyway has migrated and fails when a table or column exists that the inventory does not name,
or when the inventory names one that no longer exists. A migration that adds a column therefore
fails the build until someone decides whether it is personal and what erasure does with it.

## Considered options

**A `PersonalDataSource` interface implemented per module** — each module registers what it holds
and how to erase it. Rejected: it buys extensibility for a second consumer that does not exist, and
spreads one judgement across many files. The guard test would still be needed to catch a module
that forgot to register.

**Reflection over JPA metadata** — derive the table and column list from the entity model.
Rejected: it hides the judgement that matters. Metadata can say a column exists; it cannot say
whether it is personal or what erasure should do with it, and the erasure and export are native SQL
against the schema, not the entity model.

## Consequences

- The safety property is the same under all three options: the schema test. The hand-written file
  is the cheapest thing that test can check against.
- Every new migration that adds a table or column has to touch `PersonalData` in the same change.
  That is intended friction.
- The inventory is also a test input: `AccountDeletionTest` walks it and asserts that no table with
  an `owner_id` column keeps a row for a deleted account, so a new owner-scoped table is covered
  without anyone editing that test.
- The anonymisation SQL in `PrivacyService.anonymise` lists the `booking` personal columns
  explicitly. The test does not check that list against the inventory, so the two are kept in step
  by hand; the method's javadoc points at the inventory entry.
- The docs site's Art. 30 starter (`compliance/records-of-processing`) is written from this file.
  It is prose and is updated by hand when the inventory changes.
- `PersonalData.OUTBOUND` records where data leaves the server and whether erasure can reach it.
  Nothing can test that list; it exists so a new integration lands next to the line saying what
  erasure does about it.
