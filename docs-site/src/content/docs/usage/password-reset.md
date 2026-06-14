---
title: Resetting a password
---

Users who forget their password can reset it themselves from the sign-in page — no admin action required.

## Requesting a reset

On the sign-in page (`/login`), click **Forgot password?**. Enter your **username** (the name you sign in with — not your email address) and submit.

![Forgot-password request form](/calit/img/forgot-password.png)

calit emails a reset link to the address stored in your account settings. The confirmation is always the same whether or not the username exists — calit never reveals which accounts are registered.

The link is valid for **30 minutes** and can be used **once**.

## Setting a new password

Open the link from the email. Enter a new password and submit.

![Set a new password](/calit/img/reset-password.png)

You are returned to the sign-in page; log in with the new password. The old one no longer works. If the link has expired or was already used, request a fresh one from **Forgot password?**.

## Notes

- The reset email is sent to the **owner email** in your settings, so that address must be set and your [mailer](/calit/installation/configuration/) must be configured. Without working SMTP, no reset mail is delivered.
- Accounts that sign in **only with Google** can set a password this way too; afterwards either method works.
- Only a hashed token is stored server-side — the raw link exists only in the email.
