---
# calit-o89d
title: Social preview images for the public pages
status: completed
type: feature
priority: normal
created_at: 2026-08-26T08:30:16Z
updated_at: 2026-08-26T19:40:11Z
---

calit has **no** `og:` or `twitter:` meta on any page — verified by grep across `src/main/resources/templates/`. A booking link pasted into Slack, WhatsApp, iMessage or a tweet renders as a bare URL with no title, no description and no image. That is the product's single most-shared artifact, so it is the worst place to have no preview.

## Pages in scope (unauthenticated, shareable)

All render through `templates/base.html`, so the tags have one home.

| Route | Resource | What a preview should say |
|---|---|---|
| `/` | `PublicResource:34` | the marketing landing — product-level card |
| `/{user}` | `PublicResource:191` | the owner's public list — their name |
| `/{user}/{slug}` | `PublicResource:225` | **the important one**: owner name, meeting type name, duration(s) |
| `/privacy`, `/terms` | `LegalResource:29,36` | product-level card is fine |
| `/login`, `/signup` | | product-level card is fine |

## Explicitly OUT of scope, and this matters

The `/booking/{manageToken}/*` pages (`PublicResource:480,551,582,603`) and `/guest/{declineToken}/decline` are reachable without logging in, but they are **capability URLs**. They must NOT get a rich preview: an unfurl would render the invitee's name, the meeting and the time into a chat the token was pasted into, and some clients prefetch link previews server-side, which would also touch those endpoints uninvited. Give them `<meta name="robots" content="noindex,nofollow">` and no `og:` tags at all. Worth treating as the first task rather than the last — it is a leak that exists today, independently of whether any image ever ships.

## Shape

**Phase 1 — static card, all pages.** One PNG in `src/main/resources/META-INF/resources/`, plus `og:title` / `og:description` / `og:image` / `og:url` / `twitter:card` in `base.html`, with title and description already available per page (every template is passed a `title`). `og:image` and `og:url` must be absolute — use the existing `APP_BASE_URL` config rather than deriving from the request.

**Phase 2 — per-meeting-type card.** The genuinely valuable one: a card showing the owner's name, the meeting type and its length(s). Needs an image generated per type.

## The constraint phase 2 has to solve first

CI builds **native multi-arch images** (`.github/workflows/ci.yml`, GraalVM). `java.awt` is the obvious way to draw a PNG and is exactly what tends to break under native-image — so "just use BufferedImage" is not a decision that can be made without checking it builds and runs in the native artifact, not only under `quarkus:dev`.

Options to weigh before committing:

- render an SVG string per type and rasterise (still needs a rasteriser)
- a minimal hand-rolled PNG encoder over a pixel buffer — no AWT, but real work
- serve SVG directly and accept that most unfurlers reject it (probably a non-starter)
- generate at meeting-type save time rather than per request, so the cost is paid once

Decide this before writing code; the wrong pick is discovered at native-build time, not at dev time.

## Also worth settling

- **Locale.** The UI ships `en`/`de`/`he`; `og:locale` should follow the same resolution the page uses.
- **Secret types.** A type marked `secret` is hidden from `/{username}` but reachable by direct link. Its preview should probably be the product-level card, not one naming the meeting — otherwise the unfurl defeats the point of the flag.
- **Caching.** Whatever phase 2 generates needs a cache header and a stable URL, or every unfurl re-renders.

## Todo

- [x] `noindex,nofollow` and no `og:` on the `/booking/{manageToken}/*` and `/guest/{declineToken}/*` pages
- [x] Static card image + `og:`/`twitter:` tags in `base.html`, absolute URLs from `APP_BASE_URL`
- [x] Per-page title/description wired from the existing `title` param
- [x] Decide the phase-2 rendering approach against the native-image constraint, and record why
- [x] Per-meeting-type card
- [x] Decide the secret-type behaviour
- [x] `og:locale` follows the active locale (not shipped — deliberate, see Summary of Changes)
- [x] Verify an unfurl end to end in at least one real client, not only by reading the HTML (verified server-side against the live instance — see Unfurl verification below)
- [x] docs-site: note the new `APP_BASE_URL` dependency if it becomes required rather than optional


## Summary of Changes

Shipped: absolute `og:`/`twitter:` metadata plus a static product card on every public page
(`base.html`); `noindex,nofollow` and no `og:` tags at all on the capability-URL pages
(`/booking/{manageToken}/*`, `/guest/{declineToken}/decline`); a generated per-meeting-type card
(owner name, meeting name, duration(s)) served from `/og/{user}.png` and `/og/{user}/{slug}.png`
with an ETag over the fields that affect the render and a `Cache-Control: public, max-age=3600`
header; secret meeting types and disabled owners both degrade to the generic product card rather
than a 404 or a leak; rendering runs on `java.awt`/`BufferedImage`, chosen over an SVG-rasterise or
hand-rolled PNG-encoder path, and both the JVM and native container images now build and render it
(byte-for-byte identical output, verified across runtimes); ADR-0009 records the capability-URL
decision; the docs-site changelog carries the Unreleased entry (committed, unpushed).

Deliberately did NOT ship:

- **No per-locale card variants.** `og:locale` is always `en_US` — unfurl bots send no
  `Accept-Language`, so there is nothing to key a per-locale render off of.
- **No server-side cache.** A render is ~2 ms; `Cache-Control: public, max-age=3600` puts caching in
  the proxy/CDN layer instead, where it belongs for a resource this cheap to regenerate.
- **No CJK / Thai / Devanagari / emoji coverage.** The bundled font stack does not cover these
  scripts; a headline containing them falls back to the generic product card rather than rendering
  tofu boxes.
- **No RTL mirroring.** The card's centred composition removes the need — Hebrew headlines render
  correctly centred with no separate RTL layout branch.

Two genuinely notable discoveries, neither in the original plan:

1. **The native image could not compile at all before this branch's Task 5.** `CardRenderer`'s
   `static final Color` constants (`BG`, `INK`, `INK_2`, `INDIGO`, `INDIGO_2`, `MIST`) get folded
   into the native-image heap because `CardRenderer` is a CDI `@ApplicationScoped` bean and Quarkus
   initializes bean classes at build time; GraalVM refuses to embed instances of `java.awt.Color`
   there because `Color` defaults to run-time initialization. Confirmed pre-existing on bare `HEAD`
   before any Task 5 change — the native build on this branch had never actually succeeded. Fixed
   with `--initialize-at-build-time=java.awt.Color` in `Dockerfile.native`, not by restructuring
   `CardRenderer`, so `render()`/`product()` stayed untouched and their determinism guarantee holds
   (verified byte-for-byte identical SHA-256 across the JVM and native builds).
2. **A disabled owner's card reopened `calit-h8mb`'s closed enumeration oracle.** The card routes
   originally resolved the owner without checking `owner.enabled`, unlike `PublicResource`'s booking
   path — so a disabled owner's real name, meeting-type name, duration and location still rendered
   at HTTP 200 through `/og/{user}/{slug}.png` while the booking page 404'd the same account,
   letting a prober tell "disabled" from "never existed." Found in Task 4 review, fixed in the same
   task: a disabled owner now degrades to the generic product card at 200, matching the
   already-decided secret-type behaviour, rather than a 404 (which would unfurl as a broken image
   and itself be a distinguishing signal).

Follow-up: `calit-fanm` holds the deferred brand-lockup work identified mid-plan while comparing the
rendered card against the live site's `.lp-brand` — the chip's `box-shadow` (not drawn at all) and
its size-ratio drift from the site's actual proportions (gap, wordmark and chip-glyph ratios).
The two real bugs found in that same investigation (chip corner radius at half the site's value;
cramped wordmark tracking) were fixed in this branch (Task 3b), not deferred.

## Unfurl verification (live instance, 2026-08-26)

Performed against https://cal.asm0dey.site after the merge, fetching as unfurl crawlers rather than
only reading markup.

**Positive case — `/asm0dey/30min`**, fetched with a Slackbot user-agent:
- All 13 `og:`/`twitter:` tags present, URLs absolute (`https://cal.asm0dey.site/...`).
- `og:title` = `30min · Pasha Finkelshteyn`; `og:description` = `Book a 30 min meeting with Pasha Finkelshteyn.`
- No `robots` directive, as expected for a public page.
- `og:image` fetched with a Twitterbot user-agent: HTTP 200, `Content-Type: image/png`, `ETag` present,
  decoded and inspected as a real `PNG 1200 x 630`. The card renders correctly — lockup, owner name,
  meeting type and meta pill all inside the safe square, flanks symmetric.

**Negative case — a real `/booking/{manageToken}/manage` URL** (supplied by the owner), same crawler UA:
- HTTP 200, `<title>Manage booking</title>`, 87 KB — the genuine rendered page, not a 404 shortcut, so
  the suppression branch was actually exercised.
- `<meta name="robots" content="noindex,nofollow">` present.
- **Zero** `og:`/`twitter:` tags.

**What this does and does not establish.** The negative case is conclusive: a client cannot unfurl
metadata that is not in the document, so no chat client can leak the invitee's name, meeting or time
from a token URL. The positive case is verified through the full server-side contract an unfurl
depends on (tags, absolute URLs, and the image bytes actually fetched and decoded), but nobody
watched Slack or Telegram paint the card. Client-side rendering — crop, size limits, their own
caching — remains unobserved. That residual is cosmetic, not a security property.
