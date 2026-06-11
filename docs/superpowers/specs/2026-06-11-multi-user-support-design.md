# calit Multi-User Support — Design

**Date:** 2026-06-11
**Status:** Approved (brainstorming)

## Goal

Turn calit from a single-owner booking app into a multi-user one. Each user is their
own independent "Calendly": isolated meeting types, availability, bookings, settings,
and Google account, served from a personal public URL `/<username>/<slug>`. Sign-up is
opt-in (off by default). On first run the instance bootstraps a first (admin) user. On a
user's first login they complete an initial-settings wizard.

## Non-Goals (YAGNI)

Email verification, password-reset-by-email, social/OAuth login, per-user custom domains,
teams/orgs, automatic failed-attempt lockout, billing, rate-limited sign-up. May come later.

---

## 1. Data Model

### New table: `app_user`

| column                 | type         | notes |
|------------------------|--------------|-------|
| `id`                   | BIGSERIAL PK |       |
| `username`             | VARCHAR(64) UNIQUE NOT NULL | lowercased, URL-safe, validated |
| `password_hash`        | TEXT NOT NULL | argon2id, MCF-style encoded string |
| `is_admin`             | BOOLEAN NOT NULL DEFAULT FALSE | site-admin privilege flag |
| `enabled`              | BOOLEAN NOT NULL DEFAULT TRUE  | admin lock/unlock |
| `must_change_password` | BOOLEAN NOT NULL DEFAULT FALSE | admin-created temp password |
| `settings_complete`    | BOOLEAN NOT NULL DEFAULT FALSE | first-login wizard done |
| `created_at`           | TIMESTAMPTZ NOT NULL |       |

### `owner_id` foreign key

Added to **root** tenant tables (each references `app_user(id)`):
`owner_settings`, `meeting_type`, `booking`, `google_credential`, `google_calendar`,
`booking_field`.

Also added to `availability_rule` and `date_override` — these support **global rows**
(`meeting_type_id IS NULL`, meaning "applies to all of this owner's meeting types"), which have
no parent to inherit ownership from, so they carry their own denormalized `owner_id` (the same
reason `booking_field` does). The column is set on every rule/override at creation, not only the
global ones, so all queries can filter uniformly by `owner_id`.

Child tables that always have a parent stay scoped through their parent FK (no own `owner_id`):
`date_override_window` → `date_override`, `reminder` → `booking`.

### Singletons removed

- `owner_settings`: drop `SINGLETON_ID`; one row per user, `owner_id` UNIQUE.
- `google_credential`: drop fixed `id = 1`; one row per user, `owner_id` UNIQUE.
- `booking_field` global default rows (`meeting_type_id IS NULL`) become per-owner: such a
  row now carries `owner_id` and applies to all of that owner's meeting types.

### Slug uniqueness

`meeting_type.slug` changes from globally UNIQUE to UNIQUE per `(owner_id, slug)`. Two
different users may both have a `intro-call` slug.

### Google calendars per user

One connected Google **account** per user (`google_credential`, one row per owner). From that
account a user may select **many** calendars (`google_calendar` rows, each `read_for_busy`)
and **one** `write_target`. The existing global "single write-target" partial unique index
becomes **per-owner**: `UNIQUE (owner_id) WHERE write_target = TRUE`. `google_calendar.id`'s
global-unique `google_calendar_id` also relaxes to unique per `(owner_id, google_calendar_id)`
(two users may sync the same shared calendar).

### Migration

Fresh start, no backfill — pre-production, dev DB is reset. Split across two Flyway migrations
matching the implementation phases: `V7__app_user.sql` (Phase 1) creates `app_user`;
`V8__owner_scoping.sql` (Phase 2) adds the `owner_id` columns + FKs + indexes, relaxes the
old singleton/global-unique constraints to per-owner, and drops the seed rows that can no longer
be owner-attributed (e.g. the V1 global `description` booking_field insert).

---

## 2. Authentication

- Replace `quarkus-elytron-security-properties-file` (embedded env `admin` user) with
  **two custom `IdentityProvider`s over `quarkus-security` (core)** — security-jpa was
  evaluated and rejected (its generated `@UserDefinition` provider raced the custom one and
  intermittently rejected valid logins). `AppUser` is a plain Panache entity (no `@UserDefinition`).
  Its `roles` column is kept in sync with `isAdmin` (`"user"` / `"user,admin"`).
  - `AppUserIdentityProvider` (`IdentityProvider<UsernamePasswordAuthenticationRequest>`) verifies
    the argon2id hash at login and rejects disabled users.
  - `AppUserTrustedIdentityProvider` (`IdentityProvider<TrustedAuthenticationRequest>`)
    re-materializes the session identity on every post-login request from the form-auth credential
    cookie (form auth issues a `TrustedAuthenticationRequest`, no password). Enabled-enforcement is
    intentionally left to the augmentor below, not duplicated here.
  - Shared `AppUserSecurityIdentities.of(AppUser)` builds principal + roles for both providers.
- **Password hashing: argon2id** (OWASP-recommended). Parameters: memory 19456 KiB, iterations
  2, parallelism 1, 16-byte salt, 32-byte output. Hash with Bouncy Castle's
  `Argon2BytesGenerator` (`Argon2Parameters.ARGON2_id`, version `ARGON2_VERSION_13`); store as an
  MCF-style `$argon2id$...` string.
- Keep form login (`/login`, `/j_security_check`) and the remember-me cookie filter unchanged.
- **`SecurityIdentityAugmentor`**: on each authenticated request, load the `AppUser` by
  username and (a) reject the request if `enabled = false` — this invalidates the still-valid
  encrypted credential cookie of a just-disabled user — and (b) attach the owner id for
  `CurrentOwner` (see §5).
- Remove `ADMIN_PASSWORD` and the `quarkus.security.users.embedded.*` config.

---

## 3. Routing & Reserved Words

### Management (authenticated, scoped to logged-in user) — `/me/*`

Replaces all `/admin/*`:
`/me` (dashboard), `/me/meeting-types`, `/me/availability`, `/me/date-overrides`,
`/me/booking-fields`, `/me/google`, `/me/settings`, `/me/pending`.

### Site-admin only — `/me/users`

List users; add user (username + temp password); grant/revoke admin; lock/unlock (`enabled`).
Guarded by role `admin`. Sign-up on/off is **not** here (it is env-driven, §4).

### First-login wizard — `/me/setup`

Distinct from first-run `/setup`. See §4.

### Public

- `/{user}` — that user's landing page (their active meeting types).
- `/{user}/{slug}` — booking page (GET form, POST submit). Replaces `/book/{slug}`.
- `/api/google/*` — per-user Google OAuth/calendar (connect, callback, calendars,
  write-target). Stays; owner resolved from the logged-in identity.
- `/booking/{token}/manage|reschedule|cancel` — invitee self-service, unchanged (the manage
  token already identifies the booking and therefore its owner).

### Removed

`/api/meeting-types`, `/api/settings`, `/api/booking-fields`, `/api/availability` JSON CRUD
resources (`MeetingTypeResource`, `SettingsResource`, `BookingFieldResource`,
`AvailabilityResource`) and their tests — unused by any frontend and currently
**unauthenticated write endpoints** (a security hole). Booking-page slots are rendered
server-side by Qute, so nothing depends on them.

### Username validation / reserved words

Regex `^[a-z0-9](-?[a-z0-9])*$`, length 2–64, lowercased. Reserved (rejected at create/sign-up):
`me`, `login`, `logout`, `signup`, `setup`, `booking`, `api`, `q`, `health`, plus served static
asset names. (JAX-RS literal path segments still take precedence over the `/{user}` template;
the reserved list is defence-in-depth and prevents confusing/shadowed handles.)

---

## 4. User Lifecycle Flows

### First run (bootstrap)

When `app_user` is empty, every request (except `/setup` and static assets) redirects to
`/setup`. The `/setup` form creates the first user with `is_admin = true`,
`must_change_password = false`, `settings_complete = false`. Once any user exists, `/setup`
returns 404.

### Admin creates a user

At `/me/users` (admin only): supply username + temp password. New user gets
`must_change_password = true`, `settings_complete = false`, `enabled = true`,
`is_admin = false`.

### Opt-in sign-up

Public `/signup` is gated by env `SIGNUP_ENABLED` (default `false`). When false, `/signup`
returns 404. When true, anyone may self-register (username + password); the new user gets
`must_change_password = false`, `settings_complete = false`. Toggling the flag requires
changing the env and restarting — there is no runtime UI toggle.

### First-login wizard — `/me/setup`

Shown while `must_change_password` OR `!settings_complete`. All other `/me/*` routes redirect
here until both are clear. Steps:
1. If `must_change_password`: set a new password → `must_change_password = false`.
2. Display name, email, timezone → write the user's `owner_settings` row →
   `settings_complete = true`.

After completion, redirect to `/me`.

---

## 5. Isolation Enforcement ("query only what's yours")

No Hibernate multi-tenancy machinery and no row-level security — just owner-scoped queries.

- Request-scoped CDI bean `CurrentOwner`:
  - `/me/*` and `/api/google/*`: resolve from the authenticated `SecurityIdentity`
    (username → `AppUser`), surfaced via the `SecurityIdentityAugmentor` (§2).
  - Public `/{user}/*`: resolve from the `{user}` path segment; unknown user → 404.
- Every previously-singleton or `listAll` query gains an owner filter, e.g.
  `MeetingType.list("owner = ?1 and active = true", owner)`,
  `OwnerSettings.find("owner", owner).firstResult()`,
  `GoogleCredential.find("owner", owner).firstResult()`.
- Fetching another owner's row by id returns **404** (or the row simply never appears in a
  scoped list). Affected: the 13 unscoped queries, the 10 `OwnerSettings` references, and the
  4 `GoogleCredential` references found in the codebase.
- The availability busy-set is **owner-scoped**: `Booking.heldOverlapping(...)` gains an
  `owner_id` filter so one owner's bookings never block another owner's calendar. Owners are
  fully isolated (each connects their own Google account), so there is no shared physical
  calendar to reason about — A's held slot is invisible to B.
- Schedulers (`ReminderScheduler`, `PendingExpiryScheduler`) run instance-wide across all
  owners — they iterate due rows directly and need no `CurrentOwner` (each row already carries
  its owner, so per-owner emails/expiry resolve correctly).

---

## 6. Testing (TDD throughout)

New coverage:
- `AppUser` persistence; argon2id hash + verify round-trip; reserved-word + regex username
  validation.
- First-run `/setup` gating (redirect when empty, 404 once a user exists).
- Sign-up gating by `SIGNUP_ENABLED` (404 when off; creates user when on).
- Admin create-user; grant/revoke admin; lock/unlock (`enabled=false` blocks login and an
  existing cookie).
- First-login wizard gating (`/me/*` redirects to `/me/setup` until complete; password reset
  step; settings written).
- **Cross-owner isolation**: user A cannot read/edit/delete user B's meeting types,
  availability, bookings, settings, Google config → 404; B's rows absent from A's lists.
- Per-owner slug uniqueness (two users share a slug; one user can't duplicate within self).
- Public resolution: `/{user}/{slug}` serves the right owner; unknown user → 404.

Existing tests updated: `/admin/*` → `/me/*`; `FormAuth` seeds a DB `AppUser` instead of the
embedded credential; public `/book/{slug}` → `/{user}/{slug}`. Deleted: `MeetingTypeResourceTest`,
`BookingFieldResourceTest` (resources removed).

---

## 7. Documentation

Update `README.md` after implementation:
- Multi-user model and per-user data isolation.
- First-run `/setup` bootstrap.
- Opt-in sign-up via `SIGNUP_ENABLED`.
- URL scheme: `/me/*` management, `/{user}/{slug}` public booking.
- Auth change: removed `ADMIN_PASSWORD` / embedded user; DB-backed users, argon2id.
- New/changed env vars.
