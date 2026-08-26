---
# calit-fanm
title: 'Card brand lockup: chip shadow and site proportion ratios'
status: todo
type: task
priority: low
created_at: 2026-08-26T13:48:23Z
updated_at: 2026-08-26T13:48:23Z
---

Follow-up from calit-o89d (social preview images). A pixel comparison of the rendered card's brand lockup against the live site's .lp-brand found four differences. Two were fixed on feat/social-preview-images (chip corner radius, wordmark tracking). These two were deliberately deferred by the user:

## 1. The chip's box-shadow is not drawn at all

The site has `box-shadow: 0 6px 16px -6px rgba(79,70,229,.7)` on `.lp-brand .chip`; CardRenderer draws a flat rounded rect. Reproducing a CSS-style blurred, offset, spread shadow in AWT is real work (blurred rounded rect, or a pre-rendered sprite), and the payoff is small because clients downscale the 1200x630 card heavily — at Slack's render size the 56px chip lands around 17px and a soft glow is nearly invisible. Deferred on that cost/benefit, not because it is invisible up close.

## 2. Lockup proportions drift from the site's ratios

Measured as a multiple of the chip size, site vs card:

| | site | card |
|---|---|---|
| gap | 0.293x | 0.250x |
| wordmark font-size | 0.629x | 0.607x |
| chip glyph font-size | 0.560x | 0.600x |

So the card's lockup is slightly tighter and its chip glyph slightly larger, relative to the site. Fixing means retuning constants in CardRenderer.drawLockup. It is a design call (the card is viewed at a different size than the nav bar, so exact ratio parity may not even be desirable), which is why it was not folded into the feature branch.

## Not a bug — recorded so it is not re-investigated

The chip's Fraunces `c` initially looked wrong, but that was a measurement artifact: scaling the web page up pushed Fraunces' `opsz` axis to ~31. Forcing `opsz:14` to match the instanced Fraunces-Chip face made the two glyphs near-identical. The spec's opsz=14 choice is correct, because clients downscale the card back to roughly that size.

## How to reproduce the comparison

Rebuild .lp-brand verbatim from the inline CSS in src/main/resources/templates/PublicResource/index.html (~lines 58-63) in a browser with the Google-hosted Hanken Grotesk 700 + Fraunces 600, scale every length by 56/30 so the chip lands at 56px (the card's tile), screenshot the element, and measure both sides in PIL.

- [ ] Decide whether ratio parity with the nav lockup is actually wanted at card scale
- [ ] Draw the chip box-shadow, or record a decision not to
- [ ] Retune gap / wordmark / chip-glyph ratios if decided in favour
