---
# calit-20wc
title: 'Task 7: Account deletion — self-serve and admin'
status: completed
type: task
priority: normal
created_at: 2026-09-16T15:30:24Z
updated_at: 2026-09-16T16:56:27Z
parent: calit-l3fk
---

PrivacyService.deleteAccount/isLastEnabledAdmin; /me/settings/delete self-serve route + template; /me/users/{id}/delete admin route; i18n keys; AccountDeletionTest


## Summary of Changes

- `PrivacyService.isLastEnabledAdmin(Long)` + `PrivacyService.deleteAccount(Long)` (@Transactional): purges email_outbox by owner, deletes the app_user row, everything else cascades. Throws IllegalStateException("last-admin") for the last enabled admin.
- Self-serve: `GET/POST /me/settings/delete` in AdminResource — re-authenticates (password verify, or username retype for passwordless accounts), on success redirects through `/logout` so the credential cookie is cleared. New template `AdminResource/deleteAccount.html`; link added to `settings.html`.
- Admin: `POST /me/users/{id}/delete` in UsersResource — same last-admin guard, audited. An admin deleting THEIR OWN account is redirected through `/logout` (same as self-serve) instead of re-rendering the users list, since the session's backing row is gone. Deleting another admin/user re-renders the list. Button added to `users.html` (separate per-row `<form>`, matching the existing lock/unlock/resend-invite pattern — not the `formaction` pattern from settings.html's channel table).
- i18n: 12 new AdminMessages keys (`adm_delete_account_*`, `adm_users_delete`, `adm_users_error_last_admin_delete`) with German translations; all added to `HE_DEFERRED_ADMIN_KEYS` per R10 (Hebrew deferred for this epic).
- Verified: every FK from a table without `owner_id` (password_reset_token.user_id, login_ticket.user_id, meeting_type_host.owner_id which is actually the co-host's own user id) is `ON DELETE CASCADE` to app_user — confirmed by reading migrations V11/V13/V20 and by two new tests.
- Tests: `AccountDeletionTest` (service-level, brief's 3 tests + 2 added: user_id-keyed tables, and a co-host deletion that must not fail or touch the other owner's meeting type) — 5/5 passing. `AccountDeletionRoutesTest` (new, HTTP-level: confirm-page field variants, wrong-confirmation rejection, last-admin guards on both routes, self-delete-through-logout on both routes, admin-deletes-other) — 9/9 passing. Full suite: 1190/1190, BUILD SUCCESS.


## Fix Round 1 (commit c1f02a8)

- Finding 1: blocked one-click no-reauth self-delete via /me/users/{id}/delete (mirrors lock's self-guard); Delete button hidden on own row.
- Finding 2: tombstone deleted usernames forever (V35, DeletedUsername entity, AppUser.usernameUnavailable wired into every username-choosing path) to close a persistent-login-cookie account-takeover window. New EraseRoute.RETAINED_INDEFINITELY flagged for explicit review — no existing route honestly fit.
- R17: pessimistic-locked last-admin race check, 404 for unknown id on admin delete, google_note copy fix.
- Also fixed: DatabaseResetCallback wasn't truncating the new (FK-less-by-design) deleted_username table, which would have poisoned later tests reusing a username.
- Full suite: 1191/1191 green.
