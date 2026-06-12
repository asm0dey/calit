CREATE TABLE booking (
    id                BIGSERIAL    PRIMARY KEY,
    meeting_type_id   BIGINT       NOT NULL REFERENCES meeting_type(id),
    invitee_name      VARCHAR(255) NOT NULL,
    invitee_email     VARCHAR(255) NOT NULL,
    start_utc         TIMESTAMPTZ  NOT NULL,
    end_utc           TIMESTAMPTZ  NOT NULL,
    google_event_id   VARCHAR(255),
    meet_link         VARCHAR(512),
    -- Feature 14: PENDING (approval hold) / CONFIRMED / CANCELLED / DECLINED.
    status            VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED',
    created_at        TIMESTAMPTZ  NOT NULL,
    -- Invitee manage/reschedule/cancel key: a random UUID set at creation.
    manage_token      VARCHAR(36)  NOT NULL UNIQUE,
    -- Feature 10: submitted values for owner-defined custom BookingFields
    -- (fieldKey -> value). Built-in name/email live in their own columns above.
    answers           JSONB        NOT NULL DEFAULT '{}'::jsonb
);

-- Availability queries scan PENDING+CONFIRMED bookings within a time window.
CREATE INDEX idx_booking_status_start ON booking (status, start_utc);
-- Per-email/day abuse cap (feature 16) counts a single invitee's bookings by created_at.
CREATE INDEX idx_booking_email_created ON booking (invitee_email, created_at);

-- NFR (horizontal scalability): cross-node double-booking guard.
-- App-level "is this slot free?" checks (Task 5/6) cannot be trusted across
-- replicas — two nodes can pass the check simultaneously and both INSERT.
-- This DB-level exclusion constraint makes the INSERT itself the source of
-- truth: Postgres rejects any second HELD booking whose raw time range
-- overlaps an existing held one. btree_gist is required for the `=`/`&&`
-- mix in a GiST exclusion constraint; Dev Services Postgres supports it.
-- "Held" = status IN ('PENDING','CONFIRMED'): a pending approval request
-- (feature 14) holds the slot too, so it cannot be double-requested while
-- the owner decides. NOTE: this guarantees only no RAW-TIME overlap of held
-- rows. Buffers remain an app-level policy (Task 5) — the DB does not know
-- about them. Cancelling/declining sets status to CANCELLED/DECLINED, so the
-- partial WHERE clause drops the row from the constraint and frees the slot.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_held
    EXCLUDE USING gist (tstzrange(start_utc, end_utc) WITH &&)
    WHERE (status IN ('PENDING', 'CONFIRMED'));
