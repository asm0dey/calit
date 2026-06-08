-- Feature 15: one pending reminder row per CONFIRMED booking, due at
-- (booking.start_utc - lead time). The scheduler tick (Plan 6) claims due
-- unsent rows with SELECT ... FOR UPDATE SKIP LOCKED and marks them sent.
-- ON DELETE CASCADE: if a booking row is ever hard-deleted, its reminders go
-- with it (cancel/decline are soft status flips, handled in the app by deleting
-- the unsent reminder explicitly -- see ReminderScheduler).
CREATE TABLE reminder (
    id          BIGSERIAL    PRIMARY KEY,
    booking_id  BIGINT       NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
    send_at     TIMESTAMPTZ  NOT NULL,
    kind        VARCHAR(24)  NOT NULL,          -- e.g. 'REMINDER'
    sent_at     TIMESTAMPTZ                     -- NULL = unsent
);

-- The tick scans for due, unsent reminders: WHERE sent_at IS NULL AND send_at <= now().
-- A partial index on (send_at) WHERE sent_at IS NULL keeps that claim query cheap as
-- the table accumulates already-sent rows.
CREATE INDEX idx_reminder_due ON reminder (send_at) WHERE sent_at IS NULL;
