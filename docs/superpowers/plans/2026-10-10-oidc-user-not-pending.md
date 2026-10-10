# SSO-only (OIDC) user is not "Pending" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An account created through OIDC sign-in shows as Active/Locked on `/me/users`, gets no "Resend invite" button, and `POST /me/users/{id}/resend-invite` refuses it, so an admin can no longer mint a password-reset token for an SSO-only account.

**Architecture:** One predicate, `AppUser.isPending()`, replaces the three inline copies of `passwordHash == null && googleSub == null` (status cell and resend button in `users.html`, guard in `UsersResource.resendInvite`). The predicate adds `oidcSub == null`.

**Tech Stack:** Quarkus 3.40 / Java 25, Panache, Qute `@CheckedTemplate`, RestAssured + AssertJ.

**Spec:** bean `calit-2h0q` (`.beans/calit-2h0q--sso-only-oidc-user-shows-as-pending-and-can-be-sen.md`).

## Global Constraints

- Branch from `origin/main` (local `main` is behind and dirty), e.g. worktree `../calit-2h0q`, branch `fix/oidc-user-not-pending`.
- AssertJ only; Hamcrest only inside RestAssured `.body(...)`.
- No new user-facing strings → no i18n changes.
- No schema change → no migration.
- Full `mvn test` green before the PR.
- The changelog bullet goes under `## Unreleased` on `docs-site`.

## Review Focus

1. **OIDC user, admin clicks resend-invite**: no token is minted and the page shows `users_error_not_pending`. This is the security half of the bug. Pinned in Task 2.
2. **OIDC user on the list page**: the row shows Active and has no `/resend-invite` form. Pinned in Task 2.
3. **Locked OIDC user**: shows Locked, not Pending. Covered by the predicate (`isPending` false → falls through to `enabled`). The Task 2 list test sets `enabled = true`, so this case gets no separate test.
4. **Invited user who later links OIDC**: stops being pending. Their earlier invite token stays valid until it expires. Out of scope; state it in the PR.
5. **Self-service "forgot password" for an OIDC-only user**: not touched by this bug, so out of scope. State it in the PR as a possible follow-up bean.

---

### Task 1: `AppUser.isPending()` + unit tests

The work-in-progress diff in the main checkout already contains this. Carry it over: `git -C ../calit diff -- src/main/java/site/asm0dey/calit/user/AppUser.java src/test/java/site/asm0dey/calit/user/AppUserOidcTest.java | git apply`.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/user/AppUser.java` (after `usernameExists`, ~line 133)
- Test: `src/test/java/site/asm0dey/calit/user/AppUserOidcTest.java`

**Interfaces:**
- Produces: `public boolean isPending()`. It returns `passwordHash == null && googleSub == null && oidcSub == null`.

- [ ] **Step 1: Tests** (already in the diff): `isPending_oidcUser_isNotPending` (`createOidcUser("dave","sub-oidc",false)` → false), `isPending_googleUser_isNotPending` (`createGoogleUser` → false), `isPending_passwordUser_isNotPending` → false, and a user with all three null → true.
- [ ] **Step 2: Run** `mvn test -Dtest=AppUserOidcTest` and check it passes. (TDD check: temporarily drop `&& oidcSub == null` and confirm the OIDC test fails.)
- [ ] **Step 3: Commit** `fix(users): AppUser.isPending() counts OIDC-linked accounts`

### Task 2: Use `isPending()` in the users list and resend-invite

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/UsersResource.java:304`. Use `var pending = u.isPending();`.
- Modify: `src/main/resources/templates/UsersResource/users.html:31,43`. Use `{#if u.isPending}` in both places.
- Test: `src/test/java/site/asm0dey/calit/web/UsersResourceTest.java`

**Interfaces:**
- Consumes: `AppUser.isPending()` (Task 1); `AppUser.createOidcUser(String, String, boolean)`.

- [ ] **Step 1: Failing tests** (admin `@TestSecurity`, same style as `resendInviteRejectedForActiveUser`). Persist the OIDC user in `QuarkusTransaction.requiringNew()` with `enabled = true` and an `OwnerSettings` row whose `ownerEmail` is set. Without that email the guard rejects the user for a different reason and the test proves nothing. Copy the setup that `createUserSendsInviteAndStoresEmail` produces.
  - `resendInviteRejectedForOidcUser`: POST `/me/users/{id}/resend-invite` returns 200, and `PasswordResetToken.count("userId", id)` is `isZero()`.
  - `oidcUserListedAsActiveWithoutResendButton`: GET `/me/users` returns 200. `.body(not(containsString("/me/users/" + id + "/resend-invite")))`.
- [ ] **Step 2: Run** `mvn test -Dtest=UsersResourceTest`. The two new tests should FAIL on `origin/main` code.
- [ ] **Step 3: Apply** the resource and template edits (already in the WIP diff; `git apply` those two paths).
- [ ] **Step 4: Run** `mvn test -Dtest=UsersResourceTest+AppUserOidcTest`. Expect all to PASS, including `resendInviteMintsAnotherTokenForPendingUser`.
- [ ] **Step 5: Commit** `fix(users): SSO-only accounts are not pending and get no invite resend`

### Task 3: Verify, document, ship

- [ ] **Step 1:** `mvn test` (whole suite) passes with 0 failures and 0 errors. `mvn spotless:check` is clean.
- [ ] **Step 2:** On `docs-site`, add this under `## Unreleased`: "- Accounts created by OIDC sign-in no longer show as Pending on `/me/users` and cannot be sent an invite, which had let an admin add a password to an SSO-only account. ([#N](https://github.com/asm0dey/calit/pull/N))". If 1.27.1 is cut before this merges, `Unreleased` is recreated. Otherwise the bullet ships in 1.27.1.
- [ ] **Step 3:** Bean `calit-2h0q`: tick both todos, add `## Summary of Changes`, mark it completed, and commit the bean file with the fix.
- [ ] **Step 4:** Open the PR. The body includes a show-me diagram, Review Focus items 4–5 as known limits, and the Claude Code footer.
