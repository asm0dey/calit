# Invite email for admin-created users — design

**Date:** 2026-07-04
**Branch context:** feat/altcha-captcha (implement on its own branch)
**Status:** approved-pending-review

## Problem

When a site admin creates a user at `/me/users`, they type a **temp password** and
must share it out-of-band (chat, verbally). The new user has no way to learn they were
invited and no self-service activation. We want an **invite email** with an activation
link instead:

> [inviter] invited you to join calit at [host]. Set your password to activate your
> account: [link]. Didn't expect this? Ignore this email.

## Decisions (locked)

- **Model:** activation link, **no temp password**. The invitee sets their own password.
- **Email storage:** reuse the existing `OwnerSettings.ownerEmail` (already `nullable=false`,
  already filled by the setup wizard). No new column, no Flyway migration.
- **Token:** reuse `PasswordResetService` / `PasswordResetToken`. Activation == set-a-password
  -via-single-use-token, identical to reset. Reuse the reset-password landing page as-is.
- **TTL:** invite links live **48h** (reset stays 30 min). Add an `issue(userId, now, ttl)`
  overload.
- **Resend:** admin gets a **Resend invite** button per pending user
  (`POST /me/users/{id}/resend-invite`), re-mints a token and re-emails.

## Definitions

- **Pending (awaiting activation):** `passwordHash == null && googleSub == null`. Such an
  account cannot log in (null-hash guard in `AppUserIdentityProvider`) and has not linked
  Google. This is the dormant state between invite and activation. No new DB flag — the null
  password *is* the state.
  - `// ponytail: null password IS the pending state; add an explicit flag only if the admin
    list needs to distinguish "invited-never-activated" from "google-pending".`

## Flow

### Create (`UsersResource.create`)
Form fields become **username + email** (temp-password input removed).

1. Validate username via existing `Usernames.validateNew(...)`.
2. Validate email: non-blank and contains `@` (cheap sanity check; the invite bounces if wrong).
   On failure re-render the form with an error message.
3. In one `requiringNew` tx:
   - `AppUser.create(username, null, false)` → `passwordHash = null`,
     `mustChangePassword = false`, `settingsComplete = false`, `enabled = true`; persist.
   - Create the `OwnerSettings` row now with `ownerEmail = entered email`
     (stores the address + pre-fills the setup wizard). Follow the existing
     `MeSetupResource` find-or-create shape.
   - `token = passwordResetService.issue(user.id, now, Duration.ofHours(48))`
   - `emailService.sendInvite(email, activationUrl(token), inviterEmail, host, expiresAt, locale)`
   - `audit.event(admin, "invite-user", USER_TARGET + username, null)`
4. Re-render user list.

`activationUrl` = `APP_BASE_URL` + `/reset-password?token=` + token (same URL the reset flow
already builds — factor the shared bit if convenient, otherwise inline).

`inviterEmail` = current admin's `OwnerSettings.ownerEmail`, falling back to the admin username
if the admin somehow has no settings row.

### Activate (unchanged)
Invitee clicks the link → existing `GET /reset-password` page → sets password
→ `POST /reset-password` consumes the token, sets the hash → redirect to login → first login
runs the setup wizard (`settingsComplete == false`).

### Resend (`UsersResource.resendInvite`)
`POST /me/users/{id}/resend-invite`:
- Load user; guard it is **pending** (null password && null googleSub) — else 400/redirect
  with a message (don't resend to an already-active account).
- Re-mint token (48h) + `sendInvite(...)` to the stored `ownerEmail`.
- Audit `"resend-invite"`.
- Button rendered in the user-list **actions** column only for pending rows; status column
  shows "Awaiting activation".

## New code

| Piece | Where |
|---|---|
| `issue(userId, now, ttl)` overload | `PasswordResetService` (keep 30-min no-ttl overload delegating to it) |
| `sendInvite(toEmail, activationUrl, inviterEmail, host, expiresAt, locale)` | `EmailService` |
| `invite.html` email template + subject | `templates/email/` (follow `sendPasswordReset` template shape) |
| Email `@Message` keys `email_invite_subject`, `email_invite_*` | `AppMessages` + **de + he** in `messages/msg_{de,he}.properties` |
| Form: email input replaces tempPassword; new label + validation `@Message` keys | `templates/UsersResource/users.html` + `AdminMessages` + **de + he** in `adm_{de,he}.properties` |
| Status label "Awaiting activation" + Resend button | `users.html` + `AdminMessages` (de + he) |
| `create` rewrite + `resendInvite` endpoint | `UsersResource` |

`// ponytail:` skipped — invite-specific landing page copy (reuse reset page), separate
AppUser.email column (reuse ownerEmail), any "invited" DB flag (null password is the state).
Add each only when it demonstrably bites.

## i18n

Every new string ships English default + **de + he** in the same change (CLAUDE.md mandate):
- `AppMessages`: `email_invite_subject`, invite body lines. Keep `{inviter}` `{host}` `{link}`
  placeholders identical across locales.
- `AdminMessages`: `users_label_email`, `users_error_email_invalid`, `users_status_pending`,
  `users_btn_resend_invite`.

## Edge cases

- Email blank/invalid → re-render form with `users_error_email_invalid`, no user created.
- Mailer is mocked in `%dev`/`%test` → invite send is a no-op there; tests assert token minted
  + `ownerEmail` set + user pending, not real delivery.
- Resend on a non-pending user → rejected (guard), avoids re-inviting active accounts.
- CSRF: create + resend forms carry `{inject:csrf.token}` (prod gate). Create form already does.
- Duplicate username → existing `validateNew` rejects before any email/token work.

## Tests (`UsersResourceTest` or new)

1. `create` with username+email → user exists, `passwordHash == null`,
   `OwnerSettings.ownerEmail == entered`, exactly one `PasswordResetToken` for the user.
2. Consume that token via the reset flow → password set → user can authenticate.
3. `resend-invite` on the pending user → a fresh token minted (old one may coexist/expire).
4. `resend-invite` on an active user → rejected, no new token.
5. Email validation: blank / no-`@` → form error, no user, no token.

## Docs (docs-site branch — part of done)

Update the "managing users" / admin section: creating a user now sends an invite email;
document the 48h activation window and the resend button. No new env var (uses existing
`APP_BASE_URL` + `MAIL_*`).
