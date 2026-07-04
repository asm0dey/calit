---
title: Cloudflare Turnstile setup
description: Add a bot challenge to the public booking form.
---

:::note
Cloudflare Turnstile is **optional** and **off by default** (`TURNSTILE_ENABLED=false`). Booking works fully without it — no Cloudflare account needed in development or testing.
:::

## Steps

### 1. Create a Turnstile site in Cloudflare

1. Log in to the [Cloudflare dashboard](https://dash.cloudflare.com/) and navigate to **Turnstile**.
2. Click **Add site**, choose a widget type (Managed is recommended), and enter the domain calit is served from.
3. Copy the **Site Key** and **Secret Key** shown after creation.

### 2. Set the environment variables

```dotenv
TURNSTILE_ENABLED=true
TURNSTILE_SITE_KEY=your-site-key
TURNSTILE_SECRET_KEY=your-secret-key
```

Setting `TURNSTILE_ENABLED=true` activates both the client-side widget on the booking form and server-side verification of the challenge token — one switch controls both. You can also select Turnstile explicitly with `CAPTCHA_PROVIDER=turnstile`; only one CAPTCHA provider is active per instance (the alternative is self-hosted [ALTCHA](/calit/installation/altcha/)).

### 3. What it protects

Turnstile challenges the **public booking form** on submission. It is layered with two additional abuse controls that are always active:

- A **honeypot field** to catch naive bots.
- A **per-email daily cap** (`PER_EMAIL_DAILY_CAP`, default 10) that limits how many bookings a single email address can submit per day.
