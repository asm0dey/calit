-- Feature: Google disconnect detection & notification.
-- reconnect_notified_at: when we last emailed this account's owner about a disconnect.
--   NULL = not yet notified for the current outage. Reset to NULL whenever the account
--   recovers (needs_reconnect -> false) so a future outage re-notifies.
-- last_probed_at: when the hourly connection probe last attempted a refresh-token round-trip.
--   NULL = never probed. Used only to avoid redundant cross-replica probing (optimization).
ALTER TABLE google_credential ADD COLUMN reconnect_notified_at timestamptz;
ALTER TABLE google_credential ADD COLUMN last_probed_at timestamptz;
