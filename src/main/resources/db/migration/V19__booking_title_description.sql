-- Per-booking overrides for the meeting's displayed name and description. NULL = fall back to the
-- meeting type's name/description. Editable post-booking by both host (/me) and invitee (manage token).
ALTER TABLE booking
    ADD COLUMN title       text,
    ADD COLUMN description text;
