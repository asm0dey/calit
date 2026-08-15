# Viewer-local time format + host time-format preference — design

**Date:** 2026-08-15
**Issue:** [#116](https://github.com/asm0dey/calit/issues/116) — "Change date and time format on the booking page"
**Branch context:** implement on its own branch off `main`
**Status:** approved-pending-review

## Problem

An invitee on the public booking page sees `2:30 PM` where they expect `14:30`.

The server is not at fault: every server-rendered time is already 24h
(`PublicResource.java:149` and `AdminResource.java:224`, both
`DateTimeFormatter.ofPattern("HH:mm")`). The AM/PM comes from the client
enhancement in `Layout.java`:

```js
var LANG = document.documentElement.lang || undefined;   // line 56
el.textContent = d.toLocaleString(LANG, opts);           // line 65
```

`document.documentElement.lang` is the **UI translation locale** resolved by
`LocaleResolutionFilter` — one of `en` / `de` / `he`, with **no region**. Bare `en`
carries US formatting defaults, so every viewer of an English page gets 12h
regardless of where they are. Confirmed against production: `curl` on
`https://cal.asm0dey.site/asm0dey/30min` serves `<html lang="en">`.

## Decisions (locked)

- **Hour cycle only.** Words (weekday, month, connector) keep following the page
  language. Only the 12h/24h choice moves. Date order is not in scope.
- **Invitee side: the viewer's device decides.** No host setting reaches invitees.
  (Owner's call in the issue thread: a public page must not carry one person's
  preference.)
- **Host side: an explicit `auto` / `h12` / `h23` setting**, defaulting to `auto`.
- **Two phases.** Phase 1 closes #116 with no schema change (one new request-scoped
  bean and one body attribute, no migration). Phase 2 adds the setting.
- **Stored values are Intl's own vocabulary** (`auto`, `h12`, `h23`) so the client
  needs no mapping table.

## Rejected alternatives

**`toLocaleString(undefined, opts)`** — pass no locale, let the engine decide
everything. This is the snippet suggested in the issue thread. Rejected: the engine
locale would drive *words* too, so a Hebrew page viewed on a US device renders an
English date inside an RTL Hebrew layout. Measured:

```
page he / engine en-US -> Thursday, August 20, 2026 at 2:30 PM
page he / engine nl-NL -> donderdag 20 augustus 2026 om 14:30
```

**Force `hourCycle: 'h23'` for everyone** — fixes the reporter, breaks every en-US
invitee who expects AM/PM.

**An invitee-facing 12h/24h toggle** — considered when it looked like browsers might
report `en-US` on 24h machines. Dropped after measurement: the reporter's and the
owner's Firefox both resolve to a 24h locale, so the device signal is good enough.
Revisit only if invitees actually report wrong output.

**A free-text or full-locale format field on the host settings** — the request is
specifically about *time*, so a three-value hour-cycle field covers it.

## Evidence

Both probes below were run during design; they are the basis for the decisions above.

**Client (bun, same ICU as browsers):** taking only `hourCycle` from the device and
leaving the locale as the page language gives the wanted result without touching
words:

```
engine nl-NL (h23) page en -> Thursday, August 20, 2026 at 14:30      <- issue #116
engine nl-NL (h23) page he -> יום חמישי, 20 באוגוסט 2026 בשעה 14:30   <- Hebrew intact
engine en-US (h12) page en -> Thursday, August 20, 2026 at 2:30 PM    <- unchanged for US
engine en-US (h12) page de -> Donnerstag, 20. August 2026 um 02:30 PM <- known wart, below
```

`resolvedOptions().hourCycle` is only populated when an hour field is requested, so
the probe must pass `{hour:'numeric'}`. `timeStyle` combined with `hourCycle` is
legal (the illegal combination is `dateStyle`/`timeStyle` with individual component
options).

**Server (JDK 26):** `auto` needs no lookup table on the Java side either.

```java
String p = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale);
boolean h12 = p.indexOf('a') >= 0 || p.indexOf('h') >= 0 || p.indexOf('K') >= 0;
```

```
en    h:mm a  12h=true      en-GB  HH:mm  12h=false      de  HH:mm  12h=false
en-US h:mm a  12h=true      he     H:mm   12h=false      ru  HH:mm  12h=false
```

## Phase 1 — viewer-local time format (closes #116)

### 1a. Split the two locales in `TZ_SCRIPT`

`Layout.java` conflates "which language are the words in" with "how does this region
write a clock". Keep `LANG` for the former, read the latter from the device:

```js
var HC = new Intl.DateTimeFormat(undefined, {hour:'numeric'}).resolvedOptions().hourCycle;
// opts gains hourCycle: HC; toLocaleString(LANG, opts) is otherwise unchanged
```

`CALENDAR_SCRIPT` keeps `LANG` untouched — month and weekday names are content. Its
`firstDay` already reads `navigator.language` (`Layout.java:113`), which is correct
and stays.

### 1b. Make the timezone picker optional

`Layout.java:48` reads `if (!picker) { return; }`. `adminBase.html` contains **zero**
`tz-picker` elements, so on `/me` and `/me/pending` the script exits before
`render()` ever runs and the raw placeholder text survives:

```html
<time data-utc="2026-08-20T13:00:00Z">2026-08-20T13:00:00Z UTC</time>
```

(`dashboard.html:28`, `pending.html:14`.) Fix by making the picker optional:

```js
if (picker) { /* fill ZONES options + attach change listener */ }
function render() {
  var tz = picker ? picker.value : (document.body.dataset.tz || detected);
  ...
}
```

This is a prerequisite, not a side quest: a host time-format preference (phase 2)
would be invisible on the dashboard while the script bails.

### 1c. Admin pages render in the host's stored timezone

With no picker on `/me` and `/me/pending`, falling back to the browser-detected zone
would silently show a travelling host their bookings in the trip's zone, with nothing
on screen naming it. The server knows better: `OwnerSettings.timezone` is the zone the
host's availability is defined in.

`adminBase.html` gains `data-tz` on `<body>`, and the script prefers it over the
detected zone (see 1b). The value reaches the template through a new
`@Named("owner") @RequestScoped` bean reading `CurrentOwner` + `OwnerSettings`,
mirroring `SiteInfo` (`web/SiteInfo.java:18`, used as `{inject:site.…}`). This avoids
adding a parameter to every admin template's signature, and phase 2 reuses the same
bean.

`manageBooking` is unaffected — it already passes `tzBar`, so it has a picker and a
visible zone label.

### Behaviour

| Viewer | Today | After phase 1 |
|---|---|---|
| Reporter / owner (`en` page, 24h device) | `2:30 PM` | `14:30` — issue closed |
| en-US invitee | `2:30 PM` | `2:30 PM` — unchanged |
| `de` page, 24h device | `14:30` | `14:30` — unchanged |
| `he` page, any device | Hebrew, 24h | Hebrew words, hour cycle from device |
| JS off, anyone | `14:30` server-side | unchanged |
| Host `/me` dashboard | `2026-08-20T13:00:00Z UTC` | formatted, host's stored zone |

### Consequences

- **No booking can change instant.** The script only rewrites `textContent`; the
  form's hidden `startUtc` keeps its absolute instant (already documented at
  `Layout.java:20-22`). A wrong format is cosmetic, never a mis-booking.
- **No server behaviour change beyond the `data-tz` attribute.** No DB, no migration,
  no new config. Rollback is a one-file revert plus the attribute.
- **One new cosmetic wart:** a German or Hebrew page on a US device forces 12h into a
  pattern built for 24h, giving `um 02:30 PM` (zero-padded hour). Rare combination,
  and it is the literal meaning of "follow my device".
- **Existing repaint flash is unchanged:** the server paints `HH:mm`, then the script
  repaints. US viewers briefly see `14:30` before `2:30 PM`. True today too.
- **Coverage is structural, not behavioural.** RestAssured cannot execute JS
  (CLAUDE.md), so tests assert script text and marker comments. The `hourCycle` output
  itself is verified by the bun run recorded under Evidence, not by CI.

### Tests

- `LayoutLocaleMarkerTest` keeps asserting `documentElement.lang` (the calendar still
  uses it) and gains an assertion that the time path resolves `hourCycle` from the
  device rather than from `LANG`.
- New assertions that `/me` and `/me/pending` serve a `data-utc` element **and** a
  `data-tz` body attribute, i.e. that formatting no longer depends on `#tz-picker`.
- An assertion that `data-tz` carries the owner's configured zone, not a hardcoded
  default.

## Phase 2 — host time-format preference

### Storage

`V25__owner_time_format.sql` (the tree ends at `V24__backfill_owner_settings.sql`;
the "V1…V10" line in CLAUDE.md is stale and should be corrected):

```sql
ALTER TABLE owner_settings ADD COLUMN time_format VARCHAR(8) NOT NULL DEFAULT 'auto';
```

`OwnerSettings.timeFormat` is a `String` validated against `Set.of("auto","h12","h23")`
on save, falling back to `auto` — the same shape as the existing `locale` guard at
`AdminResource.java:1140`. No enum, no `@Enumerated` mapping.

### Where it applies

| Surface | `auto` resolves to | mechanism |
|---|---|---|
| `/me` pages | the viewer's device | `data-hc` on `<body>`, alongside phase 1's `data-tz` |
| Host's own emails | the host's UI locale, via the `getLocalizedDateTimePattern` probe | `EmailService.format()` |
| Invitee pages, invitee emails | never reads the setting | unchanged |

Client side, the forced value simply overrides the probe:

```js
var forced = document.body.dataset.hc;
var HC = (forced && forced !== 'auto') ? forced : detectedHourCycle;
```

### Email patterns

`EmailService.format()` (`:883`) builds from `email_datetime_pattern`, one fixed
string per locale — all currently 24h (`msg_de:169`, `msg_he:169`). Forcing 12h by
rewriting `HH:mm` inside a translated pattern means string surgery on
`'בשעה' HH:mm`, which is fragile.

Instead add a sibling key `email_datetime_pattern_h12` per locale so translators own
both forms, and pick between the two. `h23` takes the existing key, `h12` takes the
new one, `auto` chooses with the locale probe. Per CLAUDE.md the `de` and `he` values
ship in the same change as the English `@Message` default.

The recipient seam already exists: `sendForKindLocaleAware` passes
`(role, locale, zone, greetingName, linkBooking)` per recipient
(`EmailService.java:336`), and `role` distinguishes host from invitee. `format()`
gains the resolved cycle; invitee copies always pass `auto`.

### Settings UI

One `<select name="timeFormat">` after the language select at
`settings.html:20`, plus a `@RestForm String timeFormat` parameter on
`AdminResource.updateSettings` (`:1123`).

```
Time format
  [ Automatic (from your language)  v ]   -> 20 August 2026 at 14:30
    24-hour                               -> 20 August 2026 at 14:30
    12-hour                               -> 20 August 2026 at 2:30 PM
```

Four `adm_` keys (label + three option labels) in the English defaults and in both
`adm_de` / `adm_he` property files.

No JS enhancement showing what `auto` resolves to on this device.
`// ponytail: add the live sample only if hosts actually ask what "Automatic" means.`

### Consequences

- **Zero behaviour change on upgrade.** The column defaults to `auto`, and `auto`
  reproduces exactly today's output on every surface.
- **Host emails only change when a host opts into `h12`.** Every locale's email is
  24h today, so nothing shifts for existing rows.
- **The setting cannot fix invitee-side complaints** — by design. If an invitee
  reports 12h, the answer is their device.
- `.ics` is untouched: it carries `yyyyMMdd'T'HHmmss'Z'` machine timestamps
  (`IcsBuilder.java:20`), not human-readable text.

### Tests

- Settings round-trip: POST `timeFormat=h12` → GET shows it selected.
- An unknown submitted value falls back to `auto` rather than persisting.
- `/me` serves `data-hc` matching the stored value; the public booking page serves
  **no** `data-hc` (the preference must not leak to invitees).
- `EmailService` picks `email_datetime_pattern_h12` for an `h12` host and the plain
  key for an `h23` host, while an invitee copy is unaffected by the host's setting.
- Key parity: every `@Message` key added to the bundles has a matching line in the
  `de` and `he` property files.

## Docs

Per CLAUDE.md, user-facing changes land on the `docs-site` branch in the same effort:
the configuration/usage page gains the **Time format** setting, and the note that
invitees always see times in their own device's convention.
