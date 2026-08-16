# Per-meeting-type Google write target (calit-bh5t)

Date: 2026-08-17
Bean: `calit-bh5t` (feature) — blocked by `calit-rma2`

## Problem

Today an owner has exactly one write calendar: the single `google_calendar` row with
`write_target = true` (uniqueness enforced in `CalendarSelectionService.java:38`), resolved live on
every write by `GoogleCalendar.writeTarget(ownerId)` (`GoogleCalendarPort.java:305`). Every meeting
type of that owner therefore lands on the same calendar. Owners want "Coaching" on the personal
calendar and "Client intro" on the work calendar.

## Scope

An **optional** per-meeting-type override of the write calendar, per host. Unset (the default, and
what every existing row gets) means "use my default write target", so nothing changes for anyone who
does not opt in. The owner-level `write_target` keeps its column name but is renamed in user-facing
strings to **default write target**.

Out of scope: changing which host organizes a co-hosted meeting, per-type calendars for busy reads,
any backfill of existing rows.

## Ordering: calit-rma2 lands first

`calit-rma2` adds the per-booking event address (`booking.google_calendar_id` +
`booking.google_credential_id`) and makes update/delete address the stored calendar. It ships and is
verified as its own change **before** this feature.

Reason: once a type can override its write calendar, setting an override on a type that already has
future bookings orphans every one of those events on the old calendar — by design, not by accident.
The per-booking address must exist first.

One adjustment to rma2's agreed shape: its `booking.google_calendar_id` column is `text`, not
`VARCHAR(255)` (see Schema below).

## Data model

New migration (`V27`, assuming rma2 takes `V26` — renumber at implementation time). Four nullable
columns, no backfill:

```sql
ALTER TABLE meeting_type
  ADD COLUMN google_credential_id BIGINT REFERENCES google_credential(id) ON DELETE SET NULL,
  ADD COLUMN google_calendar_id   text;

ALTER TABLE meeting_type_host
  ADD COLUMN google_credential_id BIGINT REFERENCES google_credential(id) ON DELETE SET NULL,
  ADD COLUMN google_calendar_id   text;
```

`meeting_type` holds the **creator's** override; `meeting_type_host` holds each **co-host's** own
override for that shared type. Both NULL = use that owner's default write target.

Why the (credential, calendar) pair rather than an FK to `google_calendar.id`:

- `CalendarSelectionService.save()` (line 41) does `deleteForOwner(ownerId)` and re-inserts every
  row on each save, so local ids churn whenever the owner touches a calendar checkbox. An FK would
  be nulled or dangle on an unrelated settings save. The Google-side id survives untick/re-tick.
- The write path needs a token: `writeContext` (`GoogleCalendarPort.java:315`) builds the client from
  a `GoogleCredential`, which the calendar id alone does not carry.
- A calendar shared into two connected accounts produces two `google_calendar` rows with the same
  `google_calendar_id` and different credentials, so the id alone is ambiguous.

`text` rather than `VARCHAR(255)`: identical storage and performance in Postgres, no arbitrary cap.
Entity fields need `@Column(columnDefinition = "text")` because Hibernate defaults `String` to
`varchar(255)` and runs in validate-only mode. The existing `google_calendar.google_calendar_id
VARCHAR(255)` (V3) is left alone — widening it is a separate migration and mapping change for a
limit nothing has hit.

`google_calendar.write_target` and its uniqueness index (`idx_google_calendar_single_write_target`)
are unchanged: still at most one default per owner. The override needs no such constraint — it names
one calendar per (type, host) by definition.

## Resolution

New `WriteTargetResolver` in `google/`. `resolve(ownerId, type)` returns the calendar + credential to
write with:

1. Pick the override row: `ownerId.equals(type.ownerId)` → the `meeting_type` columns; otherwise
   `MeetingTypeHost.find(type.id, ownerId)` columns.
2. Override set → require a live `GoogleCalendar` row for `(ownerId, googleCalendarId)` whose
   `googleCredentialId` matches. Present → use it.
3. Override set but not resolvable (calendar unticked, account disconnected so the FK was nulled) →
   log **WARN** with meeting type id, calendar id, ownerId, then fall through to step 4.
4. Fall back to `GoogleCalendar.writeTarget(ownerId)` — the owner's default.
5. Still nothing → today's `IllegalStateException("No write-target Google calendar selected…")`.

A dangling override therefore never breaks a booking; it degrades to the default, loudly in the log
and visibly in the edit form (see UI).

`MeetingHosts.chooseOrganizer` is unchanged. Resolution runs for whichever owner ends up organizer,
so a co-host organizer gets their own override or their own default.

## Write path

rma2 has already given the port an explicit calendar address for update/delete, and made
`CreatedEvent` report the calendar it wrote to. This change adds the create side: `createEvent` takes
the resolved target instead of calling `requireWriteTarget(ownerId)` blind.

`BookingService.createGoogleEvent` (line 509) and the group/multi-host variant (line 425) call the
resolver with `(organizer, type)` and pass the result down. Everything stays behind
`calendarPort.isConnected(...)`, so degraded no-Google mode is untouched.

## UI

Both pickers list only that owner's currently selected calendars, blank option = "my default write
target". Rendered only when Google is connected.

- **Creator**: a select on `meetingTypeDetail.html`, saved by the existing meeting-type edit POST in
  `AdminResource`.
- **Co-host**: the same select on `/me/shared/{typeId}/availability`
  (`SharedMeetingsResource`), alongside the existing per-host buffers form, saved by its own POST.
- A dangling override renders as a disabled entry reading "calendar no longer available — using
  default", so the owner can see and fix it.
- Save validates server-side that the submitted `(credential, calendar)` belongs to
  `currentOwner.id()` — a live `GoogleCalendar` row must match. Not UI-only validation.
- `google.html`: the owner-level write-target label becomes **default write target**.

Where it reads well, `GoogleCalendar.writeTarget` is renamed to `defaultWriteTarget` in Java. The
`google_calendar.write_target` **column** keeps its name — a rename costs a migration and a mapping
change for zero behaviour.

## Google Meet gating

`AdminResource:604,619` currently forbids `GOOGLE_MEET` when `GoogleCalendar.writeTargetBlocksMeet`
says the owner's single write target cannot mint Meet links. It now checks the **creator's** resolved
calendar (type override, else creator's default) and its `supportsMeet` flag.

Co-host overrides do not affect the gate. Accepted limitation: if a co-host ends up organizer with a
non-Meet calendar, the create degrades through the existing `handleCreateFailure` path rather than
being blocked at edit time.

## Testing

`@QuarkusTest` cases, owner-scoped against the admin-is-id-1 invariant:

- No override anywhere → writes go to the owner's default (behaviour unchanged).
- Creator override → event created on the overridden calendar.
- Co-host override → co-host organizer writes to their own override.
- Dangling override (calendar unticked) → falls back to the default, no exception, WARN logged.
- Foreign calendar submitted on save → rejected, nothing persisted.
- Meet gate follows the creator's resolved calendar (blocked when it cannot Meet, allowed when it
  can, even if the owner's default cannot).
- Degraded no-Google mode → booking flow unaffected, no resolver call.

## i18n

Every new or changed user-facing string ships with `de` and `he` values in
`src/main/resources/messages/{msg,adm}_{de,he}.properties` in the same change: the two calendar-picker
labels, the "using default" hint, the "calendar no longer available" text, and the renamed
"default write target" label.

## Docs

`docs-site` branch: extend the Google setup page with the per-meeting-type write calendar — what the
default write target now means, how to override it per type, how a co-host overrides it for a shared
type, and what happens when the chosen calendar is unselected.
