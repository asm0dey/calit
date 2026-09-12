-- Bean calit-ighc: a channel can now be registered WITHOUT joining the owner's inherit set.
-- Before this, every channel notified every meeting type that had no override of its own, so a
-- channel meant for one meeting type had to be excluded by overriding every OTHER one
-- (the limitation recorded in docs/superpowers/specs/2026-09-12-outbound-notifications-design.md §9).
-- TRUE for existing rows preserves exactly the behaviour they have today.
ALTER TABLE notification_channel
    ADD COLUMN default_enabled BOOLEAN NOT NULL DEFAULT TRUE;
