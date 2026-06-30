# Owner Manage Bookings + Email Sender Name — Design

**Date:** 2026-06-30
**Status:** Approved

## Goal

Let a meeting owner cancel or reschedule a booking from their `/me` dashboard
and from a link in notification emails, and make the email sender display name
read as `"{Owner} via calit"` instead of `"Notify"`.

## Background (current state)

Verified against the codebase:

- `BookingService.cancel(String manageToken)` and
  `BookingService.reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails)`
  **already exist** — used today by the attendee via the public
  `/booking/{manageToken}/...` routes. No new core booking logic is needed.
- The `/me` dashboard (`AdminResource.dashboard`, template
  `AdminResource/dashboard.html`) lists CONFIRMED future bookings **read-only** —
  no per-booking action. This is the gap for owner cancel/reschedule.
- The owner already receives approve/decline-from-email links for PENDING
  bookings (`AdminResource.approveFromEmail` / `declineFromEmail`, GET, guarded
  by `approvalToken` nonce). For CONFIRMED bookings there is no such action.
- Emails are sent with a **bare From address** (`quarkus.mailer.from`, no display
  name) so clients show "Notify" (derived from the local part `notify@`).
  `quarkus.mailer.from` (mailer From) and `app.mail-from` (ICS ORGANIZER address)
  are **already separate** config keys — the display name can change without
  touching the ICS ORGANIZER.
- Owner display name lives on `OwnerSettings.ownerName`
  (`OwnerSettings.forOwner(ownerId)`), not on `AppUser`. A booking carries
  `meetingTypeId`; `MeetingType.ownerId == AppUser.id == currentOwner.id()`.

## Decisions (from brainstorming)

1. **Reschedule UX:** owner picks from their **availability slots** (reuse the
   attendee slot picker), not an arbitrary datetime.
2. **From-email actions:** the email link lands on the **login-gated** `/me`
   manage page (`@RolesAllowed("user")`). No per-owner token; the session is the
   secret. Not-logged-in → login redirect → back.
3. **Sender display name:** per-message, `"{ownerName} via calit"`, e.g.
   `Pavel Finkelshtein via calit <notify@asm0dey.site>`. Address unchanged.

## Architecture

### Owner manage page (asks 1 + 3 collapse into one surface)

A single owner-facing "manage booking" page hosts both reschedule (slot picker)
and cancel. The dashboard links to it; owner emails link to it. Login-gated.

New routes on `AdminResource` (`@Path("/me")`, `@RolesAllowed("user")`):

- `GET /me/bookings/{id}/manage` — `requireOwnedBooking(id)` (404 if not the
  current owner's booking, reusing the existing helper used by approve/decline).
  Computes day-grouped slots from `bookingService.availableSlots(type, from, to)`
  (the no-exclude overload — the current slot is not offered, matching the
  attendee `manage.html`), renders `AdminResource/manageBooking.html`.
- `POST /me/bookings/{id}/reschedule` — form params `startUtc` (UTC ISO instant)
  + CSRF hidden input. `requireOwnedBooking(id)`, then
  `bookingService.reschedule(booking.manageToken, Instant.parse(startUtc), existingGuestEmails)`.
  303 redirect to `/me`.
- `POST /me/bookings/{id}/cancel` — CSRF hidden input. `requireOwnedBooking(id)`,
  then `bookingService.cancel(booking.manageToken)`. 303 redirect to `/me`.

Template `AdminResource/manageBooking.html`: mirrors `PublicResource/manage.html`
— a reschedule `<form method=post action="/me/bookings/{id}/reschedule">` with a
radio per slot (`name="startUtc"`, value `{slot.startUtc}`, grouped by day) and a
CSRF hidden input, plus a cancel `<form method=post action="/me/bookings/{id}/cancel">`
with a CSRF hidden input. Reuses existing `{msg:pub_manage_*}` strings for
headings/buttons. The slot picker reuses the same day-grouping shape as
`PublicResource.daySlots` (a ~15-line presentation loop) mirrored locally in
`AdminResource` with admin-local view records; both delegate to the single
availability source `bookingService.availableSlots`, so only cosmetic grouping
is duplicated. (`// ponytail:` note: extract a shared helper only if a third
consumer appears.)

`dashboard.html`: each upcoming-booking card gets a **Manage** link →
`/me/bookings/{id}/manage`.

### Owner emails link to the manage page

Owner-facing emails for CONFIRMED bookings gain a link to
`{app.base-url}/me/bookings/{id}/manage`:

- `confirmation.html` owner branch (`recipientRole == 'owner'`).
- `reschedule.html` owner copy.
- `reminder.html` owner copy.

Link text is a new `{msg:...}` key. The link is rendered only for the owner copy
(guard `recipientRole == 'owner'`). `EmailService` exposes the manage URL to the
owner render the same way `approveUrl`/`declineUrl` are exposed today
(builder method on `EmailService`, passed as template data on the owner copy).

### Email sender display name (ask 2)

`MailSender.sendNow(...)` gains a `String fromName` parameter (nullable). When
non-null it calls `mail.setFrom(fromName + " <" + mailFrom + ">")` after
`Mail.withHtml(...)`; when null it leaves From to config (`quarkus.mailer.from`).

`MailSender` reads the bare address from a new injected
`@ConfigProperty("app.mail-from") String mailFrom` (same key `EmailService`
already uses for the ICS ORGANIZER). `EmailService` computes the display name as
`ownerName + " via calit"` (owner name from the loaded `OwnerSettings.ownerName`)
and passes it to `sendNow` for all booking emails. Non-booking emails
(password-reset, google-disconnected) pass `null`.

`"via calit"` is hardcoded with a `// ponytail:` comment pointing at where to add
an `app.brand-name` config if rebranding is ever needed (YAGNI now).

SPF/DKIM and ICS ORGANIZER are unaffected — only the From header's display name
changes; the envelope/header address stays `app.mail-from`.

## Data flow

```
Owner clicks Manage (dashboard or email link)
  -> GET /me/bookings/{id}/manage  [login-gated, owner-scoped]
  -> manageBooking.html: slot radios + cancel button

Reschedule: POST /me/bookings/{id}/reschedule {startUtc, csrf}
  -> requireOwnedBooking -> bookingService.reschedule(manageToken, start, guests)
  -> (existing) BookingRescheduled event -> emails (now with display-name From)
  -> 303 /me

Cancel: POST /me/bookings/{id}/cancel {csrf}
  -> requireOwnedBooking -> bookingService.cancel(manageToken)
  -> (existing) BookingCancelled event -> emails
  -> 303 /me
```

## Error handling

- Not the owner's booking → `requireOwnedBooking` throws 404 (existing behavior).
- `bookingService.reschedule` already rejects CANCELLED/DECLINED bookings and
  validates slot availability (`assertSlotAvailable`) — surfaced via existing
  exception mappers. A stale/taken slot returns the existing conflict response.
- Missing/invalid CSRF → 400 in prod (existing `quarkus-rest-csrf`), off in
  `%test`.
- `setFrom` with a non-ASCII owner name (Hebrew/German) → Vert.x encodes the
  header. No special handling.

## Testing

`@QuarkusTest` + RestAssured, admin always id 1, owner-scoped invariants
(per `CLAUDE.md` test infra notes). Mirror existing `AdminResource` auth/test
helpers for an authenticated `/me` session.

- Owner can GET the manage page for their own booking (200); GET for another
  owner's booking → 404.
- POST reschedule moves the booking to the chosen slot (assert new `startUtc`,
  `icsSequence` bumped) and redirects to `/me`.
- POST cancel sets status CANCELLED and redirects to `/me`.
- Reschedule/cancel of a booking the owner does not own → 404, no mutation.
- `MockMailbox`: a booking email's `getFrom()` carries
  `"{ownerName} via calit <…>"`; a password-reset email does not (null fromName).

## i18n

New keys, English default + `de` + `he` (per `CLAUDE.md`: both translations ship
in the same change):

- `AdminMessages` (`adm`): `adm_dashboard_btn_manage` — "Manage" / "Verwalten" /
  "ניהול".
- `AppMessages` (`msg`): owner email manage-link text, e.g.
  `email_body_owner_manage_link_text` — "Reschedule or cancel" / "Verschieben
  oder stornieren" / "שינוי מועד או ביטול".

The manage page itself reuses existing `pub_manage_*` keys, so no new page
strings.

## Docs (part of done)

- `docs-site` branch: changelog entry on next release; usage note that owners can
  now reschedule/cancel from `/me` and from email; note the sender name change.

## Out of scope (YAGNI)

- Arbitrary-datetime reschedule (slot picker chosen).
- Per-owner email token (login-gated chosen).
- `app.brand-name` config (hardcode "via calit" until a rebrand is real).
- A "RESCHEDULED" booking status (reschedule mutates in place + bumps sequence,
  as today).
