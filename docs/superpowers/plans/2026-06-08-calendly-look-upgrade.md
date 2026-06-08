# Calendly-Look Visual Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle calit's already-functional Pico UI into a polished Calendly-like look — card-on-canvas public pages with a left info panel, indigo accent, a left-sidebar admin shell, and a `<details>`-accordion meeting-type editor — working in light **and** dark mode.

**Architecture:** Pure presentation change on top of the shipped redesign. Switch the Pico WebJar to the indigo theme, drive everything off Pico CSS variables (+ a couple of `--calit-*` tokens) so dark mode flips automatically, and restructure templates. The admin sidebar is a new shared layout (`adminBase.html`) that every admin page includes instead of `base.html`+`adminNav.html`; the active nav item is a per-page literal so no Java signature changes. The only Java change is adding `ownerName` to the `book(...)` template method for the booking-page info panel.

**Tech Stack:** Quarkus 3.36, Qute templates (`@CheckedTemplate`), Pico CSS v2 `pico.indigo.min.css` (via `org.webjars.npm:picocss__pico:2.1.1`, served by `quarkus-web-dependency-locator`), native `<details>` accordion, inline SVG icons, RestAssured tests.

**Spec:** `docs/superpowers/specs/2026-06-08-calendly-look-upgrade-design.md`

---

## File Structure

**Create:**
- `src/main/resources/templates/adminBase.html` — shared admin layout: indigo `<head>`, `.admin-shell` with the left `<aside class="admin-side">` nav (brand, Create, icon items, logout) and `.admin-main` content wrapper. Params: `title`, `pendingCount`, `active`.

**Modify:**
- `src/main/resources/templates/base.html` — indigo stylesheet link; `<body class="canvas …">` with optional `bodyClass`.
- `src/main/resources/META-INF/resources/calit.css` — append: `--calit-canvas` token (light+dark), `.calit-card`, booking info/main grid, admin sidebar (+responsive +active), `<details>` accordion, `.loc-tiles`.
- `src/main/resources/templates/PublicResource/{confirmation,manage,cancelled,notReady}.html` — wrap content in `.calit-card`.
- `src/main/resources/templates/PublicResource/book.html` — two-pane `.book-shell` (info panel + main), `bodyClass="book-page"`, new `ownerName`.
- `src/main/resources/templates/AdminResource/{dashboard,pending,google,availability,dateOverrides,bookingFields,settings,meetingTypes}.html` — switch from `base`+`adminNav` to `adminBase`; pass `active`. `meetingTypes.html` also gets the accordion editor + location tiles.
- `src/main/java/com/calit/web/PublicResource.java` — add `String ownerName` to `book(...)` native method + its two call sites.
- `src/test/java/com/calit/web/StaticAssetsTest.java` — WebJar path jade → indigo.
- `src/test/java/com/calit/web/BookPageTest.java` — add an info-panel assertion.

**Delete (Task 8):**
- `src/main/resources/templates/adminNav.html` — once every admin page uses `adminBase`.

**Unchanged (verified):** `landing.html` (its article cards already pop on the canvas), `Layout.java`, `AdminResource.java`, `AdminMeetingTypesTest.java` (only checks `name="locationType"` + `GOOGLE_MEET`), all domain/migrations/services.

**Invariants that existing tests pin (must survive):**
- Book/reschedule slots stay `<input type="radio" name="startUtc" value="…">` + child `<time data-utc=…>`; `#calendar` mount; `.day-slots[data-date]` sections; `CALIT_CALENDAR` marker.
- Honeypot stays exactly `<input type="text" name="website" style="display:none" …>` (BookPageTest asserts both `name="website"` and `style="display:none"`).
- Turnstile widget `class="cf-turnstile"` + `data-sitekey` + `challenges.cloudflare.com/turnstile/v0/api.js` loader stay on the book page when enabled.
- `CALIT_TZ_REFORMAT` script, `id="tz-picker"`, `id="tz-label"`, `Times shown in:` stay on invitee pages.
- Button text exact: `Confirm booking` / `Request` / `Reschedule to selected time` / `Cancel this booking`; confirmation headings `You're booked` / `Request sent — pending owner approval`.
- Admin: `/logout` + `Log out` on `/admin` (LogoutTest); `Dashboard` on `/admin` (AdminAuthTest); `Connect Google` (AdminGoogleTest); `Approve`/`Decline`; all form field `name` attributes; `secret`/`inactive`/`approval`/`required`/`day off` badges; `name="locationType"` + `GOOGLE_MEET`.

---

## Task 1: Indigo theme + canvas/card foundation

**Files:**
- Modify: `src/test/java/com/calit/web/StaticAssetsTest.java:14`
- Modify: `src/main/resources/templates/base.html`
- Modify: `src/main/resources/META-INF/resources/calit.css` (append)

- [ ] **Step 1: Update the WebJar assertion to indigo (failing test)**

In `src/test/java/com/calit/web/StaticAssetsTest.java`, change the path on line 14 from:

```java
        given().when().get("/webjars/picocss__pico/css/pico.jade.min.css")
```

to:

```java
        given().when().get("/webjars/picocss__pico/css/pico.indigo.min.css")
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=StaticAssetsTest#picoWebjarIsServedVersionAgnostically`
Expected: PASS actually — the WebJar ships every color, so `pico.indigo.min.css` already serves 200 even before the template switch. (The webjar contains `pico.indigo.min.css`; verified.) This step confirms the indigo asset is available. If it unexpectedly 404s, the dependency is missing — stop and check `pom.xml`.

- [ ] **Step 3: Switch the stylesheet link + add the canvas body in `base.html`**

Overwrite `src/main/resources/templates/base.html`:

```html
{@java.lang.String title}
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <link rel="stylesheet" href="/webjars/picocss__pico/css/pico.indigo.min.css">
  <link rel="stylesheet" href="/calit.css">
  {#insert head}{/insert}
</head>
<body class="canvas {bodyClass ?: ''}">
  <main class="container">
    {#insert}{/insert}
  </main>
</body>
</html>
```

> `{bodyClass ?: ''}` is Qute's Elvis fallback: pages that don't pass `bodyClass` render an empty class suffix (no error); the booking page passes `bodyClass="book-page"` in Task 3.

- [ ] **Step 4: Append the foundation CSS to `calit.css`**

Append this block to the end of `src/main/resources/META-INF/resources/calit.css`:

```css
/* ============================================================
   Calendly-look upgrade — canvas, cards, shared tokens.
   Variable-driven so Pico's auto light/dark flips it for free.
   ============================================================ */
:root { --calit-canvas: #f3f4f6; }            /* soft grey behind cards (light) */
[data-theme="light"] { --calit-canvas: #f3f4f6; }
[data-theme="dark"]  { --calit-canvas: #11141b; }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) { --calit-canvas: #11141b; }
}

/* Soft canvas behind the centered card(s). */
body.canvas { background: var(--calit-canvas); }

/* The signature floating card. Surface + border + shadow all adapt with the theme. */
.calit-card {
  background: var(--pico-card-background-color);
  border: 1px solid var(--pico-muted-border-color);
  border-radius: 16px;
  box-shadow: 0 8px 30px rgba(16, 24, 40, .08);
  padding: clamp(1.25rem, 3vw, 2.25rem);
  margin-top: 2rem;
}

/* Booking page wants a wider column than the default 48rem reading width. */
body.book-page main.container { max-width: 58rem; }
```

- [ ] **Step 5: Run the static-assets test to verify it passes**

Run: `./mvnw test -Dtest=StaticAssetsTest`
Expected: PASS — indigo asset 200; `/calit.css` still contains `--calit`.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/calit/web/StaticAssetsTest.java src/main/resources/templates/base.html src/main/resources/META-INF/resources/calit.css
git commit -m "feat: switch Pico to indigo + canvas/card foundation"
```

---

## Task 2: Card-on-canvas wrap for the simple invitee pages

Wrap the single-block invitee pages (`confirmation`, `manage`, `cancelled`, `notReady`) in `.calit-card`. `landing.html` is intentionally left unchanged — its `.type-grid` articles already read as cards on the new canvas.

**Files:**
- Modify: `src/main/resources/templates/PublicResource/confirmation.html`
- Modify: `src/main/resources/templates/PublicResource/manage.html`
- Modify: `src/main/resources/templates/PublicResource/cancelled.html`
- Modify: `src/main/resources/templates/PublicResource/notReady.html`
- Test: `src/test/java/com/calit/web/{BookingPostTest,ManageBookingTest}.java` (existing — must stay green)

- [ ] **Step 1: Wrap `cancelled.html`**

Overwrite `src/main/resources/templates/PublicResource/cancelled.html`:

```html
{#include base title="Booking cancelled"}
  <article class="calit-card">
    <h1>Your booking is cancelled</h1>
    <p>The meeting has been cancelled and the calendar event removed.</p>
    <p><a role="button" href="/">Book a different time</a></p>
  </article>
{/include}
```

- [ ] **Step 2: Wrap `notReady.html`**

Overwrite `src/main/resources/templates/PublicResource/notReady.html`:

```html
{#include base title="Not available yet"}
  <article class="calit-card">
    <h1>This booking page isn't ready yet</h1>
    <p>The owner hasn't finished setting up calit. Please check back soon.</p>
  </article>
{/include}
```

- [ ] **Step 3: Wrap `confirmation.html`** (inner `<article>` → plain `<div>` to avoid a card-in-card; all headings, `tzBar`/`tzScript`, `data-utc` time, location logic, button-free content preserved)

Overwrite `src/main/resources/templates/PublicResource/confirmation.html`:

```html
{@com.calit.booking.Booking booking}
{@com.calit.domain.MeetingType type}
{@java.lang.Boolean pending}
{@java.lang.String location}
{@java.lang.String whenLabel}
{@java.lang.String startUtcIso}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{#include base title="{#if pending}Request sent{#else}Booking confirmed{/if}"}
  <article class="calit-card">
    {#if pending}
      <hgroup>
        <h1>Request sent — pending owner approval</h1>
        <p>Thanks, {booking.inviteeName}. Your requested time is held while {type.name}'s owner reviews it.
           You'll get an email once it's approved or declined.</p>
      </hgroup>
    {#else}
      <h1>You're booked, {booking.inviteeName}!</h1>
    {/if}
    {tzBar.raw}
    <div class="card-detail">
      <p><strong>When:</strong> <time data-utc="{startUtcIso}">{whenLabel}</time></p>
      {#if !pending}
        {#if type.locationType.name == 'GOOGLE_MEET'}
          <p><strong>Google Meet:</strong> <a href="{location}">{location}</a></p>
        {#else}
          <p><strong>Location:</strong> {location}</p>
        {/if}
      {/if}
    </div>
    <p>{#if pending}A request confirmation{#else}A confirmation{/if} email is on its way to {booking.inviteeEmail}.</p>
    <p><a href="/booking/{booking.manageToken}/manage">Need to change or cancel this booking?</a></p>
    {tzScript.raw}
  </article>
{/include}
```

- [ ] **Step 4: Wrap `manage.html`** (inner detail `<article>` → `<div>`; keep the booking-grid, radios, `data-utc`, both button texts)

Overwrite `src/main/resources/templates/PublicResource/manage.html`:

```html
{@com.calit.booking.Booking booking}
{@java.lang.String currentLabel}
{@java.lang.String currentUtcIso}
{@java.util.List<com.calit.web.PublicResource$DaySlots> days}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{@java.lang.String calScript}
{#include base title="Manage booking"}
  <article class="calit-card">
    <h1>Manage your booking</h1>
    {tzBar.raw}
    <div class="card-detail">
      <p><strong>Currently:</strong> <time data-utc="{currentUtcIso}">{currentLabel}</time></p>
      <p><strong>For:</strong> {booking.inviteeName} ({booking.inviteeEmail})</p>
    </div>

    <h2>Reschedule</h2>
    {#if days.isEmpty()}
      <p>No alternative times available.</p>
    {#else}
      <form method="post" action="/booking/{booking.manageToken}/reschedule">
        <div class="booking-grid">
          <div id="calendar" class="calendar"></div>
          <div class="time-column">
            {#for day in days}
              <section class="day-slots" data-date="{day.isoDate}">
                <h3>{day.label}</h3>
                {#for slot in day.slots}
                  <label class="slot">
                    <input type="radio" name="startUtc" value="{slot.startUtc}" required>
                    <time data-utc="{slot.startUtc}" data-time-only="1">{slot.label}</time>
                  </label>
                {/for}
              </section>
            {/for}
          </div>
        </div>
        <button type="submit">Reschedule to selected time</button>
      </form>
    {/if}

    <h2>Cancel</h2>
    <form method="post" action="/booking/{booking.manageToken}/cancel">
      <button type="submit" class="secondary">Cancel this booking</button>
    </form>
    {tzScript.raw}
    {calScript.raw}
  </article>
{/include}
```

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `./mvnw test -Dtest=BookingPostTest,ManageBookingTest`
Expected: PASS — `You're booked`, `Request sent — pending owner approval`, `Reschedule to selected time`, `Cancel this booking`, `data-utc`, `Times shown in:` all still present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/PublicResource/confirmation.html src/main/resources/templates/PublicResource/manage.html src/main/resources/templates/PublicResource/cancelled.html src/main/resources/templates/PublicResource/notReady.html
git commit -m "feat: card-on-canvas for confirmation/manage/cancelled/notReady"
```

---

## Task 3: Booking page — left info panel + two-pane card

**Files:**
- Modify: `src/main/java/com/calit/web/PublicResource.java:45-55` (book native method), `:120-122` (book handler call), `:169-172` (submitBooking catch call)
- Modify: `src/main/resources/templates/PublicResource/book.html`
- Modify: `src/main/resources/META-INF/resources/calit.css` (append)
- Test: `src/test/java/com/calit/web/BookPageTest.java` (add an assertion; keep all existing green)

- [ ] **Step 1: Add the info-panel assertion to `BookPageTest` (failing test)**

In `src/test/java/com/calit/web/BookPageTest.java`, add this method to the class (the `seed()` helper sets `ownerName = "Owner"`):

```java
    @Test
    void bookPageRendersLeftInfoPanelWithHostAndDuration() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        given()
            .when().get("/book/book-page")
            .then()
                .statusCode(200)
                .body(containsString("class=\"book-info\""))   // left info panel
                .body(containsString("Owner"))                  // host name from OwnerSettings
                .body(containsString("60 min"))                 // clock-icon duration line
                .body(containsString("id=\"calendar\""));       // picker still present
    }
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `./mvnw test -Dtest=BookPageTest#bookPageRendersLeftInfoPanelWithHostAndDuration`
Expected: FAIL — `class="book-info"` / `60 min` not rendered yet (current page shows `60 minutes`, no `book-info`).

- [ ] **Step 3: Add `ownerName` to the `book` native method**

In `src/main/java/com/calit/web/PublicResource.java`, change the `book` declaration (lines 45–55) from:

```java
        public static native TemplateInstance book(
                MeetingType type,
                java.util.List<PublicResource.DaySlots> days,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String tzBar,
                String tzScript,
                String calScript,
                boolean turnstileEnabled,
                String turnstileSiteKey,
                boolean googleConnected);
```

to (append `String ownerName`):

```java
        public static native TemplateInstance book(
                MeetingType type,
                java.util.List<PublicResource.DaySlots> days,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String tzBar,
                String tzScript,
                String calScript,
                boolean turnstileEnabled,
                String turnstileSiteKey,
                boolean googleConnected,
                String ownerName);
```

- [ ] **Step 4: Pass `ownerName` at both `book(...)` call sites**

Both call sites sit after the `if (OwnerSettings.get() == null) return Templates.notReady();` guard, so `OwnerSettings.get()` is non-null.

In `book()` (lines 120–122) change:

```java
        return Templates.book(type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected());
```

to:

```java
        return Templates.book(type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(),
                              OwnerSettings.get().ownerName);
```

In `submitBooking(...)`'s catch block (lines 169–172) change:

```java
            return Templates.book(type, daySlots(type), BookingField.formFor(type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected());
```

to:

```java
            return Templates.book(type, daySlots(type), BookingField.formFor(type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(),
                                  OwnerSettings.get().ownerName);
```

- [ ] **Step 5: Rewrite `book.html` with the two-pane info+main card**

Overwrite `src/main/resources/templates/PublicResource/book.html`. New: `{@java.lang.String ownerName}` param, `bodyClass="book-page"`, `.book-shell` grid (`.book-info` left + `.book-main` right). The existing `.booking-grid`, radios, `data-utc` times, honeypot (exact `style="display:none"`), Turnstile, name/email/custom fields, and button text are preserved verbatim inside `.book-main`:

```html
{@com.calit.domain.MeetingType type}
{@java.util.List<com.calit.web.PublicResource$DaySlots> days}
{@java.util.List<com.calit.domain.BookingField> fields}
{@java.lang.String error}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{@java.lang.String calScript}
{@java.lang.Boolean turnstileEnabled}
{@java.lang.String turnstileSiteKey}
{@java.lang.Boolean googleConnected}
{@java.lang.String ownerName}
{#include base title="Book — {type.name}" bodyClass="book-page"}
  {#head}
    {#if turnstileEnabled}
    <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
    {/if}
  {/head}

  <p><a href="/">&larr; All meeting types</a></p>

  <article class="calit-card book-shell">
    <div class="book-info">
      <span class="book-chip">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="17" rx="2"/><path d="M3 9h18M8 2v4M16 2v4"/></svg>
      </span>
      {#if ownerName}<p class="book-host">{ownerName}</p>{/if}
      <h1 class="book-title">{type.name}</h1>
      <p class="book-meta">
        <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>
        </svg>
        {type.durationMinutes} min
      </p>
      {#if type.description}<p class="book-desc">{type.description}</p>{/if}
      {#if type.locationType.name == 'GOOGLE_MEET'}
        {#if googleConnected}<p class="book-loc"><strong>Location:</strong> Google Meet — link sent after booking</p>{/if}
      {#else}
        <p class="book-loc"><strong>Location:</strong> {type.locationDetail}</p>
      {/if}
      {#if type.requiresApproval}
        <p class="book-desc">This meeting requires owner approval — you'll send a request and be notified once it's approved.</p>
      {/if}
    </div>

    <div class="book-main">
      <h2>Select a Date &amp; Time</h2>
      {#if error}<p class="err">{error}</p>{/if}
      {#if days.isEmpty()}
        <p>No available times in the next two weeks.</p>
      {#else}
        {tzBar.raw}
        <form method="post" action="/book/{type.slug}">
          <div class="booking-grid">
            <div id="calendar" class="calendar"></div>
            <div class="time-column">
              {#for day in days}
                <section class="day-slots" data-date="{day.isoDate}">
                  <h3>{day.label}</h3>
                  {#for slot in day.slots}
                    <label class="slot">
                      <input type="radio" name="startUtc" value="{slot.startUtc}" required>
                      <time data-utc="{slot.startUtc}" data-time-only="1">{slot.label}</time>
                    </label>
                  {/for}
                </section>
              {/for}
            </div>
          </div>

          <label>Your name <input type="text" name="inviteeName" required></label>
          <label>Your email <input type="email" name="inviteeEmail" required></label>

          {#for f in fields}
            <label>{f.label}
              {#if f.type.name == 'LONG_TEXT'}
                <textarea name="answers.{f.fieldKey}" {#if f.required}required{/if}></textarea>
              {#else if f.type.name == 'EMAIL'}
                <input type="email" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
              {#else if f.type.name == 'PHONE'}
                <input type="tel" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
              {#else if f.type.name == 'NUMBER'}
                <input type="number" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
              {#else}
                <input type="text" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
              {/if}
            </label>
          {/for}

          {! Honeypot (always present + hidden). A non-empty `website` → rejected server-side. !}
          <input type="text" name="website" style="display:none" tabindex="-1" autocomplete="off">

          {#if turnstileEnabled}
          <div class="cf-turnstile" data-sitekey="{turnstileSiteKey}"></div>
          {/if}

          {#if type.requiresApproval}
            <button type="submit">Request</button>
          {#else}
            <button type="submit">Confirm booking</button>
          {/if}
        </form>
      {/if}
      {tzScript.raw}
      {calScript.raw}
    </div>
  </article>
{/include}
```

- [ ] **Step 6: Append the booking-card CSS to `calit.css`**

Append to `src/main/resources/META-INF/resources/calit.css`:

```css
/* --- Booking page: left info panel + right picker (Calendly two-pane) --- */
.book-shell { display: grid; grid-template-columns: 1fr; gap: 1.5rem; }
@media (min-width: 48rem) {
  .book-shell { grid-template-columns: minmax(13rem, 17rem) 1fr; gap: 2rem; }
  .book-info { border-right: 1px solid var(--pico-muted-border-color); padding-right: 2rem; }
}
.book-chip {
  display: inline-flex; align-items: center; justify-content: center;
  width: 2.5rem; height: 2.5rem; border-radius: .6rem;
  background: var(--pico-primary); color: var(--pico-primary-inverse);
  margin-bottom: .75rem;
}
.book-chip svg { width: 1.3rem; height: 1.3rem; }
.book-host { color: var(--pico-muted-color); font-weight: 600; margin: 0 0 .25rem; }
.book-title { font-size: 1.6rem; margin: 0 0 .9rem; }
.book-meta { display: flex; align-items: center; gap: .5rem; font-weight: 600; color: var(--pico-color); margin: 0 0 .9rem; }
.book-meta .ic { width: 1.15rem; height: 1.15rem; color: var(--pico-muted-color); }
.book-desc { color: var(--pico-muted-color); font-size: .95rem; margin: 0 0 .75rem; }
.book-loc { font-size: .95rem; margin: 0 0 .5rem; }
.book-main h2 { font-size: 1.2rem; margin: 0 0 1rem; }
```

- [ ] **Step 7: Run the booking tests to verify they pass**

Run: `./mvnw test -Dtest=BookPageTest,BookPageTurnstileEnabledTest,BookingPostTest`
Expected: PASS — new info-panel assertion green; `Confirm booking`, `Request`, `name="website"`, `style="display:none"`, `cf-turnstile`/`data-sitekey` (turnstile test), `CALIT_CALENDAR`, `id="calendar"`, `class="day-slots"`, `name="startUtc"`, `Times shown in:` all still present; `60 min` shown.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/calit/web/PublicResource.java src/main/resources/templates/PublicResource/book.html src/main/resources/META-INF/resources/calit.css src/test/java/com/calit/web/BookPageTest.java
git commit -m "feat: booking page left info panel + two-pane card"
```

---

## Task 4: Admin sidebar shell (`adminBase.html`) + admin CSS

Create the shared admin layout and its CSS. No page is migrated yet — Tasks 5–8 do that — so the full suite stays green at the end of this task (the new template is simply unused so far).

**Files:**
- Create: `src/main/resources/templates/adminBase.html`
- Modify: `src/main/resources/META-INF/resources/calit.css` (append)

- [ ] **Step 1: Create `adminBase.html`**

Create `src/main/resources/templates/adminBase.html`. Params: `title`, `pendingCount`, `active`. The `active` literal each page passes highlights its nav item; the Pending badge reuses `pendingCount`:

```html
{@java.lang.String title}
{@java.lang.Long pendingCount}
{@java.lang.String active}
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <link rel="stylesheet" href="/webjars/picocss__pico/css/pico.indigo.min.css">
  <link rel="stylesheet" href="/calit.css">
</head>
<body class="admin-canvas">
  <div class="admin-shell">
    <aside class="admin-side">
      <div class="admin-brand"><span class="chip">c</span> calit</div>
      <a class="admin-create" href="/admin/meeting-types">+ Create</a>
      <nav class="admin-nav">
        <a href="/admin" class="{#if active == 'dashboard'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>Dashboard</a>
        <a href="/admin/pending" class="{#if active == 'pending'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>Pending{#if pendingCount && pendingCount > 0} <span class="badge">{pendingCount}</span>{/if}</a>
        <a href="/admin/meeting-types" class="{#if active == 'meetingTypes'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 7h16M4 12h16M4 17h10"/></svg>Meeting types</a>
        <a href="/admin/availability" class="{#if active == 'availability'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="4" width="18" height="17" rx="2"/><path d="M3 9h18M8 2v4M16 2v4"/></svg>Availability</a>
        <a href="/admin/date-overrides" class="{#if active == 'dateOverrides'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="4" width="18" height="17" rx="2"/><path d="M8 2v4M16 2v4M9 14l2 2 4-4"/></svg>Date overrides</a>
        <a href="/admin/booking-fields" class="{#if active == 'bookingFields'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M5 3h14v18l-7-4-7 4z"/></svg>Booking fields</a>
        <a href="/admin/settings" class="{#if active == 'settings'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="3"/><path d="M19.4 13a7.8 7.8 0 0 0 0-2l2-1.5-2-3.5-2.4 1a7.6 7.6 0 0 0-1.7-1L14.8 3H9.2l-.5 2.5a7.6 7.6 0 0 0-1.7 1l-2.4-1-2 3.5 2 1.5a7.8 7.8 0 0 0 0 2l-2 1.5 2 3.5 2.4-1a7.6 7.6 0 0 0 1.7 1l.5 2.5h5.6l.5-2.5a7.6 7.6 0 0 0 1.7-1l2.4 1 2-3.5z"/></svg>Settings</a>
        <a href="/admin/google" class="{#if active == 'google'}active{/if}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 8l9 6 9-6"/></svg>Google</a>
      </nav>
      <div class="admin-logout">
        <a href="/logout">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg>Log out</a>
      </div>
    </aside>
    <div class="admin-main">
      <main class="container">
        {#insert}{/insert}
      </main>
    </div>
  </div>
</body>
</html>
```

- [ ] **Step 2: Append the admin-shell CSS to `calit.css`**

Append to `src/main/resources/META-INF/resources/calit.css`:

```css
/* --- Admin shell: fixed left sidebar (Calendly-style) --- */
body.admin-canvas { background: var(--calit-canvas); }
.admin-shell { display: grid; grid-template-columns: 1fr; min-height: 100vh; }
@media (min-width: 48rem) { .admin-shell { grid-template-columns: 250px 1fr; } }

.admin-side {
  background: var(--pico-card-background-color);
  border-right: 1px solid var(--pico-muted-border-color);
  padding: 1rem .75rem; display: flex; flex-direction: column;
}
.admin-brand { display: flex; align-items: center; gap: .5rem; font-weight: 800; padding: .4rem .6rem 1rem; }
.admin-brand .chip {
  width: 1.7rem; height: 1.7rem; border-radius: .5rem;
  background: var(--pico-primary); color: var(--pico-primary-inverse);
  display: flex; align-items: center; justify-content: center; font-size: .85rem;
}
.admin-create {
  display: flex; align-items: center; justify-content: center; gap: .4rem;
  border: 1px solid var(--pico-primary); color: var(--pico-primary);
  font-weight: 700; padding: .55rem; border-radius: .6rem; margin-bottom: 1rem; text-decoration: none;
}
.admin-nav { display: flex; flex-direction: column; gap: .15rem; }
.admin-nav a {
  display: flex; align-items: center; gap: .6rem; padding: .55rem .7rem; border-radius: .55rem;
  color: var(--pico-color); text-decoration: none; font-weight: 600; font-size: .92rem; white-space: nowrap;
}
.admin-nav a svg { width: 1.1rem; height: 1.1rem; color: var(--pico-muted-color); flex: none; }
.admin-nav a.active { background: var(--pico-primary-background); color: var(--pico-primary-inverse); }
.admin-nav a.active svg { color: var(--pico-primary-inverse); }
.admin-nav .badge { margin-left: auto; }
.admin-logout { margin-top: auto; border-top: 1px solid var(--pico-muted-border-color); padding-top: .75rem; }
.admin-logout a { display: flex; align-items: center; gap: .6rem; padding: .55rem .7rem; color: var(--pico-muted-color); text-decoration: none; font-weight: 600; font-size: .92rem; }
.admin-logout a svg { width: 1.1rem; height: 1.1rem; color: var(--pico-muted-color); }
.admin-main { min-width: 0; }
.admin-main .container { max-width: 60rem; }

/* Collapse the sidebar to a horizontal scrolling top bar on narrow screens (no JS). */
@media (max-width: 47.99rem) {
  .admin-side { flex-direction: row; align-items: center; overflow-x: auto;
    border-right: 0; border-bottom: 1px solid var(--pico-muted-border-color); }
  .admin-brand, .admin-create { display: none; }
  .admin-nav { flex-direction: row; }
  .admin-logout { margin: 0 0 0 auto; border: 0; padding: 0; }
}
```

- [ ] **Step 3: Verify the project still compiles + full suite passes**

Run: `./mvnw test`
Expected: PASS — `adminBase.html` is valid but not yet referenced; nothing changed behaviourally.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/adminBase.html src/main/resources/META-INF/resources/calit.css
git commit -m "feat: admin sidebar layout (adminBase) + CSS"
```

---

## Task 5: Migrate dashboard, pending, google to `adminBase`

**Files:**
- Modify: `src/main/resources/templates/AdminResource/dashboard.html`
- Modify: `src/main/resources/templates/AdminResource/pending.html`
- Modify: `src/main/resources/templates/AdminResource/google.html`
- Test: `src/test/java/com/calit/web/{AdminAuthTest,LogoutTest,AdminPendingTest,AdminGoogleTest}.java` (existing — must stay green)

- [ ] **Step 1: Migrate `dashboard.html`**

Overwrite `src/main/resources/templates/AdminResource/dashboard.html`:

```html
{@java.util.List<com.calit.booking.Booking> upcoming}
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — Dashboard" pendingCount=pendingCount active="dashboard"}
  <h1>Dashboard</h1>
  <h2>Upcoming bookings</h2>
  {#if upcoming.isEmpty()}
    <p>No upcoming bookings.</p>
  {#else}
    {#for b in upcoming}
      <article>
        <p><strong>{b.inviteeName}</strong> ({b.inviteeEmail})</p>
        <p>{b.startUtc} UTC</p>
        <p><a href="{b.meetLink}">{b.meetLink}</a></p>
      </article>
    {/for}
  {/if}
{/include}
```

- [ ] **Step 2: Migrate `pending.html`** (badge count comes from the list size, as today)

Overwrite `src/main/resources/templates/AdminResource/pending.html`:

```html
{@java.util.List<com.calit.booking.Booking> pending}
{#include adminBase title="Admin — Pending approvals" pendingCount=pending.size() active="pending"}
  <h1>Pending approvals</h1>
  {#if pending.isEmpty()}
    <p>No requests are awaiting approval.</p>
  {#else}
    {#for b in pending}
      <article>
        <p><strong>{b.inviteeName}</strong> ({b.inviteeEmail})</p>
        <p>{b.startUtc} UTC</p>
        <form method="post" action="/admin/bookings/{b.id}/approve" style="display:inline">
          <button type="submit">Approve</button>
        </form>
        <form method="post" action="/admin/bookings/{b.id}/decline" style="display:inline">
          <button type="submit" class="secondary">Decline</button>
        </form>
      </article>
    {/for}
  {/if}
{/include}
```

- [ ] **Step 3: Migrate `google.html`**

Overwrite `src/main/resources/templates/AdminResource/google.html`:

```html
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — Google" pendingCount=pendingCount active="google"}
  <h1>Google Calendar</h1>
  <p>Connect your Google account so calit can read your busy times and create Meet events.</p>
  <p><a role="button" href="/api/google/connect">Connect Google</a></p>

  <h2>Calendars</h2>
  <p>After connecting, choose which calendars to read for conflicts and which one to write events to.</p>
  <ul>
    <li><a href="/api/google/calendars">List my calendars</a></li>
  </ul>
{/include}
```

- [ ] **Step 4: Run the affected tests to verify they pass**

Run: `./mvnw test -Dtest=AdminAuthTest,LogoutTest,AdminPendingTest,AdminGoogleTest`
Expected: PASS — `/admin` shows `Dashboard`, `/logout` + `Log out`; pending shows `Approve`/`Decline`; google shows `Connect Google`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/AdminResource/dashboard.html src/main/resources/templates/AdminResource/pending.html src/main/resources/templates/AdminResource/google.html
git commit -m "feat: migrate dashboard/pending/google to sidebar shell"
```

---

## Task 6: Migrate availability, dateOverrides, bookingFields, settings to `adminBase`

**Files:**
- Modify: `src/main/resources/templates/AdminResource/availability.html`
- Modify: `src/main/resources/templates/AdminResource/dateOverrides.html`
- Modify: `src/main/resources/templates/AdminResource/bookingFields.html`
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Test: `src/test/java/com/calit/web/{AdminAvailabilityTest,AdminDateOverridesTest,AdminBookingFieldsTest,AdminSettingsTest}.java` (existing — must stay green)

- [ ] **Step 1: Migrate `availability.html`**

Overwrite `src/main/resources/templates/AdminResource/availability.html`:

```html
{@java.util.List<com.calit.domain.AvailabilityRule> rules}
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — Availability" pendingCount=pendingCount active="availability"}
  <h1>Availability (work hours)</h1>

  {#for r in rules}
    <article>
      <p><strong>{r.dayOfWeek}</strong> {r.startTime} &ndash; {r.endTime}
        &middot; {#if r.meetingTypeId}type #{r.meetingTypeId}{#else}global{/if}</p>
      <form method="post" action="/admin/availability/{r.id}/delete">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}

  <h2>Add a rule</h2>
  <form method="post" action="/admin/availability">
    <label>Day
      <select name="dayOfWeek">
        <option>MONDAY</option><option>TUESDAY</option><option>WEDNESDAY</option>
        <option>THURSDAY</option><option>FRIDAY</option><option>SATURDAY</option><option>SUNDAY</option>
      </select>
    </label>
    <label>Start <input type="time" name="startTime" value="09:00" required></label>
    <label>End <input type="time" name="endTime" value="17:00" required></label>
    <label>Applies to
      <select name="meetingTypeId">
        <option value="">All (global)</option>
        {#for t in types}<option value="{t.id}">{t.name}</option>{/for}
      </select>
    </label>
    <button type="submit">Add rule</button>
  </form>
{/include}
```

- [ ] **Step 2: Migrate `dateOverrides.html`**

Overwrite `src/main/resources/templates/AdminResource/dateOverrides.html`:

```html
{@java.util.List<com.calit.domain.DateOverride> overrides}
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — Date overrides" pendingCount=pendingCount active="dateOverrides"}
  <h1>Date-specific overrides</h1>
  <p>An override REPLACES that date's normal work hours. Adding windows sets the only bookable times;
     leaving the windows empty marks the whole date as a day off.</p>

  {#for o in overrides}
    <article>
      <p><strong>{o.overrideDate}</strong> &middot; {#if o.meetingTypeId}type #{o.meetingTypeId}{#else}global{/if}</p>
      {#if o.windows.isEmpty()}
        <p><span class="badge">day off</span> (blocked)</p>
      {#else}
        <ul>{#for w in o.windows}<li>{w.startTime} &ndash; {w.endTime}</li>{/for}</ul>
      {/if}
      <form method="post" action="/admin/date-overrides/{o.id}/delete">
        <button type="submit">Delete</button>
      </form>
    </article>
  {/for}

  <h2>Add an override</h2>
  <form method="post" action="/admin/date-overrides">
    <label>Date <input type="date" name="date" required></label>
    <label>Applies to
      <select name="meetingTypeId">
        <option value="">All (global)</option>
        {#for t in types}<option value="{t.id}">{t.name}</option>{/for}
      </select>
    </label>
    {! Up to three window rows; leave all blank for a day off. Repeated names → parallel arrays. !}
    <fieldset>
      <legend>Bookable windows (leave all blank = day off)</legend>
      <label>Window 1 <input type="time" name="windowStart"> to <input type="time" name="windowEnd"></label>
      <label>Window 2 <input type="time" name="windowStart"> to <input type="time" name="windowEnd"></label>
      <label>Window 3 <input type="time" name="windowStart"> to <input type="time" name="windowEnd"></label>
    </fieldset>
    <button type="submit">Save override</button>
  </form>
{/include}
```

- [ ] **Step 3: Migrate `bookingFields.html`**

Overwrite `src/main/resources/templates/AdminResource/bookingFields.html`:

```html
{@java.util.List<com.calit.domain.BookingField> fields}
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — Booking fields" pendingCount=pendingCount active="bookingFields"}
  <h1>Custom booking fields</h1>
  <p>Full name and email are always asked. These are the extra fields shown on the booking form.</p>

  {#for f in fields}
    <article>
      <h3>{f.label}
        {#if f.required}<span class="badge">required</span>{/if}
      </h3>
      <p><code>{f.fieldKey}</code> &middot; {f.type} &middot; position {f.position}
        &middot; {#if f.meetingTypeId}type #{f.meetingTypeId}{#else}global{/if}</p>
      <form method="post" action="/admin/booking-fields/{f.id}/delete" style="display:inline">
        <button type="submit">Delete</button>
      </form>
    </article>
  {/for}

  <h2>Add a field</h2>
  <form method="post" action="/admin/booking-fields">
    <label>Label <input type="text" name="label" required></label>
    <label>Field key <input type="text" name="fieldKey" required></label>
    <label>Type
      <select name="type">
        <option>SHORT_TEXT</option><option>LONG_TEXT</option><option>EMAIL</option>
        <option>PHONE</option><option>NUMBER</option>
      </select>
    </label>
    <label><input type="checkbox" name="required"> Required</label>
    <label>Position <input type="number" name="position" value="0" required></label>
    <label>Applies to
      <select name="meetingTypeId">
        <option value="">All (global)</option>
        {#for t in types}<option value="{t.id}">{t.name}</option>{/for}
      </select>
    </label>
    <button type="submit">Add field</button>
  </form>
{/include}
```

- [ ] **Step 4: Migrate `settings.html`**

Overwrite `src/main/resources/templates/AdminResource/settings.html`:

```html
{@com.calit.domain.OwnerSettings settings}
{@java.lang.Integer reminderLeadMinutes}
{@java.lang.Long pendingCount}
{@java.util.List<java.lang.String> zones}
{#include adminBase title="Admin — Settings" pendingCount=pendingCount active="settings"}
  <h1>Owner settings</h1>
  <form method="post" action="/admin/settings">
    <label>Name
      <input type="text" name="ownerName" required
             value="{#if settings}{settings.ownerName}{/if}"></label>
    <label>Email
      <input type="email" name="ownerEmail" required
             value="{#if settings}{settings.ownerEmail}{/if}"></label>
    <label>Timezone
      <select name="timezone" required>
        {#for z in zones}
          {#if settings}
          <option value="{z}"{#if settings.timezone == z} selected{/if}>{z}</option>
          {#else}
          <option value="{z}"{#if z == 'Europe/Amsterdam'} selected{/if}>{z}</option>
          {/if}
        {/for}
      </select>
    </label>
    <label><input type="checkbox" name="ownerNotificationsEnabled"
             {#if settings && settings.ownerNotificationsEnabled}checked{/if}>
      Send me (the owner) email notifications for bookings</label>
    <button type="submit">Save</button>
  </form>

  <p><strong>Reminder lead:</strong> {reminderLeadMinutes} minutes before the meeting
     <em>(set via the REMINDER_LEAD_MINUTES environment variable)</em></p>
{/include}
```

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `./mvnw test -Dtest=AdminAvailabilityTest,AdminDateOverridesTest,AdminBookingFieldsTest,AdminSettingsTest`
Expected: PASS — every form field `name`, the `MONDAY`/`TUESDAY` options, `day off`/`required` badges, timezone select, and all button text still present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/AdminResource/availability.html src/main/resources/templates/AdminResource/dateOverrides.html src/main/resources/templates/AdminResource/bookingFields.html src/main/resources/templates/AdminResource/settings.html
git commit -m "feat: migrate availability/date-overrides/booking-fields/settings to sidebar shell"
```

---

## Task 7: Meeting-type accordion editor + location tiles

Restyle the **Create meeting type** form into native `<details>` accordion sections with a radio-tile location picker, and migrate the page to `adminBase`. The existing list of meeting types (with badges + toggle/delete) stays on top.

**Files:**
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html`
- Modify: `src/main/resources/META-INF/resources/calit.css` (append)
- Test: `src/test/java/com/calit/web/AdminMeetingTypesTest.java` (add an accordion assertion; existing checks stay green — they only require `name="locationType"` + `GOOGLE_MEET`)

- [ ] **Step 1: Add an accordion assertion to `AdminMeetingTypesTest` (failing test)**

In `src/test/java/com/calit/web/AdminMeetingTypesTest.java`, add this method:

```java
    @Test
    void createFormUsesAccordionSectionsAndLocationTiles() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("<details"))                 // accordion sections
                .body(containsString("class=\"loc-tiles\""))      // location picker tiles
                .body(containsString("type=\"radio\" name=\"locationType\"")) // tiles are radios
                .body(containsString("value=\"GOOGLE_MEET\""));   // a tile per LocationType
    }
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#createFormUsesAccordionSectionsAndLocationTiles`
Expected: FAIL — current page uses a `<select name="locationType">`, no `<details>` / `loc-tiles`.

- [ ] **Step 3: Rewrite `meetingTypes.html`** (adminBase + accordion + location tiles; every existing field `name` preserved; `secret`/`inactive`/`approval` badges and toggle/delete forms kept)

Overwrite `src/main/resources/templates/AdminResource/meetingTypes.html`:

```html
{@java.util.List<com.calit.domain.MeetingType> types}
{@com.calit.domain.MeetingType.LocationType[] locationTypes}
{@java.lang.Long pendingCount}
{#include adminBase title="Admin — Meeting types" pendingCount=pendingCount active="meetingTypes"}
  <h1>Meeting types</h1>

  {#for t in types}
    <article>
      <h3>{t.name}
        {#if t.secret}<span class="badge">secret</span>{/if}
        {#if !t.active}<span class="badge">inactive</span>{/if}
        {#if t.requiresApproval}<span class="badge">approval</span>{/if}
      </h3>
      <p>/{t.slug} &middot; {t.durationMinutes} min &middot; {t.locationType}{#if t.locationDetail} ({t.locationDetail}){/if}</p>
      <p>min notice {t.minNoticeMinutes} min &middot; horizon {t.horizonDays} days{#if t.slotIntervalMinutes} &middot; slot interval {t.slotIntervalMinutes} min{/if}</p>
      <form method="post" action="/admin/meeting-types/{t.id}/toggle" style="display:inline">
        <button type="submit" class="secondary">{#if t.active}Deactivate{#else}Activate{/if}</button>
      </form>
      <form method="post" action="/admin/meeting-types/{t.id}/delete" style="display:inline">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}

  <h2>Create meeting type</h2>
  <form method="post" action="/admin/meeting-types" class="editor">
    <details open>
      <summary>
        <svg class="sec-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 7h16M4 12h16M4 17h10"/></svg>
        Basics
      </summary>
      <div class="sec-body">
        <label>Name <input type="text" name="name" required></label>
        <label>Slug <input type="text" name="slug" required></label>
        <label><input type="checkbox" name="secret"> Secret (hidden from public landing)</label>
        <label><input type="checkbox" name="requiresApproval"> Requires owner approval (hold as pending)</label>
      </div>
    </details>

    <details>
      <summary>
        <svg class="sec-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
        Duration
      </summary>
      <div class="sec-body">
        <label>Duration (minutes) <input type="number" name="durationMinutes" value="30" required></label>
        <label>Slot interval (minutes, blank = back-to-back)
          <input type="number" name="slotIntervalMinutes"></label>
      </div>
    </details>

    <details>
      <summary>
        <svg class="sec-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 21s7-6 7-11a7 7 0 1 0-14 0c0 5 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/></svg>
        Location
      </summary>
      <div class="sec-body">
        <div class="loc-tiles">
          {#for lt in locationTypes}
            <label class="tile">
              <input type="radio" name="locationType" value="{lt}"{#if lt.name == 'GOOGLE_MEET'} checked{/if}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10l5-3v10l-5-3"/></svg>
              {lt}
            </label>
          {/for}
        </div>
        <small class="loc-note">Pick where the meeting happens. Google Meet generates a link after booking (requires Google connected); for the others, fill in the detail below.</small>
        <label>Location detail (phone number / address / custom text; ignored for Google Meet)
          <input type="text" name="locationDetail"></label>
      </div>
    </details>

    <details>
      <summary>
        <svg class="sec-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="17" rx="2"/><path d="M3 9h18M8 2v4M16 2v4"/></svg>
        Scheduling limits
      </summary>
      <div class="sec-body">
        <label>Min scheduling notice (minutes)
          <input type="number" name="minNoticeMinutes" value="0" required></label>
        <label>Booking horizon (days)
          <input type="number" name="horizonDays" value="60" required></label>
        <p><a href="/admin/availability">Edit weekly work hours &rarr;</a></p>
      </div>
    </details>

    <button type="submit">Create</button>
  </form>
{/include}
```

- [ ] **Step 4: Append the accordion + location-tiles CSS to `calit.css`**

Append to `src/main/resources/META-INF/resources/calit.css`:

```css
/* --- Meeting-type editor: native <details> accordion (Calendly-style) --- */
.editor details {
  border: 1px solid var(--pico-muted-border-color); border-radius: 12px;
  margin-bottom: .75rem; background: var(--pico-card-background-color);
}
.editor summary {
  list-style: none; cursor: pointer; display: flex; align-items: center; gap: .6rem;
  padding: 1rem 1.1rem; font-weight: 700;
}
.editor summary::-webkit-details-marker { display: none; }
.editor summary::after { content: '⌄'; margin-left: auto; color: var(--pico-muted-color); transition: transform .15s; }
.editor details[open] summary::after { transform: rotate(180deg); }
.editor summary .sec-ic { width: 1.15rem; height: 1.15rem; color: var(--pico-muted-color); }
.editor .sec-body { padding: 0 1.1rem 1.1rem; }
.editor .sec-body > label { margin-top: .5rem; }

/* Location picker tiles (radios styled via :has — Pico v2 targets modern browsers). */
.loc-tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr)); gap: .6rem; margin-bottom: .6rem; }
.loc-tiles .tile {
  border: 1px solid var(--pico-muted-border-color); border-radius: 10px; padding: .85rem .5rem;
  text-align: center; cursor: pointer; font-size: .85rem; font-weight: 600;
  display: flex; flex-direction: column; align-items: center; gap: .4rem; margin: 0;
}
.loc-tiles .tile svg { width: 1.4rem; height: 1.4rem; color: currentColor; }
.loc-tiles .tile input { position: absolute; opacity: 0; width: 0; height: 0; }
.loc-tiles .tile:has(input:checked) {
  border-color: var(--pico-primary); background: var(--pico-primary-background); color: var(--pico-primary-inverse);
}
.loc-note { display: block; color: var(--pico-muted-color); margin-bottom: .6rem; }
```

- [ ] **Step 5: Run the meeting-type tests to verify they pass**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest`
Expected: PASS — new accordion/tiles assertion green; `name="minNoticeMinutes"`, `name="horizonDays"`, `name="locationType"`, `GOOGLE_MEET`, `name="locationDetail"`, `name="slotIntervalMinutes"`, `name="requiresApproval"`, `secret` badge, and `Create` all still present; create-via-form persistence tests still pass (field names unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/AdminResource/meetingTypes.html src/main/resources/META-INF/resources/calit.css src/test/java/com/calit/web/AdminMeetingTypesTest.java
git commit -m "feat: accordion meeting-type editor + location tiles"
```

---

## Task 8: Remove the dead `adminNav.html` + full-suite green

Every admin page now uses `adminBase`. The old top-nav fragment is unreferenced.

**Files:**
- Delete: `src/main/resources/templates/adminNav.html`

- [ ] **Step 1: Confirm nothing still includes `adminNav`**

Run: `grep -rn "adminNav" src/main/resources/templates/`
Expected: no matches (all pages migrated). If any match remains, migrate that page to `adminBase` before deleting.

- [ ] **Step 2: Delete the fragment**

Run: `git rm src/main/resources/templates/adminNav.html`

- [ ] **Step 3: Run the full test suite**

Run: `./mvnw test`
Expected: PASS — every test green. Qute compiles cleanly with no reference to `adminNav`.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore: drop unused adminNav fragment (replaced by sidebar shell)"
```

---

## Task 9: Manual light/dark visual verification

Automated tests assert markup, not appearance. Verify the look against the approved mockups in both themes.

- [ ] **Step 1: Start the app in dev mode**

Run: `./mvnw quarkus:dev`
Expected: starts on `http://localhost:8080`.

- [ ] **Step 2: Verify the public booking page (light + dark)**

- Open `http://localhost:8080/` → seed/confirm at least one active meeting type exists; click into a type.
- On `/book/<slug>`: confirm the floating card on the grey canvas, the left info panel (chip + host + title + clock-icon "N min" + description/location), the right "Select a Date & Time" calendar + time column, indigo accent.
- Toggle OS theme (or DevTools → Rendering → emulate `prefers-color-scheme: dark`) and confirm the canvas, card, calendar, and slots all flip to a legible dark palette with indigo accent.

- [ ] **Step 3: Verify the admin shell + accordion (light + dark)**

- Log in at `/login`, open `/admin`: confirm the left sidebar (brand, Create, icon nav, active highlight on Dashboard, Pending badge when requests exist, Log out at the bottom).
- Open `/admin/meeting-types`: confirm the Create form renders as `<details>` accordion sections (Basics open) and the Location tiles highlight the selected option in indigo.
- Narrow the window below ~768px: confirm the sidebar collapses to a horizontal scrolling top bar.
- Toggle dark mode and re-check legibility.

- [ ] **Step 4: Stop dev mode**

Press `q` in the dev-mode console (or Ctrl-C).

> No commit — verification only. If any visual issue is found, fix the relevant template/CSS, re-run `./mvnw test`, and commit with a descriptive message.

---

## Self-Review

**1. Spec coverage:**
- Indigo accent → Task 1 (base.html + adminBase.html links). ✓
- Dark mode via variables → Tasks 1/3/4/7 CSS all use Pico vars + `--calit-canvas` (light+dark defined). ✓
- Booking card + left info panel → Task 3. ✓
- Card-on-canvas for other invitee pages → Task 2; landing intentionally unchanged (noted). ✓
- Admin left sidebar (brand, Create, icon nav, active, badge, logout, mobile collapse) → Tasks 4–6. ✓
- `<details>` accordion editor + location tiles → Task 7. ✓
- Invariants preserved; only deliberate test edits: StaticAssetsTest path (Task 1), additive BookPageTest/AdminMeetingTypesTest assertions (Tasks 3/7). ✓ (AdminMeetingTypesTest existing checks pass with tiles — verified.)
- Delete adminNav → Task 8. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows full content. ✓

**3. Type consistency:** `book(...)` gains a single trailing `String ownerName` (method + both call sites match). `adminBase.html` params `title`/`pendingCount`/`active` are passed by every migrated page (`pendingCount` from each handler's existing arg, or `pending.size()` for pending; `active` is a per-page literal). No `AdminResource.java` signature changes. `.book-info`, `.loc-tiles`, `class="editor"`, `<details>` markers referenced by tests are produced by the corresponding templates. ✓
