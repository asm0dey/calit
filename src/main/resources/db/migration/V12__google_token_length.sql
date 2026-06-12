-- SEC-SECRET-02: Google OAuth tokens are now stored AES-256-GCM-encrypted (base64 + marker),
-- which is longer than the raw token. Columns are already TEXT (unbounded), so this migration
-- is a documented no-op guard that fails loudly if a future change narrows them.
DO $$
BEGIN
    IF (SELECT data_type FROM information_schema.columns
        WHERE table_name = 'google_credential' AND column_name = 'refresh_token') <> 'text' THEN
        RAISE EXCEPTION 'refresh_token must remain TEXT to hold encrypted tokens';
    END IF;
    IF (SELECT data_type FROM information_schema.columns
        WHERE table_name = 'google_credential' AND column_name = 'access_token') <> 'text' THEN
        RAISE EXCEPTION 'access_token must remain TEXT to hold encrypted tokens';
    END IF;
END $$;
