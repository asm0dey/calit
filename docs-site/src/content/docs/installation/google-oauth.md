---
title: Google OAuth setup
description: Connect Google Calendar so bookings create events and Meet links.
---

:::note
Google Calendar sync is **optional**. Leaving the keys blank runs calit in degraded mode — all booking functionality works fully without a Google account.
:::

## Steps

### 1. Create a Google Cloud project, enable the Calendar API, create an OAuth client

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create (or select) a project.
2. Go to **APIs & Services → Library**, search for **Google Calendar API**, and click **Enable**.
3. Navigate to **APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID**.
4. Set the application type to **Web application**.

:::caution
Step 2 is easy to miss. Signing in works without it, but every calendar read then fails with HTTP 403 and `/me/google` shows *"couldn't load — try reload"*.
:::

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

## OAuth verification

Until your OAuth app is verified, Google shows users an **"Google hasn't verified this app"** warning and caps the app at **100 new users** (a per-project lifetime cap that cannot be reset). To remove the warning and lift the cap, complete verification in the Google Cloud Console:

1. Set `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL` (see [configuration](/calit/installation/configuration/#public-site--legal-pages-optional)). calit then serves a complete privacy policy at `${APP_BASE_URL}/privacy` and terms at `${APP_BASE_URL}/terms`, including the required Google **Limited Use** disclosure.
2. On the **OAuth consent screen**, set the privacy-policy link to `${APP_BASE_URL}/privacy` (and, optionally, terms to `${APP_BASE_URL}/terms`).
3. Verify domain ownership — either set `GOOGLE_SITE_VERIFICATION` to the token from Google Search Console (calit renders the `<meta>` tag on every page), or add the DNS TXT record Google offers.
4. Submit for verification.

:::note
calit only requests **sensitive** Calendar scopes, not **restricted** scopes, so verification does **not** require a third-party security assessment (CASA).
:::

## Disconnect detection

A Google connection can break without warning — access is revoked, the account password is changed, or the refresh token simply expires. calit detects this and **fails closed**: while an owner's Google account is disconnected, their public booking page shows *"Scheduling temporarily unavailable"* instead of offering every slot as free. This prevents bookings landing on top of calendar events calit can no longer see.

Each connected account is probed on a schedule (every `GOOGLE_PROBE_INTERVAL`, default `1h`) for a still-valid connection; the probe also keeps the refresh token warm. When a disconnect is found, the owner is emailed once per outage with a link to reconnect on the `/me/google` settings page.

:::tip[Avoid recurring disconnects]
The most common cause of repeated disconnects is leaving your Google OAuth app in **"Testing"** publishing status. Google expires refresh tokens for testing apps after **7 days**, so calit loses access roughly once a week no matter what.

In the Google Cloud Console, open **APIs & Services → OAuth consent screen** and publish the app to **"In production"**. Production refresh tokens do not expire on a fixed schedule, so the connection stays alive.
:::

## Troubleshooting

### Read the logs first

calit logs every Google failure. With Docker Compose:

```bash
docker compose logs -f app | grep -i google
```

At boot you get one line confirming what the app actually loaded (no secrets — only whether the client secret is set):

```
Google OAuth configured: clientId=1234-abc.apps.googleusercontent.com clientSecret=set
  redirectUri=https://book.example.com/api/google/callback
  loginRedirectUri=https://book.example.com/api/google/login/callback
  scope=https://www.googleapis.com/auth/calendar openid email
```

Compare `redirectUri` and `loginRedirectUri` against the **Authorized redirect URIs** in your OAuth client — they must match character for character. A wrong `APP_BASE_URL` (http vs https, trailing slash, wrong host) shows up here.

Connecting an account logs:

```
Google account connected for owner 1 (credential 3), refreshToken=stored
```

`refreshToken=MISSING` means Google returned no offline refresh token, so every later refresh will fail — disconnect the account on `/me/google` and connect again.

For extra detail (including transient probe failures), raise the log level:

```dotenv
QUARKUS_LOG_CATEGORY__SITE_ASM0DEY_CALIT_GOOGLE__LEVEL=DEBUG
```

### "Couldn't reach Google for one or more accounts"

The account is connected but calit could not list its calendars. The log line naming the failure looks like:

```
WARN  Google calendar list failed for owner 1, credential 3
      java.io.UncheckedIOException: calendarList.list failed: HTTP 403 —
      Google Calendar API has not been used in project 123... before or it is disabled
```

Common causes, in the order they turn up:

| What the log says | Cause | Fix |
| --- | --- | --- |
| `HTTP 403 … has not been used in project … or it is disabled` | The Calendar API was never enabled | Enable **Google Calendar API** (step 1 above), then reload `/me/google` |
| `HTTP 403 … insufficient authentication scopes` | The account was connected before the calendar scope was granted | Disconnect and reconnect the account |
| `error=invalid_client` | Wrong `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` | Re-copy both from the Credentials page |
| Google shows `redirect_uri_mismatch` in the browser before returning | Registered URI ≠ the `redirectUri` in the boot log | Register the exact URIs from the boot log |
| `invalid_grant` | The refresh token is dead (revoked, password change, or a "Testing" app older than 7 days) | Reconnect the account; publish the OAuth app to production |
| `I/O error` / connect timeout | The container cannot reach `oauth2.googleapis.com` / `www.googleapis.com` | Check egress firewall and DNS from inside the container |
