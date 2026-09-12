---
title: Notification channels
description: Route booking events to Telegram, Slack, Discord, ntfy, Gotify, or any webhook, alongside email.
---

Each owner can register their own outbound notification channels — one or more URLs that every
booking event (new booking, cancellation, reschedule, approval request, reminder, and the rest) is
sent to. Channels are independent of the owner-email setting: email goes out separately, and
channels deliver whether or not `Send me (the owner) email notifications for bookings` is checked.
That setting only governs your own email; a registered channel always fires.

## Adding a channel

From `/me/settings`, under **Notification channels**:

1. Paste a channel URL into the empty row (see [Getting a channel URL](#getting-a-channel-url) below
   for each provider).
2. Give it a **Label** — a short name for your own reference. Leave it blank and calit fills in the
   channel's display name (e.g. "Telegram").
3. Press **Send test** on that row to confirm delivery. This works *before* you save, so you can
   correct a wrong URL without storing it first.
4. Leave **Default** ticked to notify this channel for every meeting type, or untick it to keep the
   channel registered but silent until a meeting type names it explicitly.
5. Press **Save channels**.

:::note
The **Send test** button uses a short timeout (about 4 seconds) and a single attempt, so you get an answer while you wait. Real booking deliveries are more patient — they retry up to `NOTIFY_MAX_ATTEMPTS` times. A slow self-hosted target can therefore fail the test button and still deliver bookings fine.
:::

**Default** is the enable switch for the inherit set. Unticking it does not delete the channel — the
URL stays registered and testable, and any meeting type that names the channel explicitly still
delivers to it. Deleting the row is how you remove a channel entirely.

A new channel is created with **Default** on. The checkbox needs a saved row to carry, so untick it
on the next save if you want the channel silent by default.

## Getting a channel URL

Every channel is an Apprise-style URL — the same shape Apprise popularised, implemented here by [notify4j](https://www.alexmond.org/notify4j/current/), whose [Channel URLs reference](https://www.alexmond.org/notify4j/current/configuration/#_channel_urls) is the authority on the exact syntax calit accepts. Here is how to obtain one
per provider:

### Telegram

1. Message [@BotFather](https://t.me/BotFather) on Telegram, send `/newbot`, and follow the prompts.
   BotFather gives you a **bot token** (looks like `123456:AAbbCCddEE...`).
2. Get the **chat id** to send to — message your new bot (or add it to a group), then call
   `https://api.telegram.org/bot<token>/getUpdates` and read the `chat.id` field from the response.
3. The channel URL is:

   ```
   telegram://<bot-token>/<chat-id>
   ```

### Slack

1. Create an [incoming webhook](https://api.slack.com/messaging/webhooks) for the channel you want
   notifications posted to.
2. Slack gives you a webhook URL; the channel URL uses the same path components after the
   `slack://` scheme; the exact token layout is in [Slack's incoming-webhook docs](https://api.slack.com/messaging/webhooks).

### Discord

1. In the target Discord channel's settings, add a **webhook** (Server Settings → Integrations →
   Webhooks → New Webhook).
2. Discord gives you a webhook URL; use it in `discord://` form with the webhook id and token.

### ntfy

1. Pick a topic name (a private, hard-to-guess string works as the access control) on
   [ntfy.sh](https://ntfy.sh) or your own self-hosted ntfy server.
2. The channel URL is:

   ```
   ntfy://<topic>
   ```

   For a self-hosted server, use the `+http` or `+https` transport suffix to point at your own host:

   ```
   ntfy+http://<host>:<port>/<topic>
   ```

### Gotify

1. In your Gotify server's web UI, create an **application** — Gotify gives you an app token.
2. The channel URL points at your Gotify server with that token.

### Every supported channel

Two references, for two different questions. For **the URL syntax calit accepts**, use notify4j's own
[Channel URLs reference](https://www.alexmond.org/notify4j/current/configuration/#_channel_urls) — calit
implements notify4j's catalog, which follows Apprise conventions without being identical to it, so
Apprise's reference can describe field layouts calit rejects. For **how to obtain the token or webhook
in the first place**, use the provider's own docs below. The **?** icon beside the **Notification
channels** heading on `/me/settings` opens this page.

| Scheme | Channel | Where the URL format is documented |
|---|---|---|
| `telegram://` | Telegram | [Telegram bots](https://core.telegram.org/bots) |
| `slack://` | Slack | [Slack incoming webhooks](https://api.slack.com/messaging/webhooks) |
| `discord://` | Discord | [Discord webhooks](https://support.discord.com/hc/en-us/articles/228383668) |
| `ntfy://`, `ntfy+http://` | ntfy | [ntfy docs](https://docs.ntfy.sh/) |
| `gotify://` | Gotify | [Gotify push](https://gotify.net/docs/pushmsg) |
| `teams://` | Microsoft Teams | [Teams incoming webhooks](https://learn.microsoft.com/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook) |
| `googlechat://` | Google Chat | [Google Chat webhooks](https://developers.google.com/chat/how-tos/webhooks) |
| `mattermost://` | Mattermost | [Mattermost incoming webhooks](https://developers.mattermost.com/integrate/webhooks/incoming/) |
| `rocketchat://` | Rocket.Chat | [Rocket.Chat integrations](https://docs.rocket.chat/docs/integrations) |
| `matrix://` | Matrix | [Matrix docs](https://matrix.org/docs/) |
| `mastodon://` | Mastodon | [Mastodon tokens](https://docs.joinmastodon.org/client/token/) |
| `bluesky://` | Bluesky | [AT Protocol](https://atproto.com) |
| `signal://` | Signal | [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api) |
| `pushover://` | Pushover | [Pushover API](https://pushover.net/api) |
| `pushbullet://` | Pushbullet | [Pushbullet API](https://docs.pushbullet.com/) |
| `zulip://` | Zulip | [Zulip send-message](https://zulip.com/api/send-message) |
| `pagerduty://` | PagerDuty | [Events API v2](https://developer.pagerduty.com/docs/events-api-v2/overview/) |
| `opsgenie://` | Opsgenie | [Opsgenie API integration](https://support.atlassian.com/opsgenie/docs/api-integration/) |
| `twilio://` | Twilio SMS | [Twilio SMS](https://www.twilio.com/docs/sms) |
| `whatsapp://` | WhatsApp | [WhatsApp Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api) |
| `webhook://` | Generic webhook | Posts JSON to any URL you control — see the caveat below |

An operator can restrict which of these are accepted with `NOTIFY_ALLOWED_SCHEMES`.

### Generic webhook

Any HTTP endpoint that accepts a JSON POST can be used directly as a `webhook://` or `webhook+http://`
URL, with no provider-specific setup. This is the fallback for anything not natively supported.

:::note[Generic webhooks don't carry a title]
The generic webhook payload is `{"id", "status", "message"}` — there is no title field, and severity
always renders as the string `"ALERT"`. All the booking content is in `message`. Telegram, Slack,
Discord, ntfy, and Gotify all render both a title and a body.
:::

## Per-meeting-type routing

By default a meeting type notifies every channel the host has marked **Default**. On a meeting type's
page, under **Notifications**, a host can instead pick a **custom** subset for that specific type —
and that subset may include channels that are *not* Default, which is how a channel is scoped to one
meeting type without touching any other. Non-default channels are shown there with a
**not by default** badge.

Naming a channel on a meeting type always wins over its **Default** setting: the explicit pick is
the opt-in.

This override is **per host**: on a shared (multi-host) meeting type, each co-host sets their own
routing independently from their own view of the type. A co-host narrowing their own notifications
down to one channel has no effect on what the creator, or any other co-host, receives.

## What the UI can and can't tell you about a failed delivery

Each channel row shows the timestamp of its last successful and last failed delivery, so you can see
**that** a delivery failed and **when**. It cannot show **why**: notify4j reports only a pass/fail
count for a delivery attempt, with no HTTP status code or exception detail, so there is nothing more
specific calit could honestly display. If a channel is failing, double check the URL with
**Send test**, and confirm the destination (bot, webhook, topic) still exists and accepts the token.

## Channel URLs are never shown again in full

Once saved, a channel's URL is never rendered back to you in the clear — the settings page shows a
**redacted** form instead, e.g. `telegram://…` or `ntfy+http://host:port/…`, with the secret portion
replaced. Leaving that redacted value in place and pressing **Save channels** keeps the stored URL
unchanged; pasting a redacted value into a *different* (empty) row is rejected rather than silently
stored as a dead channel.

One consequence: **two channels of the same scheme render an identical mask.** If you have two
Telegram channels, both show as `telegram://…` in the list — the **Label** is the only thing that
tells them apart, so label your channels meaningfully.

## Operator note: `NOTIFY_MAX_ATTEMPTS` and worker pressure

Delivery retries run synchronously on a background thread and block that worker until they either
succeed or exhaust their attempts. At the default of 3 attempts, an unreachable channel occupies a
worker for up to roughly 33 seconds (three 10-second request timeouts, plus 1 s and 2 s of backoff
between attempts). On an instance with many registered channels and a large reminder burst, lowering
`NOTIFY_MAX_ATTEMPTS` reduces how long a single bad channel can hold a worker thread.

See [Configuration](/calit/installation/configuration/#outbound-notification-channels-optional) for the full
list of `NOTIFY_*` environment variables.
