# Signed-in `/` redirects to the dashboard — design

**Bean:** calit-q8m1
**GitHub issue:** [#193](https://github.com/asm0dey/calit/issues/193)
**Date:** 2026-09-18

## Problem

Visiting `/` while signed in renders the marketing landing page. Every account
holder on an instance — the operator included — pays a click or a typed `/me`
on every visit. Form login already lands on `/me`
(`application.properties:110`), so the signed-in landing is only ever reached by
typing the bare domain, which is exactly the cheapest thing a browser does.

The counter-argument recorded on the issue is that a blanket redirect guesses
the visitor's intent. Two findings dissolve it:

- The admin shell has **no link back to `/`** (`adminBase.html:25` renders the
  brand as a `<div>`, not an anchor). `/` and `/me` are today connected in
  neither direction, so whichever one you land on is a dead end for reaching the
  other.
- The only visitors who benefit from marketing copy are anonymous, and they
  never redirect. A signed-in user is already a member of the instance.

## Decision

`/` becomes the **instance entrance**. The product page gets a permanent home at
`/calit`, and the redirect is a per-user preference that defaults on.

| Request | Signed out | Signed in, pref on (default) | Signed in, pref off |
|---|---|---|---|
| `GET /` | 200 product page | **303 → `/me`** | 200 product page |
| `GET /calit` | 200 product page | 200 product page | 200 product page |
| `GET /{username}` | 200 that owner's landing | 200 | 200 |

`/` and `/calit` render the same template. `/` carries
`<link rel="canonical" href="/">` so the two URLs are not competing duplicates.

### Terminology

This change resolves a live collision: "landing" already means two things in the
codebase (`Templates.landing` is `/{user}`; `index.html` is `/` and its CSS
classes are all `lp-*`). The three surfaces are now named:

- **home** — `/`. Belongs to the instance, not to any user.
- **landing** — `/{username}`. That owner's public booking page.
- **product page** — `/calit`. The marketing pitch, at a permanent URL.

### Principle

Being signed in never redirects you away from a page that belongs to someone.
`/` belongs to the instance, so it may redirect; `/{username}` belongs to a user
and never does. Checking your own public page as a guest sees it is the main
reason to visit it.

## Why `/calit` and not `/about`

`calit` is already in `Usernames.RESERVED` (`Usernames.java:16-31`, alongside
`index`, `privacy`, `terms`). `about` is not. A literal `@Path("/about")` wins
over `@Path("/{user}")` in JAX-RS, so on any existing instance where someone
registered the username `about`, their public booking page would silently become
the marketing page after upgrade — with nothing enforcing the changelog warning.
`/calit` costs no reserved-word change and cannot break an existing instance.

## Cache headers

`/` has no cache headers today and already varies by auth (it renders
"Signed in as X"). Once it returns a 303 for some cookies and a 200 for others, a
reverse proxy — which the docs tell operators to run — can cache one and serve it
to the wrong visitor; a cached 303 bounces anonymous visitors to `/me` → `/login`.

Both 200 responses carry `Cache-Control: private`; the 303 carries
`Cache-Control: no-store`. `private` rather than `Vary: Cookie`: the page is a
template render with no per-request DB work, so shared caching buys nothing, and
`Vary: Cookie` keyed on a header that includes the CSRF and locale cookies has a
near-zero hit rate while failing in a way that serves one user's "Signed in as X"
to another.

## The preference

Per-user, stored on `OwnerSettings` as `home_redirect_enabled`, defaulting on.
Precedent: `ownerNotificationsEnabled` (`OwnerSettings.java:44`, checkbox at
`settings.html:32`).

Named `home_redirect_enabled`, not `landing_redirect_enabled`, because "landing"
means `/{username}` in this glossary.

### Existing rows get TRUE

`V33__notification_channel_default_enabled.sql` states the repo's rule in its own
comment: *"TRUE for existing rows preserves exactly the behaviour they have
today."* Applied literally here it inverts — today's behaviour is no redirect, so
preserving it means backfilling FALSE, which ships an opt-in to every user who
already exists and leaves the problem in place on every current instance.

Existing rows get TRUE deliberately. The V33 rule exists to stop silent surprises
that are hard to notice and hard to undo; landing on your own dashboard is
noticed instantly, understood instantly, and undone by one checkbox or by
`/calit`. The migration comment records the departure.

A user with no `OwnerSettings` row is not redirected — that fails toward today's
behaviour, and `PublicResource:245` already treats a null row as a real state.

## Rejected

- **Close the issue.** The friction is real, recurring, and universal.
- **`/?home` as the escape hatch.** One URL with two meanings, disambiguated by a
  query param nobody discovers; the product page gets no linkable URL.
- **Redirect anonymous visitors to `/calit` too.** The bare domain is what people
  paste into Slack and what carries the OG card (`ogCards.product("/")`).
- **Opt-in (default off).** Only helps people who go hunting in settings.
- **A `LANDING_REDIRECT` env var.** Whether the instance is public-facing is
  already handled by the anonymous branch, so an operator knob protects nothing
  the design does not already protect.
- **No preference at all.** Defensible — `/calit` is a per-visit opt-out — but a
  persisted choice was wanted.
- **Redirecting `/{username}` for its own owner.** Breaks checking your own page.

## Out of scope

Anything that changes what `/me` itself renders, and the `/{username}` landing.
