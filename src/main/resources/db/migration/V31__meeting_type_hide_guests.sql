-- Per-meeting-type switch that hides the Guests field on the public booking page (issue #130).
ALTER TABLE meeting_type ADD COLUMN hide_guests BOOLEAN NOT NULL DEFAULT FALSE;
