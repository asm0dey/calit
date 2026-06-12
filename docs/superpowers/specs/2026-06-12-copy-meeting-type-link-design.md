# Copy Meeting-Type Link — Design

**Goal:** On the admin meeting-types list (`/me/meeting-types`), each meeting-type card gets a "Copy link" button that copies that type's absolute public booking URL to the clipboard, with a daisyUI toast confirmation.

## Decisions (locked)

- **Placement:** list page only (`meetingTypes.html`). Not the detail page, not public pages.
- **Link target:** absolute booking URL — `{app.base-url}/{username}/{slug}`. Pasteable into chat/email.
- **Feedback:** daisyUI toast ("Link copied").

## Context (existing code)

- Public booking URL pattern: `/{username}/{slug}` — `PublicResource` `@Path("/{user}/{slug}")` (book page, line 134).
- `MeetingType.slug` is unique per owner; `AppUser.username` is unique, normalized at creation.
- `app.base-url` config already exists: `application.properties:56` → `${APP_BASE_URL:http://localhost:8080}`, no trailing slash. Used by `EmailService` (`@ConfigProperty(name="app.base-url")`). Test default = `http://localhost:8080`.
- Owner of current request: `CurrentOwner currentOwner` (injected in `AdminResource:81`); `currentOwner.require()` returns `AppUser`; `.username` is a public field.
- List template `meetingTypes.html` declares its params at the top and is rendered by the native method `AdminResource.Templates.meetingTypes(...)` (declared `AdminResource.java:46-49`).
- Call sites of `Templates.meetingTypes(...)`: `AdminResource.java` lines **117, 159, 217, 229** (meetingTypes GET, create POST, toggle POST, delete POST). All four must pass the new args.
- Layout `adminBase.html` inserts page content at `{#insert}{/insert}` (line 60); page-specific `<script>` tags inside the inserted block work fine (see `workplan.js` pattern, though that one is an external file).
- No existing clipboard JS anywhere — this is new.
- daisyUI 5 + Tailwind v4 confirmed (`package.json`). Buttons use `.btn .btn-*`; daisyUI provides `.toast` + `.alert`.

## Architecture

Server renders the absolute URL into a `data-copy-link` attribute on each card's button (avoids inline-onclick quoting issues and keeps the URL build server-side where `baseUrl`/`username` live). A small vanilla-JS handler, event-delegated on `.copy-link-btn`, reads the attribute, calls `navigator.clipboard.writeText(url)`, and shows a transient toast.

### Components

1. **`AdminResource` (backend wiring):**
   - New field: `@ConfigProperty(name = "app.base-url") String baseUrl;`
   - Extend native template method signature: `meetingTypes(List<MeetingType> types, LocationType[] locationTypes, DayOfWeek[] daysOfWeek, Long pendingCount, boolean isAdmin, String username, String baseUrl)`.
   - All 4 call sites append `, currentOwner.require().username, baseUrl`.

2. **`meetingTypes.html` (markup + JS):**
   - Two new header param declarations: `{@java.lang.String username}` and `{@java.lang.String baseUrl}`.
   - Per card: a "Copy link" button in the existing actions row, `class="btn btn-ghost btn-sm copy-link-btn"`, `data-copy-link="{baseUrl}/{username}/{t.slug}"`.
   - A `<div class="toast toast-end" id="copy-toast" ...>` (hidden by default) + a `<script>` block at the bottom of the inserted content: clipboard write + show/hide toast.

### Data flow

`meetingTypes()` (or create/toggle/delete) → `Templates.meetingTypes(..., username, baseUrl)` → Qute renders `data-copy-link="http://host/username/slug"` per card → user clicks → JS copies + toasts.

### Error handling

- `navigator.clipboard` may be unavailable (non-secure context / old browser). JS guards: if `navigator.clipboard?.writeText` missing or the promise rejects, show a fallback toast ("Copy failed — URL: …") rather than throwing. No server-side error path (pure render).

### Testing

- **`AdminMeetingTypesTest` (RestAssured + QuarkusTest, existing patterns):**
  - Seed a meeting type with a known slug; GET `/me/meeting-types` (authed via `FormAuth.login()`, admin username `admin`).
  - Assert body contains `data-copy-link="http://localhost:8080/admin/<slug>"`.
  - Assert body contains the copy button class `copy-link-btn`.
  - Assert body contains the toast container id `copy-toast`.
- **Manual / Playwright (note in plan):** clipboard write + toast appearance can't be asserted by RestAssured (no JS execution). Verify in a real browser.

## Out of scope (YAGNI)

- Copy button on detail page or public pages.
- Showing the URL as visible text (only the existing `/{slug}` text stays).
- Per-type "secret" link warnings, QR codes, short links.
