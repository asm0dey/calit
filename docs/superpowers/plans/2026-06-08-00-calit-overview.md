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
- **Stateless background work.** Emails are fired inline per request (no node-local queue). If a scheduler is ever added, it must use a clustered lock — not in scope now.

**Time model (applies to all plans):**
- All instants stored in DB as UTC (`timestamptz` / `Instant`).
- The owner has one configured IANA timezone in `OwnerSettings.timezone`. Work hours and slot generation are computed in that zone, then converted to UTC for storage/comparison.
- `LocalTime`/`DayOfWeek` are used only for recurring weekly work-hour rules.
- **Display timezone:**
  - **Invitee-facing pages render times in the *viewer's* local timezone** (detected via the browser, with a timezone picker to override) — Calendly-standard. The slot/booking REST responses serialize instants as ISO-8601 with offset (e.g. `2026-06-08T09:00:00+02:00`), which are absolute points in time, so the invitee frontend converts them to the chosen display zone client-side. No backend change is needed for this — it is a Plan 5 frontend concern.
  - **Owner/admin pages render in the owner's timezone** (`OwnerSettings.timezone`) — it is the owner's own schedule.
  - Emails render in the owner's timezone (server-rendered, no viewer context); where an invitee's zone is known it may also be shown — Plan 4 detail.

---

## Subsystem plans (implement in order)

| # | Plan file | Scope | Features | Depends on |
|---|-----------|-------|----------|------------|
| 1 | `2026-06-08-01-core-domain-availability.md` | Project bootstrap, health probes, domain entities, raw slot generation, secret/public listing, custom booking-field definitions | 1 (lengths), 6 (buffers, stored), 7 (work hours global + per-type), 9 (secret types), 10 (custom field defs), NFR health/stateless | — |
| 2 | `2026-06-08-02-google-calendar-sync.md` | OAuth, read N calendars (busy), write to 1, Meet link creation | 2, 3 | Plan 1 |
| 3 | `2026-06-08-03-booking-reschedule.md` | Bookable-slot calc (work hours − busy − buffers), create booking, reschedule | 1, 5, 6 (enforced) | Plans 1, 2 |
| 4 | `2026-06-08-04-email-notifications.md` | Confirmation / reschedule / cancel emails to owner + invitee | 4 | Plans 1, 3 |
| 5 | `2026-06-08-05-frontends.md` | Invitee booking UI + owner admin UI (Qute) | 8 | Plans 1–4 |

**Cross-plan type contract (defined in Plan 1, reused everywhere):**
- `MeetingType` — name, slug, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes, active, secret (true = hidden from public list, still bookable by direct slug/link)
- `AvailabilityRule` — dayOfWeek, startTime, endTime, meetingTypeId (null = global)
- `OwnerSettings` — singleton (id=1): ownerName, ownerEmail, timezone
- `BookingField` — owner-defined EXTRA booking-form field: meetingTypeId (null = global default form), fieldKey, label, type (FieldType enum: SHORT_TEXT/LONG_TEXT/EMAIL/PHONE/NUMBER), required, position. `BookingField.formFor(meetingTypeId)` resolves per-type fields if any exist, else global (same override pattern as AvailabilityRule). **Full name + email are always-present built-ins** (backed by `Booking.inviteeName`/`inviteeEmail`), NOT BookingField rows. A global `description` field (LONG_TEXT, optional) is seeded by default; the owner may add/remove/reorder/require more.
- `TimeSlot` — record(ZonedDateTime start, ZonedDateTime end)
- `SlotService.generateRawSlots(MeetingType, LocalDate from, LocalDate to)` — raw work-hour slots, ignoring conflicts (Plan 3 subtracts busy/buffers)

**Booking type contract (defined in Plan 3, reused by Plans 4–5):**
- `Booking` — meetingTypeId, inviteeName, inviteeEmail, startUtc, endUtc, googleEventId, meetLink, status (CONFIRMED/CANCELLED), createdAt, **answers** (JSONB `Map<String,String>`: custom `BookingField.fieldKey` → submitted value)
- `BookingService.book(slug, startUtc, inviteeName, inviteeEmail, answers)` validates that every required `BookingField` in `formFor(type)` has a non-blank value in `answers`, else rejects (422).

---

## Self-review against spec (overview level)

| Spec feature | Covered by |
|---|---|
| 1. Create meetings of a given length | Plan 1 (MeetingType.durationMinutes + slot gen), Plan 3 (booking) |
| 2. Dual-way Google sync (read N, write 1) | Plan 2 |
| 3. Auto Google Meet links | Plan 2 |
| 4. Emails to participants | Plan 4 |
| 5. Rescheduling | Plan 3 |
| 6. Min buffer before/after | Plan 1 (stored), Plan 3 (enforced in conflict check) |
| 7. Work hours global + per-meeting-type | Plan 1 (AvailabilityRule, override resolution) |
| 8. Two frontends | Plan 5 |
| 9. Secret meeting types (hidden from default list, bookable by direct link) | Plan 1 (`MeetingType.secret`, public vs admin listing); Plan 5 (admin shows all, public list hides secret) |
| 10. Owner-defined custom booking fields (defaults: full name req, email req, description optional) | Plan 1 (`BookingField` def + `formFor` + CRUD + seeded `description`); Plan 3 (`Booking.answers` JSONB + required-field validation in `book`); Plan 4 (answers in emails); Plan 5 (dynamic invitee form + admin form-builder) |
| NFR. Horizontal scalability | Overview NFR section; Plan 1 (health probes, stateless); Plan 3 (DB-level double-booking guard); Plan 5 (stateless HTTP Basic, no sessions); Plan 2 (DB-stored tokens, refresh tolerant of concurrent nodes) |

No spec feature is unmapped.
