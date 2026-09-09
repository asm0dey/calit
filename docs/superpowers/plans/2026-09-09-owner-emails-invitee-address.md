# Owner Emails Show the Invitee's Address Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every owner-facing booking email shows the invitee's email address as a mailto-linked `Invitee:` line, so the owner can reply, forward, or look the person up without opening the admin UI.

**Architecture:** One new shared Qute partial (`email/_invitee.html`) renders the line and self-gates on `recipientRole == 'owner'`; the seven owner-facing templates `{#include}` it inside their existing `<ul>`. Each template declares a new `{@java.lang.String inviteeEmail}` param, each matching `@CheckedTemplate` native method in `EmailService.Templates` grows an `inviteeEmail` parameter next to `inviteeName`, and each call site passes `l.booking.inviteeEmail` (already loaded — it is the invitee copy's recipient and the ICS attendee party). No mail-plumbing, DB, or config change.

**Tech Stack:** Java 25 on Quarkus 3.38, Qute `@CheckedTemplate` email templates, Qute `@MessageBundle` i18n (`AppMessages` + `messages/msg_{de,he}.properties`), JUnit 5 + `@QuarkusTest` + `MockMailbox`, Spotless/palantir-java-format.

**Spec:** GitHub issue [#196](https://github.com/asm0dey/calit/issues/196) — "Owner emails don't include the invitee's email address". Tracked locally as bean `calit-ibpe`.

## Global Constraints

- **Bean tracking.** Work is tracked in bean `calit-ibpe`. Tick its todo item as each task lands, and include the `.beans/` file in that task's commit. Do not use TodoWrite.
- **Build JDK.** `mvn`/`./mvnw` default to JDK 21 on this machine and fail with "release 25 not supported". Export `JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` in every shell before building or testing.
- **Docker must be running.** `mvn test` boots a Dev Services Postgres. No embedded/H2 fallback.
- **Never run two `mvn test` invocations against the same reused Dev Services container concurrently** — `quarkus.flyway.clean-at-start=true` drops the schema at boot and the second run fails with "relation does not exist".
- **Suite must be fully green before a PR.** `mvn test` → 0 failures, 0 errors, `BUILD SUCCESS`, whole suite, not just touched classes. A pre-existing failure on `main` is still this branch's job to fix first.
- **i18n parity is mandatory.** Every new `@Message` key in `AppMessages` gets a `de` **and** a `he` value in `src/main/resources/messages/msg_de.properties` and `msg_he.properties` in the *same* change. Placeholder names identical across locales. No leaning on the English fallback.
- **Qute templates are not Prettier-formatted.** Do not run Prettier over `src/main/resources/templates/**`. Java is formatted by Spotless (`mvn spotless:apply`); `verify` fails on unformatted Java.
- **Scope is the body line only.** `Reply-To: <invitee>` on the owner copy is explicitly **out of scope** for this plan (see issue's "nice complement"): it would need a `replyTo` parameter threaded through `EmailService.MailSink`, `MailSender.send`/`sendNow`, and an `email_outbox` column + Flyway migration, or a retried mail would silently drop the header. Do not add it.
- **Docs are part of done.** A user-facing change lands its changelog bullet on the `docs-site` branch under `## Unreleased` at merge time, not at release time (Task 3).
- **Exact address rendering** (used verbatim in every template and test): `{inviteeName} (<a href="mailto:{inviteeEmail}">{inviteeEmail}</a>)`. The plain address is visible for copy/paste *and* clickable; parentheses read correctly in both LTR and RTL.

---

### Task 1: The label, the shared partial, and the `confirmation` slice

This task establishes the pattern end to end on one template (`confirmation.html`, which backs both `BookingConfirmed` and `BookingApproved`). Task 2 replicates it mechanically across the other six.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` (insert after `email_body_meeting_label()`, around line 556)
- Modify: `src/main/resources/messages/msg_de.properties` (shared-labels block, around line 177)
- Modify: `src/main/resources/messages/msg_he.properties` (shared-labels block, around line 177)
- Create: `src/main/resources/templates/email/_invitee.html`
- Modify: `src/main/resources/templates/email/confirmation.html`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` — `Templates.confirmation` signature (around line 164) and its two call sites (`handleConfirmed`, around line 373; `handleApproved`, around line 405)
- Test: `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - Message key `String email_body_invitee_label()` on `AppMessages`, English default `"Invitee:"`, usable from templates as `{msg:email_body_invitee_label}`.
  - Partial `email/_invitee.html`, included as `{#include email/_invitee /}`. It requires the including template to have `recipientRole`, `inviteeName` and `inviteeEmail` in scope, and renders nothing unless `recipientRole == 'owner'`.
  - `EmailService.Templates.confirmation(String recipientRole, String lang, String greetingName, String inviteeName, String inviteeEmail, String meetingTypeName, String startTime, int durationMinutes, String location, boolean isMeetLink, String manageUrl, String ownerManageUrl, String cancelUrl, List<AnswerLine> answers)` — `inviteeEmail` inserted immediately after `inviteeName`. Task 2 follows the same "immediately after `inviteeName`" placement for all six remaining methods.

---

- [ ] **Step 1: Write the failing template-level test**

Open `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`. In the `base(Template, String)` helper, add the new datum immediately after the existing `inviteeName` line:

```java
                .data("inviteeName", "Sam Invitee")
                .data("inviteeEmail", "sam@example.com")
```

Then add these two tests at the end of the class (before the closing brace):

```java
    @Test
    void confirmationOwnerCopyShowsInviteeAddressAsMailto() {
        String body = base(confirmation, "owner").render();
        assertTrue(body.contains("Invitee:"), "owner copy carries the invitee label");
        assertTrue(body.contains("Sam Invitee (<a href=\"mailto:sam@example.com\">sam@example.com</a>)"),
                "owner copy shows the address, mailto-linked, beside the name");
    }

    @Test
    void confirmationInviteeCopyDoesNotEchoTheirOwnAddress() {
        String body = base(confirmation, "invitee").render();
        assertFalse(body.contains("sam@example.com"), "invitee copy must not gain an Invitee: line");
    }
```

Both `assertTrue` and `assertFalse` are already statically imported at the top of this file.

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailRoleCopyTest
```

Expected: `confirmationOwnerCopyShowsInviteeAddressAsMailto` FAILS (the rendered body has no `Invitee:` line). `confirmationInviteeCopyDoesNotEchoTheirOwnAddress` passes vacuously — that is fine, it is a regression guard.

If the whole class errors instead with a Qute build failure about an unknown `inviteeEmail` property, that is also an acceptable "red": Qute validates `Template`-injected data loosely, so it should not happen, but a hard build error still means "not implemented yet".

- [ ] **Step 3: Add the message key with all three translations**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, in the `// ---- Email body — shared labels ----` block, insert directly after the `email_body_meeting_label()` method:

```java
    /** Owner-copy-only label naming the person who booked; the address follows it. */
    @Message("Invitee:")
    String email_body_invitee_label();
```

In `src/main/resources/messages/msg_de.properties`, in the `# Email body — shared labels` block, insert directly after the `email_body_meeting_label=` line:

```properties
email_body_invitee_label=Eingeladener:
```

In `src/main/resources/messages/msg_he.properties`, same position:

```properties
email_body_invitee_label=מוזמן:
```

- [ ] **Step 4: Create the shared partial**

Create `src/main/resources/templates/email/_invitee.html` with exactly this content:

```html
{@java.lang.String recipientRole}
{@java.lang.String inviteeName}
{@java.lang.String inviteeEmail}
{#if recipientRole == 'owner'}<li><strong>{msg:email_body_invitee_label}</strong> {inviteeName} (<a href="mailto:{inviteeEmail}">{inviteeEmail}</a>)</li>{/if}
```

Two things to know about this file:
- The `{@...}` lines are Qute *parameter declarations*. They give the build-time validator the types; the values come from the including template's scope, exactly as `email/_location.html` already does for `location`/`isMeetLink`.
- `booking.invitee_email` is `NOT NULL` (see `V4__booking.sql:5`), so no null guard is needed. Do not add one.

- [ ] **Step 5: Wire the partial into `confirmation.html`**

In `src/main/resources/templates/email/confirmation.html`, add the parameter declaration right after the existing `inviteeName` one:

```html
{@java.lang.String inviteeName}
{@java.lang.String inviteeEmail}
```

and include the partial as the first `<li>` in the list, so the owner reads *who* before *what*:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
```

- [ ] **Step 6: Add the parameter to the `confirmation` template binding**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`, inside the `Templates` class, change the `confirmation` native method (around line 164) so `inviteeEmail` sits immediately after `inviteeName`:

```java
        static native TemplateInstance confirmation(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String inviteeEmail,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);
```

Why the signature must change at all: `@CheckedTemplate` binds native-method parameter *names* to template parameters at build time (this is why the module sets `maven.compiler.parameters=true`). A template param with no matching method parameter is a build-time error, not a silent blank.

- [ ] **Step 7: Pass the address at both `confirmation` call sites**

Still in `EmailService.java`, in `handleConfirmed` (around line 373) and `handleApproved` (around line 405), both call `Templates.confirmation(...)`. In each, add the new argument directly after `l.booking.inviteeName`:

```java
                                l.booking.inviteeName,
                                l.booking.inviteeEmail,
```

Both call sites live inside the same lambda shape, so the surrounding lines are identical:

```java
                (role, locale, zone, greetingName, linkBooking, hourCycle) -> Templates.confirmation(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                l.booking.inviteeEmail,
                                label(l),
                                format(l.booking.startUtc, zone, locale, hourCycle),
                                BookingService.lengthOf(l.booking),
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
```

Note the address goes to *both* recipient copies — the invitee copy receives it too, and the partial simply renders nothing there. That is deliberate: the role branch lives in one place (the partial) rather than being duplicated in the Java.

- [ ] **Step 8: Run the template test to verify it passes**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailRoleCopyTest
```

Expected: PASS, all tests in the class.

- [ ] **Step 9: Write the failing end-to-end test**

The template test proves the markup. This one proves the address actually survives the `EmailService` → `MailSender` → mailbox path for a real booking.

In `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java`, add this test (it reuses the class's existing `seed(...)` helper, `INVITEE_EMAIL`, `OWNER_EMAIL` constants and the `calendarPort` mock):

```java
    @Test
    void confirmedOwnerCopyCarriesTheInviteeAddressButTheInviteeCopyDoesNot() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.CUSTOM, "Room 1");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        String ownerHtml = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getHtml();
        assertTrue(ownerHtml.contains("mailto:" + INVITEE_EMAIL), "owner can click through to the invitee");
        assertTrue(ownerHtml.contains("Invitee:"), "owner copy labels the line");

        String inviteeHtml = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst().getHtml();
        assertFalse(inviteeHtml.contains("mailto:" + INVITEE_EMAIL), "invitee copy is unchanged");
    }
```

`LocationType` is `{GOOGLE_MEET, PHONE, IN_PERSON, CUSTOM}` (`site.asm0dey.calit.domain.MeetingType`); `CUSTOM` with a detail string keeps this test off the Google-Meet branch, which is irrelevant here.

- [ ] **Step 10: Run the end-to-end test to verify it passes**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailServiceTest#confirmedOwnerCopyCarriesTheInviteeAddressButTheInviteeCopyDoesNot
```

Expected: PASS. It was written after the implementation deliberately — the template test above was the red-first driver, and this one guards the wiring the template test cannot see. If it fails on `mailto:` not being present, the `l.booking.inviteeEmail` argument did not reach the template; re-check Step 7.

- [ ] **Step 11: Format, then run the email tests**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
mvn test -Dtest='Email*Test'
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. `-Dtest` matches simple class names, so this is a subset — it misses `MultiHostEmailFanoutTest`, `UpdatedEmailTest` and `GoogleDisconnectedEmailTest`. That is fine for this checkpoint; Task 2 Step 8 runs the whole suite.

- [ ] **Step 12: Tick the bean and commit**

```bash
beans update calit-ibpe \
  --body-replace-old "- [ ] Task 1: label + shared \`_invitee.html\` partial + confirmation.html slice" \
  --body-replace-new "- [x] Task 1: label + shared \`_invitee.html\` partial + confirmation.html slice"

git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/main/resources/templates/email/_invitee.html \
        src/main/resources/templates/email/confirmation.html \
        src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java \
        src/test/java/site/asm0dey/calit/email/EmailServiceTest.java \
        .beans/
git commit -m "fix(email): show the invitee's address on the owner's confirmation mail

Owner copies named the invitee but never gave the address, so replying or
looking the person up meant leaving the mail. Adds a shared email/_invitee
partial, rendered only on the owner branch, and wires it into confirmation.

Refs #196"
```

---

### Task 2: Roll the line out to the remaining six owner templates

Same mechanical change six more times: `requested`, `reminder`, `reschedule`, `updated`, `declined`, `cancellation`. Each needs a template param declaration, a `{#include email/_invitee /}` inside its `<ul>`, an `inviteeEmail` parameter after `inviteeName` on its native method, and the argument at its call site.

**Files:**
- Modify: `src/main/resources/templates/email/requested.html`
- Modify: `src/main/resources/templates/email/reminder.html`
- Modify: `src/main/resources/templates/email/reschedule.html`
- Modify: `src/main/resources/templates/email/updated.html`
- Modify: `src/main/resources/templates/email/declined.html`
- Modify: `src/main/resources/templates/email/cancellation.html`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` — six native methods (`reminder` ~line 133, `requested` ~148, `declined` ~179, `reschedule` ~188, `updated` ~206, `cancellation` ~224) and six call sites (`handleRequested` ~342, `deliverDeclined` ~445, `handleRescheduled` ~475, `handleDetailsChanged` ~509, `handleCancelled` ~541, `deliverReminder` ~570)
- Test: `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`

**Interfaces:**
- Consumes: `email/_invitee.html` and `AppMessages.email_body_invitee_label()` from Task 1; the "`inviteeEmail` immediately after `inviteeName`" parameter convention from Task 1.
- Produces: nothing new for later tasks — after this task all seven owner-facing templates carry the line.

---

- [ ] **Step 1: Write the failing test for the second template**

`EmailRoleCopyTest` already injects `email/requested.html` as the `requested` field, so `requested` is the cheapest second template to assert on. Add this test to `EmailRoleCopyTest`:

```java
    @Test
    void requestedOwnerCopyShowsInviteeAddressAsMailto() {
        String body = base(requested, "owner").render();
        assertTrue(body.contains("Invitee:"), "owner copy carries the invitee label");
        assertTrue(body.contains("Sam Invitee (<a href=\"mailto:sam@example.com\">sam@example.com</a>)"),
                "owner copy shows the address, mailto-linked, beside the name");
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailRoleCopyTest#requestedOwnerCopyShowsInviteeAddressAsMailto
```

Expected: FAIL — `requested.html` has no `Invitee:` line yet.

- [ ] **Step 3: Edit all six templates**

In each of the six files, add the declaration right after the existing `{@java.lang.String inviteeName}` line:

```html
{@java.lang.String inviteeEmail}
```

and add `{#include email/_invitee /}` as the **first** child of that template's `<ul>`. Concretely:

`requested.html` — the list becomes:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_requested_time_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
```

`reminder.html`:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
```

`reschedule.html`:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_reschedule_previous_time}</strong> {oldStartTime}</li>
  <li><strong>{msg:email_reschedule_new_time}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
```

`updated.html`:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  {#if description}<li><strong>{msg:email_updated_description_label}</strong> {description}</li>{/if}
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
```

`declined.html`:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_requested_time_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
</ul>
```

`cancellation.html`:

```html
<ul>
  {#include email/_invitee /}
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_cancellation_was_scheduled}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
</ul>
```

Note `declined.html` and `cancellation.html` deliberately have no location line and no answers — that is existing behaviour, leave it.

- [ ] **Step 4: Add the parameter to all six native methods**

In `EmailService.Templates`, insert `String inviteeEmail,` directly after `String inviteeName,` in each of `reminder`, `requested`, `declined`, `reschedule`, `updated`, `cancellation`. Two of them (`reschedule`, `updated`) list `inviteeName` before `ownerName`, so the insert lands between those two:

```java
        static native TemplateInstance reminder(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String inviteeEmail,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance requested(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String inviteeEmail,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String cancelUrl,
                String approveUrl,
                String declineUrl,
                List<AnswerLine> answers);

        static native TemplateInstance declined(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String inviteeEmail,
                String meetingTypeName,
                String startTime,
                int durationMinutes);

        static native TemplateInstance reschedule(
                String recipientRole,
                boolean byOwner,
                String lang,
                String inviteeName,
                String inviteeEmail,
                String ownerName,
                String greetingName,
                String meetingTypeName,
                String startTime,
                String oldStartTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance updated(
                String recipientRole,
                boolean byOwner,
                String description,
                String lang,
                String inviteeName,
                String inviteeEmail,
                String ownerName,
                String greetingName,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance cancellation(
                String recipientRole,
                boolean byOwner,
                String lang,
                String inviteeName,
                String inviteeEmail,
                String ownerName,
                String greetingName,
                String meetingTypeName,
                String startTime,
                int durationMinutes);
```

Leave `guestInvite` alone — its second parameter is the *guest's* address, a different concept, and `guestInvite.html` is not an owner-facing template.

- [ ] **Step 5: Pass the address at all six call sites**

In each of the six handlers, add `l.booking.inviteeEmail,` directly after the existing `l.booking.inviteeName,` argument. The six sites, by enclosing method:

- `handleRequested` → `Templates.requested(...)` (~line 342)
- `deliverDeclined` → `Templates.declined(...)` (~line 445)
- `handleRescheduled` → `Templates.reschedule(...)` (~line 475)
- `handleDetailsChanged` → `Templates.updated(...)` (~line 509)
- `handleCancelled` → `Templates.cancellation(...)` (~line 541)
- `deliverReminder` → `Templates.reminder(...)` (~line 570)

For example, `handleRequested` becomes:

```java
                (role, locale, zone, greetingName, linkBooking, hourCycle) -> Templates.requested(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                l.booking.inviteeEmail,
                                label(l),
                                format(l.booking.startUtc, zone, locale, hourCycle),
                                BookingService.lengthOf(l.booking),
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                approveUrl(linkBooking),
                                declineUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
```

and `handleRescheduled` (note `inviteeEmail` lands between `inviteeName` and `ownerName`):

```java
                (role, locale, zone, greetingName, linkBooking, hourCycle) -> Templates.reschedule(
                                role,
                                e.byOwner(),
                                locale.getLanguage(),
                                l.booking.inviteeName,
                                l.booking.inviteeEmail,
                                l.owner.ownerName,
                                greetingName,
                                label(l),
                                format(l.booking.startUtc, zone, locale, hourCycle),
                                format(e.oldStartUtc(), zone, locale, hourCycle),
                                BookingService.lengthOf(l.booking),
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
```

There is no compiler safety net for argument *order* here — every one of these is a `String` — so read each edited call against its native method before moving on. A swapped `inviteeName`/`inviteeEmail` compiles cleanly and produces a mail with a mailto to "Sam Invitee".

- [ ] **Step 6: Run the template test to verify it passes**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailRoleCopyTest
```

Expected: PASS, all tests.

- [ ] **Step 7: Verify all seven templates actually include the partial**

A grep is cheaper than a test per template and catches a missed file:

```bash
grep -L "email/_invitee" \
  src/main/resources/templates/email/{confirmation,requested,reminder,reschedule,updated,declined,cancellation}.html
```

Expected: **no output**. `grep -L` lists files that do *not* match, so any filename printed is a template you missed.

- [ ] **Step 8: Format and run the full suite**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply
mvn test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors, across the whole suite — not just the email package. Other email tests (`EmailLocaleTest`, `EmailLocaleHebrewTest`, `MultiHostEmailFanoutTest`, `UpdatedEmailTest`, `EmailServiceGuestTest`, …) drive these handlers and would catch a bad argument order or a broken template.

If a locale test fails on a missing `Invitee:` translation, re-check that both `msg_de.properties` and `msg_he.properties` got the key in Task 1 Step 3.

- [ ] **Step 9: Tick the bean and commit**

```bash
beans update calit-ibpe \
  --body-replace-old "- [ ] Task 2: roll out to requested/reminder/reschedule/updated/declined/cancellation" \
  --body-replace-new "- [x] Task 2: roll out to requested/reminder/reschedule/updated/declined/cancellation"

git add src/main/resources/templates/email/ \
        src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java \
        .beans/
git commit -m "fix(email): show the invitee's address on every owner-facing mail

Extends the owner Invitee: line to requested, reminder, reschedule, updated,
declined and cancellation, so no owner notification names a person without
also giving a way to reach them.

Refs #196"
```

---

### Task 3: Changelog entry on the `docs-site` branch

The repo's convention is that a user-facing change writes its changelog bullet at merge time under `## Unreleased`, not at release time. The changelog lives on the **`docs-site`** branch, which is a different branch from the feature work.

**Files:**
- Modify (on branch `docs-site`): `docs-site/src/content/docs/releases/changelog.md`

**Interfaces:**
- Consumes: the user-visible behaviour delivered by Tasks 1 and 2.
- Produces: nothing consumed by later tasks.

---

- [ ] **Step 1: Get a working copy of the `docs-site` branch without disturbing the feature branch**

```bash
git worktree add /tmp/finkel/calit-docs-site docs-site
```

If that path is already taken, pick another under `/tmp/finkel/`. Do not `git checkout docs-site` in the main working tree — the feature branch's uncommitted state and the Maven target dir live there.

- [ ] **Step 2: Check whether an `## Unreleased` section already exists**

```bash
head -20 /tmp/finkel/calit-docs-site/docs-site/src/content/docs/releases/changelog.md
```

As of writing the file goes straight from the front-matter block and intro paragraph to `## 1.23.0`, so the section must be created. If a later change has already created it, skip Step 3's heading and subtitle and just add the bullet.

- [ ] **Step 3: Add the section and the bullet**

Edit `/tmp/finkel/calit-docs-site/docs-site/src/content/docs/releases/changelog.md`. Insert this directly above the `## 1.23.0` heading:

```markdown
## Unreleased

Merged but not yet in a tagged release.

- **Owner booking emails now show the invitee's email address.** Every
  owner-facing notification — confirmation, request, reminder, reschedule,
  update, decline and cancellation — named the person who booked but never
  gave their address, so replying, forwarding or looking them up meant
  leaving the mail and opening the admin UI. Replying was no help either:
  the mail comes from your configured `MAIL_FROM`, not from the invitee.
  Those mails now carry an **Invitee:** line with the name and the address,
  and the address is a `mailto:` link. The invitee's own copy is unchanged —
  it never echoes their address back at them.
  ([#196](https://github.com/asm0dey/calit/issues/196))

Nothing to do on upgrade — no configuration or database changes. The line
appears on mails sent from this version onward; already-delivered mail is of
course untouched.
```

- [ ] **Step 4: Commit and push the docs branch**

```bash
cd /tmp/finkel/calit-docs-site
git add docs-site/src/content/docs/releases/changelog.md
git commit -m "docs(changelog): owner emails now show the invitee's address

Refs #196"
git push origin docs-site
```

Pushing `docs-site` triggers `.github/workflows/docs.yml`, which deploys GitHub Pages. Watch it to a successful conclusion:

```bash
gh run list --branch docs-site --limit 1
gh run watch
```

- [ ] **Step 5: Clean up the worktree**

```bash
cd /home/finkel/work_self/calit
git worktree remove /tmp/finkel/calit-docs-site
```

- [ ] **Step 6: Tick the bean, close it, and record the summary**

```bash
beans update calit-ibpe \
  --body-replace-old "- [ ] Task 3: docs-site \`## Unreleased\` changelog entry" \
  --body-replace-new "- [x] Task 3: docs-site \`## Unreleased\` changelog entry" \
  --body-append "## Summary of Changes

Added \`AppMessages.email_body_invitee_label()\` (en/de/he) and a shared
\`templates/email/_invitee.html\` partial that renders \`Invitee: <name>
(<mailto link>)\` only when \`recipientRole == 'owner'\`. Included it in all
seven owner-facing templates (confirmation, requested, reminder, reschedule,
updated, declined, cancellation), added an \`inviteeEmail\` parameter after
\`inviteeName\` on each \`@CheckedTemplate\` native method, and passed
\`l.booking.inviteeEmail\` at all eight call sites in \`EmailService\`.

Covered by \`EmailRoleCopyTest\` (markup, owner and invitee branches) and an
\`EmailServiceTest\` case asserting the address survives the real send path
to the owner and never appears on the invitee copy. Changelog bullet landed
on \`docs-site\` under \`## Unreleased\`.

\`Reply-To: <invitee>\` was deliberately left out — see the plan's Global
Constraints for why (outbox has no column for it)." \
  -s completed
```

- [ ] **Step 7: Open the PR**

Before opening, confirm the suite is green on the feature branch — this is a hard gate, not a formality:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test
```

Then run the `show-me` skill on this change and put the resulting diagram in the PR body — PRs here have readers beyond the author. The PR description should say the changelog entry already landed on `docs-site`, name the `Reply-To` omission and its reason, and close with `Closes #196`.

---

## Follow-ups deliberately not in this plan

- **`Reply-To: <invitee>` on the owner's copy.** The issue floats it and it is a genuinely good idea, but it is a different change: `EmailService.MailSink.deliver` and `MailSender.send`/`sendNow` would each need a `replyTo`, and `EmailOutbox` persists neither `fromName` nor any header today — a mail that falls back to the outbox and is retried by `OutboxScheduler` would silently lose the header unless a Flyway migration adds a column. Worth its own bean and its own decision about whether that column is warranted.
