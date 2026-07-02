# Typed Email Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `EmailService` from stringly-typed `Template.data(key,value)` chains to Qute `@CheckedTemplate` so template↔code drift fails the build.

**Architecture:** Replace the 12 injected `@Location Template` fields with one `@CheckedTemplate static class Templates` inner class holding one `native TemplateInstance` method per template, params typed to exactly the values passed today. Each `.data(K,v)…render()` chain becomes a typed method call; `.setLocale()`/`.render()` and the twice-per-role render loop are unchanged. Templates gain `{@Type name}` declaration lines; shared include fragments gain their own param declarations so includes validate.

**Tech Stack:** Quarkus 3.36, Qute `@CheckedTemplate`, Java 25, JUnit/RestAssured (`@QuarkusTest`).

## Global Constraints

- **No behaviour change.** Every existing email test must stay green; wording, i18n, subjects, ics attachment logic unchanged.
- **Verification is the build + tests.** Qute `@CheckedTemplate` validation runs at build; a template variable with no matching method param (or vice versa) fails compile. Per-task check: `mvn test -Dtest='site.asm0dey.calit.email.*'` (builds + runs the email suite). Docker must be running.
- **Pattern to match:** existing resources (`PublicResource.Templates`, `PasswordResetResource.Templates`) — flat typed params, `native TemplateInstance` methods, no view records.
- **`AnswerLine` type:** `site.asm0dey.calit.email.EmailService.AnswerLine` (public record `{String label, String value}`), already exists. Reused as `java.util.List<...AnswerLine>`.
- **Template location:** templates live in `src/main/resources/templates/email/`. Native methods carry `@io.quarkus.qute.Location("email/<file>.html")` (mirrors today's injection). If the build rejects `@Location` on a `@CheckedTemplate` native method, fall back to `@CheckedTemplate(basePath = "email")` on the class and rename the 5 hyphenated leaf templates (`guest-invite.html` → `guestInvite.html` etc.; they are leaves, included by nothing — verified — so renaming is safe). Resolve this in Task 1.
- **Commits:** end messages with the Co-Authored-By + Claude-Session trailers already configured for this repo. Work on branch `typed-email-templates` (already checked out).

---

### Task 1: Scaffold `Templates` class, convert `reminder`, declare all fragments

De-risks the one uncertain piece: whether `{#include}` fragments validate under `@CheckedTemplate` when they declare their own params but receive data by inherited scope. `reminder` includes all four shared fragments (`_location`, `_answers`, `_inviteelinks`, `_ownerlinks`) plus `layout`, so converting it exercises every fragment.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` (add `Templates` inner class; convert `deliverReminder`; remove injected `reminder` field)
- Modify: `src/main/resources/templates/email/reminder.html` (add `{@}` decls)
- Modify: `src/main/resources/templates/email/_location.html` (add `{@}` decls)
- Modify: `src/main/resources/templates/email/_answers.html` (add `{@}` decls)
- Modify: `src/main/resources/templates/email/_inviteelinks.html` (add `{@}` decls)
- Modify: `src/main/resources/templates/email/_ownerlinks.html` (add `{@}` decls)
- Test (existing, must stay green): `src/test/java/site/asm0dey/calit/email/EmailEnqueueTest.java`, `EmailServiceFallbackTest.java`, `EmailRoleCopyTest.java`

**Interfaces:**
- Produces: `EmailService.Templates` `@CheckedTemplate static class` with, after this task, one method:
  `static native TemplateInstance reminder(String recipientRole, String lang, String greetingName, String inviteeName, String meetingTypeName, String startTime, int durationMinutes, String location, boolean isMeetLink, String manageUrl, String ownerManageUrl, String cancelUrl, java.util.List<AnswerLine> answers)`
- Produces: fragment param contracts later tasks rely on — `_location`{location,isMeetLink}, `_answers`{answers}, `_inviteelinks`{manageUrl,cancelUrl}, `_ownerlinks`{ownerManageUrl}.

- [ ] **Step 1: Add fragment param declarations**

Prepend to `src/main/resources/templates/email/_location.html`:
```html
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
```
Prepend to `src/main/resources/templates/email/_answers.html`:
```html
{@java.util.List<site.asm0dey.calit.email.EmailService$AnswerLine> answers}
```
Prepend to `src/main/resources/templates/email/_inviteelinks.html`:
```html
{@java.lang.String manageUrl}
{@java.lang.String cancelUrl}
```
Prepend to `src/main/resources/templates/email/_ownerlinks.html`:
```html
{@java.lang.String ownerManageUrl}
```
(Note the `$` nested-class separator in the fully-qualified `AnswerLine` type — Qute type declarations use the binary name for nested classes.)

- [ ] **Step 2: Add param declarations to `reminder.html`**

`reminder.html` currently starts `{@java.lang.String recipientRole}` then `{#include email/layout}`. `layout` already declares `lang` + `greetingName`. Add the remaining vars the body/includes reference. Prepend after the existing `recipientRole` line:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String inviteeName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
{@java.lang.String manageUrl}
{@java.lang.String ownerManageUrl}
{@java.lang.String cancelUrl}
{@java.util.List<site.asm0dey.calit.email.EmailService$AnswerLine> answers}
```

- [ ] **Step 3: Add the `@CheckedTemplate` `Templates` inner class**

In `EmailService.java`, add these imports if missing: `io.quarkus.qute.CheckedTemplate`, `io.quarkus.qute.TemplateInstance` (keep `io.quarkus.qute.Location`). Add the inner class (place it just above the `MailSink` interface, ~line 173):
```java
@CheckedTemplate
static class Templates {
    @Location("email/reminder.html")
    static native TemplateInstance reminder(
            String recipientRole,
            String lang,
            String greetingName,
            String inviteeName,
            String meetingTypeName,
            String startTime,
            int durationMinutes,
            String location,
            boolean isMeetLink,
            String manageUrl,
            String ownerManageUrl,
            String cancelUrl,
            java.util.List<AnswerLine> answers);
}
```

- [ ] **Step 4: Remove the injected `reminder` field and rewrite `deliverReminder`**

Delete the field (lines ~114-116):
```java
@Inject
@Location("email/reminder.html")
Template reminder;
```
Replace the body-builder lambda in `deliverReminder` (currently `return reminder.instance().setLocale(locale).data(...)....render();`, lines ~531-546) with:
```java
role -> {
    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
    return Templates.reminder(
                    role,
                    locale.getLanguage(),
                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                    l.booking.inviteeName,
                    label(l),
                    start,
                    l.meetingType.durationMinutes,
                    location,
                    isMeet(l),
                    manageUrl(l.booking),
                    ownerManageUrl(l.booking),
                    cancelUrl(l.booking),
                    l.answers)
            .setLocale(locale)
            .render();
},
```
(3rd arg = `greetingName`: invitee name for the invitee copy, owner name for the owner copy — same conditional the old `.data(GREETING_NAME, …)` used.)

- [ ] **Step 5: Run the email suite — verify fragment validation and green tests**

Run: `mvn test -Dtest='site.asm0dey.calit.email.*'`
Expected: BUILD SUCCESS, all tests pass. Critically, the build's Qute validation accepted the fragment `{@}` declarations with inherited-scope includes. If instead the build fails with a Qute validation error naming a fragment variable, apply the fallback from Global Constraints (pass fragment data explicitly at each include site, e.g. `{#include email/_location location=location isMeetLink=isMeetLink /}`) and re-run.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(email): typed @CheckedTemplate for reminder + fragment decls"
```

---

### Task 2: Convert `requested`, `confirmation` (+approved), `declined`

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/resources/templates/email/requested.html`, `confirmation.html`, `declined.html`
- Test (existing): `EmailRoleCopyTest.java`, `EmailServiceFallbackTest.java`

**Interfaces:**
- Consumes: `EmailService.Templates` (from Task 1), fragment contracts from Task 1.
- Produces: `Templates.requested(...)`, `Templates.confirmation(...)`, `Templates.declined(...)` with signatures below.

- [ ] **Step 1: Add param decls to the three templates**

`requested.html` already declares `recipientRole`, `approveUrl`, `declineUrl`. Add after them:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String inviteeName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
{@java.lang.String manageUrl}
{@java.lang.String cancelUrl}
{@java.util.List<site.asm0dey.calit.email.EmailService$AnswerLine> answers}
```
`confirmation.html` already declares `recipientRole`. Add:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String inviteeName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
{@java.lang.String manageUrl}
{@java.lang.String ownerManageUrl}
{@java.lang.String cancelUrl}
{@java.util.List<site.asm0dey.calit.email.EmailService$AnswerLine> answers}
```
`declined.html` already declares `recipientRole`. Add:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String inviteeName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
```

- [ ] **Step 2: Add the three native methods to `Templates`**

```java
@Location("email/requested.html")
static native TemplateInstance requested(
        String recipientRole, String lang, String greetingName, String inviteeName,
        String meetingTypeName, String startTime, int durationMinutes,
        String location, boolean isMeetLink,
        String manageUrl, String cancelUrl, String approveUrl, String declineUrl,
        java.util.List<AnswerLine> answers);

@Location("email/confirmation.html")
static native TemplateInstance confirmation(
        String recipientRole, String lang, String greetingName, String inviteeName,
        String meetingTypeName, String startTime, int durationMinutes,
        String location, boolean isMeetLink,
        String manageUrl, String ownerManageUrl, String cancelUrl,
        java.util.List<AnswerLine> answers);

@Location("email/declined.html")
static native TemplateInstance declined(
        String recipientRole, String lang, String greetingName, String inviteeName,
        String meetingTypeName, String startTime, int durationMinutes);
```

- [ ] **Step 3: Remove injected fields and rewrite the call sites**

Delete injected fields `requested`, `confirmation`, `declined` (the `@Inject @Location Template` blocks, ~lines 90-100).

In `handleRequested` (~lines 248-265), replace the `requested.instance()….render()` lambda body with:
```java
return Templates.requested(
                role,
                locale.getLanguage(),
                INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                l.booking.inviteeName,
                label(l),
                start,
                l.meetingType.durationMinutes,
                location,
                isMeet(l),
                manageUrl(l.booking),
                cancelUrl(l.booking),
                approveUrl(l.booking),
                declineUrl(l.booking),
                l.answers)
        .setLocale(locale)
        .render();
```
In `handleConfirmed` (~lines 285-301) **and** `handleApproved` (~lines 323-339) — both use the `confirmation` template — replace each lambda body with:
```java
return Templates.confirmation(
                role,
                locale.getLanguage(),
                INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                l.booking.inviteeName,
                label(l),
                start,
                l.meetingType.durationMinutes,
                location,
                isMeet(l),
                manageUrl(l.booking),
                ownerManageUrl(l.booking),
                cancelUrl(l.booking),
                l.answers)
        .setLocale(locale)
        .render();
```
In `deliverDeclined` (~lines 372-381), replace the lambda body with:
```java
return Templates.declined(
                role,
                locale.getLanguage(),
                INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                l.booking.inviteeName,
                label(l),
                start,
                l.meetingType.durationMinutes)
        .setLocale(locale)
        .render();
```

- [ ] **Step 4: Run the email suite**

Run: `mvn test -Dtest='site.asm0dey.calit.email.*'`
Expected: BUILD SUCCESS, all pass. `EmailRoleCopyTest.requestedOwnerCopyGreetsOwnerNamesInviteeAndLinksApproveDecline`, `requestedInviteeCopy…`, `confirmationOwnerCopyNamesInviteeAndHasOwnerManageLink` exercise these paths directly.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(email): typed @CheckedTemplate for requested/confirmation/declined"
```

---

### Task 3: Convert `reschedule`, `updated`, `cancellation`

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/resources/templates/email/reschedule.html`, `updated.html`, `cancellation.html`
- Test (existing): `EmailEnqueueTest.detailsChangeEmailsBothPartiesWithNewNameAndDescription`, `EmailServiceFallbackTest.java`, `EmailRoleCopyTest.java`

**Interfaces:**
- Consumes: `Templates`, fragment contracts.
- Produces: `Templates.reschedule(...)`, `Templates.updated(...)`, `Templates.cancellation(...)`.

- [ ] **Step 1: Add param decls**

`reschedule.html` already declares `recipientRole`, `byOwner`. Add:
```html
{@java.lang.String lang}
{@java.lang.String inviteeName}
{@java.lang.String ownerName}
{@java.lang.String greetingName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.String oldStartTime}
{@java.lang.Integer durationMinutes}
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
{@java.lang.String manageUrl}
{@java.lang.String ownerManageUrl}
{@java.lang.String cancelUrl}
{@java.util.List<site.asm0dey.calit.email.EmailService$AnswerLine> answers}
```
`updated.html` already declares `recipientRole`, `byOwner`, `description`. Add:
```html
{@java.lang.String lang}
{@java.lang.String inviteeName}
{@java.lang.String ownerName}
{@java.lang.String greetingName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
{@java.lang.String manageUrl}
{@java.lang.String ownerManageUrl}
{@java.lang.String cancelUrl}
{@java.util.List<site.asm0dey.calit.email.EmailService$AnswerLine> answers}
```
`cancellation.html` already declares `recipientRole`, `byOwner`. Add:
```html
{@java.lang.String lang}
{@java.lang.String inviteeName}
{@java.lang.String ownerName}
{@java.lang.String greetingName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
```

- [ ] **Step 2: Add native methods to `Templates`**

```java
@Location("email/reschedule.html")
static native TemplateInstance reschedule(
        String recipientRole, boolean byOwner, String lang, String inviteeName, String ownerName,
        String greetingName, String meetingTypeName, String startTime, String oldStartTime,
        int durationMinutes, String location, boolean isMeetLink,
        String manageUrl, String ownerManageUrl, String cancelUrl,
        java.util.List<AnswerLine> answers);

@Location("email/updated.html")
static native TemplateInstance updated(
        String recipientRole, boolean byOwner, String description, String lang,
        String inviteeName, String ownerName, String greetingName, String meetingTypeName,
        String startTime, int durationMinutes, String location, boolean isMeetLink,
        String manageUrl, String ownerManageUrl, String cancelUrl,
        java.util.List<AnswerLine> answers);

@Location("email/cancellation.html")
static native TemplateInstance cancellation(
        String recipientRole, boolean byOwner, String lang, String inviteeName, String ownerName,
        String greetingName, String meetingTypeName, String startTime, int durationMinutes);
```

- [ ] **Step 3: Remove injected fields and rewrite call sites**

Delete injected fields `reschedule`, `cancellation`, `updated`.

In `handleRescheduled` (~lines 412-431) replace the lambda body with:
```java
return Templates.reschedule(
                role,
                e.byOwner(),
                locale.getLanguage(),
                l.booking.inviteeName,
                l.owner.ownerName,
                INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                label(l),
                newStart,
                oldStart,
                l.meetingType.durationMinutes,
                location,
                isMeet(l),
                manageUrl(l.booking),
                ownerManageUrl(l.booking),
                cancelUrl(l.booking),
                l.answers)
        .setLocale(locale)
        .render();
```
In `handleDetailsChanged` (~lines 453-471) replace the lambda body with:
```java
return Templates.updated(
                role,
                e.byOwner(),
                desc,
                locale.getLanguage(),
                l.booking.inviteeName,
                l.owner.ownerName,
                INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                label(l),
                start,
                l.meetingType.durationMinutes,
                location,
                isMeet(l),
                manageUrl(l.booking),
                ownerManageUrl(l.booking),
                cancelUrl(l.booking),
                l.answers)
        .setLocale(locale)
        .render();
```
In `handleCancelled` (~lines 493-505) replace the lambda body with:
```java
return Templates.cancellation(
                role,
                e.byOwner(),
                locale.getLanguage(),
                l.booking.inviteeName,
                l.owner.ownerName,
                INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                label(l),
                start,
                l.meetingType.durationMinutes)
        .setLocale(locale)
        .render();
```

- [ ] **Step 4: Run the email suite**

Run: `mvn test -Dtest='site.asm0dey.calit.email.*'`
Expected: BUILD SUCCESS, all pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(email): typed @CheckedTemplate for reschedule/updated/cancellation"
```

---

### Task 4: Convert guest templates (`guestInvite`, `guestCancel`, `guestDeclinedNotice`)

Drops dead params surfaced by typing: `guest-invite`/`guest-cancel` are passed `recipientRole` they never reference; `guest-cancel` is passed `durationMinutes` it never shows. The typed methods omit them.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/resources/templates/email/guest-invite.html`, `guest-cancel.html`, `guest-declined.html`
- Test (existing): `EmailServiceFallbackTest` (guest fan-out cases: `confirmedSendsGuestIcsWhenGoogleDisconnected`, `guestDeclinedNotifiesInviteeAndCancelsThatGuest`, `guestRemovedSendsCancelToThatGuestOnly`, `cancelledSendsGuestCancel`)

**Interfaces:**
- Consumes: `Templates`, `layout`{lang,greetingName}, `_location` fragment.
- Produces: `Templates.guestInvite(...)`, `Templates.guestCancel(...)`, `Templates.guestDeclinedNotice(...)`.

- [ ] **Step 1: Add param decls**

`guest-invite.html` (no current decls; first line is `{#include email/layout}`). Prepend:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String inviteeName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.Integer durationMinutes}
{@java.lang.String location}
{@java.lang.Boolean isMeetLink}
{@java.lang.String declineGuestUrl}
```
`guest-cancel.html`. Prepend:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
```
`guest-declined.html`. Prepend:
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String guestEmail}
{@java.lang.String meetingTypeName}
{@java.lang.String startTime}
{@java.lang.String manageUrl}
```

- [ ] **Step 2: Add native methods to `Templates`**

```java
@Location("email/guest-invite.html")
static native TemplateInstance guestInvite(
        String lang, String greetingName, String inviteeName, String meetingTypeName,
        String startTime, int durationMinutes, String location, boolean isMeetLink,
        String declineGuestUrl);

@Location("email/guest-cancel.html")
static native TemplateInstance guestCancel(
        String lang, String greetingName, String meetingTypeName, String startTime);

@Location("email/guest-declined.html")
static native TemplateInstance guestDeclinedNotice(
        String lang, String greetingName, String guestEmail, String meetingTypeName,
        String startTime, String manageUrl);
```

- [ ] **Step 3: Remove injected fields and rewrite call sites**

Delete injected fields `guestInvite`, `guestCancel`, `guestDeclinedNotice`.

In `sendGuestInvites` (~lines 570-583) replace `String body = guestInvite.instance()….render();` with:
```java
String body = Templates.guestInvite(
                locale.getLanguage(),
                g.email,
                l.booking.inviteeName,
                label(l),
                start,
                l.meetingType.durationMinutes,
                location,
                isMeet(l),
                declineGuestUrl(g))
        .setLocale(locale)
        .render();
```
In `guestCancelBody` (~lines 653-662) replace `return guestCancel.instance()….render();` with:
```java
return Templates.guestCancel(locale.getLanguage(), g.email, label(l), format(l.booking.startUtc, l.zone, locale))
        .setLocale(locale)
        .render();
```
In `handleGuestDeclined` (~lines 633-642) replace `String inviteeBody = guestDeclinedNotice.instance()….render();` with:
```java
String inviteeBody = Templates.guestDeclinedNotice(
                locale.getLanguage(),
                l.booking.inviteeName,
                guest.email,
                label(l),
                start,
                manageUrl(l.booking))
        .setLocale(locale)
        .render();
```

- [ ] **Step 4: Run the email suite**

Run: `mvn test -Dtest='site.asm0dey.calit.email.*'`
Expected: BUILD SUCCESS, all pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(email): typed @CheckedTemplate for guest templates"
```

---

### Task 5: Convert `passwordReset` + `googleDisconnected`, delete dead constants, final cleanup

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify: `src/main/resources/templates/email/password-reset.html`, `google-disconnected.html`
- Test (existing): `EmailEnqueueTest.sendsReconnectLinkToOwner`, `EmailLocaleHebrewTest.germanPasswordResetSubjectNonBlankAndDiffersFromEnglish`, `germanGoogleDisconnectedSubjectNonBlankAndDiffersFromEnglish`

**Interfaces:**
- Consumes: `Templates`.
- Produces: `Templates.passwordReset(...)`, `Templates.googleDisconnected(...)`. After this task no `Template` field or `.data()` call remains in `EmailService`.

- [ ] **Step 1: Add param decls**

`password-reset.html` already declares `lang`. Add:
```html
{@java.lang.String resetUrl}
```
`google-disconnected.html` already declares `lang`. Add:
```html
{@java.lang.String accountEmail}
{@java.lang.String reconnectUrl}
```

- [ ] **Step 2: Add native methods to `Templates`**

```java
@Location("email/password-reset.html")
static native TemplateInstance passwordReset(String lang, String resetUrl);

@Location("email/google-disconnected.html")
static native TemplateInstance googleDisconnected(String lang, String accountEmail, String reconnectUrl);
```

- [ ] **Step 3: Rewrite the two remaining call sites and delete injected fields**

Delete injected fields `passwordReset`, `googleDisconnected`.

In `sendPasswordReset` (~lines 145-150) replace:
```java
String body = Templates.passwordReset(locale.getLanguage(), resetUrl)
        .setLocale(locale)
        .render();
```
In `sendGoogleDisconnected` (~lines 163-169) replace:
```java
String body = Templates.googleDisconnected(
                locale.getLanguage(), accountEmail == null ? "your account" : accountEmail, reconnectUrl)
        .setLocale(locale)
        .render();
```

- [ ] **Step 4: Delete the dead key constants and unused imports**

All `public static final String` key constants (`RECIPIENT_ROLE`, `DECLINE_GUEST_URL`, `GUEST_EMAIL_DATA`, `INVITEE_NAME`, `OWNER_NAME`, `MEETING_TYPE_NAME`, `DESCRIPTION`, `START_TIME`, `DURATION_MINUTES`, `LOCATION`, `IS_MEET_LINK`, `MANAGE_URL`, `OWNER_MANAGE_URL`, `ANSWERS`, `BY_OWNER`, `GREETING_NAME`, `APPROVE_URL`, `DECLINE_URL`, `CANCEL_URL`) are now unreferenced (verified: no use outside `EmailService`). Delete them. Keep the private role constants `INVITEE_ROLE`, `OWNER_ROLE`, `GUEST_ROLE` — still used by the role logic. Remove the now-unused `import io.quarkus.qute.Template;` (only `TemplateInstance`, `CheckedTemplate`, `Location` remain used).

- [ ] **Step 5: Run the full email suite, then the whole test suite**

Run: `mvn test -Dtest='site.asm0dey.calit.email.*'`
Expected: BUILD SUCCESS, all pass.
Then run: `mvn test`
Expected: BUILD SUCCESS, entire suite green (booking-flow tests render these templates end-to-end).

- [ ] **Step 6: Verify formatting gate**

Run: `mvn spotless:check`
Expected: BUILD SUCCESS. If it fails, run `mvn spotless:apply` and re-stage.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(email): typed @CheckedTemplate for password-reset/google-disconnected; drop dead key constants"
```

---

### Task 6: Docs

Per CLAUDE.md, interesting changes update docs. This is an internal refactor (no user-facing env var / route / feature change), so **no `docs-site` changelog entry is required** and no release is being cut. The design + plan docs under `docs/superpowers/` are the record.

- [ ] **Step 1: Confirm no user-facing surface changed**

Verify (already true by design): no new/changed env var, route, config flag, template wording, or email content. If all unchanged, no `docs-site` work. Done.

---

## Self-Review

**Spec coverage:** every spec change item maps to a task — Templates class + methods (Tasks 1-5), call-site rewrites (Tasks 1-5), constant deletion (Task 5 Step 4), fragment decls (Task 1 Step 1), template decls (each task Step 1), verification via build+tests (each task's run step + Task 5 full suite). The spec's flagged fragment-validation risk is de-risked first (Task 1 Step 5) with the documented fallback. Non-goal (no `EmailService` boilerplate de-dup) respected — tasks only swap render calls.

**Placeholder scan:** no TBD/TODO; every code step shows concrete before/after; template decl blocks are literal; commands have expected output.

**Type consistency:** `Templates` method names/signatures introduced in a task are used verbatim in that task's call sites. `AnswerLine` fully-qualified consistently (`site.asm0dey.calit.email.EmailService$AnswerLine` in templates' `{@}`; `AnswerLine` unqualified inside the `Templates` inner class where it's in scope). `recipientRole`/`byOwner`/`description` first-arg ordering in each method matches the `.data()` values being replaced. Guest methods intentionally drop unused `recipientRole`/`durationMinutes` — noted in Task 4.
