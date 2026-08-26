---
# calit-7hls
title: Card image responses are cacheable and carry a csrf-token Set-Cookie
status: in-progress
type: bug
priority: normal
created_at: 2026-08-26T19:36:28Z
updated_at: 2026-08-26T19:49:05Z
---

Found by probing the live instance (cal.asm0dey.site) immediately after calit-o89d merged. This is the concrete form of the risk ADR-0009 records as a consequence and the final whole-branch review flagged as a Minor — it is real in production, not hypothetical.

## Observed

```
$ curl -sSI https://cal.asm0dey.site/og/asm0dey/30min.png
HTTP/1.1 200 OK
Content-Type: image/png
ETag: "617b0d31..."
set-cookie: csrf-token=-WZJvVXUZO-BfazPYUUqTg; Max-Age=7200; Path=/; Secure; HTTPOnly
Expires: Wed, 26 Aug 2026 22:30:00 GMT
Cache-Control: max-age=10468
```

The response is **cacheable and carries a per-session cookie at the same time**. A shared cache or CDN in front of the instance could store one visitor's `csrf-token` and serve it to another.

Confirmed on `/og.png`, `/og/{user}.png` and `/og/{user}/{slug}.png` — and on `/privacy` too, so this is not specific to the card endpoints. `quarkus-rest-csrf` issues the cookie on safe GETs regardless of content type; `application.properties:170-180` configures http-only and force-secure but nothing about which responses get a token.

The card endpoints make it newly relevant because they are (a) explicitly designed to be cached at the proxy/CDN layer, and (b) fetched by unfurl crawlers rather than browsers, so the cookie serves no purpose on them whatsoever.

## Second, related finding: the proxy rewrites Cache-Control

`OgImageResource` sets `public, max-age=3600`. The live response shows `max-age=10468` with **no `public`** and an `Expires` header — openresty is overriding it. Two consequences:

- The application's caching intent is not what actually reaches clients. Worth knowing before tuning `max-age` in code again, since the code value is currently inert.
- The missing `public` is the only thing limiting the blast radius of the cookie issue above. That is accidental protection, not design.

## Fix options (decide, do not assume)

- [x] Stop issuing a csrf-token cookie on responses that cannot host a form — at minimum the `/og*` image endpoints. Check whether `quarkus-rest-csrf` supports a path or content-type exclusion; if not, a filter that strips the cookie on those routes.
- [ ] Alternatively/additionally, set `Cache-Control: private` or `no-store` on any response that carries a `Set-Cookie`, so a shared cache can never retain it. (Not needed: the cookie is now never issued on these routes in the first place, so there is nothing left to protect against — `public, max-age=3600` stays as designed.)
- [ ] Decide whether the proxy should stop rewriting `Cache-Control` on `/og*` — moot for this bug (already resolved per the 2026-08-26 update below) but left open as a general infra question, out of scope for an application-code branch.
- [ ] Update ADR-0009's consequence note once resolved — deferred; not touched by this branch, flagged as a follow-up.

## Severity reasoning

Not critical: the token is a CSRF token rather than a session credential, the cookie is `HttpOnly`/`Secure`, and `public` is absent so well-behaved shared caches should decline to store it. But it is a cookie leaking into a response class explicitly designed for third-party caching, which is the wrong shape regardless of current exploitability.

## Related

- `calit-o89d` — the feature that surfaced it
- `docs/adr/0009-capability-urls-never-carry-a-preview.md` — records the anticipated version of this

## Update after the proxy was reconfigured (2026-08-26, same day)

The instance owner disabled Nginx Proxy Manager's "Cache Assets" for this host. That removed the
proxy's `expires`/`Cache-Control` override, so the application's own header now reaches clients —
which is the intended design, and it also restored HSTS on those paths (the cache location block had
been swallowing inherited `add_header` directives).

**It also made this bug more exposed, not less.** The card responses now carry:

```
cache-control: public, max-age=3600
set-cookie: csrf-token=...; Max-Age=7200; Path=/; Secure; HttpOnly
```

Previously the proxy emitted `max-age=10468` with **no** `public`. The `public` token is precisely
the one that tells a shared cache it MAY store the response, so a CDN or shared proxy is now
explicitly invited to store a response carrying a per-session cookie. The accidental protection
noted above is gone.

Confirmed still scoped to dynamic endpoints: `/calit.css` and `/favicon.svg` carry no `Set-Cookie`,
so this is the card routes (and other app responses), not static resources.

This makes the application-side fix the right one rather than something to solve in proxy config —
the proxy is now correctly transparent, and the cookie should not be on these responses in the first
place. A crawler fetching an `og:image` has no use for a CSRF token.

## Summary of Changes

Fixed on branch `fix/no-csrf-cookie-on-card-endpoints`.

- Added `CardCsrfCookieFilter` (`src/main/java/site/asm0dey/calit/web/CardCsrfCookieFilter.java`): a
  Vert.x `@RouteFilter` matching `/og.png`, `/og/{user}.png`, `/og/{user}/{slug}.png`, which
  registers a `headersEndHandler` to remove the `csrf-token` cookie (name read from
  `quarkus.rest-csrf.cookie-name`, default `csrf-token`) from the response before it is written.

- **Approach chosen and why**: a JAX-RS `ContainerResponseFilter` (the first approach considered)
  was ruled out by decompiling `CsrfRequestResponseReactiveFilter` (quarkus-rest-csrf 3.38.3): its
  response-side method sets the cookie via `RoutingContext.response().addCookie(...)`, directly on
  Vert.x's CookieJar — the exact mechanism `RememberMeFilter` already documents in this codebase for
  the `quarkus-credential` cookie. A cookie added that way never appears in
  `ContainerResponseContext.getHeaders()`; it is serialized into a real `Set-Cookie` header only when
  Vert.x prepares the response for write, strictly after every `headersEndHandler` runs, itself
  strictly after the whole JAX-RS filter chain (CSRF filter included) has completed. So this is not a
  filter-priority race a `@Priority` could win — the cookie is structurally invisible to
  `getHeaders()` at every point in the JAX-RS filter chain. Went straight to the Vert.x
  `@RouteFilter` + `headersEndHandler` approach, mirroring `RememberMeFilter`'s proven technique.

- `quarkus.rest-csrf.create-token-path` (a path allow-list) was rejected as previously decided:
  calit's form paths are dynamic (`/{username}/{slug}`, `/booking/{manageToken}/manage`,
  `/guest/{declineToken}/decline`) and cannot be enumerated; a missed path would silently disarm CSRF
  there.

- Added `CardCsrfCookieFilterTest`
  (`src/test/java/site/asm0dey/calit/web/CardCsrfCookieFilterTest.java`), using the same
  `@TestProfile(CsrfEnforcementTest.CsrfOn.class)` pattern as `CsrfEnforcementTest` to re-enable the
  real `quarkus-rest-csrf` extension (disabled by default in `%test`, so a plain `@QuarkusTest` would
  pass vacuously here). Four tests:
  - `productCardHasNoCsrfCookie`, `ownerCardHasNoCsrfCookie`, `meetingTypeCardHasNoCsrfCookie`: all
    three card routes return no `csrf-token` cookie, still 200 with `image/png`, PNG magic bytes,
    `Cache-Control: public, max-age=3600`, and an `ETag`.
  - `bookingPageStillGetsCsrfCookieAndToken`: `GET /admin/{slug}` (the real booking form) still sets
    the `csrf-token` cookie AND still renders the matching hidden `{inject:csrf.token}` field — proof
    the fix doesn't disarm CSRF on the one route that needs it.
  - Confirmed the first three fail against the pre-fix tree (ran them before `CardCsrfCookieFilter`
    existed): all three failed with `expected: <null> but was: <csrf-token=...>`; the booking-page
    test already passed (untouched behavior).

- `./mvnw verify` (JDK 26/Liberica): **BUILD SUCCESS** — 1036 unit tests + 1 integration test
  (`OgImageResourceIT`), 0 failures/errors, `spotless:check` clean.

- Full report at `.superpowers/sdd/csrf-cookie-fix-report.md`.

- Not done in this branch (left unchecked above / follow-ups): the `Cache-Control: private`
  alternative (superseded — no cookie means nothing to protect against), the proxy `Cache-Control`
  rewrite question (already moot, see the update above, and is infra not app code), and the ADR-0009
  consequence-note update (deferred as a documentation follow-up).
