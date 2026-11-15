# Multi-slot booking — design

Status: approved design, pre-plan · 2026-11-15
Prior art: [Cal ID — Multi-Slot Booking](https://cal.id/docs/event-types/multi-slot-booking)
Mockup: `calit · multi-slot booking mockup` artifact (claude.ai, owner-only) — the four views below.

Vocabulary follows `CONTEXT.md`. Two new terms, added there in the same change: a **Booking set** —
the Bookings one Invitee made of one meeting type in a single submit — and a **Proposal** — several
times one Invitee offers, of which a Host confirms one. Invitee-facing copy calls each Booking a
"session"; this document says Booking everywhere except in quoted copy.

The feature has two modes sharing one picking UI:

- **Set** — the Invitee books several slots and keeps them all (sections up to "Behaviour").
- **Proposal** — the Invitee offers several slots and a Host confirms one (section "Proposal mode").

## Intent

A user asked for it. An Owner can let an Invitee make **several Bookings of one meeting type in one
booking flow** — a coaching package, a block of tutoring — instead of coming back to the page N
times. Each Booking stays a normal calit Booking (own manage link, reschedule, cancel); they are tied
together as a Booking set so they are confirmed, approved and (optionally) cancelled together.

And, the second use-case (user's request): an interview candidate offers several times that suit
them, and the interviewer confirms the one that suits them best. Only one Booking results.

## Goals

- The Owner turns it on per meeting type: **Off**, **the Invitee picks between _min_ and _max_**,
  **exactly _N_**, or **the Invitee proposes up to _N_ and a Host confirms one**; cap 5.
  Plus a toggle **"Allow more than one session on the same day"** (default off).
- The Invitee picks the slots on the existing booking page, then fills the form **once**.
- When the meeting type has several allowed durations, **each Booking in the set can have its own
  duration**.
- The set is booked **atomically**: one slot unavailable → nothing is booked.
- **Approval is all-or-nothing**: one request for the whole set, one approve / decline.
- **Meeting types with Co-hosts**: every slot must be free for every Host; any failure refuses the
  whole set.
- After booking, each Booking is managed independently; the manage page also offers **Cancel all N**.
- **Proposal mode** holds nothing while waiting; any Host confirms one time, which is booked then.
- Works **without JavaScript** (project rule). JS only adds live feedback.

## Non-goals

- Recurring Bookings (fixed cadence generated from one pick) — different feature.
- Payments / packages priced as a unit.
- Booking a set through the JSON API (`POST /api/bookings`) — stays single-slot (see Builder notes).
- Changing anything for meeting types with multi-slot **off**: their page, flow and emails stay
  byte-identical.

## Anti-goals (failure even if it "works")

- Half-booked sets (some Bookings held, others not).
- An Invitee silently losing their picks when switching duration or hitting a validation error.
- Invitee PII (name, email, answers) in a URL.
- An Invite's page (or anything reached from it) revealing the meeting type's `/{user}/{slug}`.
- One Invitee able to drain a week of availability (hence the cap of 5 and the per-email cap below).

## Approaches considered (booking page)

| | How | Trade-off |
|---|---|---|
| A. Basket via links | each slot is an "Add" link; picks ride in `?pick=` query; rail lists them | any mix of durations, but without JS every pick is a full reload |
| **B. Checkboxes + details step (chosen)** | step 1 is a GET form of checkboxes; duration buttons and Continue resubmit it; step 2 is the existing details form with picks as hidden inputs | picking within one duration needs no reload; PII only enters at step 2; one new step, only on multi-slot meeting types |

Chosen by the user after reviewing the mockup.

## The decided design

### Owner: meeting-type settings (mockup view "Owner")

A fieldset **"Several sessions per booking"** on the meeting-type edit form:

- radio **Off — one session per booking** (default)
- radio **Invitee picks between [min] and [max] sessions**
- radio **Exactly [N] sessions (a package)**
- radio **Invitee proposes up to [N] times, you confirm one** (2 ≤ N ≤ 5)
- divider, then a **toggle** (daisyUI `toggle`, not a round checkbox) **Allow more than one session on
  the same day** — shown only for the two set modes
- helper text: "Up to 5. Approval, co-hosts and invites apply to the whole set."

Validation: 1 ≤ min ≤ max ≤ 5; "exactly N" stores min = max = N with 2 ≤ N ≤ 5. Proposal mode
stores max = N, min = 1 (the Invitee may offer a single time) and a mode flag; it implies approval,
so the meeting type's "requires approval" setting is shown on and locked while it is selected.
A range with min = 1 is allowed (the Invitee may make one Booking or several).

### Invitee step 1 — pick slots (mockup view 1)

Same page and layout as today (`PublicResource/book.html`), changed only when multi-slot is on:

- Steps header: **1 Pick sessions · 2 Your details**.
- Duration switch (only when the meeting type has several allowed durations) labelled **"Length of
  the next session"** with hint "Each session can have its own length. Switching keeps what you
  already picked."
- Slots are **checkboxes** (`name="pick"`, value `<startUtc>/<minutes>`), styled like today's radio
  pills via `:checked + time`.
- Slots that cannot be picked are **disabled** (dashed border, faded) with a one-line reason under the
  day's slots: "You already have a session this day" (same-day off), "Overlaps a session you picked",
  "You have the maximum of N".
- A slot already picked at another duration shows a small accent badge ("60m") on that time.
- Calendar days holding a pick get an accent dot.
- Left rail gains a **"Your sessions"** box: count badge `n / max`, a `role="status" aria-atomic="true"`
  sentence ("2 sessions picked. You can add up to 3 more." / "Pick at least 2."), and the list of picks
  (date · time · duration badge · ✕ remove).
- Primary button: "Pick at least N sessions" (disabled) → "Continue with N sessions".
- Without JS: the whole step is one `GET` form. Duration buttons are submit buttons
  (`name="duration"`) so checked picks travel with them; ✕ remove is a submit button too; Continue
  submits to step 2. Picks at other durations are carried as hidden inputs and listed in the rail.
  Server-side, the same rules (min/max, same-day, overlap) decide what is rendered disabled.

### Invitee step 2 — details (mockup view 2)

The existing form fields (name, email, guests, custom fields, captcha, honeypot), plus:

- the picks as hidden `pick` inputs, listed in the rail;
- an info alert: "You're booking **N sessions**. They're confirmed together — if one of them was just
  taken, none are booked and you pick again.";
- buttons "‹ Change sessions" (back to step 1 with picks preserved) and **"Confirm N sessions"**
  ("Request N sessions" for meeting types that require approval).

On a conflict the Invitee lands back on step 1 with their picks preserved, the taken one(s) dropped,
and an error alert naming them.

### Confirmation (mockup view 3)

"You're booked: N sessions" (or the pending wording for meeting types that require approval), one row
per Booking — "k of N", date, time range, duration, **Join Meet / Reschedule / Cancel** — plus "Add
all to calendar (.ics)" and **Cancel all N sessions**.

### Manage page

The existing `/booking/{manageToken}/manage` page for a Booking in a set also lists the other
Bookings of the set (each linking to its own manage page) and offers **Cancel all** for those still
held.

### Behaviour

- **Set identity.** New nullable `booking.set_id uuid`. All rows of a Booking set share it
  (independent of `group_id`, which keeps meaning "per-Host rows of one Booking on a meeting type with
  Co-hosts"). A set of 3 Bookings × 2 Hosts = 6 rows: 3 `group_id`s, 1 `set_id`.
- **Atomic booking.** One transaction books every pick through the existing single-Booking path
  (availability re-check, `booking_no_overlap_held` flush); any `BookingConflictException` rolls back
  the whole set. Validations that are per-form (captcha, honeypot, required fields, email) run once.
- **Rules enforced server-side** on submit: count within [min, max], each duration in the allowed
  durations, no two picks overlapping, same-day rule, each pick individually bookable (min-notice,
  horizon, buffers).
- **Approval.** One request for the set: one Owner email listing every Booking with one Approve /
  Decline. Approving or declining any row of a set applies to all of that Host's rows in the set
  (with Co-hosts: each Host approves the set once; the set confirms when every Host has).
- **Pending expiry** (my call, follows from all-or-nothing): a pending set expires **as a unit** at the
  earliest of `created_at + hold` and its **first** Booking's start. `PendingExpiryScheduler` locks per
  set, declines every row, sends one expiry email for the set. A late approve after that is a no-op.
- **A single pick is a plain Booking** (my call): a range with min = 1 where the Invitee picks one
  slot books exactly as today — no `set_id`, today's emails and pages. Sets always have ≥ 2.
- **Owner notification channels** (my call): one "new booking / request" notification per set, driven
  by the same set-shaped event as the emails.
- **Guests** (user's call): a guest gets **one invitation email for the set** with one `.ics` (one
  VEVENT per Booking) and **one decline link** that removes them from every Booking in the set.
  "Cancel all" sends each guest one cancellation for the set.
- **Invite**: consumed once by the whole set.
- **An Invite never discloses the meeting type's URL** (user's requirement). Today's
  `/{user}?invite=` page already hides `/{user}/{slug}` (`BookingInviteRoutesTest` pins it). Every new
  surface reached through an Invite must keep that: step 1's GET form action, duration / remove /
  "Change sessions" submits, step 2, the conflict bounce, the set confirmation page, the manage page and
  the set emails all use the Invite's URL or token-based URLs, never the slug. Extend
  `BookingInviteRoutesTest` with the same `not(containsString(SLUG))` check on each.
- **Per-email daily cap** (`PER_EMAIL_DAILY_CAP`): a set counts as **one** booking, like a group does.
- **Emails.** One confirmation (or request) email for the set listing every Booking, with **one `.ics`
  containing one VEVENT per Booking** (each its own UID). Later reschedule / cancel of one Booking sends
  today's single-Booking emails. "Cancel all" sends one cancellation email listing the cancelled
  Bookings.
- **Mirror events**: one per Booking, as today.
- **Rescheduling one Booking**: normal reschedule rules; the other Bookings of the set are held, so
  they already block overlap. Set rules (same-day) are not re-checked on reschedule (my call — keeps
  reschedule unchanged).
- **Owner dashboard**: one row per Booking, tagged "k of N".
- **Reminders**: per Booking, unchanged.

### Proposal mode — the Invitee proposes, a Host confirms one

The interview use-case. Same step 1 and step 2 as a set (checkboxes, then details once), with
proposal wording; the rules differ:

- **Nothing is held while waiting** (user's call). A Proposal is not a Booking: no slot is blocked,
  no Mirror event is created, other Invitees can still book the proposed times.
- **Confirming books.** A Host picks one time; that runs the normal single-Booking path (availability
  re-check, buffers, `booking_no_overlap_held`) and creates a **confirmed** Booking — the confirm is
  the approval. If the time was taken meanwhile: "That time was just taken", the other times stay
  confirmable.
- **Co-hosts** (user's call): every Host sees the Proposal; **any Host may confirm**, first one wins,
  and only on a time free for every Host at that moment (the existing group path). Later confirms on
  the same Proposal are a no-op with "Already confirmed by …".
- **Host UI**: the request email lists each proposed time with its own **Confirm** link, plus one
  **Decline**. Links follow today's approve-link pattern (Owner-authenticated
  `/me/proposals/{id}/confirm?time=…&t=<token>`, the token a CSRF nonce). The dashboard shows a
  Proposal as one row with each time marked **free / taken now**; a taken time's Confirm is disabled.
- **Invitee**: confirmation page "Waiting for {Owner} to pick one", listing the proposed times, and a
  token page to **withdraw** the Proposal (my call). On confirm: today's single-Booking confirmation
  email; on decline or expiry: one email "No time was confirmed".
- **Expiry** (my call): a proposed time whose start has passed drops off; the Proposal expires when no
  time is left or `created_at + hold` passes, whichever is first. Swept by `PendingExpiryScheduler`
  (same `FOR UPDATE SKIP LOCKED` pattern).
- **Guests** (my call): get nothing until a time is confirmed, then today's single-Booking invitation.
- **Invite** (my call): the Proposal consumes it on submit, like a committed booking does — otherwise
  one Invite could send unlimited Proposals. A declined or expired Proposal does not give it back,
  matching "consumed even if cancelled". The Invite's URL rule applies to every Proposal page and
  email.
- **Per-email daily cap**: a Proposal counts as one booking.
- **Owner notification channels**: one "new request" notification per Proposal.
- Proposals never count toward availability, so they never appear in the slot computation.

### Data (expand-only, previous release keeps working)

- `meeting_type.multi_slot_min int null`, `meeting_type.multi_slot_max int null` (both null = off),
  `meeting_type.multi_slot_same_day boolean not null default false`.
- `booking.set_id uuid null` + index.
- `meeting_type.multi_slot_mode text null` (`SET` / `PROPOSAL`; null = off).
- New tables (my call: separate tables, not a new Booking status — the previous release reads
  `booking` and would fail on an unknown status): `booking_proposal` (owner, meeting type, Invitee
  fields and answers as a Booking stores them, status `PENDING/CONFIRMED/DECLINED/EXPIRED/WITHDRAWN`,
  confirm token, manage token, `created_at`, `booking_id` once confirmed) and
  `booking_proposal_time` (proposal, `start_utc`, `minutes`).
- The previous release ignores these columns; a multi-slot meeting type served by it takes single
  Bookings.

### i18n

Every new string gets `en` default + `de` + `he` in the same change (project rule). Invitee-facing
copy says **"sessions"**.

## Left to the builder

- Exact Qute structure and whether step 1 / step 2 are one template with a mode flag or two.
- Same-day means the **Owner's timezone** day (the page already groups slots that way).
- Captcha, honeypot, Invite consumption and the per-email cap run once per submit, lifted out of the
  per-Booking path (a Turnstile token is single-use; `consumeInvite` deletes the Invite).
- Create Mirror events only after every row of the set is flushed, so a conflict on the last pick
  leaves no Mirror events behind.
- Rolling deploy: the previous release's per-email cap query counts each row of a set separately —
  acceptable (it only makes the cap stricter during the deploy).
- How events carry the set (e.g. one `BookingRequested`/`BookingConfirmed` for the set's first row,
  listeners expanding by `set_id`) — any shape that yields one email per set.
- The JSON API: keep single-slot; a meeting type whose `min > 1` should refuse single-slot API
  bookings with the existing validation error.
- Proposal-mode copy for the Invitee steps ("Pick times that suit you", "Send N proposed times").
- Docs-site page + `## Unreleased` changelog bullet (project rule).
