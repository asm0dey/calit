# Footer & First-Run Setup Polish — Design

**Date:** 2026-06-22
**Status:** Approved (pending spec review)

## Problem

Post-launch issues found on the freshly-shipped Google-verification / legal-pages work and the first-run setup:

1. **Footer is grey-on-grey.** `text-base-content/50` on `bg-base-200` makes the build version/commit, the Privacy/Terms links, and the language switcher hard to read and not obviously interactive.
2. **Footer duplicated & inconsistent.** `base.html` (public) and `adminBase.html` (admin) each hand-maintain a footer. The language switcher and Privacy/Terms links exist only in `base.html`, so they are absent on every `/me/*` admin page.
3. **Language switcher does not scale.** It renders every locale as a middot-separated inline link. Locales auto-discover from `msg_XX.properties` files (adding a language needs no code), so 20 languages would mean 20 footer links.
4. **`/privacy` and `/terms` are unreachable before first-user bootstrap.** `FirstRunRedirectFilter` redirects every non-allowlisted path to `/setup` while no user exists; the legal routes are not allowlisted, so Google's verification crawler (and any visitor) hitting a fresh instance gets bounced to setup.
5. **First-run timezone defaults to `Europe/Amsterdam`.** The setup wizard hardcodes that zone when no settings are saved, ignoring the visitor's actual timezone.
6. **App `/privacy` is a thin, divergent copy with off-style framing.** The shipped `privacy.html` is a short hand-written policy in a generic daisyUI card using a no-op `prose` class. Meanwhile `docs-site` already has a comprehensive, canonical `privacy.md`. The app page should carry that canonical content (on the app's own origin, with operator details injected) and both legal pages should match the **marketing landing page** visual style, not a one-off card.

## Goals / Non-goals

- **Goals:** legible footer, single shared footer, a language switcher that scales, reachable legal pages pre-bootstrap, sensible first-run timezone, and an app `/privacy` that carries the canonical docs policy in the marketing-landing visual style.
- **Non-goals (YAGNI):** locale search/filter box, flag icons, a JS framework, reworking the saved-settings timezone behavior (already correct), changing how locales are discovered, a single shared markdown source rendered by both Astro and the app (rejected as too heavy), adding a `terms.md` to docs-site, the Tailwind typography (`prose`) plugin.

## Global Constraints

- Server-rendered Qute only. **No new runtime JavaScript** except the one tiny timezone-autodetect snippet (item 5), which follows the project's existing inline-vanilla-JS idiom (`Layout.TZ_SCRIPT`) and degrades gracefully with JS off.
- **No new dependency.** UI is Tailwind v4 + daisyUI 5 compiled to `/calit.css`. Use daisyUI components (consult the `daisyui` skill for exact markup at implementation time).
- RestAssured cannot run JS — assert on stable marker comments, not script behavior.
- Tests require Docker (Dev Services Postgres). Admin user is always id 1 in the default test profile; the first-run (no-user) case needs the empty-DB setup used by `SetupFlowTest`.
- Preserve the `CALIT_BUILD_FOOTER` marker (asserted by `FooterBuildInfoTest`) wherever the footer moves.

## Design

### Item 1+2+3: Shared, legible, scalable footer

**New file `src/main/resources/templates/footer.html`** — one Qute template holding the whole footer: the `calit` link + `{inject:build.version}`/`{inject:build.commit}`, the Privacy/Terms links, and the language switcher. `base.html` and `adminBase.html` each replace their inline footer with `{#include footer /}`.

- Data needs nothing passed in: `localeOptions` is set on every `TemplateInstance` by `LocaleTemplateInitializer`, and `{inject:build.*}` / `{msg:*}` are global. The include works in both base templates unchanged.
- Carry the `<!-- CALIT_BUILD_FOOTER -->` marker into the fragment.

**Contrast.** Footer container muted text moves `text-base-content/50` → `text-base-content/70`. Interactive elements (the `calit` link, Privacy, Terms, and the language-switcher button) render at full content strength (no opacity reduction) so they read as controls. Version/commit stay muted (`/70`) — visible but secondary.

**Language switcher → no-JS daisyUI dropdown.** Replaces the inline links:
- A `<details class="dropdown dropdown-top dropdown-end">` (pure HTML/CSS, no JS) whose `<summary class="btn btn-ghost btn-xs">` shows the **current** locale's endonym (the `localeOptions` entry with `active == true`) plus a chevron.
- `<ul class="menu dropdown-content ... max-h-64 overflow-y-auto">` lists every locale as an `<a href="{o.href}">{o.label}</a>`; the active one gets `menu-active` + `aria-current="true"`. `max-h-64 overflow-y-auto` makes 20+ languages scroll instead of overflowing the footer.
- Same `localeOptions` data and same `/lang/{code}?return=…` hrefs — **no backend change**.
- `aria-label="{msg:common_language}"` on the `<details>` for accessibility.

### Item 4: Reachable legal pages pre-bootstrap

In `FirstRunRedirectFilter.isAllowedWhileUnbootstrapped(path)` add `path.equals("/privacy")` and `path.equals("/terms")` to the allowlist (alongside `/`, `/setup`, `/calit.css`, etc.). They are public, content-only, no auth — safe to serve before any user exists.

### Item 5: First-run timezone auto-detect

The hardcoded `Europe/Amsterdam` lives only in the **no-saved-settings** (`{#else}`) branch of the timezone `<select>` in two templates: `MeSetupResource/meSetup.html` and `AdminResource/settings.html`. The saved-value branch (`{#if settings}` → `settings.timezone`) is correct and untouched.

- **No-JS fallback changes `Europe/Amsterdam` → `UTC`** in that `{#else}` branch in both templates (neutral default).
- The no-saved-settings `<select>` gets a `data-tz-autodetect` attribute.
- A small inline script (with a `CALIT_TZ_AUTODETECT` marker comment) reads `Intl.DateTimeFormat().resolvedOptions().timeZone` and, only for a `[data-tz-autodetect]` select, selects the matching `<option>` if present. A saved-value select has no such attribute, so an existing owner's timezone is never overridden. JS off → the `UTC` fallback stands.

### Item 6: Privacy/terms content port + landing-style restyle

**Privacy content** is replaced with the canonical policy from `docs-site/src/content/docs/privacy.md` (sections: *What calit is*, *Data calit stores*, *How Google user data is used* incl. the **Limited Use disclosure**, *Data sharing*, *Retention and deletion*, *Security*, *Contact*), ported to Qute. Operator specifics are injected, not hardcoded:
- Intro names the data controller via `{inject:site.operatorName}` (falls back to `APP_BASE_URL`).
- The *Contact* section, when `{inject:site.contactEmail}` is set, shows "contact this deployment's operator at <email>" (`{#if}`-guarded); otherwise the generic "contact the operator" wording.

**Terms** has no canonical docs source, so its existing app-authored content stays (acceptable-use / no-warranty / changes) — only restyled.

**Both pages adopt the landing visual style** (see `PublicResource/landing.html`): a `max-w-3xl mx-auto` container, a `<header class="mb-6">` with `<h1 class="text-3xl font-bold">` and a muted `text-base-content/70` intro line, then sections with `<h2 class="text-xl font-semibold mt-…">` and plain paragraphs/lists. **Drop the `prose` class and the standalone card framing.** Keep the `CALIT_LEGAL_PRIVACY` / `CALIT_LEGAL_TERMS` marker comments.

**Sync note:** the policy now lives in two places — `docs-site` `privacy.md` (canonical, public docs) and the app `privacy.html` (operator-injected, same-origin). Both carry a comment pointing at the other so a future edit updates both. Rarely-changing legal text; manual sync is acceptable (a shared renderer was rejected as too heavy).

### Item 7: Theme consistency on the fixed-palette landing

**Found during implementation (Firefox dark-OS):** the marketing landing (`PublicResource/index.html`) hardcodes its own cream palette (`body.lp-body` → `--paper`/`--ink` hex) but does not pin a daisyUI theme. The shared footer (Item 1) uses theme-aware `text-base-content`, which in dark-OS resolves to the dark theme's light text → invisible on the forced-cream background. It is the **only** page that mixes a fixed background with theme-aware text; every other page uses daisyUI semantic colors for both, so they are correct in light and dark already.

Fix (structural, no CSS hacks):
- **Pin the landing to its theme.** `base.html` gains an optional `forceTheme` param; when set it renders `data-theme="{forceTheme}"` on `<body>`. The landing passes `forceTheme="calit-light"`. daisyUI reads the nearest ancestor `data-theme`, so every daisyUI element on the landing (and the appended footer, before it is suppressed below) resolves to the light palette matching its cream design, in any OS theme. This also future-proofs: any daisyUI component later dropped on the landing stays consistent.
- **De-duplicate the landing footer.** `base.html` makes the shared-footer include opt-out: `{#if !ownFooter}{#include footer /}{/if}`. The landing passes `ownFooter=true` (it already has its own `lp-foot`) and gains `/privacy` + `/terms` links inside `lp-foot`, styled with the existing `lp-navlink` hex classes (theme-independent → always visible). No language switcher on the landing (keep the marketing footer minimal).

Principle captured: a page must be either all-daisyUI-theme-colors (they flip together) or all-its-own-fixed-palette (never flips) — never mix theme-aware foreground onto a fixed background.

## Components / files

- **Create:** `templates/footer.html`.
- **Modify:** `templates/base.html`, `templates/adminBase.html` (replace inline footer with include).
- **Modify:** `user/FirstRunRedirectFilter.java` (allowlist).
- **Modify:** `templates/MeSetupResource/meSetup.html`, `templates/AdminResource/settings.html` (UTC fallback + `data-tz-autodetect` + autodetect script; the script can live in one shared spot or inline per page).
- **Modify:** `templates/LegalResource/privacy.html` (port canonical policy, operator injection, landing-style), `templates/LegalResource/terms.html` (landing-style restyle). `LegalResource.java` is unchanged (same template methods).
- **Modify:** `docs-site` `privacy.md` (add a sync-pointer comment to the app page) — on the `docs-site` branch, separate commit.

## Testing strategy

- **Footer (1+2+3):** RestAssured on a public page (`/login`) and an authenticated admin page (`/me`, via `FormAuth`): assert `CALIT_BUILD_FOOTER`, `href="/privacy"`, `href="/terms"`, and the language-switcher markup (the active endonym + a per-locale link) appear on **both**. A negative contrast assertion is not feasible via RestAssured; contrast is verified by the class change (`/50`→`/70`) in review.
- **Filter (4):** with zero users (empty-DB setup as in `SetupFlowTest`), `GET /privacy` and `GET /terms` return **200** (not a redirect to `/setup`); a non-allowlisted path still redirects.
- **Timezone (5):** assert the first-run wizard `<select>` carries `data-tz-autodetect` and that its no-JS selected option is `UTC` (not `Europe/Amsterdam`); assert the `CALIT_TZ_AUTODETECT` script marker is present. (Actual zone detection is JS — not executed by RestAssured.)
- **Legal content (6):** extend `LegalPagesTest` — `/privacy` still returns 200 with `CALIT_LEGAL_PRIVACY`, the **Limited Use** disclosure, "Google Calendar", and the ported sections (e.g. "Retention and deletion"); the contact line appears only when `PRIVACY_CONTACT_EMAIL` is set (a `@TestProfile` setting it asserts the email renders; the default profile asserts it does not). Assert the landing-style markers (`max-w-3xl`, `text-3xl font-bold`) and absence of `class="prose"`.

## Risks

- Moving the footer into an include must keep `localeOptions`/`{inject:build.*}` resolving in both bases — verified by the footer tests on public + admin pages.
- daisyUI `dropdown-top` inside a `<footer>` near the viewport bottom must open upward and not clip — visual check during implementation.
