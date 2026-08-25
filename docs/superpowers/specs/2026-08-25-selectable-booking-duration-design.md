# Selectable booking duration per meeting type

A meeting type offers one length today, so an Owner who runs 30-, 60- and 120-minute sessions
keeps three near-duplicate types that differ only in their duration. This lets a type carry a set
of allowed lengths; the Invitee picks one on the public page and the slot grid re-renders for it.

Upstream: [#119](https://github.com/asm0dey/calit/issues/119). Tracked as `calit-p5xm`.

## Decisions this rests on

- [ADR-0002](../../adr/0002-buffers-are-constraints-not-settings.md) — buffers are constraints, so
  the strictest applicable one governs. **Amended** while designing this: the maximum is taken over
  the overrides actually set, never over a `NULL` fallen back to the type's buffer (see Buffers).
- [ADR-0003](../../adr/0003-a-meeting-types-duration-doubles-as-its-default.md) —
  `meeting_type.duration_minutes` is the default of the allowed set. **Amended**: the default
  is an *implicit* member rather than a row the save refuses to delete (see Owner UI).

Two questions were open upstream and are now answered by the reporter
([comment](https://github.com/asm0dey/calit/issues/119#issuecomment-5316044376)):

- **Fixed lattice.** Candidate start times do not move when the length changes; a longer pick
  simply drops the starts that no longer fit.
- **No per-host duration limits.** The length list belongs to the type. A host who will not run
  the 2-hour version is a different meeting type. Duration falls out of the normal availability
  intersection: a length is offered only where every host can fit it.

## Data model

`V29__meeting_type_duration.sql` (latest applied migration is `V28`; the bean's `V26` is stale):

```sql
create table meeting_type_duration (
  meeting_type_id       bigint  not null references meeting_type(id) on delete cascade,
  duration_minutes      int     not null check (duration_minutes > 0),
  buffer_before_minutes int     null check (buffer_before_minutes >= 0),
  buffer_after_minutes  int     null check (buffer_after_minutes  >= 0),
  primary key (meeting_type_id, duration_minutes)
);
```

No `id`, no `position`, no `is_default`. The composite primary key is the natural key and rules
out a duplicate length for free. Ordering by `duration_minutes` is the only ordering anyone needs.

DDL only — **no backfill**. `MeetingType` gains no column.

Entity `MeetingTypeDuration` plus two derived helpers:

```
allowedDurations(type) = sorted(rows(type.id).duration_minutes ∪ {type.durationMinutes})
shortestAllowed(type)  = min(allowedDurations(type))
```

The union is what makes the default unbreakable: an empty table means the set is exactly
`{durationMinutes}`, and no combination of edits can produce a set that omits the default.

A row whose `duration_minutes` equals the default carries only that length's buffer overrides;
deleting it drops the overrides, never the duration.

## Slot computation

`SlotService.generateRawSlots` gains an `int durationMinutes` parameter; the existing 5-argument
overload delegates with `type.durationMinutes`, so every current caller and test is untouched.
Inside, the two grid inputs come from different places:

```java
int step     = type.slotIntervalMinutes > 0 ? type.slotIntervalMinutes : shortestAllowed(type);
int duration = durationMinutes;
```

Step from the **shortest** allowed length, body from the **chosen** one. That is the whole
fixed-lattice property: a 30/120 type with no explicit cadence puts candidate starts on a 30-minute
lattice, and picking 120 keeps 09:00, 09:30, 10:00 … dropping only those that run past the window.
The lattice never moves under the Invitee.

The fallback branch of `MeetingType.effectiveSlotIntervalMinutes()` moves into `SlotService`,
because it now needs the allowed set and an entity method should not query for it. The explicit-
cadence branch stays on the entity. A single-duration type has `shortestAllowed == durationMinutes`,
so its output is byte-identical to today.

Nothing caches slots across durations: a different length can mean a different buffer and therefore
a different slot set.

## Buffers

`MeetingHosts.effectiveBufferBefore/After` gain the chosen duration. Two sources can apply to one
host — that host's own override and the chosen duration's override — and per ADR-0002 the maximum
of them governs. The maximum is taken over the overrides actually **set**; a `NULL` is the absence
of a requirement, not a requirement equal to the type's flat buffer:

```
set       = { host override if not null } ∪ { duration override if not null }
effective = set.isEmpty() ? type buffer : max(set)
```

| host override | duration override | effective |
|---|---|---|
| null | null | type buffer |
| 5 | null | 5 |
| null | 45 | 45 |
| 5 | 45 | 45 |
| 90 | 45 | 90 |

Letting the `NULL` fall back *inside* the maximum would defeat a host who deliberately set a buffer
below the type's — a 5-minute host override against a 10-minute type default would be raised back
to 10. Every pre-existing row has a null duration override, so its answer is unchanged: the new
column can only ever raise a buffer.

**Across hosts nothing is maximised.** Each host's slots are filtered with that host's own effective
buffer and the free sets are intersected, so the strictest host wins per slot wherever they
genuinely have something to protect. Worked example: co-host busy until 10:00 with an 80-minute
before-buffer, the other host free from 11:10. The 11:10 slot's buffered interval starts at 09:50
and overlaps the co-host's busy block, so it is dropped; 11:20's starts exactly at 10:00, and
`Interval.overlaps` is strict, so it survives. Earliest shared start is 11:20. Maximising across
hosts instead would apply one host's personal turnaround to another host's unrelated calendar and
silently remove slots from every existing shared type with mixed buffers.

## Public booking page

`book()` gains `@RestQuery Integer duration`, resolved forgivingly:

```java
int chosen = allowedDurations(type).contains(duration) ? duration : type.durationMinutes;
```

Absent, malformed, or not-allowed all fall back to the default, so a stale shared link never 404s.

**Picker** — rendered only when the set has more than one entry, so a single-duration type renders
an identical page. Plain `<a href="?duration=N">` links styled as a daisyUI `join` group, the
current one `btn-active`. No form, no JavaScript; a shareable link with a preselected length falls
out for free.

The picker sits **outside** the `{#if days.isEmpty()}` branch. Pick 120, find nothing free, and the
Invitee must still be able to switch back to 30 — inside that branch it would be a dead end.

The header's `{type.durationMinutes} min` becomes the chosen length. The form carries
`<input type="hidden" name="durationMinutes" value="{chosen}">`; `submitBooking` takes it as
`@RestForm`, passes it to `book(...)`, and re-renders the error path with the same value so a
validation bounce does not silently reset the Invitee to the default.

**Reschedule gets no picker.** The length is frozen: `daySlots(type, lengthOf(booking))` where
`lengthOf` is `Duration.between(startUtc, endUtc).toMinutes()`. Changing length means cancel and
rebook.

**Landing page** shows `30 / 60 / 120 min` for a multi-duration type, plain `30 min` otherwise. The
list goes inside the existing `LandingType` view-model record, not the template signature.

### Template parameter grouping

`Templates.book(...)` already takes 14 positional arguments — including a bare `null` and a bare
`""` — and is called from two places that must stay in lockstep. Three records land first, as their
own mechanical no-behaviour-change commit, taking it to 11:

```java
record DurationChoice(int chosen, List<Integer> allowed) {
    boolean multiple() { return allowed.size() > 1; }
}
record Chrome(String tzBar, String tzScript, String calendarScript) {}
record Captcha(String provider, String siteKey) {}
```

`Chrome` and `Captcha` are already passed identically at both call sites and never vary
independently. No other template has the problem.

## Owner UI

A new collapse section on `meetingTypeDetail.html` with its own form posting to
`/me/meeting-types/{id}/durations` — mirroring how shared buffers already have their own POST
rather than growing `applyEditableFields`.

```
Allowed durations

  Duration   Buffer before   Buffer after
  [ 30    ]  [ 10        ]   [ 10       ]
  [ 60  * ]  [           ]   [          ]    * default
  [ 120   ]  [ 45        ]   [ 45       ]
  [       ]  [           ]   [          ]  <- spare

  blank buffer = use the meeting type's buffer
  clear a duration to remove it
```

One row per member of the union, sorted, the default marked, plus one blank spare. Growth is one
duration per save; no add/remove buttons, one POST, no JavaScript.

Rows are parallel form fields: `MultivaluedMap.get("d.duration")` preserves document order, so row
*i* pairs `d.duration[i]` with `d.before[i]` / `d.after[i]` positionally. Save is delete-all-then-
insert over the type's rows inside the existing transaction — the set is tiny, and it makes "clear
a duration to remove it" fall out for free.

### ADR-0003 amendment

ADR-0003 originally had removing the default from the set rejected at save. Implicit membership
replaced that: the default is always in the set by construction, so there is no rejection path, no error
message, no i18n for it, and no way for the main edit form (which owns `durationMinutes`) and the
durations form to disagree when the Owner moves the default. Clearing the default's duration field
is a no-op rather than an error. ADR-0003 says so.

## Booking write path

```java
assertDurationAllowed(type, chosen);                    // new, before anything else
Instant endUtc = startUtc.plus(chosen, ChronoUnit.MINUTES);
```

`assertDurationAllowed` rejects anything outside the union as a `BookingConflictException` — the
same 409 an unavailable slot already produces. The asymmetry with the GET, which falls back to the
default instead of erroring, is deliberate: a `?duration=` in a URL is something a human may have
shared or edited, while a submitted `durationMinutes` is a value this server just rendered, so a
wrong one means the form was tampered with. It is not optional: a POST carrying `duration=45`
would otherwise build a self-consistent 45-minute lattice that passes every downstream check.
`bookGroup` already receives `endUtc` as a parameter, so multi-host needs nothing.

`book(...)` gains the parameter with a defaulting overload, so existing callers and tests compile
untouched.

Reschedule stops reading the type: `newEnd = newStartUtc.plus(lengthOf(booking), ChronoUnit.MINUTES)`,
and the slot re-check gets the same length. This fixes a latent bug that exists independently of
this feature — today a reschedule recomputes the end from `type.durationMinutes`, so the moment a
type offers a second length, rescheduling a 120-minute booking silently shrinks it to the default.

`Instant` has no `plusMinutes`, which is why the current code writes `plusSeconds(60L * n)`.
`plus(n, ChronoUnit.MINUTES)` is the same arithmetic with the unit named instead of implied; the
three sites being touched adopt it, untouched sites are left alone.

## Email, ICS, Google

**ICS needs no change** — `IcsEvent.end(l.booking.endUtc)` already reads the booking. **Google needs
no change** — the event is built from `booking.startUtc`/`endUtc`.

**Email does.** Roughly a dozen `EmailService` call sites pass `l.meetingType.durationMinutes` as the
displayed length; each becomes the booking's own length (`l.booking` is already in scope at every
one). Without this a 120-minute booking on a 30-minute-default type prints "30 min" in its own
confirmation.

## i18n

New `AppMessages` keys for the picker and the multi-duration landing label, new `AdminMessages` keys
for the durations section. Each ships with its `de` and `he` value in the same change; placeholder
names identical across locales.

## Testing

| Area | Test |
|---|---|
| `SlotServiceTest` | 30/120 type, no explicit cadence → starts on a 30-min lattice for both picks; 120 drops the starts that do not fit; single-duration output byte-identical to today |
| `MeetingHostsTest` | the five-row buffer table above |
| `BookingServiceTest` | chosen duration sets `endUtc`; `duration=45` on a 30/60/120 type → 409, no row written; reschedule of a 120-min booking stays 120 when the default is 30 |
| multi-host | 120 not offered when one host cannot fit it while 30 still is; the 11:20 case above as a regression test |
| `PublicResource` | `?duration=120` renders 120-min slots; `?duration=45` and `?duration=abc` fall back without erroring; no picker on a single-duration type; picker still rendered when the grid is empty; hidden field survives the error re-render |
| `AdminResource` | the spare row creates a duration; clearing one removes it; clearing the default's row keeps the duration and drops its buffers; per-duration buffers persist |
| email | a 120-min booking's confirmation prints 120, not the type's 30 |
| i18n | every new key has a `de` and `he` line |

RestAssured cannot execute JavaScript, but nothing here needs it — the picker is links and the form
is a plain POST.

## Out of scope

- **Per-host duration restrictions.** Answered upstream: a host who will not run a length is a
  different meeting type.
- **Host requirement modes** (all hosts / any one host / named hosts). The reporter raised it as a
  possible future feature; it is unrelated to duration.
- **Changing length on reschedule.** Frozen by decision; cancel and rebook instead.
- **Sweeping `plusSeconds(60L * n)` out of untouched call sites.**

## Docs

`docs-site` branch: the meeting-type usage page gains the allowed-durations section and the
per-duration buffer semantics. Changelog bullet under `## Unreleased` at merge, naming both the
feature and the reschedule-length fix, with an upgrade note that no configuration or migration
action is required and existing single-duration types are unaffected.
