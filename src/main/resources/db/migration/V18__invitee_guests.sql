-- Invitee-added guests. They receive create/reschedule/cancel emails + .ics and can decline,
-- but cannot reschedule/cancel the booking. ics_sequence is the iTIP SEQUENCE shared by the
-- booking's guest .ics invites: it bumps on every reschedule so a guest's calendar client
-- treats the new (or cancelling) .ics as superseding the previous one.
ALTER TABLE booking ADD COLUMN ics_sequence INT NOT NULL DEFAULT 0;

CREATE TABLE booking_guest (
    id            BIGSERIAL    PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    booking_id    BIGINT       NOT NULL REFERENCES booking(id)  ON DELETE CASCADE,
    email         VARCHAR(254) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'INVITED',
    decline_token VARCHAR(36)  NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT booking_guest_unique_email UNIQUE (booking_id, email)
);

CREATE INDEX idx_booking_guest_booking ON booking_guest (booking_id);
