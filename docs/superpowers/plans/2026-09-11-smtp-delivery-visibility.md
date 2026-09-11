# SMTP Delivery Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make broken or unconfigured SMTP visible everywhere it matters — a banner on the owner's dashboard, a loud copy-link toast, an honest sentence on the guest's confirmation page, a downloadable `.ics` so a failed send never costs the guest their calendar entry, and a README that says SMTP is required.

**Architecture:** One new `MailHealth` bean in the `email` package is the single seam. It answers three questions — *is mail configured and reachable* (cached 60s, delegating to the existing `SmtpHealthCheck`), *how many messages were given up on* (`email_outbox` rows with `next_attempt_at IS NULL`), and *is there undelivered mail for this address* — and those three answers drive all four UI surfaces. No new dependency, no new table, no polling.

**Tech Stack:** Quarkus 3.38 / Java 25, Panache (`EmailOutbox`), Qute `@CheckedTemplate`, MicroProfile Health, Tailwind v4 + daisyUI 5, RestAssured + JUnit 5 + Mockito (`@QuarkusTest`, `@InjectSpy`).

**Spec:** GitHub issue [#195](https://github.com/asm0dey/calit/issues/195) — *"No warning when SMTP is unreachable. Bookings silently send no confirmation email"*. Bean: `calit-2oik`.

---

## Two deliberate deviations from the spec

Read these before Task 4. Both are documented here so a reviewer can reject them on the spot rather than discovering them in the diff.

### 1. No polling on the guest confirmation page

The spec assumes the confirmation page cannot know the send outcome at render time, and proposes polling the outbox row. **It can know.** The chain is entirely synchronous inside one HTTP request:

- `PublicResource.book(...)` calls `bookingService.book(...)`, which is `@Transactional`.
- The mail observers in `EmailService` are `@Observes(during = TransactionPhase.AFTER_SUCCESS)` — synchronous observers that fire on the committing thread when that transaction commits, i.e. when `bookingService.book(...)` returns.
- `MailSender.send(...)` attempts a direct SMTP send and, on failure, parks the mail in `email_outbox` in a `requiringNew()` transaction. It never throws.
- Only *then* does `PublicResource` reach `return confirmationPage(booking, type);` (`src/main/java/site/asm0dey/calit/web/PublicResource.java:460`).

So by the time the page renders, either the mail went out or an `email_outbox` row exists. A single count query answers it. Polling, a JS timer, and a status endpoint are all unnecessary — and this version degrades correctly with JavaScript off, which the polling version does not (see `CLAUDE.md`: *every feature works without JavaScript*).

Task 4 Step 2 is written to **fail loudly** if this reasoning is wrong: it forces `sendNow` to throw, posts a real booking, and asserts the failure copy appears in that same response. If that test cannot be made to pass, stop and report — do not silently reintroduce polling.

The correlation key is the recipient address, not a booking id, because `email_outbox` has no booking column and adding one would mean a migration plus threading a booking id through the generic `MailSender` seam. The consequence: a guest who books twice while SMTP is down sees the warning on the second booking too, even if that second mail somehow went out. That reads as *"we have undelivered mail for you"*, which is true. Documented as a `ponytail:` comment in the code.

### 2. The `.ics` download is offered unconditionally

The spec makes the download a failure-path affordance and notes that offering it on the success path "would do no harm". Unconditional is the smaller diff — no branch, one link — and it is strictly more useful: a guest whose mail client eats the attachment, or who booked from a shared machine, gets the calendar entry either way. The failure path still gets it, which is the case the spec cares about.

---

## Global Constraints

- Docker must be running for `mvn test` (Dev Services Postgres). No embedded/H2 fallback.
- Build JDK: `export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca` before `./mvnw` — the default `mvn` is JDK 21 and fails with *"release 25 not supported"*.
- **Owner scoping:** every tenant query filters by `currentOwner.id()`. `email_outbox` is deliberately **not** a tenant table — it has no `owner_id` and the dead-letter count is a deployment-wide operator signal. Do not invent per-owner scoping for it; do note in the banner copy that it describes the instance, not the owner's own mail.
- **i18n:** every new or changed user-facing string needs its `@Message` default plus a `de` **and** a `he` value in `src/main/resources/messages/{msg,adm}_{de,he}.properties`, keyed by method name, in the same commit. `{placeholder}` names must be identical across all locales. The German and Hebrew values are supplied verbatim in the tasks below; the Hebrew was not written by a native speaker, so open a translation-review issue and reference it in the PR (see Task 7).
- **CSRF:** `quarkus-rest-csrf` is on in prod, off in `%test`. This plan adds no new POST form, so no `{inject:csrf.token}` is needed. If you add one, it is mandatory or the form 400s in prod.
- Qute `.html` templates are deliberately NOT Prettier-formatted. `bun run format` covers Java (`spotless:apply`) and `*.{js,ts,css}` only.
- **Test profile note:** `%test` and `%dev` run `quarkus.mailer.mock=true`, so `MailHealth` reports `UNCONFIGURED` by default in both. That means the degraded UI is the *default* in tests (convenient — no mocking needed to assert it) and that the dev server will show the banner. Both are correct behaviour, not bugs.
- Never open a PR while `mvn test` is red — the *whole* suite, not just touched classes.
- Migrations: this plan adds none. If you find yourself needing one, add a new `V32__*.sql`; never edit an applied migration.

---

### Task 1: `MailHealth` — the single mail-delivery-status seam

**Files:**
- Create: `src/main/java/site/asm0dey/calit/email/MailHealth.java`
- Test: `src/test/java/site/asm0dey/calit/email/MailHealthTest.java` (create)

**Interfaces:**
- Consumes: `site.asm0dey.calit.health.SmtpHealthCheck` (existing, unchanged) and `site.asm0dey.calit.email.EmailOutbox` (existing, unchanged).
- Produces, relied on by Tasks 2–5:
  - `MailHealth.State` — enum `OK`, `UNCONFIGURED`, `UNREACHABLE`
  - `MailHealth.Status` — record `(State state, long deadLetters)` with `boolean degraded()`, `boolean unconfigured()`, `boolean unreachable()`, `boolean hasDeadLetters()`
  - `Status status()` — cached probe + live dead-letter count
  - `boolean degraded()` — convenience for Qute's `{cdi:mailHealth.degraded}`
  - `boolean undeliveredFor(String recipient)` — live, uncached
  - CDI bean name: `mailHealth`

`SmtpHealthCheck` is **not modified**. `MailHealth` reads its `data.state` value, which is that check's already-documented public contract (exposed at `/q/health/ready`, values `mocked-or-unconfigured` / `reachable` / `unreachable`). Step 5 adds a test that pins those exact spellings so a rename of either side is caught.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/MailHealthTest.java`:

```java
package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// #195: MailHealth is the single seam four UI surfaces read to decide whether to warn about mail.
@QuarkusTest
class MailHealthTest {

    @Inject
    MailHealth mailHealth;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(EmailOutbox::deleteAll);
    }

    @Test
    void mockedMailerCountsAsUnconfigured() {
        // %test runs quarkus.mailer.mock=true -- a deployment that sends nothing.
        var status = mailHealth.status();
        assertEquals(MailHealth.State.UNCONFIGURED, status.state());
        assertTrue(status.degraded(), "unconfigured mail is degraded: guests get no confirmations");
        assertTrue(status.unconfigured());
        assertFalse(status.unreachable(), "unconfigured and unreachable are different operator problems");
    }

    @Test
    void deadLettersAreCounted() {
        assertEquals(0L, mailHealth.status().deadLetters(), "clean outbox has no dead letters");
        assertFalse(mailHealth.status().hasDeadLetters());

        QuarkusTransaction.requiringNew().run(() -> {
            park("dead@example.com", null); // next_attempt_at null = given up on
            park("retrying@example.com", Instant.now()); // still scheduled = not dead
        });

        assertEquals(
                1L,
                mailHealth.status().deadLetters(),
                "only rows we've given up on count -- a row still in backoff is not a dead letter");
        assertTrue(mailHealth.status().hasDeadLetters());
    }

    @Test
    void sentRowsAreNotDeadLetters() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = parkRow("delivered@example.com", null);
            r.sentAt = Instant.now(); // delivered on a retry, then parked-row bookkeeping cleared it
            r.persist();
        });

        assertEquals(0L, mailHealth.status().deadLetters(), "a row that eventually sent is not a dead letter");
    }

    @Test
    void undeliveredForFindsMailStillOwedToAnAddress() {
        assertFalse(mailHealth.undeliveredFor("guest@example.com"), "nothing parked -> nothing owed");

        QuarkusTransaction.requiringNew().run(() -> park("guest@example.com", Instant.now()));
        assertTrue(mailHealth.undeliveredFor("guest@example.com"), "a parked, unsent row means mail is owed");

        assertFalse(mailHealth.undeliveredFor("someone.else@example.com"), "scoped to the address asked about");
    }

    @Test
    void undeliveredForCountsGivenUpMailToo() {
        QuarkusTransaction.requiringNew().run(() -> park("gone@example.com", null));
        assertTrue(
                mailHealth.undeliveredFor("gone@example.com"),
                "a dead letter is the strongest form of undelivered -- it will never arrive");
    }

    @Test
    void undeliveredForIgnoresMailThatWasSent() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = parkRow("ok@example.com", null);
            r.sentAt = Instant.now();
            r.persist();
        });
        assertFalse(mailHealth.undeliveredFor("ok@example.com"), "a sent row owes nothing");
    }

    private static void park(String recipient, Instant nextAttemptAt) {
        parkRow(recipient, nextAttemptAt);
    }

    private static EmailOutbox parkRow(String recipient, Instant nextAttemptAt) {
        var r = new EmailOutbox();
        r.recipient = recipient;
        r.subject = "Subj";
        r.htmlBody = "<p>hi</p>";
        r.attempts = 0;
        r.nextAttemptAt = nextAttemptAt;
        r.createdAt = Instant.now();
        r.persist();
        return r;
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=MailHealthTest
```

Expected: compilation FAILS — `cannot find symbol: class MailHealth`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/site/asm0dey/calit/email/MailHealth.java`:

```java
package site.asm0dey.calit.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Map;
import site.asm0dey.calit.health.SmtpHealthCheck;

/**
 * Whether this deployment can actually deliver mail, and what it already failed to deliver.
 * <p>
 * The single seam behind every "email isn't working" warning in the UI: the owner dashboard banner,
 * the copy-link toast, and the guest's confirmation page. Named {@code mailHealth} so Qute fragments
 * with no owning resource can reach it as {@code {cdi:mailHealth.degraded}}.
 * <p>
 * Reachability delegates to {@link SmtpHealthCheck} rather than duplicating the probe -- that check
 * already publishes the three states at {@code /q/health/ready} under {@code data.state}, and having
 * one prober means the banner can never disagree with the health endpoint. Its result is cached for
 * {@link #PROBE_TTL_MS}: the probe opens a TCP socket with a 2s timeout, which must not happen on
 * every page render.
 * <p>
 * ponytail: a plain volatile-field cache, not quarkus-cache -- one value, one TTL, no new extension.
 */
@Named("mailHealth")
@ApplicationScoped
public class MailHealth {

    /** Long enough that page renders never probe; short enough that a fixed SMTP box clears fast. */
    static final long PROBE_TTL_MS = 60_000L;

    /** Values published by {@link SmtpHealthCheck} under {@code data.state}. Pinned by a test. */
    static final String STATE_REACHABLE = "reachable";

    static final String STATE_UNREACHABLE = "unreachable";

    /** How mail delivery is doing. {@code UNCONFIGURED} and {@code UNREACHABLE} are different
     *  operator problems: nobody set SMTP up, versus SMTP is set up and the host won't answer. */
    public enum State {
        OK,
        UNCONFIGURED,
        UNREACHABLE
    }

    /**
     * A snapshot for one render. {@code deadLetters} counts {@code email_outbox} rows we gave up on
     * ({@code next_attempt_at IS NULL}, never sent) -- the signal the reachability probe misses,
     * because a host that accepts TCP but rejects auth probes as reachable while mail never leaves.
     */
    public record Status(State state, long deadLetters) {
        public boolean degraded() {
            return state != State.OK;
        }

        public boolean unconfigured() {
            return state == State.UNCONFIGURED;
        }

        public boolean unreachable() {
            return state == State.UNREACHABLE;
        }

        public boolean hasDeadLetters() {
            return deadLetters > 0;
        }
    }

    final SmtpHealthCheck smtp;

    @Inject
    public MailHealth(SmtpHealthCheck smtp) {
        this.smtp = smtp;
    }

    private volatile State cachedState;

    private volatile long probedAtMs;

    /** Cached reachability + a live dead-letter count. Safe to call from a page render. */
    public Status status() {
        return new Status(state(), deadLetters());
    }

    /** Convenience for {@code {cdi:mailHealth.degraded}} in fragments that take no parameters. */
    public boolean degraded() {
        return state() != State.OK;
    }

    /**
     * True when this deployment still owes mail to {@code recipient}: a parked row that has not been
     * sent, whether it is still retrying or was given up on.
     * <p>
     * ponytail: keyed by address, not booking -- {@code email_outbox} has no booking column and
     * adding one means a migration plus threading an id through the generic MailSender seam. The
     * cost is that a guest who books twice during an outage sees the warning on both, which reads as
     * "we have undelivered mail for you" and is true. Add the column only if per-booking precision
     * is ever actually needed.
     */
    public boolean undeliveredFor(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return false;
        }
        return EmailOutbox.count("recipient = ?1 and sentAt is null", recipient) > 0;
    }

    /** Rows we gave up on: dead ({@code nextAttemptAt} null) and never delivered. */
    long deadLetters() {
        return EmailOutbox.count("nextAttemptAt is null and sentAt is null");
    }

    private State state() {
        long now = System.currentTimeMillis();
        State cached = cachedState;
        if (cached != null && now - probedAtMs < PROBE_TTL_MS) {
            return cached;
        }
        State fresh = probe();
        cachedState = fresh;
        probedAtMs = now;
        return fresh;
    }

    private State probe() {
        Map<String, Object> data = smtp.call().getData().orElse(Map.of());
        String reported = String.valueOf(data.get("state"));
        if (STATE_REACHABLE.equals(reported)) {
            return State.OK;
        }
        if (STATE_UNREACHABLE.equals(reported)) {
            return State.UNREACHABLE;
        }
        // "mocked-or-unconfigured", or a value we don't recognise: treat as "can't promise delivery".
        return State.UNCONFIGURED;
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=MailHealthTest
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Pin the `SmtpHealthCheck` contract**

`MailHealth.probe()` matches on two string literals owned by another class. Add a test that fails if either side is renamed. Append to `src/test/java/site/asm0dey/calit/email/MailHealthTest.java`:

```java
    @Test
    void reachableStateSpellingMatchesTheHealthCheck() {
        // MailHealth matches on the literal values SmtpHealthCheck publishes under data.state.
        // If either side is renamed, the banner would silently report UNCONFIGURED forever --
        // a wrong-but-plausible state, which is the worst kind of silent break. Pin both spellings.
        var reachable = new site.asm0dey.calit.health.SmtpHealthCheck(
                        false, java.util.Optional.of("localhost"), 2)
                .call();
        // Port 2 refuses fast -> "unreachable", proving the unreachable spelling.
        assertEquals(
                MailHealth.STATE_UNREACHABLE,
                reachable.getData().orElseThrow().get("state"),
                "MailHealth.STATE_UNREACHABLE must equal what SmtpHealthCheck publishes");

        var mocked = new site.asm0dey.calit.health.SmtpHealthCheck(true, java.util.Optional.empty(), 587)
                .call();
        assertEquals(
                "mocked-or-unconfigured",
                mocked.getData().orElseThrow().get("state"),
                "the mocked/unconfigured spelling is MailHealth's default branch");
    }
```

The `reachable` spelling has no local-port test that can prove it without a live SMTP server, so `STATE_REACHABLE` is covered by review rather than assertion — it is the value in `SmtpHealthCheck:56`. Note this in the PR.

Run again:

```bash
./mvnw test -Dtest=MailHealthTest
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Format and commit**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw spotless:apply
git add src/main/java/site/asm0dey/calit/email/MailHealth.java \
        src/test/java/site/asm0dey/calit/email/MailHealthTest.java
git commit -m "feat(email): add MailHealth, the mail-delivery-status seam (#195)"
```

---

### Task 2: Dashboard banner

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (add keys near the dashboard block around line 109)
- Modify: `src/main/resources/messages/adm_de.properties`
- Modify: `src/main/resources/messages/adm_he.properties`
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java:42-43` (the `dashboard` native template method) and `:319-330` (the `dashboard()` resource method)
- Modify: `src/main/resources/templates/AdminResource/dashboard.html`
- Test: `src/test/java/site/asm0dey/calit/web/AdminMailBannerTest.java` (create)

**Interfaces:**
- Consumes from Task 1: `MailHealth.status()` returning `MailHealth.Status` with `degraded()`, `unconfigured()`, `unreachable()`, `hasDeadLetters()`, `deadLetters()`.
- Produces: `AdminResource.Templates.dashboard(List<Booking>, long, String, boolean, String, String, MailHealth.Status)` — one appended parameter, `mailHealth`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminMailBannerTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;

// #195: a deployment with broken or unconfigured SMTP must say so on the dashboard. %test runs
// quarkus.mailer.mock=true, so MailHealth reports UNCONFIGURED and the banner is the default here.
@QuarkusTest
class AdminMailBannerTest {

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(EmailOutbox::deleteAll);
    }

    @Test
    void unconfiguredMailWarnsOnTheDashboard() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-banner"))
                .body(containsString("Email is not configured"));
    }

    @Test
    void deadLetterCountIsShownWhenThereAreDeadLetters() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(not(containsString("data-mail-dead-letters")));

        QuarkusTransaction.requiringNew().run(() -> {
            var r = new EmailOutbox();
            r.recipient = "dead@example.com";
            r.subject = "Subj";
            r.htmlBody = "<p>hi</p>";
            r.attempts = 10;
            r.nextAttemptAt = null; // given up on
            r.createdAt = Instant.now();
            r.persist();
        });

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-dead-letters"))
                .body(containsString("1"));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=AdminMailBannerTest
```

Expected: both FAIL — no `data-mail-banner` in the response.

- [ ] **Step 3: Add the message keys**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, next to the other `adm_dashboard_*` keys:

```java
    // ---- Mail-delivery banner (#195) ----

    @Message("Email is not configured — guests are not receiving booking confirmations.")
    String adm_mail_banner_unconfigured();

    @Message("Email is not being delivered — guests are not receiving booking confirmations.")
    String adm_mail_banner_unreachable();

    @Message("{count} message(s) were given up on after repeated delivery failures.")
    String adm_mail_banner_dead_letters(long count);

    @Message("This describes the whole instance, not just your account.")
    String adm_mail_banner_scope_note();
```

In `src/main/resources/messages/adm_de.properties`:

```properties
adm_mail_banner_unconfigured=E-Mail ist nicht konfiguriert — Gäste erhalten keine Buchungsbestätigungen.
adm_mail_banner_unreachable=E-Mails werden nicht zugestellt — Gäste erhalten keine Buchungsbestätigungen.
adm_mail_banner_dead_letters=Bei {count} Nachricht(en) wurde die Zustellung nach wiederholten Fehlern aufgegeben.
adm_mail_banner_scope_note=Dies betrifft die gesamte Instanz, nicht nur Ihr Konto.
```

In `src/main/resources/messages/adm_he.properties`:

```properties
adm_mail_banner_unconfigured=אימייל אינו מוגדר — אורחים אינם מקבלים אישורי הזמנה.
adm_mail_banner_unreachable=הודעות אימייל אינן נשלחות — אורחים אינם מקבלים אישורי הזמנה.
adm_mail_banner_dead_letters=ויתרנו על שליחת {count} הודעות לאחר כישלונות חוזרים.
adm_mail_banner_scope_note=מצב זה נוגע לכל המערכת, לא רק לחשבון שלך.
```

The `{count}` placeholder name is identical in all three — required, or the non-English render breaks.

- [ ] **Step 4: Thread the status into the template**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, change the native method (line ~42):

```java
        public static native TemplateInstance dashboard(
                List<Booking> upcoming,
                long pendingCount,
                String tzScript,
                boolean isAdmin,
                String title,
                String zone,
                MailHealth.Status mailHealth);
```

Add the import `import site.asm0dey.calit.email.MailHealth;` and inject the bean alongside the resource's other collaborators:

```java
    @Inject
    MailHealth mailHealthBean;
```

(Match the surrounding injection style — this class already uses CDI field injection with the `java:S6813` suppression at type level; if it uses constructor injection instead, follow that.)

Then in `dashboard()` (line ~328):

```java
        return Templates.dashboard(
                upcoming,
                pendingCount,
                Layout.TZ_SCRIPT,
                isAdmin(),
                m().adm_dashboard_title(),
                ownerZone(),
                mailHealthBean.status());
```

- [ ] **Step 5: Render the banner**

In `src/main/resources/templates/AdminResource/dashboard.html`, add the param declaration at the top, after the existing `{@…}` lines:

```html
{@site.asm0dey.calit.email.MailHealth$Status mailHealth}
```

and insert the banner immediately after the `{#include adminBase …}` line's `<h1>`, before the `<div class="stats …">` block:

```html
  {#if mailHealth.degraded}
  {! #195: a deployment with broken or unconfigured SMTP looks healthy from inside the app while
     every guest-facing mail silently fails. This is the one place an owner is guaranteed to look. !}
  <div data-mail-banner class="alert alert-error mb-6" role="alert">
    <div>
      <p class="font-semibold">
        {#if mailHealth.unconfigured}{adm:adm_mail_banner_unconfigured}{#else}{adm:adm_mail_banner_unreachable}{/if}
      </p>
      {#if mailHealth.hasDeadLetters}
      <p data-mail-dead-letters class="text-sm">{adm:adm_mail_banner_dead_letters(mailHealth.deadLetters)}</p>
      {/if}
      <p class="text-sm opacity-80">{adm:adm_mail_banner_scope_note}</p>
    </div>
  </div>
  {/if}
```

Note the Qute nested-class syntax `MailHealth$Status` in the `{@…}` declaration — a `.` there resolves as a package separator and fails the build.

The dead-letter line renders whenever there are dead letters, which today only happens inside the `degraded` branch's markup. If you later want dead letters surfaced while SMTP probes healthy (the "accepts TCP, rejects auth" case the spec names), lift the `{#if mailHealth.hasDeadLetters}` block out of the `degraded` guard into its own banner — leave that for a follow-up bean, not this task.

- [ ] **Step 6: Run the test and verify it passes**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=AdminMailBannerTest
```

Expected: PASS, 2 tests.

- [ ] **Step 7: Check i18n key parity**

```bash
for k in adm_mail_banner_unconfigured adm_mail_banner_unreachable \
         adm_mail_banner_dead_letters adm_mail_banner_scope_note; do
  for f in src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties; do
    grep -q "^$k=" "$f" || echo "MISSING $k in $f"
  done
done
```

Expected: no output.

- [ ] **Step 8: Run the neighbouring suites**

The dashboard is re-rendered by other flows (`AdminResource:1667`, `:1678`). Prove nothing broke:

```bash
./mvnw test -Dtest='AdminNavTest,AdminI18nTest,AdminAuthTest,AdminPendingTest'
```

Expected: PASS.

- [ ] **Step 9: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/dashboard.html \
        src/test/java/site/asm0dey/calit/web/AdminMailBannerTest.java
git commit -m "feat(admin): warn on the dashboard when mail isn't being delivered (#195)"
```

---

### Task 3: Loud copy-link toast when mail is degraded

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (next to `adm_meetingTypes_toast_copied`, line ~288)
- Modify: `src/main/resources/messages/adm_de.properties`
- Modify: `src/main/resources/messages/adm_he.properties`
- Modify: `src/main/resources/templates/AdminResource/_copyToast.html`
- Test: `src/test/java/site/asm0dey/calit/web/AdminCopyToastTest.java` (create)

**Interfaces:**
- Consumes from Task 1: the CDI bean name `mailHealth` and its `degraded()` method, reached from Qute as `{cdi:mailHealth.degraded}`.
- Produces: nothing later tasks rely on.

`_copyToast.html` is a fragment with no owning resource method — it is pulled in by `shared.html:40` and `meetingTypes.html:186` via a bare `{#include AdminResource/_copyToast /}`. Threading a parameter to it would mean editing two templates, two `@CheckedTemplate` native signatures, and two resource methods. The `cdi:` namespace reaches the bean directly, the same mechanism the codebase already uses for `{inject:csrf.token}`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/AdminCopyToastTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// #195: copying a booking link is the moment before the owner hands it to someone. If mail is
// broken, the toast must say so instead of a cheerful green "Copied". %test runs the mock mailer,
// so MailHealth is UNCONFIGURED and the degraded variant is what these pages should render.
@QuarkusTest
class AdminCopyToastTest {

    @Test
    void meetingTypesPageCarriesTheDegradedToastCopy() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-degraded=\"true\""))
                .body(containsString("will get no confirmation"));
    }

    @Test
    void sharedPageCarriesTheDegradedToastCopy() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-degraded=\"true\""));
    }
}
```

Confirm the shared page's path before running — check the `@Path` on the method in `AdminResource` that renders `shared.html`. If it is not `/me/shared`, use the real one.

- [ ] **Step 2: Run the test and verify it fails**

```bash
./mvnw test -Dtest=AdminCopyToastTest
```

Expected: both FAIL — no `data-mail-degraded` attribute.

- [ ] **Step 3: Add the message key**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, next to `adm_meetingTypes_toast_copied`:

```java
    @Message("Link copied. Email is not configured — whoever books this will get no confirmation.")
    String adm_meetingTypes_toast_copied_no_mail();
```

In `src/main/resources/messages/adm_de.properties`:

```properties
adm_meetingTypes_toast_copied_no_mail=Link kopiert. E-Mail ist nicht konfiguriert — wer hier bucht, erhält keine Bestätigung.
```

In `src/main/resources/messages/adm_he.properties`:

```properties
adm_meetingTypes_toast_copied_no_mail=הקישור הועתק. אימייל אינו מוגדר — מי שיזמין דרך קישור זה לא יקבל אישור.
```

- [ ] **Step 4: Rewrite the toast fragment**

Replace `src/main/resources/templates/AdminResource/_copyToast.html` with:

```html
{! #195: when mail can't be delivered, a copied booking link is a promise the deployment can't keep.
   The degraded flag comes from {cdi:mailHealth.degraded} rather than a template parameter because
   this fragment has no owning resource method -- it's pulled in bare by shared.html and
   meetingTypes.html, so a parameter would mean editing two templates, two @CheckedTemplate
   signatures and two resource methods to say one boolean. !}
<div class="toast toast-end z-50" id="copy-toast" style="display:none" role="status" aria-live="polite">
  <div class="alert alert-success" id="copy-toast-alert">
    <span id="copy-toast-msg">{adm:adm_meetingTypes_toast_copied}</span>
  </div>
</div>
<div id="copy-toast-config"
     style="display:none"
     data-mail-degraded="{#if cdi:mailHealth.degraded}true{#else}false{/if}"
     data-degraded-msg="{adm:adm_meetingTypes_toast_copied_no_mail}"></div>

<script>
  (function () {
    var toast = document.getElementById('copy-toast');
    var alert = document.getElementById('copy-toast-alert');
    var msg = document.getElementById('copy-toast-msg');
    var cfg = document.getElementById('copy-toast-config');
    var mailDegraded = !!cfg && cfg.dataset.mailDegraded === 'true';
    var degradedMsg = cfg ? cfg.dataset.degradedMsg : '';
    var timer = null;
    function showToast(text, ok) {
      if (!toast) { return; }
      if (msg) { msg.textContent = text; }
      if (alert) { alert.className = ok ? 'alert alert-success' : 'alert alert-error'; }
      toast.style.display = '';
      if (timer) { clearTimeout(timer); }
      // A warning has to be readable, not glimpsed: hold it long enough to actually read.
      timer = setTimeout(function () { toast.style.display = 'none'; }, ok ? 2000 : 6000);
    }
    document.addEventListener('click', function (e) {
      var btn = e.target.closest ? e.target.closest('.copy-link-btn') : null;
      if (!btn) { return; }
      var url = btn.dataset.copyLink;
      var okText = msg ? msg.textContent : '';
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(
          function () {
            if (mailDegraded) { showToast(degradedMsg, false); } else { showToast(okText, true); }
          },
          function () { showToast('Copy failed — ' + url, false); }
        );
      } else {
        showToast('Copy failed — ' + url, false);
      }
    });
  })();
</script>
```

Two behavioural changes beyond the copy: the degraded toast renders `alert-error` (red), and non-ok toasts hold for 6s instead of 2s so a warning can actually be read.

One subtlety carried over from the original: `msg.textContent` was read *inside* the success callback, after `showToast` may already have overwritten it. It is now captured as `okText` before the async call, which fixes a latent bug where a second copy after a failed one would show the failure text as the success text.

- [ ] **Step 5: Run the test and verify it passes**

```bash
./mvnw test -Dtest=AdminCopyToastTest
```

Expected: PASS, 2 tests.

If `{cdi:mailHealth.degraded}` does not resolve (build-time Qute error, or the attribute renders empty), the fallback is the parameter route: add a `boolean mailDegraded` param to `_copyToast.html`, pass it at both `{#include AdminResource/_copyToast mailDegraded=mailDegraded /}` call sites, add the param to `shared.html` and `meetingTypes.html`, extend both native `Templates` signatures in `AdminResource`, and pass `mailHealthBean.degraded()` from both resource methods. Do not skip the feature.

- [ ] **Step 6: Check the other toast-bearing suites and i18n parity**

```bash
./mvnw test -Dtest='AdminMeetingTypesTest,AdminMeetingTypeFormTest,AdminNavTest'
for f in src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties; do
  grep -q "^adm_meetingTypes_toast_copied_no_mail=" "$f" || echo "MISSING in $f"
done
```

Expected: PASS, and no `MISSING` output.

- [ ] **Step 7: Format and commit**

```bash
./mvnw spotless:apply
bunx prettier --check src/main/resources/templates/AdminResource/_copyToast.html || true
git add src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/main/resources/templates/AdminResource/_copyToast.html \
        src/test/java/site/asm0dey/calit/web/AdminCopyToastTest.java
git commit -m "feat(admin): copy-link toast warns when mail can't be delivered (#195)"
```

Do **not** actually apply Prettier to the `.html` — the `|| true` above is only to remind you the check is advisory here; Qute templates are excluded from formatting by project policy.

---

### Task 4: Honest confirmation-page copy for the guest

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java:346-351` (beside `pub_conf_pending_email` / `pub_conf_confirmed_email`)
- Modify: `src/main/resources/messages/msg_de.properties`
- Modify: `src/main/resources/messages/msg_he.properties`
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (the `confirmation` native template method at `:61`, and `confirmationPage(...)` at `:464-490`)
- Modify: `src/main/resources/templates/PublicResource/confirmation.html:31`
- Test: `src/test/java/site/asm0dey/calit/web/GuestConfirmationMailCopyTest.java` (create)

**Interfaces:**
- Consumes from Task 1: `MailHealth.undeliveredFor(String)`.
- Produces, relied on by Task 5: `PublicResource.Templates.confirmation(...)` gains a trailing `boolean mailUndelivered` parameter; `confirmation.html` gains `{@java.lang.Boolean mailUndelivered}`.

Read the "Two deliberate deviations" section above before starting. **Step 2 is the load-bearing test for the no-polling design** — if it cannot pass, the synchronous-observer reasoning is wrong and this task needs redesigning, not patching.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/GuestConfirmationMailCopyTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailSender;

// #195: the confirmation page must not promise an email that was never sent. The page renders in
// the SAME request as the booking commit -- BookingService is @Transactional, the mail observers
// are AFTER_SUCCESS (synchronous, on the committing thread), and MailSender parks failures in the
// outbox before PublicResource.book reaches `return confirmationPage(...)`. So the outcome is
// already knowable at render time and no polling is needed. THIS TEST IS THE PROOF OF THAT.
@QuarkusTest
class GuestConfirmationMailCopyTest {

    @InjectSpy
    MailSender mailSender;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(EmailOutbox::deleteAll);
    }

    @Test
    void smtpDownMakesTheConfirmationPageSayTheMailDidNotGoOut() {
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender)
                .sendNow(any(), anyString(), anyString(), anyString(), any());

        GuestBookingFixture.book(this.getClass().getSimpleName() + "-down")
                .then()
                .statusCode(200)
                .body(containsString("couldn't send the confirmation email"))
                .body(not(containsString("is on its way")));
    }

    @Test
    void workingSmtpKeepsTheOptimisticCopy() {
        doNothing().when(mailSender).sendNow(any(), anyString(), anyString(), anyString(), any());

        GuestBookingFixture.book(this.getClass().getSimpleName() + "-ok")
                .then()
                .statusCode(200)
                .body(containsString("is on its way"))
                .body(not(containsString("couldn't send")));
    }
}
```

You need a booking fixture. `src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java` and `BookingPostTest.java` already post a real booking end-to-end — read them and extract the setup into a small `GuestBookingFixture` helper in the same package, exposing:

```java
/** Seeds a meeting type + availability for owner 1 and POSTs one booking. Returns the response. */
static io.restassured.response.Response book(String slugSuffix)
```

Do not duplicate the seeding inline in two test classes — extract once, and update the two existing tests to use it only if that is a trivial edit; otherwise leave them alone and let the helper be new code.

- [ ] **Step 2: Run the test and verify it fails — and verify *how* it fails**

```bash
./mvnw test -Dtest=GuestConfirmationMailCopyTest
```

Expected: `smtpDownMakesTheConfirmationPageSayTheMailDidNotGoOut` FAILS because the page still says "is on its way"; `workingSmtpKeepsTheOptimisticCopy` PASSES (that is today's unconditional behaviour).

**Then verify the design assumption before writing any code.** Add a temporary assertion inside the first test, right after the `book(...)` call, and run it:

```java
long parked = QuarkusTransaction.requiringNew()
        .call(() -> EmailOutbox.count("sentAt is null"));
org.junit.jupiter.api.Assertions.assertTrue(
        parked > 0, "outbox row must exist by the time the response is produced");
```

If `parked` is 0, the observers are not running synchronously within the request and this whole design is wrong — **stop, report, and do not proceed**. If it is > 0 (expected), delete the temporary assertion and continue.

- [ ] **Step 3: Add the message keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, after `pub_conf_confirmed_email`:

```java
    @Message(
            "We couldn't send the confirmation email to {inviteeEmail} yet — we'll keep trying. "
                    + "Your booking is confirmed either way.")
    String pub_conf_email_failed(String inviteeEmail);
```

In `src/main/resources/messages/msg_de.properties`:

```properties
pub_conf_email_failed=Die Bestätigungs-E-Mail an {inviteeEmail} konnte noch nicht gesendet werden — wir versuchen es weiter. Ihre Buchung ist trotzdem bestätigt.
```

In `src/main/resources/messages/msg_he.properties`:

```properties
pub_conf_email_failed=עדיין לא הצלחנו לשלוח אימייל אישור אל {inviteeEmail} — אנחנו ממשיכים לנסות. ההזמנה שלך מאושרת בכל מקרה.
```

The wording is deliberately reassuring (the booking really is fine, only the mail failed) and deliberately carries **no SMTP error text** — a guest has no use for "Connection refused" and it leaks deployment internals. The owner's side already has the detail: `EmailOutbox.lastError`.

- [ ] **Step 4: Compute and pass the flag**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`, add `import site.asm0dey.calit.email.MailHealth;` and inject the bean the same way the class injects its other collaborators:

```java
    @Inject
    MailHealth mailHealth;
```

Extend the native template method (around `:61`) with a trailing parameter — keep the existing parameters and their order:

```java
        public static native TemplateInstance confirmation(
                String title,
                Booking booking,
                MeetingType type,
                String meetingName,
                boolean pending,
                String location,
                String whenLabel,
                String startUtcIso,
                String tzBar,
                String tzScript,
                boolean mailUndelivered);
```

In `confirmationPage(...)`, just before the `return`:

```java
        // #195: the mail observers are AFTER_SUCCESS -- synchronous, on the thread that committed
        // bookingService.book(). So by the time we render, either the mail went out or MailSender
        // parked it. No polling needed; the page can just tell the truth.
        boolean mailUndelivered = mailHealth.undeliveredFor(booking.inviteeEmail);
```

and pass `mailUndelivered` as the final argument to `Templates.confirmation(...)`.

- [ ] **Step 5: Render the honest sentence**

In `src/main/resources/templates/PublicResource/confirmation.html`, add to the param block at the top:

```html
{@java.lang.Boolean mailUndelivered}
```

and replace line 31:

```html
      <p>{#if pending}{msg:pub_conf_pending_email(booking.inviteeEmail)}{#else}{msg:pub_conf_confirmed_email(booking.inviteeEmail)}{/if}</p>
```

with:

```html
      {#if mailUndelivered}
      <p class="alert alert-warning" data-mail-undelivered>{msg:pub_conf_email_failed(booking.inviteeEmail)}</p>
      {#else}
      <p>{#if pending}{msg:pub_conf_pending_email(booking.inviteeEmail)}{#else}{msg:pub_conf_confirmed_email(booking.inviteeEmail)}{/if}</p>
      {/if}
```

- [ ] **Step 6: Run the test and verify it passes**

```bash
./mvnw test -Dtest=GuestConfirmationMailCopyTest
```

Expected: PASS, 2 tests.

- [ ] **Step 7: Run the guest-flow suites and check i18n parity**

```bash
./mvnw test -Dtest='GuestBookingFlowTest,BookingPostTest,BookPageTest,PublicI18nTest'
for f in src/main/resources/messages/msg_de.properties src/main/resources/messages/msg_he.properties; do
  grep -q "^pub_conf_email_failed=" "$f" || echo "MISSING in $f"
done
```

Expected: PASS, no `MISSING`. If `PublicI18nTest` does not exist under that name, run whatever `*I18n*` tests the `web` package has.

Note: in `%test` the mailer is mocked and mock sends succeed, so existing guest-flow tests keep seeing the optimistic copy and should stay green. If one goes red, it is asserting on the exact sentence — read it before changing it.

- [ ] **Step 8: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/resources/templates/PublicResource/confirmation.html \
        src/test/java/site/asm0dey/calit/web/GuestConfirmationMailCopyTest.java \
        src/test/java/site/asm0dey/calit/web/GuestBookingFixture.java
git commit -m "feat(booking): confirmation page stops promising mail that didn't send (#195)"
```

---

### Task 5: Serve the `.ics` to the guest

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java` (add one public method near `resolveLocation` at `:899`)
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (new `@GET` endpoint near the other `/booking/{manageToken}/…` routes at `:493+`)
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Modify: `src/main/resources/messages/msg_de.properties`, `msg_he.properties`
- Modify: `src/main/resources/templates/PublicResource/confirmation.html`
- Test: `src/test/java/site/asm0dey/calit/web/GuestIcsDownloadTest.java` (create)

**Interfaces:**
- Consumes from Task 4: `confirmation.html` already declares `{@java.lang.Boolean mailUndelivered}` (unused by this task — the link is unconditional, see deviation 2).
- Produces:
  - `EmailService.inviteeIcs(Long bookingId)` → `byte[]`, or `null` when the booking or its owner settings are gone.
  - Route `GET /booking/{manageToken}/invite.ics` → `text/calendar`, `Content-Disposition: attachment; filename="invite.ics"`, 404 on unknown token.

Today `IcsBuilder` output only ever leaves the app as a mail attachment, so a failed send takes the calendar entry with it. The bytes already exist at the moment the failure is known — this task just serves them.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/GuestIcsDownloadTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// #195: IcsBuilder output only ever left the app as a mail attachment, so a failed send took the
// guest's calendar entry with it. The confirmation page now serves it directly.
@QuarkusTest
class GuestIcsDownloadTest {

    @Test
    void confirmationPageOffersTheIcsDownload() {
        GuestBookingFixture.book(this.getClass().getSimpleName() + "-link")
                .then()
                .statusCode(200)
                .body(containsString("/invite.ics"))
                .body(containsString("Add to your calendar"));
    }

    @Test
    void icsEndpointServesTheCalendarEntry() {
        String token = GuestBookingFixture.manageTokenOf(
                GuestBookingFixture.book(this.getClass().getSimpleName() + "-dl"));

        given().when()
                .get("/booking/" + token + "/invite.ics")
                .then()
                .statusCode(200)
                .contentType(containsString("text/calendar"))
                .header("Content-Disposition", containsString("invite.ics"))
                .body(containsString("BEGIN:VCALENDAR"))
                .body(containsString("BEGIN:VEVENT"))
                .body(containsString("UID:" + token));
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/not-a-real-token/invite.ics").then().statusCode(404);
    }
}
```

Add `manageTokenOf(Response)` to the `GuestBookingFixture` helper created in Task 4 — the confirmation page links `/booking/{manageToken}/manage`, so the token can be regexed out of the response body:

```java
    /** Pulls the manage token out of a confirmation-page response (it links /booking/{t}/manage). */
    static String manageTokenOf(io.restassured.response.Response r) {
        var m = java.util.regex.Pattern.compile("/booking/([^/\"]+)/manage").matcher(r.body().asString());
        org.junit.jupiter.api.Assertions.assertTrue(m.find(), "confirmation page links the manage URL");
        return m.group(1);
    }
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./mvnw test -Dtest=GuestIcsDownloadTest
```

Expected: all three FAIL — no `/invite.ics` route (404 on the download, missing link on the page). `unknownTokenIs404` may accidentally pass for the wrong reason (no route at all is also a 404); that is fine, it becomes meaningful once the route exists.

- [ ] **Step 3: Expose the invitee's `.ics` from `EmailService`**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`, add next to `resolveLocation`:

```java
    /**
     * The invitee's calendar entry for one booking — byte-identical to what the confirmation mail
     * attaches. Returns null when the booking (or its owner's settings) is gone, matching every
     * other {@link #load} caller's "nothing to build" path.
     * <p>
     * Public because a failed send used to take the calendar entry with it: the .ics existed only as
     * a mail attachment, so a guest with a confirmed booking had no way to get it. Opens its own
     * transaction via {@link #load}, so it is safe to call from a plain GET.
     */
    public byte[] inviteeIcs(Long bookingId) {
        Loaded l = load(bookingId);
        if (l == null) {
            return null;
        }
        return IcsBuilder.build(IcsEvent.builder()
                        .uid(l.booking.manageToken)
                        .summary(label(l))
                        .description(l.booking.effectiveDescription(l.meetingType))
                        .location(resolveLocation(l))
                        .organizer(new IcsBuilder.Party(l.owner.ownerName, mailFrom))
                        .attendee(new IcsBuilder.Party(l.booking.inviteeName, l.booking.inviteeEmail))
                        .start(l.booking.startUtc)
                        .end(l.booking.endUtc)
                        .build())
                .getBytes(StandardCharsets.UTF_8);
    }
```

This mirrors the invitee `.ics` built in `sendForKindLocaleAware` (`EmailService:812-822`) exactly, with one deliberate difference: it is built **regardless of whether Google is connected**. In the mail path calit omits the `.ics` when Google natively notifies attendees; here the guest explicitly asked for the file, so always give it to them.

- [ ] **Step 4: Add the endpoint**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`, beside the other `/booking/{manageToken}/…` routes, add `import site.asm0dey.calit.email.EmailService;`, inject it, and add:

```java
    /**
     * The guest's calendar entry for their booking. Keyed by the unguessable manage token, same as
     * every other guest-facing booking route — no session, no owner scoping (the token IS the
     * authorization). Serves the bytes the confirmation mail would have attached, so a booking whose
     * mail failed still ends up in the guest's calendar.
     */
    @GET
    @Path("/booking/{manageToken}/invite.ics")
    @Produces("text/calendar;charset=UTF-8")
    public Response inviteIcs(@PathParam("manageToken") String manageToken) {
        Booking booking = Booking.findByManageToken(manageToken); // unguessable key, not id
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        byte[] ics = emailService.inviteeIcs(booking.id);
        if (ics == null) {
            throw new NotFoundException("No calendar entry for token " + manageToken);
        }
        return Response.ok(ics)
                .header("Content-Disposition", "attachment; filename=\"invite.ics\"")
                .build();
    }
```

Add `jakarta.ws.rs.core.Response` to the imports if it is not already there.

- [ ] **Step 5: Add the link and its message key**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, next to `pub_conf_manage_link`:

```java
    @Message("Add to your calendar (.ics)")
    String pub_conf_ics_download();
```

`src/main/resources/messages/msg_de.properties`:

```properties
pub_conf_ics_download=Zum Kalender hinzufügen (.ics)
```

`src/main/resources/messages/msg_he.properties`:

```properties
pub_conf_ics_download=הוספה ליומן (.ics)
```

In `src/main/resources/templates/PublicResource/confirmation.html`, add the link just above the existing manage link:

```html
      {! #195: unconditional, not failure-only. It's one link either way, and a guest whose mail
         client swallowed the attachment needs it just as much as one whose mail never sent. !}
      <p><a class="btn btn-sm btn-outline" href="/booking/{booking.manageToken}/invite.ics">{msg:pub_conf_ics_download}</a></p>
```

- [ ] **Step 6: Run the test and verify it passes**

```bash
./mvnw test -Dtest=GuestIcsDownloadTest
```

Expected: PASS, 3 tests.

- [ ] **Step 7: Check i18n parity and run the neighbouring suites**

```bash
for f in src/main/resources/messages/msg_de.properties src/main/resources/messages/msg_he.properties; do
  grep -q "^pub_conf_ics_download=" "$f" || echo "MISSING in $f"
done
./mvnw test -Dtest='GuestBookingFlowTest,GuestConfirmationMailCopyTest,IcsBuilderTest,IcsBuilderEscapeTest,EmailServiceGuestTest'
```

Expected: no `MISSING`, all PASS.

- [ ] **Step 8: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/main/resources/templates/PublicResource/confirmation.html \
        src/test/java/site/asm0dey/calit/web/GuestIcsDownloadTest.java \
        src/test/java/site/asm0dey/calit/web/GuestBookingFixture.java
git commit -m "feat(booking): let the guest download the .ics for their booking (#195)"
```

---

### Task 6: Say that SMTP is required

**Files:**
- Modify: `README.md` (new `## Requirements` section, before `## Run it` at line 40)
- Modify (on the `docs-site` branch): `docs-site/src/content/docs/releases/changelog.md`, plus the install and configuration pages

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Add a Requirements section to the README**

Insert before `## Run it`:

```markdown
## Requirements

- **PostgreSQL** — the only supported database. No embedded fallback.
- **An SMTP server** — **required**, not optional. calit's core promise to an invitee is
  "you'll get a confirmation", and every booking confirmation, reminder, approval request,
  cancellation notice, password reset and account invite goes out over SMTP. Without working
  SMTP the app still runs and bookings still succeed, but nobody is ever told about them.
  Configure `MAIL_*` (see [Configuration](#configuration)); a failed send is retried from a
  durable outbox, and `/q/health/ready` reports SMTP reachability under `SMTP` → `data.state`.
  The owner dashboard shows a banner whenever mail is not being delivered.
- **Docker** — only for development (`mvn quarkus:dev` and the test suite use Dev Services to
  provision a throwaway Postgres). Not needed to run a release image.
```

Adjust the `#configuration` anchor to whatever the README's real config heading is — check with `grep -n '^#' README.md`.

- [ ] **Step 2: Verify the README renders**

```bash
grep -n "^## " README.md
```

Expected: `## Requirements` appears between `## Screenshots` and `## Run it`.

- [ ] **Step 3: Update the docs site**

```bash
git fetch origin docs-site
git worktree add /tmp/finkel/calit-docs docs-site
```

In `/tmp/finkel/calit-docs/docs-site/src/content/docs/`:

1. On the **install** page, add the same "SMTP is required" requirement, worded for a self-hoster about to deploy.
2. On the **configuration** page, next to the `MAIL_*` reference, note that leaving it unset produces an instance that looks healthy and silently delivers nothing, and that the dashboard banner and `/q/health/ready` are how to tell.
3. In `releases/changelog.md`, under `## Unreleased` (create the section with the subtitle `Merged but not yet in a tagged release.` if absent):

```markdown
- **Broken email is no longer invisible.** An instance with unconfigured or unreachable SMTP looked completely healthy from the inside: bookings succeeded, the dashboard said nothing, and the guest was told a confirmation was on its way that would never arrive. Now the owner's dashboard carries a banner whenever mail isn't being delivered — naming which problem it is, unconfigured versus unreachable, and counting messages that were given up on after repeated failures. Copying a booking link while mail is down shows a red warning instead of a green "Copied", because that is the moment before the link gets handed to someone. The guest's confirmation page says plainly that the email couldn't be sent, and reassures them the booking itself is fine. And every confirmation page now offers the calendar entry as a direct `.ics` download — previously that file only ever existed as a mail attachment, so a failed send took the guest's calendar entry with it. ([#N](https://github.com/asm0dey/calit/pull/N))
```

Close the section with an upgrade note:

```markdown
**Upgrading:** nothing to do — no configuration or database changes. If the new dashboard banner appears after upgrading, it is not a regression: it is reporting an SMTP problem that was already there. The dead-letter count covers only messages already parked in `email_outbox`; mail that failed before the outbox existed is not retroactively counted.
```

- [ ] **Step 4: Commit and push the docs branch**

```bash
cd /tmp/finkel/calit-docs
git add docs-site/src/content/docs/
git commit -m "docs: SMTP is required; document the mail-delivery warnings (#195)"
git push origin docs-site
cd /home/finkel/work_self/calit
git worktree remove /tmp/finkel/calit-docs
```

- [ ] **Step 5: Commit the README**

```bash
git add README.md
git commit -m "docs(readme): state that SMTP is required (#195)"
```

---

### Task 7: Wrap up — full suite, translation issue, PR

**Files:**
- Modify: `.beans/calit-2oik--*.md`

- [ ] **Step 1: Run the whole suite**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/26.0.1-librca
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. A red suite blocks the PR — no exceptions for "unrelated" failures (see `CLAUDE.md`).

- [ ] **Step 2: Verify formatting passes the CI gate**

```bash
./mvnw spotless:check
```

Expected: PASS. If not, run `./mvnw spotless:apply` and amend.

- [ ] **Step 3: Check every new key has all three locales**

```bash
for k in adm_mail_banner_unconfigured adm_mail_banner_unreachable adm_mail_banner_dead_letters \
         adm_mail_banner_scope_note adm_meetingTypes_toast_copied_no_mail; do
  for f in adm_de adm_he; do
    grep -q "^$k=" "src/main/resources/messages/$f.properties" || echo "MISSING $k in $f"
  done
done
for k in pub_conf_email_failed pub_conf_ics_download; do
  for f in msg_de msg_he; do
    grep -q "^$k=" "src/main/resources/messages/$f.properties" || echo "MISSING $k in $f"
  done
done
```

Expected: no output.

- [ ] **Step 4: Open the Hebrew translation-review issue**

The Hebrew strings in this plan were not written by a native speaker. Per `CLAUDE.md` the key still ships with its English default, but the gap must be tracked, never silent:

```bash
gh issue create \
  --title "Review the Hebrew strings added for the mail-delivery warnings (#195)" \
  --body "PR for #195 adds seven user-facing strings with machine-drafted Hebrew:

adm_mail_banner_unconfigured, adm_mail_banner_unreachable, adm_mail_banner_dead_letters,
adm_mail_banner_scope_note, adm_meetingTypes_toast_copied_no_mail (adm_he.properties);
pub_conf_email_failed, pub_conf_ics_download (msg_he.properties).

They render, and the {count} / {inviteeEmail} placeholders match the other locales, but the
phrasing needs a native reader. The German was written with more confidence but is worth a
skim too."
```

Reference the resulting issue number in the PR body.

- [ ] **Step 5: Draw the change and open the PR**

Per project practice, run the `show-me` skill and put the resulting diagram in the PR body — a PR has readers beyond the person who asked for the work.

```bash
git push -u origin HEAD
gh pr create --title "Make broken SMTP visible instead of silent (#195)" --body "..."
```

The PR body must cover:
- What the four surfaces now do (dashboard banner, copy toast, guest page, `.ics` download).
- **Both deliberate deviations from the issue, with their reasoning** — no polling (the confirmation page renders in the same request as the commit, proven by `GuestConfirmationMailCopyTest`), and the unconditional `.ics` link.
- The address-keyed correlation and its known cost (a guest who books twice during an outage sees the warning on both).
- That `email_outbox` is deployment-wide, so the dead-letter count is not owner-scoped, and the banner says so.
- That the whole suite is green, and the Hebrew-translation issue number.
- The `show-me` diagram.

- [ ] **Step 6: Close out the bean**

```bash
beans update calit-2oik -s completed --body-append "## Summary of Changes

New MailHealth bean (email package, CDI name mailHealth) is the single seam: cached 60s
reachability delegated to SmtpHealthCheck, a dead-letter count over email_outbox, and an
undeliveredFor(address) lookup. It drives four surfaces -- dashboard banner (unconfigured vs
unreachable, plus dead-letter count), red copy-link toast via {cdi:mailHealth.degraded},
honest confirmation-page copy, and an unconditional /booking/{token}/invite.ics download
backed by a new EmailService.inviteeIcs(bookingId).

Two deliberate deviations from the issue, both argued in the PR: no polling (the confirmation
page renders in the same request as the booking commit, since the mail observers are
AFTER_SUCCESS and synchronous -- pinned by GuestConfirmationMailCopyTest), and the .ics link
is unconditional rather than failure-only.

README gained a Requirements section stating SMTP is mandatory; docs-site install +
configuration pages and the Unreleased changelog updated. Hebrew strings flagged for native
review in a follow-up issue."
```
