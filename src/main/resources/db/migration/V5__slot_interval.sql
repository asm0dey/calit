-- Per-type slot cadence (Calendly-style "slot interval"). NULL => fall back to duration_minutes
-- (back-to-back), preserving prior behavior for existing rows.
ALTER TABLE meeting_type ADD COLUMN slot_interval_minutes INTEGER;
