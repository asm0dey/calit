---
# calit-7hls
title: Card image responses are cacheable and carry a csrf-token Set-Cookie
status: todo
type: bug
priority: normal
created_at: 2026-08-26T19:36:28Z
updated_at: 2026-08-26T19:36:28Z
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

- [ ] Stop issuing a csrf-token cookie on responses that cannot host a form — at minimum the `/og*` image endpoints. Check whether `quarkus-rest-csrf` supports a path or content-type exclusion; if not, a filter that strips the cookie on those routes.
- [ ] Alternatively/additionally, set `Cache-Control: private` or `no-store` on any response that carries a `Set-Cookie`, so a shared cache can never retain it.
- [ ] Decide whether the proxy should stop rewriting `Cache-Control` on `/og*`, so the application's `public, max-age=3600` is what unfurl crawlers and CDNs actually see. That is the value the design intended.
- [ ] Update ADR-0009's consequence note once resolved — it currently describes this as a low-risk possibility involving `quarkus-credential`; the real observed cookie is `csrf-token`, and it is present on every card response.

## Severity reasoning

Not critical: the token is a CSRF token rather than a session credential, the cookie is `HttpOnly`/`Secure`, and `public` is absent so well-behaved shared caches should decline to store it. But it is a cookie leaking into a response class explicitly designed for third-party caching, which is the wrong shape regardless of current exploitability.

## Related

- `calit-o89d` — the feature that surfaced it
- `docs/adr/0009-capability-urls-never-carry-a-preview.md` — records the anticipated version of this
