---
# calit-2727
title: Sign-up accepts a blank or whitespace password
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:51:40Z
updated_at: 2026-09-30T09:14:33Z
---

Spec: UC-006 A3. SignupResource.register (web/SignupResource.java:69-81) validates only the username; PasswordHasher.hash takes any string. Blank/whitespace passwords are stored; a missing field NPEs at PasswordHasher.java:81 (500). Only the browser's required attribute guards it.

- [x] Reject null/blank password in register, re-render with auth_signup_error
- [x] Test: blank, whitespace and missing password

## Summary of Changes

SignupResource.register rejects null/blank passwords and re-renders with a new `auth_signup_password_blank` message (de/he). Test in SignupEnabledTest.
