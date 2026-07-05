-- OIDC (e.g. Authelia) single sign-on.
-- oidc_sub: stable id_token "sub" linking this account to the OIDC identity (unique, nullable).
-- oidc_admin: admin granted by an OIDC group; effective admin = is_admin OR oidc_admin.
--             Kept separate from is_admin so a local admin is never demoted by the IdP.
ALTER TABLE app_user ADD COLUMN oidc_sub VARCHAR(255);
ALTER TABLE app_user ADD CONSTRAINT app_user_oidc_sub_key UNIQUE (oidc_sub);
ALTER TABLE app_user ADD COLUMN oidc_admin BOOLEAN NOT NULL DEFAULT FALSE;
