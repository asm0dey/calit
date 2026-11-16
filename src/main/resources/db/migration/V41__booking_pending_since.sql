-- #309: when the booking last (re)entered PENDING through a reschedule; NULL = since created_at.
-- Expand-only: nullable, no backfill; the expiry query reads COALESCE(pending_since, created_at).
ALTER TABLE booking ADD COLUMN pending_since TIMESTAMPTZ;
