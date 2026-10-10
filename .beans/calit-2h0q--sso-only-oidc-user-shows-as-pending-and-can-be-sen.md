---
# calit-2h0q
title: SSO-only (OIDC) user shows as Pending and can be sent an invite
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:52:11Z
updated_at: 2026-10-10T21:13:28Z
---

createOidcUser (AppUser.java:97-109) leaves passwordHash and googleSub null. Pending check ignores oidcSub in users.html:31 (status), users.html:43 (resend button), UsersResource.resendInvite:304. Resend issues a password-reset token, letting an admin add a password to an SSO-only account. Google-only users are fine.

- [x] AppUser.isPending() incl. oidcSub; use in template and resendInvite
- [x] Test

## Summary of Changes

AppUser.isPending() is true only with no passwordHash, googleSub or oidcSub. /me/users (status cell, resend button) and UsersResource.resendInvite use it, so OIDC-only accounts show Active/Locked and get no invite resend.

Correction: the resend token went to the account owner's own ownerEmail, not the admin. The effect was a wrong Pending label plus an unsolicited invite email that let the owner set a local password.

Tests: AppUserOidcTest isPending_* (4), UsersResourceTest resendInviteRejectedForOidcUser, oidcUserListedWithoutResendInviteButton (button + label).

Follow-up: calit-kmoe (forgot-password on SSO-only accounts).
