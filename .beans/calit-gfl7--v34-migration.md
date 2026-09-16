---
# calit-gfl7
title: V34 migration
status: completed
type: task
priority: normal
created_at: 2026-09-16T13:16:28Z
updated_at: 2026-09-16T13:25:40Z
parent: calit-l3fk
---

Add erasure, retention and outbox-link columns; entity fields for Booking/OwnerSettings/EmailOutbox



## Summary of Changes

Added `V34__privacy.sql` (four independent, no-backfill changes): `booking.erased_at` (nullable erasure marker), `owner_settings.booking_retention_days` (nullable per-owner retention override), `email_outbox.booking_id`/`email_outbox.owner_id` (nullable FKs, ON DELETE CASCADE, partial indexes), and an explicit `ON DELETE CASCADE` on the existing `booking_meeting_type_id_fkey` (verified the auto-generated name against the running Dev Services Postgres before dropping/recreating it).

Added matching entity fields: `Booking.erasedAt` + `Booking.isErased()`, `OwnerSettings.bookingRetentionDays`, `EmailOutbox.bookingId`/`EmailOutbox.ownerId`.

TDD: wrote `V34MigrationTest` first (RED — all 4 assertions failed), then the migration + entity fields (GREEN). Found and fixed a bug in the brief's verbatim `newColumnsAreNullable` query: its loose `table_name IN (...) AND column_name IN (...)` cross-product collided with the pre-existing, correctly NOT NULL `booking.owner_id` and `owner_settings.owner_id` columns (unrelated to V34), making the assertion impossible to satisfy regardless of migration correctness. Repaired by pairing each check to its actual (table, column).

Full suite: 1149/1149 passing, 0 failures/errors, BUILD SUCCESS. One unrelated environment gap found and resolved (not committed): `calit.css` had never been built in this fresh worktree, failing `StaticAssetsTest`; ran `bun install && bun run css:build` per CLAUDE.md — the file is gitignored so nothing to commit there.
