# calit — Self-Hosted Calendly Alternative (Quarkus) — Plan Index

> **For agentic workers:** This is the overview. Each subsystem has its own plan file. Implement them in order — later plans depend on types defined in earlier ones. Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` per plan file.

**Goal:** A single-user, self-hosted scheduling app (Calendly-style) built on Quarkus, where the owner exposes bookable meeting types (some public, some secret/link-only) and invitees pick slots that auto-create Google Meet events and email both parties.

**Locked decisions (from brainstorming):**
- **Tenancy:** Single user, self-hosted. No signup. One owner. Owner auth is a simple shared admin credential.
- **Persistence:** PostgreSQL + Hibernate ORM with Panache (active-record). Flyway for migrations.
- **Frontend:** Qute server-rendered templates. Plain HTML/CSS, minimal vanilla JS where needed. Two route groups: invitee (public) and user/admin (auth-gated).
- **Email:** `quarkus-mailer` over SMTP (config-driven provider).
- **Google:** Owner-level OAuth2 (offline refresh token, stored once). Google Calendar API via the Google API Java client.
- **Java 25, Maven, Quarkus 3.35.x.** Tests use Quarkus **Dev Services** (Testcontainers Postgres) — Docker must be running for `mvn test`.

**Non-functional: horizontal scalability (applies to all plans).**
The app must run as N identical stateless replicas behind a load balancer. Enforced by:
- **No in-process state.** No HTTP sessions; admin auth is stateless HTTP Basic. All persistent state lives in shared Postgres. Never cache mutable domain state in instance memory.
- **Shared external state.** Google OAuth tokens (`GoogleCredential`) live in the DB, not per-node memory, so any replica can call Google. Token refresh is written back to the DB and tolerates a concurrent refresh from another node (last-write-wins; a refreshed token from either node is valid).
- **Cross-node booking safety.** Double-booking prevention cannot rely on app-level checks alone (two replicas can pass the check simultaneously). A DB-level guard (see Plan 3) makes the final insert the source of truth.
- **12-factor config.** All secrets/URLs via env vars (`%prod` profile). No node-local files.
- **Health probes.** `quarkus-smallrye-health` exposes `/q/health/live` and `/q/health/ready` for the load balancer / orchestrator (added in Plan 1).
- **Idempotent, stateless requests.** Any replica serves any request; no sticky sessions required.
- **Stateless background work.** Most emails are fired inline per request. The scheduler (Plan 6, for reminders + pending-booking expiry) runs on every replica but is multi-node-safe **without a leader**: each tick claims due rows via Postgres `SELECT … FOR UPDATE SKIP LOCKED` and marks them processed, so exactly one replica handles each item. No clustered lock / quartz JobStore needed — the DB row-claim is the arbiter.

**Time model (applies to all plans):**
- All instants stored in DB as UTC (`timestamptz` / `Instant`).
- The owner has one configured IANA timezone in `OwnerSettings.timezone`. Work hours and slot generation are computed in that zone, then converted to UTC for storage/comparison.
- `LocalTime`/`DayOfWeek` are used only for recurring weekly work-hour rules.
- **Display timezone:**
  - **Invitee-facing pages render times in the *viewer's* local timezone** (detected via the browser, with a timezone picker to override) — Calendly-standard. The slot/booking REST responses serialize instants as ISO-8601 with offset (e.g. `2026-06-08T09:00:00+02:00`), which are absolute points in time, so the invitee frontend converts them to the chosen display zone client-side. No backend change is needed for this — it is a Plan 5 frontend concern.
  - **Owner/admin pages render in the owner's timezone** (`OwnerSettings.timezone`) — it is the owner's own schedule.
  - Emails render in the owner's timezone (server-rendered, no viewer context).
  - **Canonical rule: "for the invitee, their tz; for us, our tz."** The invitee's timezone is **never persisted** — viewer-local rendering is purely a client-side display aid on invitee browser pages. The owner's timezone is the single authoritative zone for all stored-instant interpretation, every server-rendered surface (emails, owner/admin pages), and **Google Calendar events** — Plan 2 sets each event's `start.timeZone`/`end.timeZone` to the owner's IANA zone (read from `OwnerSettings.timezone`), so the event carries the owner's zone and each attendee's own Google client displays it in their local zone automatically. No `book()`/`CalendarPort` signature change is needed (the port reads `OwnerSettings`).

---

## Subsystem plans (implement in order)

| # | Plan file | Scope | Features | Depends on |
|---|-----------|-------|----------|------------|
| 1 | `2026-06-08-01-core-domain-availability.md` | **(BUILT)** Project bootstrap, health probes, domain entities, raw slot generation, secret/public listing, custom booking-field definitions | 1, 6 (buffers stored), 7, 9, 10, NFR health/stateless | — |
| 1b | `2026-06-08-1b-domain-extensions.md` | Additive migrations + entity/SlotService edits on the built Plan 1: meeting_type extra columns (min-notice, horizon, location, approval), owner_settings notify flag, **date_override** entity (replace-semantics overrides) + SlotService integration | 11 (min-notice/horizon), 12 (date overrides), 13 (location types), 14a (requires_approval flag) | Plan 1 |
| 2 | `2026-06-08-02-google-calendar-sync.md` | OAuth, read N calendars (busy), write to 1, Meet link creation, **connection-state + sendUpdates=all + conference only for GOOGLE_MEET** | 2, 3 | Plan 1 |
| 3 | `2026-06-08-03-booking-reschedule.md` | Bookable-slot calc (overrides − busy − bookings − buffers, + min-notice/horizon), create booking, **approval workflow, manage-token, degraded (Google-optional) mode, abuse guards**, reschedule | 1, 5, 6 (enforced), 11, 12, 13, 14, 16 | Plans 1, 1b, 2 |
| 4 | `2026-06-08-04-email-notifications.md` | Email kinds (requested/approved/declined/confirmed/reschedule/cancel/reminder); **fallback-vs-Google invitee logic, always-owner opt-out, .ics attachments** | 4 | Plans 1b, 3 |
| 5 | `2026-06-08-05-frontends.md` | Invitee booking UI (viewer-tz, Turnstile, token routes, location) + owner admin UI (overrides, approval queue, location, settings) | 8, 12–16 (UI) | Plans 1–4 |
| 6 | `2026-06-08-06-scheduler.md` | quarkus-scheduler tick with `SELECT … FOR UPDATE SKIP LOCKED` claim: reminder sending + pending-booking auto-expiry | 14 (expiry), 15 (reminders) | Plans 3, 4 |

**Cross-plan type contract (authoritative — every plan conforms to these exact shapes):**

*Defined in Plan 1 (BUILT):*
- `MeetingType` — name, slug, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes, active, secret. `findBySlug`, `listPublic()` (active & !secret), `listAll()`.
- `AvailabilityRule` — dayOfWeek, startTime, endTime, meetingTypeId (null = global). `forMeetingType(id,dow)`, `globalFor(dow)`.
- `OwnerSettings` — singleton (id=1): ownerName, ownerEmail, timezone. `get()`.
- `BookingField` — meetingTypeId (null = global), fieldKey, label, type (FieldType: SHORT_TEXT/LONG_TEXT/EMAIL/PHONE/NUMBER), required, position. `formFor(meetingTypeId)` (per-type else global, ordered). Full name + email are built-ins (Booking columns), NOT rows. Global `description` (LONG_TEXT, optional) seeded.
- `TimeSlot` — record(ZonedDateTime start, ZonedDateTime end).
- `SlotService.generateRawSlots(MeetingType, LocalDate from, LocalDate to)` — raw bookable windows.

*Added in Plan 1b (additive migrations on the built schema):*
- `MeetingType` gains: `minNoticeMinutes` (int, default 0), `horizonDays` (int, default 60), `locationType` (`LocationType` enum: GOOGLE_MEET[default]/PHONE/IN_PERSON/CUSTOM), `locationDetail` (String, nullable), `requiresApproval` (boolean, default false).
- `OwnerSettings` gains: `ownerNotificationsEnabled` (boolean, default true).
- `DateOverride` — replace-semantics override for one date. Fields: meetingTypeId (null = global), date (LocalDate), and a list of windows (`DateOverrideWindow`: startTime/endTime). **Empty windows = day off.** Resolver `DateOverride.resolve(meetingTypeId, date)` → per-type override if present, else global, else `null` (meaning: fall through to weekly `AvailabilityRule`). When an override exists it REPLACES that day's weekly hours.
- `SlotService.generateRawSlots` is updated so that, per date, it uses the resolved `DateOverride` windows when one exists, else the weekly rules. (min-notice/horizon are NOT applied here — they are Plan 3 slot filters relative to "now".)

*Defined in Plan 3:*
- `Booking` — meetingTypeId, inviteeName, inviteeEmail, startUtc, endUtc, googleEventId (nullable), meetLink (nullable), `status` (`BookingStatus`: PENDING/CONFIRMED/CANCELLED/DECLINED), createdAt, answers (JSONB Map), **`manageToken`** (String UUID, unique — invitee manage/reschedule/cancel key). DB overlap-exclusion guard covers `status IN ('PENDING','CONFIRMED')`.
- `BookingService.book(slug, startUtc, inviteeName, inviteeEmail, answers, turnstileToken, honeypot)` → enforces abuse guards **inside the service** (Turnstile verify on `turnstileToken` when enabled → 400; non-empty `honeypot` → reject; per-email/day cap → 429); validates required fields (422); enforces slot is available under the full pipeline (overrides−busy−bookings−buffers, min-notice, horizon → 409 on race); then: if `requiresApproval` → create **PENDING** (hold slot, NO Google event yet, fire `BookingRequested`); else **CONFIRMED** (+ Google event if connected, fire `BookingConfirmed`). Returns the Booking (with manageToken). The Plan 5 web layer just forwards the two form values (`cf-turnstile-response`, `website` honeypot).
- `BookingService.approve(bookingId)` → PENDING→CONFIRMED + create Google event (if connected) + fire `BookingApproved`. `decline(bookingId)` → PENDING→DECLINED + fire `BookingDeclined`.
- `BookingService.reschedule(manageToken, newStartUtc)` → approval-type returns to PENDING (delete event, fire `BookingRescheduleRequested`/re-`BookingRequested`); auto-type moves and stays CONFIRMED (updateEvent if connected, fire `BookingRescheduled`). `cancel(manageToken)` → CANCELLED (deleteEvent if eventId, fire `BookingCancelled`).
- CDI events (`com.calit.booking.events`): `BookingRequested`, `BookingConfirmed`, `BookingApproved`, `BookingDeclined`, `BookingRescheduled`, `BookingCancelled` — all carry `Long bookingId` (reschedule also `Instant oldStartUtc`). Plan 4 observes; Plan 6 also fires reminder/expiry-driven ones.

*Defined in Plan 2:*
- `CalendarPort` (`com.calit.google`): `boolean isConnected()`; `List<BusyInterval> freeBusy(Instant from, Instant to)`; `CreatedEvent createEvent(String summary, String description, Instant start, Instant end, List<String> attendeeEmails, boolean createMeetLink, String locationText)` (always `sendUpdates=all` so Google notifies; `createMeetLink=false` → `meetLink` null; `locationText` used for non-Meet types); `void updateEvent(String eventId, Instant start, Instant end)` and `void deleteEvent(String eventId)` (both `sendUpdates=all`). `BusyInterval(Instant,Instant)`, `CreatedEvent(String googleEventId, String meetLink, String htmlLink)`. BookingService calls Google methods only when `isConnected()`.

---

## Self-review against spec (overview level)

| Spec feature | Covered by |
|---|---|
| 1. Create meetings of a given length | Plan 1 (MeetingType.durationMinutes + slot gen), Plan 3 (booking) |
| 2. Dual-way Google sync (read N, write 1) | Plan 2; optional (degraded mode when not connected — Plan 3 branches on `CalendarPort.isConnected()`) |
| 3. Auto Google Meet links | Plan 2 (only for `locationType=GOOGLE_MEET`; none when disconnected) |
| 4. Emails to participants | Plan 4 — invitee: Google sends when connected, app fallback when off; owner: app always sends (opt-out); `.ics` attached; kinds requested/approved/declined/confirmed/reschedule/cancel/reminder |
| 5. Rescheduling | Plan 3 (via manage-token; approval types return to PENDING, auto types move) |
| 6. Min buffer before/after | Plan 1 (stored), Plan 3 (enforced in conflict check) |
| 7. Work hours global + per-meeting-type | Plan 1 (AvailabilityRule, override resolution) |
| 8. Two frontends | Plan 5 |
| 9. Secret meeting types (hidden from default list, bookable by direct link) | Plan 1 (`MeetingType.secret`, public vs admin listing); Plan 5 (admin shows all, public list hides secret) |
| 10. Owner-defined custom booking fields | Plan 1 (`BookingField` + `formFor` + seeded `description`); Plan 3 (`Booking.answers` + required validation); Plan 4 (answers in emails); Plan 5 (dynamic form + builder) |
| 11. Min scheduling notice + booking horizon (per-type) | Plan 1b (`minNoticeMinutes`, `horizonDays` columns); Plan 3 (slot filters relative to now); Plan 5 (admin fields) |
| 12. Date-specific availability overrides (replace semantics, per-type→global, empty=day off) | Plan 1b (`DateOverride`/`DateOverrideWindow` + `resolve` + SlotService integration); Plan 5 (override admin UI) |
| 13. Per-type meeting location (Meet/phone/in-person/custom) | Plan 1b (`locationType`/`locationDetail`); Plan 2 (conference only for GOOGLE_MEET, locationText otherwise); Plan 4 (location in emails); Plan 5 (location fields) |
| 14. Per-type approval workflow (PENDING hold, approve/decline, auto-expire) | Plan 1b (`requiresApproval`); Plan 3 (PENDING status + hold + overlap guard incl. PENDING + approve/decline + reschedule re-approval); Plan 6 (auto-expire); Plan 4 (requested/approved/declined emails); Plan 5 (approval queue) |
| 15. Reminder emails (configurable lead, multi-node-safe) | Plan 6 (scheduler + SKIP LOCKED claim + `reminder` rows); Plan 4 (reminder email kind, fallback-style); Plan 5 (lead-time config) |
| 16. Public-form abuse protection | Plan 3 (Turnstile verify + honeypot + per-email/day cap, Postgres-counted); Plan 5 (Turnstile widget + honeypot field) |
| —. Invitee manage-token | Plan 3 (`Booking.manageToken` UUID); Plan 4 (tokenized link in emails); Plan 5 (manage/reschedule/cancel routes keyed by token) |
| —. Owner-notify opt-out | Plan 1b (`OwnerSettings.ownerNotificationsEnabled`); Plan 4 (gate owner emails); Plan 5 (settings toggle) |
| NFR. Horizontal scalability | Overview NFR; Plan 1 (health, stateless); Plan 3 (DB overlap guard); Plan 5 (stateless Basic); Plan 2 (DB tokens); Plan 6 (SKIP LOCKED claim — scheduler without leader) |

**v1 scope exclusions (deliberate):** 1:1 only (no group/collective events); cancel/reschedule allowed any time before start (no min-lead policy); min-notice/horizon per-type only (no global default); no owner-initiated manual bookings in admin; no recurring meeting types.

No spec feature is unmapped.
