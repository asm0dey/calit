# Social preview images for the public pages

calit emits no `og:` or `twitter:` meta on any page. A booking link pasted into Slack, WhatsApp,
iMessage or a tweet renders as a bare URL — no title, no description, no image. That link is the
product's single most-shared artifact, so it is the worst place in the product to have no preview.

This adds preview metadata to every shareable page, a rendered card image per meeting type, and —
first, because it is a leak that exists today — suppresses previews on the capability URLs that
must never unfurl.

Tracked as `calit-o89d`. The rendering approach rests on spike `calit-xu12`, whose findings are
summarised under [Runtime image](#runtime-image) and recorded in full on that bean.

## Decisions this rests on

Taken while brainstorming, each with the reason it went that way:

- **Capability URLs get no preview, by default rather than by flag.** `/booking/{manageToken}/*`
  and `/guest/{declineToken}/*` are reachable without logging in. An unfurl would paint the
  invitee's name, the meeting and the time into whatever chat the token was pasted into, and some
  clients prefetch previews server-side, touching those endpoints uninvited.
- **Secret meeting types render the generic card.** A secret type is hidden from `/{username}` but
  bookable by direct link. A preview naming the meeting would defeat the flag for anyone who
  glances at the chat.
- **`og:locale` is always `en_US`.** Unfurl bots send no `calit_lang` cookie and usually no
  `Accept-Language`, so per-locale previews would be per-locale in name only. This is a deliberate
  exception to the translate-every-string rule in `CLAUDE.md`, and the only one here.
- **The card is crop-safe by construction, not per-network.** Clients crop differently; a square
  center crop is the harsh case. Rather than serving several images, one image keeps everything
  essential inside the central square.
- **No server-side cache.** A render measures ~2 ms, so a cache would add invalidation work
  (a renamed meeting type) and memory or disk for no observed gain.
- **Text that a shipped font cannot draw falls back to the generic card**, rather than rendering
  boxes.

## Robots and the `og` opt-in

All twenty templates already call `{#include base title=title}`. `base.html` gains one optional
parameter:

```
{#include base title=title og=og}
```

- `og` absent → `<meta name="robots" content="noindex,nofollow">`, and no `og:`/`twitter:` tags.
- `og` present → the card tags, and no robots directive.

The safe branch is the default, which is what makes the leak fix durable: the token pages are
protected because they pass nothing, so the leak cannot return by someone forgetting a flag — only
by someone deliberately adding one. Pages that opt in: `/`, `/{user}`, `/{user}/{slug}`, `/privacy`,
`/terms`, `/login`, `/signup`.

`og` is a view-model record built by the resource, not assembled in the template:

```java
public record OgCard(String title, String description, String imageUrl, String pageUrl) {}
```

## Tags emitted

`og:title`, `og:description`, `og:image`, `og:image:width` (1200), `og:image:height` (630),
`og:url`, `og:type` (`website`), `og:locale` (`en_US`), `og:site_name` (`calit`),
`twitter:card` (`summary_large_image`), `twitter:title`, `twitter:description`, `twitter:image`.

`og:image` and `og:url` must be absolute. Both are built from `SiteInfo.getBaseUrl()`, which
already reads `app.base-url` and is already injected into every template as `{inject:site.*}` — so
no new configuration, and no host derived from the request (which a forwarded header could lie
about).

Per-page content, all English:

| Route | `og:title` | `og:description` |
|---|---|---|
| `/{user}/{slug}` | `{type} · {owner}` | `Book a {durations} meeting with {owner}.` — `{durations}` is the allowed set joined as `15, 30 or 60 min`, so a multi-duration type does not claim a single length |
| `/{user}` | `{owner} · calit` | `Pick a meeting type and book a time.` |
| `/` | `calit` | the landing page's own tagline |
| `/privacy`, `/terms`, `/login`, `/signup` | page title | product-level sentence |

A secret type uses the `/` row, not its own.

## The card endpoint

```
GET /og/{user}/{slug}.png   meeting-type card
GET /og/{user}.png          owner card
GET /og.png                 product card
```

Public, and outside the protected path prefixes in `application.properties`.

- **Rendered per request.** ~2 ms measured; no server-side cache.
- **`ETag`** is a hash of the rendered inputs (owner name, type name, allowed durations, location
  kind) — so a rename changes the ETag with no invalidation step anywhere.
- **`Cache-Control: public, max-age=3600`**, putting the cache in the proxy and CDN layer where it
  belongs, and where unfurlers already look.
- **Unknown user, unknown slug, inactive type, or secret type → the product card**, HTTP 200. A
  404 would unfurl as a broken image; the product card degrades to "this is a calit link".
- **Text the font chain cannot fully draw → the product card**, same reasoning.

Because this is a public compute endpoint, it is worth naming the risk: it renders on demand for
unauthenticated callers. At 2 ms and with `Cache-Control` in front of it this is not a rate-limit
candidate on day one, but it is the kind of endpoint that becomes one.

## Composition

1200×630. Everything essential lives inside the **central 630×630 square** (x 285–915); the indigo
flanks outside it are pure decoration, symmetric, so any crop removes only decoration. Verified by
rendering the card and center-cropping it to 1.91:1, 2:1, 4:3 and 1:1 — the layout survives all
four, including the square thumbnail that reduced an earlier left-aligned design to a fragment of
one word with no logo.

Stacked and centered: brand lockup, owner name, meeting type, meta pill. Centering also means
**RTL needs no mirroring** — there is no left edge to flip. Bidi within a line (`30 דק׳ · Google
Meet`) is handled by `drawString`.

The brand lockup matches the site's `.lp-brand`: an indigo chip with 30% corner radius carrying a
white **"c" in Fraunces**, then the wordmark **"calit" in the body sans at weight 700** with
`-0.02em` tracking applied per character. (The site puts Fraunces in the chip and the sans in the
wordmark — not the other way round.)

**Headline fitting**: shrink 74 → 52 px until the name fits the safe square; then wrap to two lines
at 52 px; then ellipsize. The meta pill carries every allowed duration (`15 · 30 · 60 min`) plus the
location kind.

## Fonts

| File | Role | Scripts |
|---|---|---|
| `Rubik-Regular`, `Rubik-SemiBold` | all user-supplied text | Latin, Cyrillic, Hebrew (incl. nikud), Arabic |
| `NotoSans-Regular`, `NotoSans-SemiBold` | fallback | Greek and Latin extras |
| `NotoSansHebrew-Regular` | fallback | Hebrew |
| `HankenGrotesk-Bold` | the wordmark "calit" | Latin |
| `Fraunces-Chip` | the chip's "c" | Latin |

Rubik was chosen over the site's Hanken Grotesk for user text because Hanken is Latin-only:
`canDisplay('И')` is false, Hebrew likewise. Rubik is a geometric sans of the same family of shapes
and covers Latin, Cyrillic, Hebrew and Arabic in one file.

**Text is drawn through a fallback chain**, not a single font: the string is split into runs by
`canDisplay`, and each run is drawn with the first font that covers it, weight-matched so a Greek
headline is not silently lighter than a Latin one. AWT performs no automatic fallback for
`createFont`-loaded fonts, so without this a Greek meeting name renders as boxes.

All faces are **static instances generated with `fonttools varLib.instancer`** and committed as
assets, because **AWT ignores `fvar`**: loading `Rubik[wght].ttf` yields whatever the default
instance is (`Rubik Light`), with no way to ask for another weight at runtime. Fraunces is instanced
at `opsz=14` to match the ~17 px the site renders the chip at, not the display cut.

Each family ships with its OFL notice. The generation commands go in a short README beside the
files so the assets are reproducible rather than mystery binaries. Together the seven faces are
roughly 2 MB of repository assets, which also travel inside the native binary.

Scripts no shipped font covers — CJK, Thai, Devanagari, emoji — trigger the generic-card fallback.
Adding Noto CJK would cost 10–16 MB for one script family; that trade can be revisited if a real
user reports it.

## Runtime image

The spike established that AWT works in the native musl image, but only with runtime changes that
the *build* never warns about. Each of these was build-green and request-red:

1. `quarkus-awt` alone → build succeeds, request dies with
   `UnsatisfiedLinkError: Can't load library: awt`.
2. The runtime stage must ship the nine JDK `.so` files from `target/*-native-image-source-jar/`
   (4.6 MB) with `LD_LIBRARY_PATH` pointing at them.
3. Even then, `Font.createFont` fails with `Fontconfig head is null` — with **no fonts on disk**
   the JDK font manager will not initialise, and that breaks loading an *embedded* TTF.
   `apk add freetype fontconfig font-dejavu-core` fixes it.

Cost: 142.0 → 152.5 MB. Output is pixel-identical to the JVM run.

**The JVM image fails too — verified, not suspected.** `bellsoft/liberica-runtime-container:jre-26-musl`,
today's runtime, cannot render:

```
UnsatisfiedLinkError: .../lib/libfontmanager.so:
  Error loading shared library libfreetype.so.6: No such file or directory
```

So both images need work, and neither build says so.

The font stack can be **copied from a builder stage** rather than installed, which matters because
the hardened images have no package manager (and `jre-distroless-musl` has no shell at all). Verified
working: `libfreetype.so.6`, `libfontconfig.so.1`, plus `libexpat`, `libbz2`, `libpng16`,
`libbrotlidec`, `libbrotlicommon`, `/etc/fonts`, the font files, and `/var/cache/fontconfig` with
`fc-cache` run in the builder stage.

Measured on `bellsoft/hardened-liberica-runtime-container:jre-distroless-musl`:

| Base | Size | Renders |
|---|---|---|
| `liberica-runtime-container:jre-26-musl` (today) | 138.0 MB | no |
| `hardened-…:jre-distroless-musl` | 130.8 MB | no |
| hardened distroless + copied font stack | 134.2 MB | **yes** |

The hardened base is musl, so it runs our binaries, and the fixed image is smaller than today's
broken one. Choosing a base is `calit-gabg`'s call, not this design's — but this design must not
assume `apk` exists at runtime, so the fonts-and-libraries-by-COPY approach is the one specified.

Two further consequences, also tracked in `calit-gabg`:

- **A fully static binary is foreclosed.** AWT is `dlopen`-based (`libawt.so`, `libfontmanager.so`),
  so `--static --libc=musl` cannot render at all. Rendering in-process and a from-scratch final
  layer are mutually exclusive; this design picks rendering. (A *distroless* layer is fine — that is
  a different thing from static.)
- **It widens the patch surface.** freetype and fontconfig are C font parsers with a long CVE
  history. They only ever parse the fonts we ship — user-supplied text is drawn, never parsed as a
  font — but they are now part of what has to be kept patched.

`/tmp` must stay writable: `Font.createFont(InputStream)` spills to a temp file, so a read-only root
filesystem needs a tmpfs mount there or card rendering fails at request time.

Fonts are added to `quarkus.native.resources.includes`, which the spike confirmed works (621 572
bytes of TTF arrived intact inside the native binary).

## Tests

- `base.html` behaviour: a token page emits `noindex,nofollow` and no `og:`; a booking page emits
  the tags with absolute URLs. RestAssured against the rendered HTML.
- The endpoint: content type, PNG magic bytes, 1200×630, `ETag` present and stable across two
  requests, and changing after a rename.
- A secret type's card and tags are the generic ones — the regression that matters most.
- Unit tests for the fit/ellipsize logic, the RTL detection, and the font-chain run splitting
  (a Greek string produces a Noto run; a CJK string reports uncoverable).
- **CI native smoke must request the card** and assert PNG magic bytes. Every failure in the spike
  passed the build; a build-only check proves nothing here.

## Docs

- `docs-site` changelog entry under `## Unreleased`.
- A note that link previews need `APP_BASE_URL` to be correct — it is already required config, but
  it now has a visible failure mode (previews pointing at the wrong host).
- The native image grows ~10 MB; worth a line so it does not look like a regression.

## What this deliberately does not do

- No per-locale card variants (see the `og:locale` decision).
- No server-side cache, no stored/generated-at-save-time image.
- No per-network image sizes — one crop-safe card.
- No RTL mirroring — the centered composition removes the need.
- No CJK/Thai/Devanagari/emoji fonts.

## Candidate ADR

"Capability URLs never carry a preview" is a domain rule rather than an implementation detail: it
constrains every future page that renders behind a token, not just the four that exist today. Worth
writing as `docs/adr/0009-*` while implementing, if it survives review.
