---
# calit-wilj
title: Tag outbox rows with their booking and owner
status: completed
type: task
priority: normal
created_at: 2026-09-16T13:56:03Z
updated_at: 2026-09-16T13:56:30Z
parent: calit-l3fk
---

Task 3: MailTag + EmailOutbox/MailSender/EmailService threading so erasure can purge parked mail by key instead of by recipient address


## Summary of Changes

- Created `MailTag` record (`bookingId`, `ownerId` both nullable) with `none()`, `forBooking(bookingId, ownerId)`, `forOwner(ownerId)` factories.
- `EmailOutbox`: added a 7-arg `enqueue(..., MailTag)` overload (persists `bookingId`/`ownerId` from the tag); the existing 6-arg form now delegates with `MailTag.none()`. Added `deleteForBooking(Long)` / `deleteForOwner(Long)` (both `long`, row count).
- `MailSender`: added `send(..., MailTag)` and `send(..., Instant, MailTag)` overloads; the two existing overloads delegate with `MailTag.none()`.
- `EmailService`: widened the private `MailSink` functional interface and `enqueueToOutbox` by one `MailTag` parameter. Tagged every booking-mail call site (`sendForKindLocaleAware`'s three `sink.deliver` calls — invitee, per-host group loop, single owner; `sendGuestInvites`, `handleGuestDeclined`, `sendGuestCancelMail`) with `MailTag.forBooking(...)` using the booking/owner actually being mailed (the per-host `hd.booking().id`/`hd.settings().ownerId` for the group loop, not the lead booking). `handleHostConsent` (not a booking mail — no booking id in scope) tagged with `MailTag.forOwner(cohost.ownerId)` since it's a mail *to* that owner. The three token-link mails (`sendPasswordReset`, `sendInvite`, `sendGoogleDisconnected`) keep the untagged overloads per the brief.
- New `OutboxTagTest` (4 tests, all passing) — the brief's version used literal ids (7L/8L bookings, owner 2) that don't exist and V34's outbox FKs reject them; rewrote it to persist real `Booking` rows (with a real `MeetingType`) and a real second `AppUser` + `OwnerSettings`, keeping the same assertions.
- Fixed a break in `UpdatedEmailTest` (unrelated to this task's file list, but the `MailSink` signature widen changed which `MailSender.send` overload `mailSender::send` resolves to, so the existing Mockito `verify(...).send(any(),to.capture(),subject.capture(),body.capture(),any())` stopped matching): added a 6th typed `any(MailTag.class)` matcher.

Full suite: `./mvnw -o test` → 1158/1158 passing, BUILD SUCCESS, output pristine.
