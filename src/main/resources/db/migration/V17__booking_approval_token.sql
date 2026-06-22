-- Per-booking owner approval token: unguessable nonce for the email approve/decline link.
-- Nullable: only approval-required bookings get one. Unique so it can't collide.
ALTER TABLE booking ADD COLUMN approval_token VARCHAR(36) UNIQUE;
