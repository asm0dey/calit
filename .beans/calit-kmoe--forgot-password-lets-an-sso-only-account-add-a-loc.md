---
# calit-kmoe
title: Forgot-password lets an SSO-only account add a local password
status: draft
type: bug
priority: low
created_at: 2026-10-10T21:11:22Z
updated_at: 2026-10-10T21:11:22Z
---

PasswordResetResource.requestReset (~lines 71-82) mints a reset token for any username with a non-blank ownerEmail, with no passwordHash/googleSub/oidcSub check. An OIDC- or Google-only user can therefore set a local password via /forgot-password, and local login is always on (AppUserIdentityProvider ~65-68).

Product behaviour today, not a policy breach. It matters if calit ever enforces SSO-only (e.g. an IdP disables a user but their local password keeps working). It also covers the related case: an invite token issued before the user linked OIDC stays valid until it expires (48 h).

Found in review of calit-2h0q.

- [ ] Decide: is SSO-only enforcement a goal? If not, scrap.
- [ ] If yes: refuse reset for SSO-linked accounts (or behind a setting), and invalidate open invite tokens on SSO link
