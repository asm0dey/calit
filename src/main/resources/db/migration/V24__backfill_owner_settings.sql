-- Backfill missing owner_settings rows (issue #99). The first admin user, created via /setup,
-- historically got no owner_settings row (SetupResource seeded none — every OTHER creation path
-- did). The public booking path reads OwnerSettings.forOwner(id).timezone unguarded, so such an
-- owner 500s (NPE) on any booking. Insert a placeholder row (same shape the invite/Google paths
-- seed) for every app_user lacking one; the first-login wizard overwrites name/email/timezone.
INSERT INTO owner_settings (owner_id, owner_name, owner_email, timezone, locale, owner_notifications_enabled)
SELECT u.id, '', '', 'UTC', 'en', TRUE
FROM app_user u
WHERE NOT EXISTS (SELECT 1 FROM owner_settings s WHERE s.owner_id = u.id);
