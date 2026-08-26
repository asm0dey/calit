---
# calit-ss7l
title: Co-hosted meeting types unfurl with the generic card
status: todo
type: bug
priority: normal
created_at: 2026-08-26T15:11:12Z
updated_at: 2026-08-26T15:11:12Z
---

Found by the final whole-branch review of calit-o89d (social preview images), and deliberately NOT fixed in that branch — see 'Why this was not fixed there' below.

## The defect

The public booking page and the card endpoint resolve a meeting type by two different methods:

- page: `MeetingType.resolveForAlias(urlUser.id, slug)` — `PublicResource.java:314`
- card: `MeetingType.findBySlug(owner.id, slug)` — `OgImageResource.java:81`

On a co-host alias URL `/{cohost}/{slug}`, `findBySlug` does not find the type, so the endpoint takes its unknown-target branch and serves the **product card**. The page's `og:`/`twitter:` tags still name the meeting type correctly, so the unfurl works — it just shows the generic calit card instead of the meeting's own.

Net effect: **the per-meeting-type card feature is silently off for every multi-host type reached through a co-host alias.** Degraded, not broken, and not a data leak — the page already displays everything the card would.

## Why this was not fixed in the feature branch

Deliberate call, recorded so it does not look like an oversight. The fix is not a one-liner: it must reuse `resolveForAlias` **and** re-apply the real owner's `enabled` guard **and** `meetingHosts.bookable(type)`. That is cross-owner resolution logic on a **public, unauthenticated** endpoint.

Adding that at the tail of a branch, in a fix wave, without the same review the rest of the feature received, is exactly how a tenant leak gets introduced — and this endpoint already had one such bug caught during the branch (a disabled owner's card leaked their name and meeting details at HTTP 200 while the booking page 404'd the same account; fixed in commit 637df6f). A graceful degradation is a much better resting state than a rushed cross-tenant lookup.

## What the fix must do

- [ ] Resolve the type the same way the page does, via `resolveForAlias`, so co-host aliases find it
- [ ] Re-apply the owner `enabled` guard against the type's REAL owner, not the alias user — mirroring `OgImageResource`'s existing disabled-owner degradation
- [ ] Apply `meetingHosts.bookable(type)` so a type that is not bookable does not get a named card
- [ ] Keep every miss degrading to the product card at HTTP 200 — the endpoint must never 404 (a 404 unfurls as a broken image) and never 500
- [ ] Keep the secret-type short-circuit ahead of any field reaching card construction
- [ ] Ensure the `ETag` still covers owner name, type name, allowed durations and location kind
- [ ] Add tests: a co-host alias URL serves a card that is NOT byte-identical to the product card; a disabled real owner still degrades; a non-bookable type degrades

## Related

- `calit-o89d` — the feature branch this came from
- `calit-fanm` — the other deferred follow-up (brand-lockup chip shadow and proportion ratios)

## Also worth folding in (Minor, same class)

`PublicResource.book()` never checks `type.active`, so an inactive type's page emits `og:` tags naming it while `/og/{user}/{slug}.png` correctly serves the product card (`OgImageResource.java:87`). Cosmetic inconsistency between page tags and card, same resolution-mismatch family as the main defect.
