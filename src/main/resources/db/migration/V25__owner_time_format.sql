-- Issue #116 follow-up: the host's own 12h/24h preference for /me pages and their own emails.
-- Values are Intl's vocabulary so the client needs no mapping table: auto | h12 | h23.
-- 'auto' means "the viewer's device decides" on /me, and "leave the translated pattern alone"
-- in email (a server has no device to read), so this default is a no-op for existing rows.
ALTER TABLE owner_settings ADD COLUMN time_format varchar(8) NOT NULL DEFAULT 'auto';
