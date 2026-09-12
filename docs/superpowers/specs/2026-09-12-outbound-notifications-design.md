# Outbound notification channels

Status: approved design, not yet implemented
Issue: [#194](https://github.com/asm0dey/calit/issues/194)
Bean: `calit-6rzr`
Precedent: `#notify4j-core-for-multi-channel-booking-notifica-1789206712`

## Problem

Booking notifications are email-only. A host learns of a booking over SMTP —
calit's own mail, or Google Calendar's once a calendar is connected. That is
fine for the invitee and the slowest possible channel for the host. Issue #194
asks for a booking to reach the host over Telegram.

## Decision

Deliver host notifications through `org.alexmond:notify4j-core`, with per-owner
channel URLs stored in the database and routed per meeting type.

The issue proposed a single config-level outbound webhook
(`calit.webhook.url=…`). That shape is rejected: calit is multi-tenant, so a
config-level destination serves only the operator and leaves every other owner
without notifications. It would also require each host to run a companion
container to reach Telegram.

notify4j resolves the channel from a URL at runtime, so one 111KB artifact
(single compile dependency `slf4j-api`, Apache-2.0) covers Telegram, Slack,
Discord, Teams, Google Chat, Mattermost, Rocket.Chat, Matrix, Gotify, ntfy,
Pushover, PagerDuty, Twilio SMS and a generic webhook, and each owner chooses
their own.

Alternatives considered and why they lost:

- **NotifyHub** — abandoned. Last push 2026-03-23, zero commits in 90 days, 15
  stale issues.
- **camel-quarkus** — institutional maintenance and proven native support, but
  its entire chat roster is Slack and Telegram. No Discord, Teams or Google Chat
  component exists, so it would mean hand-rolling the channels we most want
  while paying for a routing engine for the rest.
- **Hand-rolled** — genuinely competitive, since each channel is one HTTP POST
  and these APIs barely drift. Rejected to avoid owning channel API drift.

notify4j is young (created 2026-06-16, 2 stars), so the maintenance it
outsources is thin. Accepted because the exit is cheap: Apache-2.0 and 111KB, so
vendoring the notifiers we use is the fallback.

**No calit-specific webhook payload.** notify4j's `WebhookNotifier` emits a fixed
`{"id":…,"status":…,"message":…}`, and `jsonValue` passes `Number` through raw,
so a `webhook://` consumer receives
`{"id":42,"status":"BOOKING_REQUESTED","message":"…"}`. Shipping our own richer
body was considered and dropped: it would be a public contract we must version
forever, it splits the code path, and #194's author wanted Telegram — the
webhook was their means to it. A `calit-webhook://` channel remains additive
later.

## Sequencing

The native-image spike runs **first**, before any schema, entity or UI work. The
precedent entry records adoption as conditional on it.

## 1. Native-image spike (gate)

Throwaway. Output is an answer, not code we keep.

Add `notify4j-core`, wire one trivial path calling `sendOnce` against a local
HTTP server, build `Dockerfile.native`, run the binary, confirm the POST left
the process.

Pass requires all three:

1. native build completes on amd64 (the predicted failure is arch-independent;
   arm64 follows in CI)
2. the running native binary delivers a real POST
3. the existing native reflection/init log check in CI stays clean

notify4j-core contains no reflection, no `ServiceLoader`, no `Class.forName` and
no resource loading — it is static code over `java.net.http.HttpClient`. The one
predicted hazard is:

```java
private static final HttpClientConfig DEFAULT = of(DEFAULT_TIMEOUT, DEFAULT_TIMEOUT);
```

a static initializer constructing an `HttpClient`. If that class is initialized
at build time, GraalVM rejects it — an `HttpClient` holds selectors and threads
and cannot live in the image heap. Quarkus initializes library classes at run
time by default, so it likely never fires. Fix if it does:

```properties
quarkus.native.additional-build-args=--initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig
```

TLS to `https://api.telegram.org` is already proven on this image: calit does
outbound HTTPS to Google Calendar and Turnstile in native today.

If the spike fails in a way no build argument fixes, the library decision
reopens and the fallback is hand-rolling the channels, roughly one
`HttpClient.send()` each.

## 2. Data model

`V32__notification_channel.sql`:

```sql
CREATE TABLE notification_channel (
    id              BIGSERIAL   PRIMARY KEY,
    owner_id        BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    url             TEXT        NOT NULL,          -- secret-bearing, encrypted at rest
    label           VARCHAR(64),                   -- owner's own name; NOT unique
    created_at      TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ
);
CREATE INDEX idx_notification_channel_owner ON notification_channel (owner_id);

-- Per-meeting-type OVERRIDE. No rows for a (host, type) pair = that host inherits.
CREATE TABLE notification_channel_meeting_type (
    id              BIGSERIAL PRIMARY KEY,
    channel_id      BIGINT NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    meeting_type_id BIGINT NOT NULL REFERENCES meeting_type(id)         ON DELETE CASCADE,
    CONSTRAINT uq_ncmt UNIQUE (channel_id, meeting_type_id)
);
CREATE INDEX idx_ncmt_channel ON notification_channel_meeting_type (channel_id);
CREATE INDEX idx_ncmt_type    ON notification_channel_meeting_type (meeting_type_id);
```

Modelled on `meeting_type_host` (V20): link table, both FKs cascading, unique
constraint, indexed both sides, and a documented "no rows means X" convention.

### Routing rule

```
channels for (host, meetingType):
    link rows for this meetingType belonging to THIS host's channels
      non-empty -> exactly those channels      (override)
      empty     -> ALL of this host's channels (inherit)
```

The per-host scoping in the first line matters for co-hosted meeting types:
whether routing is overridden is decided per host, so one host can narrow a
shared meeting type while their co-host keeps inheriting. Without it, one host's
override would silently change another host's delivery.

Selection is resolved in Java, not SQL — a host has a handful of channels.

### Encryption at rest

`url` is encrypted with the existing mechanism, exactly as `GoogleCredential`'s
token fields are:

```java
@Column(nullable = false)
@Convert(converter = EncryptedStringConverter.class)
public String url;      // "enc:v1:" + base64(iv || ct||tag), AES-256-GCM
```

`StartupSecretCheck` already validates `TOKEN_ENCRYPTION_KEY` unconditionally at
`StartupEvent` and rejects the dev default in prod, so this adds no new
deployment requirement and no upgrade step. No backfill is needed; the table
starts encrypted on row one.

Consequences, all accepted:

- **No unique constraint on `url`.** GCM uses a random IV, so the same URL
  encrypts differently every time and ciphertext comparison is meaningless.
  Adding a duplicate channel sends two identical messages, which is
  self-evident and self-corrected.
- **No searching or filtering by URL.** Lookup is always by `owner_id`.
- **Masking is derived, not stored.** `catalog.parse()` returns secrets as
  `MASKED_SECRET`; `recompose()` refills them from the stored URL when an
  unchanged masked value is submitted back.

`label` is plain text — it holds no secret, and keeping it unencrypted lets the
meeting-type override list render without decrypting every URL.

## 3. Notification model

`EmailService` already materializes what a channel message needs, in a private
loader:

```java
private record Loaded(Booking booking, MeetingType meetingType, OwnerSettings owner,
                      ZoneId zone, List<AnswerLine> answers, List<HostDelivery> hostDeliveries) {}
private record HostDelivery(OwnerSettings settings, Booking booking) {}
```

**Refactor:** promote that loader out of `EmailService` into a shared
`BookingSnapshot` + loader component consumed by both `EmailService` and the new
notification service. Re-querying in the observer would duplicate the subtle
part — group bookings, per-host rows, answer rendering — across two places that
must stay in step. It also shrinks a 1052-line class rather than growing it.

```java
public sealed interface HostNotification {
    Host recipient();          // OwnerSettings: locale, timezone, timeFormat, ownerId
    Kind kind();               // -> notify4j `status`, e.g. "BOOKING_REQUESTED"

    record Requested      (BookingSnapshot b, Host h)                  implements HostNotification {}
    record Confirmed      (BookingSnapshot b, Host h)                  implements HostNotification {}
    record Approved       (BookingSnapshot b, Host h)                  implements HostNotification {}
    record Declined       (BookingSnapshot b, Host h)                  implements HostNotification {}
    record Cancelled      (BookingSnapshot b, Host h, boolean byOwner) implements HostNotification {}
    record Rescheduled    (BookingSnapshot b, Host h, Instant oldStartUtc, boolean byOwner)
                                                                       implements HostNotification {}
    record DetailsChanged (BookingSnapshot b, Host h, boolean byOwner) implements HostNotification {}
    record GuestDeclined  (BookingSnapshot b, Host h, BookingGuest g)  implements HostNotification {}
    record GuestRemoved   (BookingSnapshot b, Host h, BookingGuest g)  implements HostNotification {}
    record ReminderDue    (BookingSnapshot b, Host h)                  implements HostNotification {}
    record ConsentRequested(MeetingType t, Host h, String consentToken) implements HostNotification {}
}
```

The interface guarantees only `recipient()` and `kind()`, not `booking()`.
`HostConsentRequested(meetingTypeId, cohostOwnerId, consentToken)` has no
booking — it invites someone to *become* a co-host — so a common `booking()`
accessor would force a null or a fake. The exhaustive switch handles the
difference.

`ConsentRequested` is the one event where routing is slightly unusual: the
recipient has not accepted the meeting type yet, so they are notified on their
inherited set (they cannot have overridden a type they do not host). This falls
out of the routing rule; no special case is needed.

`kind()` does double duty — exhaustive-switch discriminator and the `status`
string on the wire, so a webhook consumer sees `"BOOKING_REQUESTED"` rather than
prose.

### Consent model

The presence of a channel URL is the consent:

- no channel URL → no channel notifications (email only, as today)
- channel URL present → deliver

Consequences:

- **No `enabled` column.** "Turn it off" is "delete the row". Add a flag only if
  someone asks to pause without losing the URL.
- **`actionable` is irrelevant for channels.** That override exists so a host
  who muted *email* can still approve and not deadlock a group booking. With no
  mute switch on channels there is no deadlock to prevent.
- Channels deliver even when `ownerNotificationsEnabled` is false. That flag
  governs routine owner *email*, which is how it is documented.

Event set: channels carry what hosts already receive by email, all 11 events.

## 4. Delivery path

No executor infrastructure exists in the repo and `smallrye-context-propagation`
is not a dependency, so this uses CDI's own async events. `@ObservesAsync` and
`during = AFTER_SUCCESS` are mutually exclusive in CDI — an async observer
cannot declare a transaction phase — which forces a two-step that is what we
want anyway:

```
BookingConfirmed (etc.)
   |
   v  @Observes(during = AFTER_SUCCESS)      NotificationDispatcher   [request thread]
   |- load BookingSnapshot (shared loader)
   |- resolve hosts (MeetingHosts / hostDeliveries)
   |- per host: select channels (override ? links : all)  -- none? stop, zero work
   |- per host: render Message(title, body, severity) in THAT host's locale
   `- per channel: event.fireAsync(ChannelDelivery(channelId, url, message))
                     |
                     v  @ObservesAsync                     [container executor]
                     |- Notifications.sendOnce(List.of(url), message) -> SendResult
                     `- @Transactional: stamp last_success_at | last_failure_at
```

Rationale for the split:

- Everything needing the DB or a locale happens on the request thread, where a
  transaction and request context already exist. The async side does one HTTP
  POST and one timestamp write, and never reads through Panache.
- **One async event per channel row**, not per host, so a slow Telegram cannot
  delay a Slack and the outcome attributes to the row we already identified.
- `url` is decrypted once by the converter during the sync load and carried in
  the payload, so the async side needs no entity and no session.

`sendOnce` is used rather than a long-lived `Notifications<E>` instance because
it is stateless: no per-owner instance cache, no `AutoCloseable` lifecycle, and
per-URL calls give per-row failure attribution that a shared instance cannot —
`channelName()` is `getClass().getSimpleName()`, i.e. the channel *type*, so two
Telegram channels would be indistinguishable in a shared instance's metrics.

notify4j's transition filtering and reminder machinery stay off. `TransitionFilter`
and `RemindingNotifier` hold in-memory per-replica state, which cannot work
coherently across calit's N stateless replicas. `sendOnce` uses
`MessageAdapter.INSTANCE` and does not track transitions; reminders default to
`Set.of()` (off).

### Error handling

The booking is already committed and emailed before any of this runs, so nothing
here may escape:

- `SendResult.failed() > 0` → `last_failure_at`; otherwise `last_success_at`
- any `RuntimeException` → caught, logged, `last_failure_at`
- notify4j's retry (exponential backoff capped at 30s; retries 429/5xx and
  `IOException`; fails fast on other 4xx) runs inside `sendOnce`. It is blocking,
  which is correct on a background thread and would not have been on the request
  thread.
- nothing propagates to the caller

**`HttpClientConfig` is built from config, not `defaults()`**, so `%test` can pin
`max-attempts=1`. Otherwise a single failure test would sit through the full
backoff ladder.

**Verify during implementation:** whether Quarkus activates a request context for
`@ObservesAsync` methods, or whether the timestamp write needs an explicit
`@ActivateRequestContext` alongside `@Transactional`. The first test surfaces it.

### Failure visibility

notify4j exposes only counts — `NotificationMetrics` takes a channel name with no
exception or status code, and `SendResult` is `(attempted, sent, failed)`. The UI
therefore shows *that* a channel failed and when, not why:
`Telegram — last delivery failed 3m ago`. There is no `last_error` column, because
we cannot honestly populate one.

## 5. Configuration

```properties
calit.notify.allowed-schemes=${NOTIFY_ALLOWED_SCHEMES:*}
calit.notify.allow-private-targets=${NOTIFY_ALLOW_PRIVATE:true}
calit.notify.max-attempts=${NOTIFY_MAX_ATTEMPTS:3}
%test.calit.notify.max-attempts=1
```

**`allowed-schemes`** is an operator-side blocklist; notify4j has none of its own
(`NotifierUrlParser` is a hardcoded `switch` over every scheme it knows).
Enforced twice: at **save time**, rejecting with a validation error, and again at
**send time**, so a row saved before the allowlist was tightened stops delivering
rather than being grandfathered. Matching compares the **channel part before any
`+` transport suffix**, so `ntfy+http://` matches an allowlist entry of `ntfy`.

A shared-instance operator worried about internal probing sets
`NOTIFY_ALLOWED_SCHEMES=telegram,slack,discord,gotify,ntfy`, dropping `webhook`
while keeping every vendor channel working.

**`allow-private-targets` defaults to true.** Owner-supplied URLs can name any
host, which on a shared instance lets an authenticated user probe the internal
network. Blocking private ranges is the textbook fix and would break the primary
self-hosted case — `http://gotify.lan`, `ntfy+http://ntfy:8080` beside calit in
Docker are exactly what these users want. Default-allow, with the flag for shared
instances. Scheme channels (`telegram://`, `slack://`) resolve to the vendor's own
API host and are not a vector.

All three go in `.env.example` and the docs site.

## 6. Owner UI

Follows the existing `AdminResource` + Qute pattern; every POST carries
`{inject:csrf.token}`.

### `/me/settings` — channel list

```
Notification channels
  [ Phone       ] [ telegram://...7891        ]  [Test] [Delete]   ok delivered 2h ago
  [ Team Slack  ] [ slack://...T00/B00/xxxx   ]  [Test] [Delete]   !  failed 3m ago
  [             ] [                           ]                    <- always one empty row
  [+ Add another]
  [Save]
```

- Repeatable inputs, no per-channel "default" concept: every channel is sent to
  unless a meeting type overrides.
- **Progressive enhancement:** the page always renders existing rows plus one
  empty input, so without JS a host adds one channel per save and the re-render
  gives a fresh empty row. With JS, `+ Add another` clones an empty input so
  several go in at once. Same form, same POST.
- Existing rows render masked via `catalog.parse()`; `recompose()` refills the
  secret when an unchanged masked value comes back.
- **Send test** per row runs `sendOnce` inline and reports pass/fail immediately.
  It is the only way a host learns a URL is wrong *before* a real booking, since
  `last_failure_at` is by definition after the fact.
- **Label defaults from the scheme at save time** when left blank, so it lands as
  a real editable value rather than a render-time fallback:

  ```java
  catalog.tryParse(url)
         .flatMap(p -> catalog.describe(p.scheme()))
         .map(ChannelDescriptor::displayName)     // "Telegram", "Slack", "Gotify"
         .orElse(scheme);
  ```

- `ChannelDescriptor.docsUrl` backs a per-channel help link next to the input.

Deliberately **not** generating a per-scheme form from `catalog()` in v1: dynamic
fields per channel type need either JS or a two-step form, and calit requires
every feature to work without JS. One URL input validated by `tryParse` plus the
allowlist gives good errors with one field and a plain POST. Catalog-driven forms
are a clean later enhancement.

### Meeting type detail — override

```
Notifications
  (o) All my channels  (Phone, Team Slack)
  ( ) Custom for this meeting type
      [ ] Phone   [x] Team Slack
```

Radio plus checkboxes so it degrades without JS. An untouched type reads "All my
channels (…)" rather than looking empty.

## 7. Internationalization

Channel title/body are short plain prose, a poor fit for the Qute email templates
and a good fit for `@Message` keys. They go in `AppMessages` alongside the
existing `// ---- Email subjects ----` block, as `// ---- Channel notifications ----`.

Per repo rule, **de and he values land in the same change**, with identical
placeholder names across locales.

## 8. Testing

No new test dependency. `CaptchaVerifierTurnstileTest` already stubs HTTP with
the JDK's `com.sun.net.httpserver.HttpServer` inside a `@QuarkusTest`; delivery
tests follow that pattern.

- **Delivery** — register a channel pointing at `ntfy+http://localhost:PORT/topic`,
  drive a real booking, assert the handler received the POST and
  `last_success_at` was stamped. The stub handler counts down a `CountDownLatch`
  the test awaits with a timeout, because `fireAsync` races the assertion.
- **Failure** — stub returns 500, assert `last_failure_at` stamped and
  `last_success_at` untouched. Requires `%test` `max-attempts=1`.
- **Routing** — no links selects all of that host's channels; links select
  exactly those; on a co-hosted type where host A overrides and host B does not,
  A is narrowed and B still inherits.
- **Owner scoping** — owner 2's channels are never selected for owner 1's
  booking. Mandatory per the tenancy invariant.
- **Allowlist** — save rejects a disallowed scheme; send-time re-check drops a
  row whose scheme was removed after it was saved; `ntfy+http://` matches an
  allowlist entry of `ntfy`.
- **Rendering** — all 11 kinds produce non-empty title and body in en, de and he,
  plus a parity test that every new `@Message` key exists in both locale files.
- **Secrets** — URL round-trips through `EncryptedStringConverter` and the raw
  column starts with `enc:v1:`; the rendered settings page never contains the raw
  token.

Two repo constraints shape this: `quarkus-jacoco` only instruments code reached
through a booted `@QuarkusTest`, so even routing and rendering tests that could be
plain JUnit are written as `@QuarkusTest`; and RestAssured cannot execute JS, so
only the no-JS path of "+ Add another" is tested — one POST, one row created.

## 9. Accepted limitations

- **Per-meeting-type mute is not expressible.** "No link rows" means inherit, so
  override-with-zero-channels cannot be distinguished from inheriting. The UI
  validates that Custom requires at least one channel. If true per-type mute is
  requested, a per-(host, type) routing row whose presence means CUSTOM is
  additive.
- **A channel cannot be excluded from the inherit set.** A channel intended for
  one meeting type only requires overriding every other type. Acceptable while
  channel counts are small.
- **Failure reason is unavailable** — see Failure visibility above.
- **No delivery durability.** In-flight retries are lost on replica restart, and
  a full executor queue drops the notification with a warning. The booking is
  already committed and emailed, so channels are a latency improvement, not the
  system of record. A Postgres outbox drained with `FOR UPDATE SKIP LOCKED` — the
  pattern `ReminderScheduler` already uses — is the upgrade path if this proves
  insufficient.

## 10. Documentation obligations

- `.env.example` — the three `NOTIFY_*` variables
- `docs-site` branch — configuration reference, and a usage page covering how to
  obtain a channel URL per provider
- changelog — a bullet under `## Unreleased` on `docs-site` at merge, naming
  migration `V32`, the `NOTIFY_*` variables, and the accepted limitations that
  affect users
