# Localization (i18n) — design

**Date:** 2026-06-21
**Status:** Approved, ready for implementation plan
**First language:** German (`de`), default/fallback English (`en`)

## Goal

Add multi-language support to calit. Ship German as the first non-default
language across the whole app: public/invitee booking flow, owner admin UI,
auth/bootstrap pages, and all transactional emails. English stays the default
and the per-key fallback.

## Two locale axes

calit has two distinct audiences with independent language selection:

- **Owner locale** — chosen by the owner in their settings, stored on
  `OwnerSettings`. Drives the owner's admin UI (`/me/*`) **and** owner-copy
  emails (owner notifications). The owner controls their own back-office
  language; visitor browser settings never override it.
- **Invitee locale** — chosen automatically from the visitor's browser, with a
  manual switch. Drives the public booking pages (`/{username}`,
  `/{username}/{slug}`, confirmation, manage, cancelled, etc.) **and**
  invitee-copy emails (confirmation, reminder, cancellation, reschedule,
  declined).

## Engine — Quarkus Qute message bundles

Use the native `@MessageBundle` support already provided by `quarkus-qute`
(no new dependency).

- One bundle interface `AppMessages` in `com.calit.web` (or a new `i18n`
  package), default namespace `msg`.
- Resource files under `src/main/resources/messages/`:
  - `msg.properties` — English (default + per-key fallback)
  - `msg_de.properties` — German
- Templates reference keys as `{msg:some_key}`.
- Java code that needs strings (email subjects, any string built outside a
  template) injects `AppMessages` and calls the generated methods, passing the
  active `Locale` where the message must render in a specific language.
- Missing key in the bundle = **build error** (compile-time checked). A key
  present in `msg.properties` but absent from `msg_de.properties` falls back to
  English automatically — partial German degrades per-key, never blank.

### Translation authoring

Claude generates the German strings; the user reviews them in the PR. No
external/professional translation step for v1.

## Locale resolution

A `@RequestScoped CurrentLocale` holder carries the active `Locale` for the
request. A custom Qute `LocaleResolver` CDI bean returns it so
`{msg:...}` renders in the right language. A request filter sets
`CurrentLocale` early:

- **Owner routes (`/me`, `/me/*`):** use `OwnerSettings.locale` for the current
  owner. Cookie and `Accept-Language` are ignored here.
- **All other (public/invitee/auth) routes:** resolve in order
  1. `calit_lang` cookie, if present and supported
  2. `Accept-Language` header, best match against the supported set
  3. English

Supported set is a single config value `app.supported-locales=en,de`
(default `en`). The resolver only ever returns a locale in this set. Adding a
language later = new `msg_xx.properties` + one entry in this list.

## Invitee language switch (SSR, no runtime JS)

Pages are server-rendered and ship no runtime JS, so the server must read the
choice on the next request — a cookie, not `localStorage`.

- Endpoint `GET /lang/{code}?return=<path>`:
  - validate `{code}` is in the supported set (else ignore)
  - set cookie `calit_lang=<code>` (long max-age, `SameSite=Lax`, `Path=/`)
  - redirect (303) back to `return` (validated as a local path — must start
    with `/`, no scheme/host — to avoid open-redirect)
- A small language link/dropdown in the public footer points at this endpoint
  with the current path as `return`.
- `GET` with no state mutation beyond a preference cookie → no CSRF token
  needed (CSRF protection is for `POST`).

## Persisted locale

Emails are sent asynchronously (outbox) and reminders fire much later via the
scheduler, when the request cookie is long gone. So the chosen locale is
persisted:

- **`Booking.locale`** (new column) — captured from `CurrentLocale` at booking
  POST. All invitee-copy emails for that booking (confirmation, reminder,
  cancellation, reschedule, declined) render from `booking.locale`.
- **`OwnerSettings.locale`** (new column, default `en`) — owner-copy emails and
  owner admin UI render from it.

## Date / time localization

Dates/times appear in **two** places — both need the locale:

**Server-side formatters:**
- `EmailService` hardcodes
  `DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)`.
  Replace `Locale.ENGLISH` with the active locale (booking locale for invitee
  copy, owner locale for owner copy). The literal `'at'` becomes a translated
  connector (`msg` key per locale).
- Any view-model / resource code formatting dates for display (weekday/month
  names in availability, calendar, slot rendering) formats with the active
  `Locale`.

**Client-side JS (`com.calit.web.Layout`):** the invitee booking page renders
times in the browser, so these are NOT server-formatted:
- `TZ_SCRIPT` calls `toLocaleString([], opts)` — the empty `[]` means "browser
  default locale". Change to read the page language and pass it:
  `toLocaleString(document.documentElement.lang || [], opts)`. Since `<html
  lang>` is set from the active locale, times match the chosen language.
- `CALENDAR_SCRIPT` hardcodes English `MONTHS` and `DOW` arrays. Replace the
  hardcoded arrays by deriving names from the page language via `Intl`
  (`new Intl.DateTimeFormat(lang, {month:'long'}).format(...)`,
  `{weekday:'short'}`), reading `lang` from `document.documentElement.lang`.
  This both localizes AND deletes the hardcoded arrays (net simplification).

No new server round-trip or data attributes are needed — `<html lang>` already
carries the locale to the client.

## `<html lang>` attribute

`base.html` and `adminBase.html` set `<html lang="{lang}">` from the active
locale (currently hardcoded `lang="en"`), for accessibility and correct
browser behavior.

## RTL — out of scope, with a free hedge

No RTL language is in scope (German + English are both LTR), so RTL itself is
**not built**. To keep a future RTL language a config flip rather than a
rewrite:

- **Guideline:** any markup touched during this i18n work uses **logical**
  Tailwind classes (`ms-`/`me-`, `ps-`/`pe-`, `start-`/`end-`, `text-start`/
  `text-end`) instead of physical ones (`ml-`, `pr-`, `left-`, `text-left`).
  Costs nothing now.
- When an RTL language is later added: set `<html dir="rtl">` from a per-locale
  flag (daisyUI components are already RTL-aware), and sweep any remaining
  physical classes to logical. Mechanical, no logic risk.

## Database — Flyway `V16`

(Next migration number is V16; latest applied is V15.)

```sql
ALTER TABLE owner_settings ADD COLUMN locale varchar(8) NOT NULL DEFAULT 'en';
ALTER TABLE booking        ADD COLUMN locale varchar(8) NOT NULL DEFAULT 'en';
```

Hibernate is validate-only; entities (`OwnerSettings`, `Booking`) get a matching
`public String locale` field. Never edit an applied migration.

## Owner settings UI

`AdminResource/settings.html` gets a language `<select>` (English / Deutsch)
bound to `OwnerSettings.locale`, persisted via the existing settings POST
(carry the CSRF token like every other field).

## Testing

RestAssured-based, asserting on rendered strings (German vs English):

- Public page with `calit_lang=de` cookie → renders German strings.
- No cookie, `Accept-Language: de` → renders German.
- Unsupported locale (cookie `calit_lang=fr` or `Accept-Language: fr`) → English.
- Owner UI (`/me`) renders the owner's stored `OwnerSettings.locale` regardless
  of cookie/header.
- Booking POST persists the active locale to `Booking.locale`.
- Invitee email (e.g. reminder) is built from `booking.locale`, not the live
  request — assert a German-locale booking yields a German email body/subject.
- Owner-copy email uses `OwnerSettings.locale`.
- A key present in English but missing in German falls back to English (guards
  the partial-translation behavior).

## Documentation (`docs-site` branch — part of "done")

- New "Localization / Languages" page: supported languages, how the invitee
  switch + browser detection work, owner language setting.
- Config reference: `app.supported-locales`.
- Note the owner-settings language field in the usage/settings docs.

## Out of scope

- Language **pairs** / fallback-chain config and **region variants**
  (`de-AT`, `en-GB`) — plain language codes only.
- RTL layout (hedge above keeps it cheap later).
- Pluralization beyond Qute's built-in `{#if}`/`{#switch}`.
- Translating **user-entered content** (meeting type names, descriptions,
  custom field labels) — these render as the owner typed them.
- Currency / number-format localization beyond what date formatting needs.
