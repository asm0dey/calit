---
# calit-p5xm
title: Selectable booking duration per meeting type
status: in-progress
type: feature
priority: normal
created_at: 2026-08-15T22:57:11Z
updated_at: 2026-08-25T22:20:53Z
---

Upstream: https://github.com/asm0dey/calit/issues/119 (reporter h200101)

Meeting type currently has one fixed duration -> owners create near-duplicate types that differ only in length. Let owner define a set of allowed durations; invitee picks one on the public page.

## Requirements (from issue)

- Owner configures allowed durations for a meeting type. Reporter asked for either min/max range or explicit list; explicit list preferred (avoids arbitrary lengths).
- Public booking page: invitee selects duration, slots recomputed for that duration.
- Must work without JS (progressive enhancement): duration as a plain form control that re-submits / re-renders slot grid.

## Open questions (owner comment on issue, asm0dey)

- Buffers are per-duration in practice: 10 min before/after a 30-min meeting vs 45 min for a 120-min one. Does buffer become a per-duration setting, or a formula, or stay flat?
- Multi-host meetings: how does duration selection interact with host availability intersection?

Both unresolved upstream -> design work needed before implementation.

## Touch points

- `domain/MeetingType` (+ new Flyway `V26__*.sql`; never edit applied migrations)
- `availability/SlotService` — slot computation is duration-parameterised
- `web/PublicResource` + booking templates — duration picker, no-JS path
- `booking/BookingService` — persist chosen duration, validate against allowed set (do not trust form value)
- `email/EmailService` + `IcsBuilder` — event length follows chosen duration
- `google/` calendar sync — event end time
- i18n: new strings need `de` + `he` in `messages/*.properties`
- docs-site branch: usage docs for the new setting

## Todo

- [x] Resolve buffer semantics per duration — per-duration nullable override, strictest wins (ADR-0002)
  (Task 5: `MeetingHosts.effectiveBufferBefore/After` gain a `durationMinutes` param; `strictest` maxes
  only the overrides actually SET, never a null falling back to the type default)
- [x] Resolve multi-host interaction — NO per-host duration limits (reporter, 2026-08-17): duration is part of the normal availability intersection; a host who won't run a length is a different meeting type
- [x] Data model + migration (Task 2: `V29__meeting_type_duration.sql` + `MeetingTypeDuration` entity)
- [x] SlotService duration parameterisation
- [x] Public page duration picker — `?duration=` query param on the existing GET (no-JS)
  (Task 9: `PublicResource.DurationChoice(chosen, allowed)` + `resolveDuration` — absent, malformed,
  or not-allowed all fall back to the type's default rather than 404ing. Picker is a plain
  `<a href="?duration=N">` list (join/btn), rendered above `{#if days.isEmpty()}` so a dead-end
  duration still lets the invitee switch back. `daySlots` now takes `durationMinutes`. The
  hidden `durationMinutes` form field carries the choice through the POST; `submitBooking` resolves
  a missing/zero value to the type's default before calling the 12-arg `book(...)`, and the error
  re-render preserves the submitted length instead of resetting it. Landing page shows the full
  allowed set per type via `LandingType.durations`. Single-duration types render byte-identical —
  verified against the pre-existing `BookPageTest` "60 min" assertion.)
- [ ] Reject saving an allowed set that omits `duration_minutes` (ADR-0003)
- [x] Server-side validation of submitted duration (no new Booking column needed — startUtc/endUtc carry it)
  (Task 6: `BookingService.book` gains a 12-arg overload carrying `durationMinutes`;
  `assertDurationAllowed` checks it against `MeetingTypeDuration.isAllowed` before any other
  work and throws `BookingConflictException` (409) on a value outside the type's allowed set.
  `availableSlots`/`hostFreeSlots`/`assertSlotAvailable` all gained duration-carrying overloads
  so the end time and buffers follow the chosen length. The 11-arg `book` overload resolves the
  type itself and delegates with `type.durationMinutes` so every existing caller is unchanged.)
- [x] Reschedule preserves the booked length, not the type's default
  (Task 7: both `BookingService.reschedule` and `rescheduleGroup` recomputed `newEnd` from
  `type.durationMinutes`, so moving a non-default-length booking silently resized it — a latent
  bug that could not fire before this feature made a second length possible. Added
  `public static int lengthOf(Booking)` (public: `EmailService`, a different package, will read it
  in Task 8) and used it for both the new end time and the `assertSlotAvailable` re-check in each
  reschedule path, so the re-check validates a slot of the booking's own length, not the type's
  default.)
- [x] Email / ICS / Google sync use chosen duration
  (Task 8: only `EmailService` needed a change — all 9 call sites into the `Templates.*` methods
  passed `l.meetingType.durationMinutes`, so a non-default-length booking announced the type's
  default in its own confirmation/reminder/reschedule/cancel emails. Replaced every one with
  `BookingService.lengthOf(l.booking)`. ICS (`IcsEvent.end(l.booking.endUtc)`) and the Google
  Calendar event were already built from `booking.startUtc`/`endUtc` and needed no change.)
- [ ] i18n de + he
- [ ] Tests
- [ ] docs-site update

## Decisions so far (2026-08-17 grilling)

**Buffers: per-duration nullable override.** The allowed set needs a table anyway, so it carries
two nullable buffer columns; NULL falls back to the type's flat buffer. Same idiom
`MeetingTypeHost` already uses.

```
meeting_type_duration
  meeting_type_id, duration_minutes,
  buffer_before_minutes NULL -> type default
  buffer_after_minutes  NULL -> type default
```

**Where several buffers apply, the strictest governs** — `max()`, not most-specific-wins. A
meeting cannot be created until every host's and the duration's constraints are satisfied, so
buffers are floors, not settings. Recorded as
`docs/adr/0002-buffers-are-constraints-not-settings.md`. Lives in exactly two methods:
`MeetingHosts.effectiveBufferBefore/After`, which each gain a duration parameter.

**`Booking` needs NO new column.** `startUtc`/`endUtc` (`Booking.java:35-38`) already carry the
chosen length. The 'persist chosen duration' todo is satisfied by the existing schema; what still
matters is validating the submitted duration against the allowed set server-side.

**Slot computation must not be cached across durations** — a different length can mean a different
buffer and therefore a different slot set.

**Interaction model: invitee picks the length FIRST, grid re-renders for it.** Not a per-slot
length menu — that would need every slot to be valid for every length, which fragments once
per-duration buffers differ. Carried as `GET /{user}/{slug}?duration=60`: `book()` takes no query
params today (`PublicResource.java:207`), and POST on that path is already the booking submit, so a
query param is the only idempotent, refresh-safe, no-JS-friendly option. Shareable link with a
preselected length falls out for free.

**Default = the type's existing `duration_minutes`** (ADR-0003). The allowed-set table must contain
it; an empty table means the allowed set is exactly `{duration_minutes}`, so existing types are
valid with ZERO backfill. Removing the default from the set is rejected at save. No
`default_duration_minutes` column, no `position` column, no `is_default` partial index.

**Cadence anchor is the SHORTEST allowed duration, which is NOT the default.** Two separate
concepts on purpose: the default decides what renders before a choice, the shortest keeps the
lattice from moving when the invitee switches. Anchoring cadence to the default would offer 30-min
meetings at 60-min intervals whenever the default is 60.

## Still open — asked upstream on GH #119 (2026-08-17)

Asked: https://github.com/asm0dey/calit/issues/119#issuecomment-5316044376 — stays `todo` while
unanswered; the two questions below gate the data model, not the whole feature.

1. Fixed slot lattice across lengths, or cadence following the chosen length? Today
   `step = type.effectiveSlotIntervalMinutes()` falls back to `durationMinutes`
   (`SlotService.java:65`) — that fallback is what selectable duration breaks. Proposal: when
   `slotIntervalMinutes` is unset, fall back to the SHORTEST allowed duration, so the lattice
   never moves under the invitee and single-duration types stay byte-identical.
2. Multi-host: may a co-host restrict WHICH durations they host, or does the length list belong to
   the type? Note the buffer question needed no host x duration matrix — ADR-0002's `max()` rule
   removes it.

Multi-host otherwise needs no special handling: duration is already a parameter of
`SlotService.generateRawSlots`, every host shares `step` off the same type, so the day-anchored
intersection lattice stays aligned.

Not asked upstream, decided here: per-duration buffers ship WITH the feature, not gated on whether
the reporter needs them. They are the reason the flat buffer cannot serve a multi-length type at
all (10/30 vs 45/120), so a version without them would not remove the near-duplicate meeting types
this feature exists to remove.

## Upstream answers (2026-08-17, reporter h200101)

Both gating questions are settled — https://github.com/asm0dey/calit/issues/119#issuecomment-5316044376

1. **Fixed lattice.** Candidate starts don't move when the length changes; a longer pick just drops
   the starts that no longer fit. Reporter: it makes better use of available time, and the buffers
   are the right tool for protecting time around a long meeting.
2. **No per-host duration limits.** Length list belongs to the type. Host-requirement modes
   (all / any one / named hosts) noted as a possible future feature, explicitly out of scope here.

## Design

Spec: `docs/superpowers/specs/2026-08-25-selectable-booking-duration-design.md`

Corrections to the notes above:

- Migration is **V29**, not V26 — latest applied is V28.
- ADR-0002's formula is amended: the max is over overrides actually SET. A NULL falling back to the
  type's buffer inside the max would raise a host's deliberate 5 back to the type's 10.
- ADR-0003 to be amended: the default is an IMPLICIT member of the set (union at read time), not a
  row the save refuses to delete. No rejection path, no error message, no way for the two forms to
  disagree.
- Reschedule freezes the booked length (no picker). This also fixes a latent bug: today
  `BookingService:792/890` recompute the end from `type.durationMinutes`.
- `Templates.book` is at 14 positional args; DurationChoice / Chrome / Captcha records land first
  as a mechanical no-behaviour-change commit.
