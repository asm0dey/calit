---
# calit-o89d
title: Social preview images for the public pages
status: todo
type: feature
priority: normal
created_at: 2026-08-26T08:30:16Z
updated_at: 2026-08-26T08:30:16Z
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

- [ ] `noindex,nofollow` and no `og:` on the `/booking/{manageToken}/*` and `/guest/{declineToken}/*` pages
- [ ] Static card image + `og:`/`twitter:` tags in `base.html`, absolute URLs from `APP_BASE_URL`
- [ ] Per-page title/description wired from the existing `title` param
- [ ] Decide the phase-2 rendering approach against the native-image constraint, and record why
- [ ] Per-meeting-type card
- [ ] Decide the secret-type behaviour
- [ ] `og:locale` follows the active locale
- [ ] Verify an unfurl end to end in at least one real client, not only by reading the HTML
- [ ] docs-site: note the new `APP_BASE_URL` dependency if it becomes required rather than optional
