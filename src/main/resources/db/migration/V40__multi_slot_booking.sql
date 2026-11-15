-- Multi-slot booking: a meeting type may let the invitee pick several slots in one booking
-- (multi_slot_min..multi_slot_max, both NULL = off); the resulting booking rows share set_id.
-- Expand-only: all columns nullable or defaulted, so the previous release keeps working.
ALTER TABLE meeting_type
    ADD COLUMN multi_slot_min INT,
    ADD COLUMN multi_slot_max INT,
    ADD COLUMN multi_slot_same_day BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE booking ADD COLUMN set_id UUID;
CREATE INDEX booking_set_id_idx ON booking (set_id);
