---
# calit-xu12
title: 'Spike: which image lib can draw the OG card, JVM and native/musl'
status: completed
type: task
priority: normal
created_at: 2026-08-26T11:19:25Z
updated_at: 2026-08-26T11:37:20Z
parent: calit-o89d
---

Throwaway spike for calit-o89d phase 2. Question: can we generate a 1200x630 PNG social card with text at runtime, in both the JVM image and the GraalVM native musl image (Dockerfile.native, Alpaquita base with only ca-certificates+zlib)?

Approved probe (user: JVM first, native only if JVM works):
- [x] Survey candidates against the constraint (java.awt/quarkus-awt, TwelveMonkeys ImageIO, ImageJ, OpenIMAJ, Skija/skia bindings, pure-Java PNG encoders)
- [x] Throwaway branch: draw-a-card endpoint with an embedded TTF, prove it in quarkus:dev
- [x] Only then: Dockerfile.native build + container run + curl the PNG, verify glyphs rendered
- [x] Report recommendation with evidence (build log + produced PNG); nothing merged

Decisions already taken in brainstorming for calit-o89d:
- secret meeting types get the generic product card, not a naming one
- og:locale is always English -> no per-locale card variants, no RTL text in images

## Survey (candidate libraries vs the native/musl constraint)

- **JDK java.awt / Graphics2D + ImageIO** (`quarkus-awt`) — the only drawing API in the JDK; PNG writer is built in. Under test.
- **TwelveMonkeys ImageIO** — codec plugins (TIFF, CMYK JPEG, ...), not a drawing API. PNG write is already in the JDK; adds nothing here.
- **ImageJ / OpenIMAJ** — image processing / CV, both built on java.awt anyway, with far larger dependency graphs. Strictly worse than using AWT directly.
- **Skija / Skiko (Skia JNI)** — real drawing engine, but ships prebuilt glibc natives; musl aarch64+amd64 coverage plus JNI bundling under native-image is a research project.
- **Pure-Java PNG encoders (PNGJ, java.util.zip Deflater)** — encode only, no text rendering. Viable only paired with a hand-built glyph atlas.

So the fork is exactly: AWT works in native musl, or we hand-roll rasterisation.

## JVM result: PASS

Standalone probe and the Quarkus endpoint both produced a valid 1200x630 PNG from an embedded Noto Sans TTF (`Font.createFont` off the classpath, no fontconfig lookup). `GET /spike/og.png` -> `http=200 image/png 25913 bytes`.

Glyph coverage caveat found early: Noto Sans Regular has no Hebrew block (`canDisplay(0x05E4)=false`) -> Hebrew meeting names render as tofu. Latin + Cyrillic + umlauts fine. A per-script fallback font (or accepting tofu) is a real cost of any embedded-font approach, independent of native.

## Summary of Changes

Spike answered: **AWT works in the native musl image, but only after three runtime-image changes that the build does not tell you about.** Nothing merged; work sits on throwaway branch `spike/og-card-image`.

### Result chain (each step was a real failure, then fixed)

1. `quarkus-awt` added -> native build succeeds, `awt` in Installed features, boot 0.33s. No build-time signal of any problem.
2. Request -> `UnsatisfiedLinkError: Can t load library: awt`. The runtime stage ships only the runner binary; AWT needs 9 JDK .so files (libawt, libawt_headless, libawt_xawt, libfontmanager, liblcms, libjavajpeg, libmlib_image + libjava/libjvm stubs, 4.6 MB) from `target/*-native-image-source-jar/`, plus `LD_LIBRARY_PATH`.
3. Request -> `IOException: Problem reading font data` from `Font.createFont`. Real cause (only visible once the endpoint printed the stack): `RuntimeException: Fontconfig head is null, check your fonts or fonts configuration`. With **zero fonts on disk** the JDK font manager fails to initialise, which breaks even `createFont` on an **embedded** TTF. Fixed by `apk add font-dejavu-core` (considered copying our own TTFs to /usr/share/fonts + fc-cache instead; rejected -- duplicates bytes already embedded in the binary and adds a build step for no gain).
4. Then: `GET /spike/og.png` -> `200 image/png`, 1200x630, output pixel-identical to the JVM run.

### Cost

- Image: **142.0 MB -> 152.5 MB** (+10.5 MB): 4.6 MB .so, 3.5 MB dejavu, freetype+fontconfig.
- `Dockerfile.native` diff: one apk line, one COPY, one ENV.
- CI needs a native smoke step that actually *requests* the card -- every failure above was build-green and request-red.

### Font findings (independent of native)

- Brand fonts are Fraunces (headings) + Hanken Grotesk (body) + JetBrains Mono, per `PublicResource/index.html` and docs-site `custom.css`/`landing.css`. Both OFL, embeddable.
- **Variable fonts do not work as hoped**: AWT ignores `fvar`, loading `Fraunces[SOFT,WONK,opsz,wght].ttf` yields the default instance -- reported as `Fraunces 9pt Black`. Weight/optical-size control requires shipping **static** instance TTFs.
- **Brand fonts are Latin-only**: `canDisplay(0x0418)=false` for both Fraunces and Hanken Grotesk (Hebrew likewise). Noto Sans covers Cyrillic but not Hebrew. AWT does **no** automatic fallback for `createFont`-loaded fonts, so a Cyrillic/Hebrew owner name or meeting title renders as tofu unless we implement per-run font selection.

### Recommendation

AWT is viable and no longer a research risk -- the unknown is now a known 3-line Dockerfile change plus a mandatory request-level smoke test. The remaining judgement call for calit-o89d phase 2 is whether a per-type rendered card is worth +10.5 MB image, a font-fallback chain for non-Latin text, and a cache/invalidations story -- versus putting the same information in og:title/og:description at zero cost.
