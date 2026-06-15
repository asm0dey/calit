---
title: Google OAuth setup
description: Connect Google Calendar so bookings create events and Meet links.
---

:::note
Google Calendar sync is **optional**. Leaving the keys blank runs calit in degraded mode — all booking functionality works fully without a Google account.
:::

## Steps

### 1. Create a Google Cloud project and OAuth client

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create (or select) a project.
2. Navigate to **APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID**.
3. Set the application type to **Web application**.

### 2. Register the redirect URIs

Add **both** of the following as **Authorized redirect URIs** in your OAuth client. Replace `https://book.example.com` with your actual `APP_BASE_URL`:

```
${APP_BASE_URL}/api/google/callback
${APP_BASE_URL}/api/google/login/callback
```

Both URIs must be registered — one is used for the per-user Calendar connection flow, the other for Google sign-in.

:::tip
If you set the optional override vars `GOOGLE_OAUTH_REDIRECT_URI` or `GOOGLE_OAUTH_LOGIN_REDIRECT_URI` (for unusual reverse-proxy paths), register whatever values you set instead.
:::

### 3. Set the environment variables

Copy the **Client ID** and **Client Secret** from the Credentials page, then set:

```dotenv
GOOGLE_OAUTH_CLIENT_ID=your-client-id
GOOGLE_OAUTH_CLIENT_SECRET=your-client-secret
# Strong random string shared by ALL replicas
GOOGLE_OAUTH_STATE_SECRET=<openssl rand -hex 32>
```

`GOOGLE_OAUTH_STATE_SECRET` must be the same value on every replica. Generate it with:

```bash
openssl rand -hex 32
```

### 4. Secure tokens at rest

`TOKEN_ENCRYPTION_KEY` encrypts stored Google OAuth tokens with AES-256-GCM. See the [Configuration reference](/calit/installation/configuration/) for details.

### 5. Connect accounts and use

Each user connects their **own** Google account from the owner console (`/me`). Once connected, every new booking automatically:

- Creates a Google Calendar event on the user's calendar.
- Generates a Google Meet link included in the booking confirmation.

## Disconnect detection

A Google connection can break without warning — access is revoked, the account password is changed, or the refresh token simply expires. calit detects this and **fails closed**: while an owner's Google account is disconnected, their public booking page shows *"Scheduling temporarily unavailable"* instead of offering every slot as free. This prevents bookings landing on top of calendar events calit can no longer see.

Each connected account is probed on a schedule (every `GOOGLE_PROBE_INTERVAL`, default `1h`) for a still-valid connection; the probe also keeps the refresh token warm. When a disconnect is found, the owner is emailed once per outage with a link to reconnect on the `/me/google` settings page.

:::tip[Avoid recurring disconnects]
The most common cause of repeated disconnects is leaving your Google OAuth app in **"Testing"** publishing status. Google expires refresh tokens for testing apps after **7 days**, so calit loses access roughly once a week no matter what.

In the Google Cloud Console, open **APIs & Services → OAuth consent screen** and publish the app to **"In production"**. Production refresh tokens do not expire on a fixed schedule, so the connection stays alive.
:::
