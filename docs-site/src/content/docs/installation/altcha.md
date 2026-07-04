---
title: ALTCHA setup
description: Add a self-hosted, privacy-first bot challenge to the public booking form.
---

:::note
[ALTCHA](https://altcha.org/) is a **self-hosted**, privacy-first CAPTCHA alternative — a proof-of-work challenge that runs entirely on your own instance with **no external service, no third-party account, and no tracking**. It is **optional** and **off by default**. Booking works fully without it.
:::

ALTCHA is the choice when you want bot protection but don't want to depend on (or send visitors to) a third party like Cloudflare. The browser solves a small computational puzzle on submit; your server issues and verifies it using a secret you control.

## Steps

### 1. Generate an HMAC key

ALTCHA challenges are signed with a secret that never leaves your server. Generate a strong random one:

```bash
openssl rand -hex 32
```

### 2. Set the environment variables

```dotenv
CAPTCHA_PROVIDER=altcha
ALTCHA_HMAC_KEY=your-generated-secret
# Optional — proof-of-work difficulty (max number the browser brute-forces). Higher = harder for
# bots but slightly slower for legitimate visitors. Default is fine for most instances.
ALTCHA_MAX_NUMBER=100000
```

`CAPTCHA_PROVIDER=altcha` activates both the client-side widget on the booking form and server-side verification. calit **fails fast at startup** if `CAPTCHA_PROVIDER=altcha` is set without `ALTCHA_HMAC_KEY`, so a misconfiguration can never silently accept forged solutions.

:::caution
Exactly **one** CAPTCHA provider is active per instance. `CAPTCHA_PROVIDER` selects it: `none` (default), `turnstile`, or `altcha`. For backward compatibility, if `CAPTCHA_PROVIDER` is unset but `TURNSTILE_ENABLED=true`, Turnstile is used.
:::

### 3. Reverse proxy

The widget fetches its challenge from `GET /altcha/challenge` on your instance (public, unauthenticated, cheap). No special proxy configuration is needed beyond forwarding normal traffic — if you already proxy the app, the endpoint works. The widget script is served by calit itself (no CDN), so it also works on fully air-gapped deployments.

### 4. What it protects

ALTCHA challenges the **public booking form** on submission. It is layered with two abuse controls that are always active:

- A **honeypot field** to catch naive bots.
- A **per-email daily cap** (`PER_EMAIL_DAILY_CAP`, default 10) limiting how many bookings a single email address can submit per day.

:::note
ALTCHA verification is stateless (the server checks the signature and expiry, with no consumed-solution store), so a solved challenge can be replayed until it expires (5 minutes). The honeypot and per-email cap are the backstops for that window.
:::

## Localisation

The widget's own strings ("I'm not a robot", "Verifying…") are localised automatically from the page language — English, German, and Hebrew (right-to-left) are all covered, matching calit's own UI languages. No extra configuration.

## Notes

- Enabling any CAPTCHA provider makes the booking form require JavaScript (the proof-of-work runs in the browser). With `CAPTCHA_PROVIDER=none` (default) the form works without JavaScript.
- The widget JavaScript is bundled with calit and served from your instance — nothing is fetched from a CDN at runtime.
