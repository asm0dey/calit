# Typed email templates (`@CheckedTemplate`) — design

Date: 2026-07-02
Status: approved (brainstorming)

## Problem

`EmailService` renders every email through injected `io.quarkus.qute.Template`
fields plus stringly-typed `.data("key", value)` chains (~25 `public static final
String` key constants, ~15 `.data()` calls per handler). Nothing checks that the
keys passed match the variables the template references. Rename a template var,
typo a key, or drop a value → **no build error**; the email silently renders
wrong (or blank) at send time. Every other JAX-RS resource in the codebase
(`PublicResource`, `AdminResource`, `PasswordResetResource`, …) already uses
Qute `@CheckedTemplate`, which validates template ↔ code at build. Email is the
lone holdout.

## Goal

Convert email rendering to `@CheckedTemplate` so template/code drift fails the
build. Match the existing codebase pattern exactly. No behaviour change.

## Approach: flat typed params (no view records)

One `native TemplateInstance` method per template, params = exactly the values
passed today, typed. This is the pattern every other resource uses (`book()`,
`manage()`, `dashboard()` are all flat params). Records were considered and
rejected: the ~9 booking-email shapes differ (reschedule needs
`oldStartTime`+`byOwner`, confirmation needs `ownerManageUrl`, requested needs
`approve`/`decline`), so a uniform record doesn't fit and per-template records
would add ~9 single-use types for zero validation gain over flat params (Qute
validates both identically). Flat params also keep email consistent with the
rest of the app — one pattern to maintain, not two.

Residual transposition risk on long `String` lists is real but small (param
names visible at the method decl, IntelliJ inlay hints at call sites) and
strictly better than the status quo, where `.data("startTime", ownerStart)` can
already bind the wrong value under a key silently.

## Changes

### 1. `EmailService.Templates` `@CheckedTemplate` inner class

Replace the 12 `@Inject @Location Template` fields with one inner
`@CheckedTemplate static class Templates` holding one `native TemplateInstance`
method per template. Use per-method `@Location("email/<file>.html")` (explicit;
handles hyphenated filenames like `guest-invite.html` that can't be method
names, and mirrors the current explicit `@Location` injection).

Method → params (typed to today's values):

| method | params |
|---|---|
| `requested` | recipientRole, lang, greetingName, inviteeName, meetingTypeName, startTime, `int` durationMinutes, location, `boolean` isMeetLink, manageUrl, cancelUrl, approveUrl, declineUrl, `List<AnswerLine>` answers |
| `confirmation` | recipientRole, lang, greetingName, inviteeName, meetingTypeName, startTime, durationMinutes, location, isMeetLink, manageUrl, ownerManageUrl, cancelUrl, answers |
| `declined` | recipientRole, lang, greetingName, inviteeName, meetingTypeName, startTime, durationMinutes |
| `reschedule` | recipientRole, `boolean` byOwner, lang, inviteeName, ownerName, greetingName, meetingTypeName, startTime, oldStartTime, durationMinutes, location, isMeetLink, manageUrl, ownerManageUrl, cancelUrl, answers |
| `updated` | recipientRole, byOwner, description, lang, inviteeName, ownerName, greetingName, meetingTypeName, startTime, durationMinutes, location, isMeetLink, manageUrl, ownerManageUrl, cancelUrl, answers |
| `cancellation` | recipientRole, byOwner, lang, inviteeName, ownerName, greetingName, meetingTypeName, startTime, durationMinutes |
| `reminder` | recipientRole, lang, greetingName, inviteeName, meetingTypeName, startTime, durationMinutes, location, isMeetLink, manageUrl, ownerManageUrl, cancelUrl, answers |
| `guestInvite` | lang, greetingName, inviteeName, meetingTypeName, startTime, durationMinutes, location, isMeetLink, declineGuestUrl |
| `guestCancel` | lang, greetingName, meetingTypeName, startTime |
| `guestDeclinedNotice` | lang, greetingName, guestEmail, meetingTypeName, startTime, manageUrl |
| `passwordReset` | lang, resetUrl |
| `googleDisconnected` | lang, accountEmail, reconnectUrl |

`confirmation` is reused by both the confirmed and approved paths (as today —
only the subject differs; the body call is identical).

Dead-data cleanup surfaced by typing: guest templates are currently passed
`recipientRole` (guest templates never branch on it) and `guestCancel` is passed
`durationMinutes` (not shown). These params are simply omitted — a free
correctness/maintainability win.

### 2. Call sites

Each `<template>.instance().setLocale(l).data(K, v)….render()` becomes
`Templates.<method>(v1, v2, …).setLocale(l).render()`. The `.setLocale()` /
`.render()` calls and the twice-per-role render loop (`bodyForRole`
`UnaryOperator`) are unchanged — the lambda just calls the typed method instead
of chaining `.data()`.

### 3. Delete the key constants

The ~25 `public static final String` key constants (`RECIPIENT_ROLE`,
`INVITEE_NAME`, `MANAGE_URL`, …) are referenced only inside `EmailService`
(verified: `grep` finds no external use) and become dead once `.data()` is gone.
Delete them.

### 4. Template param declarations

Each template needs `{@Type name}` declaration lines at the top for every
variable it references (some already present, e.g. `{@String recipientRole}`).
Bodies are otherwise unchanged. The shared included fragments must also declare
their params so `{#include}` validates:

- `_location.html` → `location` (String), `isMeetLink` (boolean)
- `_answers.html` → `answers` (`List<EmailService.AnswerLine>`)
- `_inviteelinks.html` → `manageUrl`, `cancelUrl` (String)
- `_ownerlinks.html` → `ownerManageUrl` (String)
- `layout.html` → already declares `lang`, `greetingName`

## Risk / open question

**Fragment validation under `@CheckedTemplate` is the one uncertain piece.** The
fragments (`_location`, `_answers`, `_inviteelinks`, `_ownerlinks`) receive their
data by inheriting the including template's scope, not via explicit `{#include …
x=y}` params. Adding `{@}` declarations to a fragment declares its params for
validation while values still flow from the parent's data context at runtime.
This is expected to work but must be confirmed at build. If Qute rejects
inherited-scope includes under strict validation, the fallback is passing the
fragment's data explicitly at each include site (`{#include email/_location
location=location isMeetLink=isMeetLink /}`). Resolve this on the first template
converted before doing the rest.

## Non-goals

- No de-duplication of the per-handler locale/format/label boilerplate in
  `EmailService` (every handler recomputes `inviteeLocale`/`ownerStart`/`label`).
  Out of scope — this change is about template typing only.
- No template content, wording, or i18n changes.
- No new dependency; `quarkus-qute` `@CheckedTemplate` is already in use.

## Verification

Qute `@CheckedTemplate` validation runs at build. `mvn quarkus:build` (or
`mvn test`, which builds) fails the compile on any template var not satisfied by
a method param, and vice versa — the drift the whole change targets. Existing
`EmailServiceTest` / booking-flow tests exercise every handler and assert on
rendered output; they must stay green. That is the self-check: green build +
green tests = every template still wired correctly.
