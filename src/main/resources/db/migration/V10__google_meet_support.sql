-- Track whether each calendar can mint Google Meet conferences (i.e. "hangoutsMeet" is in the
-- calendar's conferenceProperties.allowedConferenceSolutionTypes). Used to forbid GOOGLE_MEET
-- meeting types whose write-target calendar can't actually create Meet links -- otherwise Google
-- rejects the event insert with 400 "Invalid conference type value" and the booking 500s.
--
-- Default TRUE for backfill: existing selections were made under the old code that always assumed
-- Meet worked, so keep them unblocked. The real capability is written on the next calendar re-save
-- (and the booking-time fallback flips it to FALSE the first time Google rejects a Meet request).
ALTER TABLE google_calendar ADD COLUMN supports_meet BOOLEAN NOT NULL DEFAULT TRUE;
