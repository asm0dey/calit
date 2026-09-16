-- GDPR epic, Task 7 fix round (R16): close an account-takeover window through username reuse.
-- Quarkus form-auth's persistent-login cookie carries only "expiry:username" (no user id) and
-- FormAuthenticationMechanism re-saves (renews) it on every restored request; EnabledUserAugmentor
-- downgrades a request whose username no longer resolves to an AppUser to anonymous, but does NOT
-- invalidate that cookie. So a deleted user's stale cookie keeps renewing indefinitely in the
-- browser, and if the SAME username is ever re-registered (signup, admin invite, first-run setup,
-- or OIDC/Google auto-provisioning), the stale cookie silently authenticates as the NEW account.
--
-- Tombstoning every deleted username forever closes this: no account may ever reuse a name that
-- once belonged to a deleted account. Only the SHA-256 hash is stored, not the username itself —
-- this table exists purely to answer "was this name ever used and deleted", not to record who.
CREATE TABLE deleted_username (
    username_sha256 TEXT        PRIMARY KEY,
    deleted_at      TIMESTAMPTZ NOT NULL
);
