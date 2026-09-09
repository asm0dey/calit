# Owner Cancellation Email Wording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A host who cancels a booking reads *"You cancelled your meeting with Sam Invitee."* in their own copy of the cancellation mail instead of the invitee's passive *"Your booking has been cancelled."*, and a host whose invitee cancelled reads the active *"Sam Invitee cancelled their booking."* instead of *"Sam Invitee's booking was cancelled."*

**Architecture:** Pure copy change. `email/cancellation.html` already branches on `byOwner` × `recipientRole`; three of the four cells have their own message key and the fourth (host cancelled, host reads) falls through to the invitee's `email_cancellation_body`. This plan adds the missing fourth key, `email_cancellation_body_owner_self(String name)`, points that branch at it, and rewords the existing `email_cancellation_body_owner`. `inviteeName` is already declared in the template and already passed by `EmailService.Templates.cancellation` — no signature, plumbing, DB, or config change.

**Tech Stack:** Java 25 on Quarkus 3.38, Qute `@CheckedTemplate` email templates, Qute `@MessageBundle` i18n (`AppMessages` + `messages/msg_{de,he}.properties`), JUnit 5 + `@QuarkusTest` + `MockMailbox`, Spotless/palantir-java-format.

**Spec:** GitHub issue [#198](https://github.com/asm0dey/calit/issues/198) — "Owner's cancellation email reads as if someone cancelled on them". Tracked locally as bean `calit-ot22`.

## Global Constraints

- **Branch off `origin/main`, and fetch first.** The `#196` work (PR #199, merged) added `inviteeEmail` and the `email/_invitee` include to `cancellation.html`; a local checkout from before 2026-09-09 does not have it. Run `git fetch origin && git switch -c fix/198-owner-cancellation-wording origin/main` before Task 1. Every line number in this plan refers to `origin/main` at commit `d6df357a`.
- **Bean tracking.** Work is tracked in bean `calit-ot22`. Tick its todo item as each task lands, and include the `.beans/` file in that task's commit. Do not use TodoWrite.
- **Build JDK.** `mvn`/`./mvnw` default to JDK 21 on this machine and fail with "release 25 not supported". Export `JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` in every shell before building or testing.
- **Docker must be running.** `mvn test` boots a Dev Services Postgres. No embedded/H2 fallback.
- **Never run two `mvn test` invocations against the same reused Dev Services container concurrently** — `quarkus.flyway.clean-at-start=true` drops the schema at boot and the second run fails with "relation does not exist".
- **Suite must be fully green before a PR.** `mvn test` → 0 failures, 0 errors, `BUILD SUCCESS`, the whole suite, not just the touched classes. A pre-existing failure on `main` is still this branch's job to fix first.
- **i18n parity is mandatory and test-enforced.** `MultiHostMessageParityTest` reflects over every `@Message` method and fails if a key is missing from `messages/msg_de.properties` or `msg_he.properties` — and fails the other way (`appPropertyFilesHaveNoOrphanKeys`) if a property file carries a key with no interface method. Add the `de` **and** `he` value in the *same* change as the `@Message` default. Placeholder name is `{name}` in all three locales.
- **Qute templates are not Prettier-formatted.** Do not run Prettier over `src/main/resources/templates/**`. Java is formatted by Spotless (`mvn spotless:apply`); `verify` fails on unformatted Java.
- **Scope is the cancellation template's two body strings.** Do not touch `confirmation`, `requested`, `reminder`, `reschedule`, `updated`, or `declined`. Do not add `Reply-To`. Do not touch `email_cancellation_body` (the invitee's copy) — for the invitee that wording is correct.
- **The issue's third bullet is already done.** #198 closes with "The owner's copy drops the invitee's email address here too — same root cause as #196." That was fixed by PR #199: on `origin/main`, `cancellation.html` already declares `{@java.lang.String inviteeEmail}` and pulls in `{#include email/_invitee /}`, which renders the mailto-linked `Invitee:` line for `recipientRole == 'owner'`. Verify it is there before starting (`git show origin/main:src/main/resources/templates/email/cancellation.html`); do not re-implement it.
- **Known limitation, deliberately accepted (do not "fix" it in this plan).** For a group booking (`booking.groupId != null`), `EmailService.sendForKindLocaleAware` fans the owner copy out to *every* co-host, and `BookingCancelled` carries only a `byOwner` boolean — not which host clicked cancel. So co-hosts who did not cancel will read "You cancelled …". That mis-attribution already exists on `main` in the same shape (the invitee's `email_cancellation_body_by_owner` names `l.owner.ownerName`, who may not be the canceller either), and fixing it needs an actor id on the event. Task 3 files a follow-up bean; do not widen this branch to carry it.
- **Docs are part of done.** A user-facing change lands its changelog bullet on the `docs-site` branch under `## Unreleased` at merge time, not at release time (Task 3).

---

### Task 1: The host's own copy says the host cancelled

The `byOwner` × `recipientRole == 'owner'` branch currently renders `email_cancellation_body` — the invitee's string. This task gives that branch its own message.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java:698` (insert immediately before the `/** Invitee copy when the host drove the cancellation; … */` javadoc)
- Modify: `src/main/resources/messages/msg_de.properties:313` (insert after `email_cancellation_body_owner`)
- Modify: `src/main/resources/messages/msg_he.properties:313` (insert after `email_cancellation_body_owner`)
- Modify: `src/main/resources/templates/email/cancellation.html:14`
- Test: `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java:346-362`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `String email_cancellation_body_owner_self(String name)` on `AppMessages`, English default `"You cancelled your meeting with {name}."`, called from a template as `{msg:email_cancellation_body_owner_self(inviteeName)}`. `{name}` is the **invitee's** display name (contrast with `email_cancellation_body_by_owner`, whose `{name}` is the owner's).
  - `EmailRoleCopyTest` gains a `@Inject @Location("email/cancellation.html") Template cancellation;` field and two `.data(...)` entries in its `base` helper (`ownerName`, `byOwner`). Task 2 reuses both.

---

- [ ] **Step 1: Write the failing template-level test**

Open `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`.

Add a third injected template after the `confirmation` field:

```java
    @Inject
    @Location("email/cancellation.html")
    Template cancellation;
```

`cancellation.html` declares `{@java.lang.String ownerName}` and `{@java.lang.Boolean byOwner}`, which the shared `base(Template, String)` helper does not supply. Add both to it, immediately after the `inviteeEmail` line — `byOwner` defaults to `false` here and each cancellation test overrides it explicitly:

```java
                .data("inviteeEmail", "sam@example.com")
                .data("ownerName", "Olivia Owner")
                .data("byOwner", false)
```

Then add these two tests at the end of the class, before the closing brace:

```java
    @Test
    void hostCancelOwnerCopySaysTheHostCancelledAndNamesTheInvitee() {
        String body = base(cancellation, "owner").data("byOwner", true).render();
        assertTrue(
                body.contains("You cancelled your meeting with Sam Invitee."),
                "host who cancelled reads an active line naming the invitee");
        assertFalse(
                body.contains("Your booking has been cancelled."),
                "host copy must not reuse the invitee's passive string");
    }

    @Test
    void hostCancelInviteeCopyStillNamesTheHost() {
        String body = base(cancellation, "invitee").data("byOwner", true).render();
        assertTrue(body.contains("Olivia Owner cancelled your booking."), "invitee copy names the host");
        assertFalse(body.contains("You cancelled"), "invitee copy must not claim the invitee acted");
    }
```

`.data(...)` on an existing `TemplateInstance` overwrites the earlier value for the same key, so the `byOwner` override after `base(...)` wins.

- [ ] **Step 2: Run the two new tests to verify they fail**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest=EmailRoleCopyTest
```

Expected: `hostCancelOwnerCopySaysTheHostCancelledAndNamesTheInvitee` FAILS on the first assertion — the rendered body still holds "Your booking has been cancelled." `hostCancelInviteeCopyStillNamesTheHost` passes already (that branch is correct on `main`); it is a regression guard for the branch you are about to edit.

- [ ] **Step 3: Add the message key**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, find:

```java
    @Message("{name}'s booking was cancelled.")
    String email_cancellation_body_owner(String name);

    /** Invitee copy when the host drove the cancellation; {name} is the owner's display name. */
    @Message("{name} cancelled your booking.")
    String email_cancellation_body_by_owner(String name);
```

Insert the new method between them, so the block reads:

```java
    @Message("{name}'s booking was cancelled.")
    String email_cancellation_body_owner(String name);

    /** Host's own copy when the host drove the cancellation; {name} is the invitee's display name. */
    @Message("You cancelled your meeting with {name}.")
    String email_cancellation_body_owner_self(String name);

    /** Invitee copy when the host drove the cancellation; {name} is the owner's display name. */
    @Message("{name} cancelled your booking.")
    String email_cancellation_body_by_owner(String name);
```

- [ ] **Step 4: Add the German and Hebrew values**

In `src/main/resources/messages/msg_de.properties`, the block around line 312 currently reads:

```properties
email_cancellation_body_owner=Die Buchung von {name} wurde storniert.
email_cancellation_body_by_owner={name} hat Ihre Buchung storniert.
```

Insert between those two lines:

```properties
email_cancellation_body_owner_self=Sie haben Ihr Meeting mit {name} storniert.
```

In `src/main/resources/messages/msg_he.properties`, the block around line 312 currently reads:

```properties
email_cancellation_body_owner=ההזמנה של {name} בוטלה.
email_cancellation_body_by_owner={name} ביטל/ה את ההזמנה שלך.
```

Insert between those two lines:

```properties
email_cancellation_body_owner_self=ביטלת את הפגישה שלך עם {name}.
```

- [ ] **Step 5: Point the template branch at the new key**

In `src/main/resources/templates/email/cancellation.html`, the first branch currently reads:

```html
{#if byOwner}
  {#if recipientRole == 'owner'}
<p><strong>{msg:email_cancellation_body}</strong></p>
  {#else}
```

Change that one line to:

```html
{#if byOwner}
  {#if recipientRole == 'owner'}
<p><strong>{msg:email_cancellation_body_owner_self(inviteeName)}</strong></p>
  {#else}
```

Leave the `{#else}` half of the outer `{#if}` alone — the invitee-cancelled branches are Task 2's business and the invitee's own copy is correct as-is.

- [ ] **Step 6: Run the template tests to verify they pass**

```bash
mvn test -Dtest=EmailRoleCopyTest
```

Expected: PASS, all tests in the class.

- [ ] **Step 7: Tighten the end-to-end assertion in `EmailServiceTest`**

`hostCancelNamesHostToGuestAndDoesNotBlameGuestToOwner` currently ends with a negative-only check on the owner copy that this change makes vacuous — after Task 2 the substring `"was cancelled"` will not exist anywhere in the codebase, so the `&&` can never be true regardless of what the mail says. Replace the whole test body's owner half. The test currently reads:

```java
    @Test
    void hostCancelNamesHostToGuestAndDoesNotBlameGuestToOwner() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CANCELLED, true, LocationType.PHONE, "+1");

        emailService.handleCancelled(new BookingCancelled(bookingId, true));

        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(
                invitee.getHtml().contains("Owner cancelled your booking"),
                "host-initiated: invitee copy names the host");
        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertFalse(
                owner.getHtml().contains("Sam Invitee") && owner.getHtml().contains("was cancelled"),
                "host-initiated: owner copy must not attribute to the guest");
    }
```

Make it:

```java
    @Test
    void hostCancelNamesHostToGuestAndSaysTheHostActedToOwner() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CANCELLED, true, LocationType.PHONE, "+1");

        emailService.handleCancelled(new BookingCancelled(bookingId, true));

        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(
                invitee.getHtml().contains("Owner cancelled your booking"),
                "host-initiated: invitee copy names the host");
        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertTrue(
                owner.getHtml().contains("You cancelled your meeting with Sam Invitee."),
                "host-initiated: owner copy says the host acted and names the guest");
        assertFalse(
                owner.getHtml().contains("Your booking has been cancelled."),
                "host-initiated: owner copy must not reuse the invitee's passive string");
    }
```

The seed fixture sets `ownerName = "Owner"` and the invitee name is `"Sam Invitee"` — both are hardcoded in the `seedAt` helper in the same class, so the literals above are exact.

- [ ] **Step 8: Run the email tests and the i18n parity sweep**

```bash
mvn test -Dtest='EmailRoleCopyTest,EmailServiceTest,MultiHostMessageParityTest,AppMessagesTest'
```

Expected: PASS. `MultiHostMessageParityTest` is the guard that the `de`/`he` values from Step 4 actually landed; if it reports a missing key, Step 4 was skipped or misspelled.

- [ ] **Step 9: Format and run the whole suite**

```bash
mvn spotless:apply
mvn test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 10: Tick the bean todo and commit**

```bash
beans update calit-ot22 \
  --body-replace-old "- [ ] Task 1: new email_cancellation_body_owner_self key + cancellation.html branch + tests" \
  --body-replace-new "- [x] Task 1: new email_cancellation_body_owner_self key + cancellation.html branch + tests"

git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/main/resources/templates/email/cancellation.html \
        src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java \
        src/test/java/site/asm0dey/calit/email/EmailServiceTest.java \
        .beans/
git commit -m "fix(email): the host's cancellation copy says the host cancelled"
```

---

### Task 2: The invitee-cancelled copy names the actor instead of going passive

`email_cancellation_body_owner` ("{name}'s booking was cancelled.") never says the invitee is who cancelled it, and reads as if it happened by itself. Match the tone of the by-owner string.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java:694-695` (the `@Message` default on `email_cancellation_body_owner`)
- Modify: `src/main/resources/messages/msg_de.properties:312`
- Modify: `src/main/resources/messages/msg_he.properties:312`
- Test: `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java`

**Interfaces:**
- Consumes: from Task 1, the `cancellation` template field and the `ownerName` / `byOwner` entries in `EmailRoleCopyTest.base`.
- Produces: `email_cancellation_body_owner(String name)` keeps its name and signature; only its English default and the two translations change. No new key, so no parity-test surface change.

---

- [ ] **Step 1: Write the failing tests**

In `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`, add at the end of the class:

```java
    @Test
    void guestCancelOwnerCopyNamesTheGuestAsTheActor() {
        String body = base(cancellation, "owner").render();
        assertTrue(body.contains("Sam Invitee cancelled their booking."), "owner copy names who cancelled");
        assertFalse(body.contains("was cancelled"), "owner copy is not passive");
    }
```

In `src/test/java/site/asm0dey/calit/email/EmailServiceTest.java`, add after `hostCancelNamesHostToGuestAndSaysTheHostActedToOwner` (the test Task 1 renamed):

```java
    @Test
    void guestCancelNamesGuestToOwnerAndStaysPassiveToGuest() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CANCELLED, true, LocationType.PHONE, "+1");

        emailService.handleCancelled(new BookingCancelled(bookingId, false));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertTrue(
                owner.getHtml().contains("Sam Invitee cancelled their booking."),
                "guest-initiated: owner copy names the guest as the actor");
        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(
                invitee.getHtml().contains("Your booking has been cancelled."),
                "guest-initiated: invitee copy stays passive — it happened to them");
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test -Dtest='EmailRoleCopyTest,EmailServiceTest'
```

Expected: both new tests FAIL — the rendered owner body still says "Sam Invitee's booking was cancelled."

- [ ] **Step 3: Reword the English default**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, change:

```java
    @Message("{name}'s booking was cancelled.")
    String email_cancellation_body_owner(String name);
```

to:

```java
    /** Host's copy when the invitee drove the cancellation; {name} is the invitee's display name. */
    @Message("{name} cancelled their booking.")
    String email_cancellation_body_owner(String name);
```

- [ ] **Step 4: Reword the German and Hebrew values**

In `src/main/resources/messages/msg_de.properties`, change:

```properties
email_cancellation_body_owner=Die Buchung von {name} wurde storniert.
```

to:

```properties
email_cancellation_body_owner={name} hat die Buchung storniert.
```

In `src/main/resources/messages/msg_he.properties`, change:

```properties
email_cancellation_body_owner=ההזמנה של {name} בוטלה.
```

to:

```properties
email_cancellation_body_owner={name} ביטל/ה את ההזמנה.
```

Both drop the passive construction and keep the `{name}` placeholder. German avoids a gendered possessive ("die Buchung", not "seine/ihre Buchung"); Hebrew keeps the existing `ביטל/ה` dual-gender verb form already used by `email_cancellation_body_by_owner`.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn test -Dtest='EmailRoleCopyTest,EmailServiceTest,MultiHostMessageParityTest'
```

Expected: PASS.

- [ ] **Step 6: Format and run the whole suite**

```bash
mvn spotless:apply
mvn test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 7: Tick the bean todo and commit**

```bash
beans update calit-ot22 \
  --body-replace-old "- [ ] Task 2: reword email_cancellation_body_owner to active voice + tests" \
  --body-replace-new "- [x] Task 2: reword email_cancellation_body_owner to active voice + tests"

git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java \
        src/test/java/site/asm0dey/calit/email/EmailServiceTest.java \
        .beans/
git commit -m "fix(email): the guest-cancelled host copy names who cancelled"
```

---

### Task 3: Changelog on `docs-site`, follow-up bean, PR

The changelog lives on a different branch, so this is its own worktree and its own push. The follow-up bean records the group-booking mis-attribution named in the Global Constraints, so it is not silently dropped.

**Files:**
- Modify (on branch `docs-site`): `docs-site/src/content/docs/releases/changelog.md` — the `## Unreleased` section near the top
- Create: `.beans/` entry for the group-booking follow-up (on the feature branch)

**Interfaces:**
- Consumes: the finished wording from Tasks 1 and 2 — quote the shipped English strings verbatim in the changelog bullet.
- Produces: nothing later tasks depend on.

---

- [ ] **Step 1: File the group-booking follow-up bean**

```bash
beans create "Group cancellation mail mis-attributes who cancelled" -t bug -s todo -p low -d "For a group booking, BookingCancelled carries only a byOwner boolean, so EmailService cannot tell which co-host clicked cancel. Every co-host's copy therefore reads \"You cancelled your meeting with {name}.\" and the invitee's copy names l.owner.ownerName as the canceller, who may be a different host. Fix needs an actor id on the BookingCancelled event (and the same treatment for BookingRescheduled, which has the identical shape).

Deferred out of GH #198 / bean calit-ot22 deliberately — that branch was a copy fix and the mis-attribution predates it."
```

- [ ] **Step 2: Add the changelog bullet on `docs-site`**

The `docs-site` branch is a separate Astro project; do not merge it into the feature branch. Use a worktree so the feature branch checkout stays put:

```bash
git fetch origin
git worktree add /tmp/calit-docs-site origin/docs-site
cd /tmp/calit-docs-site
git switch -c docs/198-cancellation-wording origin/docs-site
```

Open `docs-site/src/content/docs/releases/changelog.md`. Under the `## Unreleased` heading (whose standing subtitle is "Merged but not yet in a tagged release."), add this bullet as the **first** bullet of the section, above the existing ones:

```markdown
- **Cancellation emails now say who cancelled.** When a host cancelled a
  booking, their own copy of the mail reused the invitee's wording — *"Your
  booking has been cancelled."* — which reads as though the invitee cancelled
  on them, and never named who the meeting was with. The host now reads *"You
  cancelled your meeting with `<invitee>`."* The other direction was passive in
  the same way: when the invitee cancelled, the host's copy said *"`<invitee>`'s
  booking was cancelled."* and now says *"`<invitee>` cancelled their booking."*
  Both strings are translated in German and Hebrew. The invitee's own copy is
  unchanged — for the person the cancellation happened to, the existing wording
  is right. ([#201](https://github.com/asm0dey/calit/pull/201))
```

Replace `201` with this branch's actual PR number once it is open — check with `gh pr list --head fix/198-owner-cancellation-wording --json number`. Do not guess it; a wrong number links to someone else's PR.

If the `## Unreleased` section already carries an upgrade note at its end, leave it. If the section has no upgrade note, add one after the last bullet:

```markdown
Upgrade: nothing to do — copy change only, no configuration or database changes.
```

- [ ] **Step 3: Commit and push the docs branch**

```bash
git add docs-site/src/content/docs/releases/changelog.md
git commit -m "docs(changelog): cancellation emails say who cancelled"
git push -u origin docs/198-cancellation-wording
```

Open a PR against `docs-site` (not `main`):

```bash
gh pr create --base docs-site --title "docs(changelog): cancellation emails say who cancelled" \
  --body "Changelog bullet for the #198 wording fix. Pairs with the code PR on \`main\`."
```

Then clean up the worktree:

```bash
cd -
git worktree remove /tmp/calit-docs-site
```

- [ ] **Step 4: Verify the code branch is green and complete**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn test
git log --oneline origin/main..HEAD
```

Expected: `BUILD SUCCESS` with 0 failures and 0 errors, and three commits (Task 1, Task 2, the follow-up bean) on the branch.

- [ ] **Step 5: Mark the bean complete and open the PR**

```bash
beans update calit-ot22 \
  --body-replace-old "- [ ] Task 3: docs-site \`## Unreleased\` changelog entry" \
  --body-replace-new "- [x] Task 3: docs-site \`## Unreleased\` changelog entry" \
  --body-append "## Summary of Changes

- New \`email_cancellation_body_owner_self(name)\` message (en/de/he) and the \`byOwner\` x \`recipientRole == 'owner'\` branch of \`email/cancellation.html\` now uses it: \"You cancelled your meeting with {name}.\"
- \`email_cancellation_body_owner\` reworded from the passive \"{name}'s booking was cancelled.\" to \"{name} cancelled their booking.\" (en/de/he).
- Tests: two branch-level tests in \`EmailRoleCopyTest\` (which gained the \`cancellation\` template plus \`ownerName\`/\`byOwner\` data), one new end-to-end test in \`EmailServiceTest\`, and the previously vacuous negative assertion in the host-cancel test replaced with a positive one.
- Changelog bullet on \`docs-site\`. Group-booking canceller mis-attribution deferred to its own bean." \
  -s completed

git add .beans/
git commit -m "chore(beans): close calit-ot22 (cancellation email wording)"
git push -u origin fix/198-owner-cancellation-wording
```

Before opening the PR, run the `show-me` skill on the change and put the resulting diagram in the PR body — the four-cell `byOwner` × `recipientRole` matrix with the old and new string in each cell is the whole change and reads far better as a table than as prose.

```bash
gh pr create --base main --title "fix(email): cancellation mail says who cancelled" --body "…closes #198, matrix diagram, link to the docs-site PR…"
```
