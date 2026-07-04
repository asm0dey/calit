# Pluggable CAPTCHA provider — add ALTCHA alongside Turnstile

**Date:** 2026-07-04
**Status:** Approved (design)

## Goal

Let a calit operator choose the booking-form CAPTCHA provider at deploy time:
`none` (default), Cloudflare `turnstile` (existing), or self-hosted `altcha`.
ALTCHA is a privacy-first, self-hostable proof-of-work CAPTCHA — no external
service, works air-gapped. Exactly one provider is active per instance.

Non-goals: per-tenant provider selection, signup-form CAPTCHA (Turnstile is
booking-only today; ALTCHA matches that scope), localized widget strings
(English MVP; de/he is a flagged follow-up).

## Decisions

- **Provider selection is global deploy config** (env var), consistent with how
  Turnstile is configured today. Not per-owner.
- **One server-side verifier class**, switch on provider — no interface with a
  single implementation per provider.
- **Server verify uses the official `org.altcha:altcha:2.0.2` library.**
- **Widget JS comes from mvnpm** (`org.mvnpm:altcha:3.1.0`), served version-less
  via the `quarkus-web-dependency-locator` extension. Renovate tracks the pom
  version; the template path has no version in it.
- Widget JS is **self-hosted** (honors CLAUDE.md "no runtime CDN"; the whole
  point of the "local service" use case). Turnstile still loads its script from
  cloudflare.com — unavoidable for Turnstile, left as-is.

## Config

New global config (env → `application.properties`):

| Env var | Property | Default | Notes |
|---|---|---|---|
| `CAPTCHA_PROVIDER` | `calit.captcha.provider` | `none` | `none` \| `turnstile` \| `altcha` |
| `ALTCHA_HMAC_KEY` | `calit.captcha.altcha.hmac-key` | (unset) | required when provider=altcha |
| `ALTCHA_MAX_NUMBER` | `calit.captcha.altcha.max-number` | `100000` | PoW difficulty (challenge upper bound) |

Existing Turnstile props (`calit.abuse.turnstile.*`, `calit.turnstile.*`) stay.

**Back-compat:** deployments that only set `TURNSTILE_ENABLED=true` must keep
working. Effective provider is resolved by a small CDI producer:
`calit.captcha.provider` if explicitly set; else `turnstile` if
`TURNSTILE_ENABLED=true`; else `none`. Empty-string config tolerated via
`Optional<String>` (Quarkus/SmallRye treats empty as null), same pattern the
current `TurnstileVerifier.secret` already uses.

`ALTCHA_HMAC_KEY` unset while provider=altcha is a misconfiguration → fail fast
at startup with a clear message (not a silent no-op).

## Server-side verification

Rename `booking/TurnstileVerifier` → `booking/CaptchaVerifier`
(`@ApplicationScoped`). Single method switching on the resolved provider:

```
void verify(String turnstileToken, String altchaSolution) {
    switch (provider) {
        case NONE     -> { /* no-op success */ }
        case TURNSTILE-> { /* existing Cloudflare siteverify code, moved verbatim */ }
        case ALTCHA   -> {
            if (altchaSolution == null || altchaSolution.isBlank())
                throw new AbuseException("Missing ALTCHA solution");
            boolean ok = Altcha.verifySolution(altchaSolution, hmacKey, /*checkExpires*/ true);
            if (!ok) throw new AbuseException("ALTCHA verification failed");
        }
    }
}
```

- `AbuseException` (→ HTTP 400 via `AbuseMapper`) unchanged.
- Turnstile branch is the current implementation moved as-is (Cloudflare
  siteverify POST, whitespace-tolerant success regex, 5s/10s timeouts).
- ALTCHA verification is fully local: HMAC check against `ALTCHA_HMAC_KEY`, plus
  expiry check. No network call.

## Challenge endpoint (ALTCHA only)

New `booking/AltchaResource` (JAX-RS):

- `GET /altcha/challenge` → `application/json`, body = `Altcha.createChallenge(opts)`
  where opts carry the HMAC key + `max-number`.
- Public, unauthenticated (challenge issuance is cheap and safe to expose).
- When provider≠altcha the endpoint is harmless (can still return a challenge or
  404; simplest is to always serve it — nothing reads it unless the widget is
  rendered). MVP: always serve.

## Template — `templates/PublicResource/book.html`

- Replace params `turnstileEnabled` (Boolean) + `turnstileSiteKey` with
  `captchaProvider` (String) + keep `turnstileSiteKey` (still needed for the
  Turnstile branch).
- Head block:
  ```
  {#if captchaProvider == 'turnstile'}
    <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
  {#else if captchaProvider == 'altcha'}
    <script async defer type="module" src="/_static/altcha/dist/main/altcha.min.js"></script>
  {/if}
  ```
- Widget slot (where `.cf-turnstile` div is today):
  ```
  {#if captchaProvider == 'turnstile'}
    <div class="cf-turnstile" data-sitekey="{turnstileSiteKey}"></div>
  {#else if captchaProvider == 'altcha'}
    <altcha-widget challengeurl="/altcha/challenge" name="altcha"></altcha-widget>
  {/if}
  ```
  The widget writes its solution into a hidden form field named `altcha`.

## Form wiring

- `web/PublicResource`: add `@RestForm("altcha") String altchaSolution`
  alongside the existing `@RestForm("cf-turnstile-response") String turnstileToken`.
  Pass both into the booking call. Build `captchaProvider` for the template from
  the resolved provider.
- `booking/BookingService.book(...)`: add `altchaSolution` param; call
  `captchaVerifier.verify(turnstileToken, altchaSolution)`.
- `booking/BookingResource` (JSON API) `BookRequest` record: add optional
  `altchaSolution` field; pass through.

## Dependencies (pom.xml)

- Add mvnpm repository:
  ```xml
  <repositories>
    <repository><id>mvnpm.org</id><url>https://repo.mvnpm.org/maven2</url></repository>
  </repositories>
  ```
- `org.mvnpm:altcha:3.1.0` — widget JS, served at
  `META-INF/resources/_static/altcha/3.1.0/dist/main/altcha.min.js`; the
  web-dependency-locator exposes it version-less at
  `/_static/altcha/dist/main/altcha.min.js`.
- `io.quarkus:quarkus-web-dependency-locator` — version-less static path.
- `org.altcha:altcha:2.0.2` — server-side challenge/verify.
- Renovate tracks all three via pom versions; template path is version-less, so
  no lockstep edit needed on bump.

**Deliberately not using** the extension's ImportMaps / `{#bundle}` /
`web/app/` features: those serve a bare-specifier ESM app-bundle workflow calit
doesn't have. ALTCHA is one self-contained custom element loaded via a single
`<script type="module" src="/_static/altcha/...">` tag (URL, not bare
specifier) — no importmap required. Only the version-less static-path feature of
web-dependency-locator is used.

## Progressive enhancement note

Enabling **any** CAPTCHA provider makes the booking form require JavaScript (PoW
solve for ALTCHA, widget for Turnstile). This is identical to the current
Turnstile behavior and is an accepted, documented tradeoff. With provider=none
(default) the form remains fully no-JS.

## Internationalization

- No new `@Message` keys expected — the ALTCHA widget renders its own strings.
- MVP ships the English widget build (`dist/main/altcha.min.js`). Localizing the
  widget to de/he is a follow-up: switch to the `dist/main/altcha.i18n.min.js`
  build and set the widget language. Flagged here, not silently skipped.
- If any new server-side user-facing string appears during impl, add de+he in
  the same change per project i18n policy.

## Tests

- `CaptchaVerifierTest`: provider=altcha with a valid solution (generated in-test
  via the `org.altcha:altcha` lib against a known HMAC key) passes; tampered /
  expired / missing solution throws `AbuseException`; provider=none is a no-op;
  Turnstile branch behavior unchanged.
- Book-page render tests (RestAssured, marker asserts — no JS execution):
  provider=altcha renders `<altcha-widget>` + the `/_static/altcha/...` script;
  provider=turnstile renders `.cf-turnstile` + Cloudflare script (existing test);
  provider=none omits both.
- `AltchaResource` challenge endpoint returns JSON containing the expected fields
  (algorithm, challenge, salt, signature, maxnumber).
- Follows existing test infra: `@TestProfile` to flip provider, admin user id 1,
  per-test DB reset.

## Docs (docs-site branch + repo)

- docs-site config page: document `CAPTCHA_PROVIDER`, `ALTCHA_HMAC_KEY`,
  `ALTCHA_MAX_NUMBER`; note the public `/altcha/challenge` endpoint for
  reverse-proxy configs; note self-hosted/air-gapped support.
- `.env.example` + README env table: add the three vars.
- Changelog entry on release.
