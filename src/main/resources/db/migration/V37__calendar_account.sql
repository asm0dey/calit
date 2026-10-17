-- calit-yotg: provider-neutral calendar accounts (Google today, CalDAV next).
--
-- Views, old booking/meeting_type columns and their compat triggers are shims for the previous
-- release; drop them all in the next release (bean created in Task 10).
--
-- The previous release keeps running on this schema during a rolling deploy: it reads and writes
-- google_credential / google_calendar through the single-table (hence auto-updatable) views below,
-- never sees CalDAV rows, and its inserts get provider = 'GOOGLE' from the column default. FKs and
-- constraints follow the renamed tables.

ALTER TABLE google_credential RENAME TO calendar_account;
ALTER TABLE google_calendar RENAME TO calendar;
ALTER TABLE calendar RENAME COLUMN google_credential_id TO account_id;
ALTER TABLE calendar RENAME COLUMN google_calendar_id TO remote_id;

ALTER TABLE calendar_account
    ADD COLUMN provider text NOT NULL DEFAULT 'GOOGLE' CHECK (provider IN ('GOOGLE', 'CALDAV')),
    ALTER COLUMN refresh_token DROP NOT NULL,
    ALTER COLUMN google_sub DROP NOT NULL,
    ADD COLUMN caldav_server_url    text,
    ADD COLUMN caldav_username      text,
    ADD COLUMN caldav_password      text,
    ADD COLUMN caldav_principal_url text,
    ADD COLUMN caldav_home_url      text,
    ADD CONSTRAINT ck_calendar_account_google
        CHECK (provider <> 'GOOGLE' OR (refresh_token IS NOT NULL AND google_sub IS NOT NULL)),
    ADD CONSTRAINT ck_calendar_account_caldav
        CHECK (provider <> 'CALDAV'
               OR (caldav_server_url IS NOT NULL AND caldav_username IS NOT NULL AND caldav_password IS NOT NULL));

CREATE UNIQUE INDEX uq_calendar_account_caldav_principal
    ON calendar_account (owner_id, caldav_principal_url)
    WHERE provider = 'CALDAV';

CREATE VIEW google_credential AS
SELECT id, owner_id, refresh_token, access_token, access_token_expiry, google_sub, account_email,
       needs_reconnect, reconnect_notified_at, last_probed_at
FROM calendar_account
WHERE provider = 'GOOGLE';

-- The IN (subquery) filter keeps the view single-table, so it stays auto-updatable.
CREATE VIEW google_calendar AS
SELECT id, owner_id, account_id AS google_credential_id, remote_id AS google_calendar_id, summary,
       read_for_busy, write_target, supports_meet
FROM calendar
WHERE account_id IN (SELECT id FROM calendar_account WHERE provider = 'GOOGLE');

-- booking / meeting_type: neutral columns next to the old ones, kept equal by a trigger.
ALTER TABLE booking
    ADD COLUMN event_id            text,
    ADD COLUMN calendar_remote_id  text,
    ADD COLUMN calendar_account_id bigint REFERENCES calendar_account (id) ON DELETE SET NULL;
UPDATE booking
SET event_id = google_event_id, calendar_remote_id = google_calendar_id, calendar_account_id = google_credential_id;

ALTER TABLE meeting_type
    ADD COLUMN override_remote_calendar_id text,
    ADD COLUMN override_account_id         bigint REFERENCES calendar_account (id) ON DELETE SET NULL;
UPDATE meeting_type
SET override_remote_calendar_id = google_calendar_id, override_account_id = google_credential_id;

-- Insert: both = coalesce(new, old). Update: whichever side changed is copied to the other (the new
-- side wins if both did), so an old full-row update that rewrites a stale old value is a no-op.
CREATE FUNCTION booking_calendar_compat() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.event_id := coalesce(NEW.event_id, NEW.google_event_id);
        NEW.google_event_id := NEW.event_id;
        NEW.calendar_remote_id := coalesce(NEW.calendar_remote_id, NEW.google_calendar_id);
        NEW.google_calendar_id := NEW.calendar_remote_id;
        NEW.calendar_account_id := coalesce(NEW.calendar_account_id, NEW.google_credential_id);
        NEW.google_credential_id := NEW.calendar_account_id;
    ELSE
        IF NEW.event_id IS DISTINCT FROM OLD.event_id THEN
            NEW.google_event_id := NEW.event_id;
        ELSIF NEW.google_event_id IS DISTINCT FROM OLD.google_event_id THEN
            NEW.event_id := NEW.google_event_id;
        END IF;
        IF NEW.calendar_remote_id IS DISTINCT FROM OLD.calendar_remote_id THEN
            NEW.google_calendar_id := NEW.calendar_remote_id;
        ELSIF NEW.google_calendar_id IS DISTINCT FROM OLD.google_calendar_id THEN
            NEW.calendar_remote_id := NEW.google_calendar_id;
        END IF;
        IF NEW.calendar_account_id IS DISTINCT FROM OLD.calendar_account_id THEN
            NEW.google_credential_id := NEW.calendar_account_id;
        ELSIF NEW.google_credential_id IS DISTINCT FROM OLD.google_credential_id THEN
            NEW.calendar_account_id := NEW.google_credential_id;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER booking_calendar_compat
    BEFORE INSERT OR UPDATE ON booking
    FOR EACH ROW EXECUTE FUNCTION booking_calendar_compat();

CREATE FUNCTION meeting_type_override_compat() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.override_remote_calendar_id := coalesce(NEW.override_remote_calendar_id, NEW.google_calendar_id);
        NEW.google_calendar_id := NEW.override_remote_calendar_id;
        NEW.override_account_id := coalesce(NEW.override_account_id, NEW.google_credential_id);
        NEW.google_credential_id := NEW.override_account_id;
    ELSE
        IF NEW.override_remote_calendar_id IS DISTINCT FROM OLD.override_remote_calendar_id THEN
            NEW.google_calendar_id := NEW.override_remote_calendar_id;
        ELSIF NEW.google_calendar_id IS DISTINCT FROM OLD.google_calendar_id THEN
            NEW.override_remote_calendar_id := NEW.google_calendar_id;
        END IF;
        IF NEW.override_account_id IS DISTINCT FROM OLD.override_account_id THEN
            NEW.google_credential_id := NEW.override_account_id;
        ELSIF NEW.google_credential_id IS DISTINCT FROM OLD.google_credential_id THEN
            NEW.override_account_id := NEW.google_credential_id;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER meeting_type_override_compat
    BEFORE INSERT OR UPDATE ON meeting_type
    FOR EACH ROW EXECUTE FUNCTION meeting_type_override_compat();
