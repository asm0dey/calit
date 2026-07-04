# Invite Email for Admin-Created Users — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a site admin creates a user, send them an invite email with a 48h activation link instead of setting a temp password; the invitee sets their own password to activate.

**Architecture:** Reuse the existing single-use password-reset token machinery (`PasswordResetService` / `PasswordResetToken` / the `/reset-password` page) as the activation link. The new user is created password-less (dormant — a null hash blocks login). The entered email is stored in the existing `OwnerSettings.ownerEmail` (row pre-created with placeholder name/timezone, mirroring `GoogleSignInService.provision`). A new `EmailService.sendInvite` + `invite.html` template renders the letter. Admins can resend from the user list.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache, Qute `@CheckedTemplate`, RESTEasy Reactive, RestAssured + `@TestSecurity` tests, type-safe i18n `@Message` bundles.

## Global Constraints

- **i18n mandatory:** every new/changed user-facing string ships its English `@Message` default **plus** `de` and `he` values in the matching `src/main/resources/messages/*.properties` file, same change. Keep `{placeholder}` names identical across locales.
- **Formatting:** Java is Spotless/palantir-formatted; run `mvn spotless:apply` (or let the pre-commit hook) before committing. Do not hand-fight the formatter.
- **CSRF:** every POST `<form>` carries `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">` (prod gate; off in `%test`).
- **Qute param names:** `maven.compiler.parameters=true` is on; `@CheckedTemplate` native method param names must match the template `{@type name}` declarations, or the build fails. This is a real compile-time check.
- **No migration:** this feature adds NO schema change. Do not create a `V*.sql`.
- **Tests need Docker** (Dev Services Postgres). Admin user is always id 1; DB truncated+reseeded per test.

---

### Task 1: 48-hour token TTL overload

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/user/PasswordResetService.java`
- Test: `src/test/java/site/asm0dey/calit/user/PasswordResetServiceTest.java` (create if absent)

**Interfaces:**
- Produces: `String PasswordResetService.issue(Long userId, Instant now, Duration ttl)` — mints a token expiring at `now.plus(ttl)`. The existing `issue(userId, now)` keeps its 30-min behaviour by delegating.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/user/PasswordResetServiceTest.java`:

```java
package site.asm0dey.calit.user;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PasswordResetServiceTest {

    @Inject
    PasswordResetService service;

    @Test
    void customTtlTokenValidBeyond30MinButExpiresAtTtl() {
        // Admin user is always id 1 (test infra).
        Instant now = Instant.now();
        String token = service.issue(1L, now, Duration.ofHours(48));

        // Still valid 40 minutes later (would be dead under the 30-min default).
        assertNotNull(service.consume(token, now.plusSeconds(40 * 60)));

        // A fresh token is expired just after its 48h window.
        String token2 = service.issue(1L, now, Duration.ofHours(48));
        assertNull(service.consume(token2, now.plus(Duration.ofHours(48)).plusSeconds(1)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PasswordResetServiceTest`
Expected: compile failure — `issue(Long, Instant, Duration)` not defined.

- [ ] **Step 3: Add the overload**

In `PasswordResetService.java`, keep the existing `issue(Long, Instant)` but have it delegate, and add the TTL overload:

```java
    /** Mint a token for {@code userId}, persist its hash, and return the raw token (emailed once). */
    @Transactional
    public String issue(Long userId, Instant now) {
        return issue(userId, now, TTL);
    }

    /** As {@link #issue(Long, Instant)} but with a caller-chosen lifetime (e.g. longer for invites). */
    @Transactional
    public String issue(Long userId, Instant now, Duration ttl) {
        var raw = new byte[32];
        RNG.nextBytes(raw);
        var token = B64URL.encodeToString(raw);

        PasswordResetToken t = new PasswordResetToken();
        t.userId = userId;
        t.tokenHash = LoginTicketService.sha256Hex(token);
        t.expiresAt = now.plus(ttl);
        t.persist();
        return token;
    }
```

(Delete the old body of the 2-arg method — it now delegates. `Duration` is already imported.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PasswordResetServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/user/PasswordResetService.java src/test/java/site/asm0dey/calit/user/PasswordResetServiceTest.java
git commit -m "feat(invite): add custom-TTL PasswordResetService.issue overload"
```

---

### Task 2: i18n keys (email + admin UI)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`
- Modify: `src/main/resources/messages/msg_de.properties`, `msg_he.properties`
- Modify: `src/main/resources/messages/adm_de.properties`, `adm_he.properties`

**Interfaces:**
- Produces (AppMessages, `msg` namespace): `email_invite_subject()`, `email_invite_title()`, `email_invite_greeting()`, `email_invite_body(String inviter, String host)`, `email_invite_btn()`, `email_invite_expiry()`.
- Produces (AdminMessages, `adm` namespace): `users_label_email()`, `users_error_email_invalid()`, `users_error_not_pending()`, `users_status_pending()`, `users_btn_resend_invite()`.

- [ ] **Step 1: Add AppMessages keys**

In `AppMessages.java`, next to the existing `email_password_reset_*` methods, add:

```java
    @Message("You're invited to calit")
    String email_invite_subject();

    @Message("You've been invited to calit")
    String email_invite_title();

    @Message("Hello,")
    String email_invite_greeting();

    @Message("{inviter} invited you to join calit at {host}. Set your password to activate your account.")
    String email_invite_body(String inviter, String host);

    @Message("Activate your account")
    String email_invite_btn();

    @Message(
            "This link expires in 48 hours and can be used once. If you didn't expect this invitation, you can safely ignore this email.")
    String email_invite_expiry();
```

- [ ] **Step 2: Add AppMessages de/he translations**

Append to `src/main/resources/messages/msg_de.properties`:

```properties
email_invite_subject=Sie wurden zu calit eingeladen
email_invite_title=Sie wurden zu calit eingeladen
email_invite_greeting=Hallo,
email_invite_body={inviter} hat Sie eingeladen, calit unter {host} beizutreten. Legen Sie Ihr Passwort fest, um Ihr Konto zu aktivieren.
email_invite_btn=Konto aktivieren
email_invite_expiry=Dieser Link läuft in 48 Stunden ab und kann einmal verwendet werden. Wenn Sie diese Einladung nicht erwartet haben, können Sie diese E-Mail ignorieren.
```

Append to `src/main/resources/messages/msg_he.properties`:

```properties
email_invite_subject=הוזמנת ל-calit
email_invite_title=הוזמנת ל-calit
email_invite_greeting=שלום,
email_invite_body={inviter} הזמין אותך להצטרף ל-calit בכתובת {host}. הגדר סיסמה כדי להפעיל את חשבונך.
email_invite_btn=הפעל את החשבון
email_invite_expiry=קישור זה יפוג בעוד 48 שעות וניתן להשתמש בו פעם אחת. אם לא ציפית להזמנה זו, ניתן להתעלם מהודעה זו.
```

- [ ] **Step 3: Add AdminMessages keys**

In `AdminMessages.java`, next to the existing `users_*` methods, add:

```java
    @Message("Email")
    String users_label_email();

    @Message("Enter a valid email address.")
    String users_error_email_invalid();

    @Message("That user has already activated their account.")
    String users_error_not_pending();

    @Message("Awaiting activation")
    String users_status_pending();

    @Message("Resend invite")
    String users_btn_resend_invite();
```

`// ponytail:` leave the now-unused `users_label_temp_password()` in place — dropping the form input is enough; removing the key would touch three more files for no behaviour change.

- [ ] **Step 4: Add AdminMessages de/he translations**

Append to `src/main/resources/messages/adm_de.properties`:

```properties
users_label_email=E-Mail
users_error_email_invalid=Geben Sie eine gültige E-Mail-Adresse ein.
users_error_not_pending=Dieser Benutzer hat sein Konto bereits aktiviert.
users_status_pending=Wartet auf Aktivierung
users_btn_resend_invite=Einladung erneut senden
```

Append to `src/main/resources/messages/adm_he.properties`:

```properties
users_label_email=אימייל
users_error_email_invalid=הזן כתובת אימייל תקינה.
users_error_not_pending=משתמש זה כבר הפעיל את חשבונו.
users_status_pending=ממתין להפעלה
users_btn_resend_invite=שלח הזמנה מחדש
```

- [ ] **Step 5: Verify it compiles (keys resolve)**

Run: `mvn -q compile`
Expected: BUILD SUCCESS (bundle interfaces compile; used by later tasks).

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java src/main/java/site/asm0dey/calit/i18n/AdminMessages.java src/main/resources/messages/msg_de.properties src/main/resources/messages/msg_he.properties src/main/resources/messages/adm_de.properties src/main/resources/messages/adm_he.properties
git commit -m "feat(invite): i18n keys for invite email + user-list resend/pending"
```

---

### Task 3: `EmailService.sendInvite` + `invite.html` template

**Files:**
- Create: `src/main/resources/templates/email/invite.html`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`

**Interfaces:**
- Consumes: AppMessages `email_invite_*` keys (Task 2); `mailSender.send(fromName, to, subject, html, ics, expiresAt)` (existing 6-arg overload, as used by `sendPasswordReset`).
- Produces: `void EmailService.sendInvite(String toEmail, String activationUrl, String inviter, String host, Instant expiresAt, Locale locale)`.

- [ ] **Step 1: Create the email template**

Create `src/main/resources/templates/email/invite.html` (mirrors `passwordReset.html`):

```html
{@java.lang.String lang}
{@java.lang.String activationUrl}
{@java.lang.String inviter}
{@java.lang.String host}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_invite_title}</title></head>
<body>
<p>{msg:email_invite_greeting}</p>
<p>{msg:email_invite_body(inviter, host)}</p>
<p><a href="{activationUrl}">{msg:email_invite_btn}</a></p>
<p>{msg:email_paste_link_hint}<br>{activationUrl}</p>
<p>{msg:email_invite_expiry}</p>
</body>
</html>
```

- [ ] **Step 2: Declare the CheckedTemplate method**

In `EmailService.java`, inside `static class Templates`, next to the `passwordReset` declaration, add:

```java
        static native TemplateInstance invite(String lang, String activationUrl, String inviter, String host);
```

- [ ] **Step 3: Add the send method**

In `EmailService.java`, next to `sendPasswordReset`, add:

```java
    /**
     * Sends an account-invite email carrying a set-password activation link (same single-use token
     * machinery as a password reset). {@code inviter} is the admin's display email, {@code host} the
     * app base URL, {@code expiresAt} the token expiry (retries stop there so no dead link is sent).
     * {@code locale} drives the {msg:} keys in the body.
     */
    public void sendInvite(
            String toEmail, String activationUrl, String inviter, String host, Instant expiresAt, Locale locale) {
        String body = Templates.invite(locale.getLanguage(), activationUrl, inviter, host)
                .setLocale(locale)
                .render();
        mailSender.send(null, toEmail, messages.forLocale(locale).email_invite_subject(), body, null, expiresAt);
    }
```

- [ ] **Step 4: Verify template params + build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS. (Qute validates `invite.html`'s `{@...}` params against the native `invite(...)` signature and every `{msg:...}` key at build time — a mismatch fails here.)

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/resources/templates/email/invite.html src/main/java/site/asm0dey/calit/email/EmailService.java
git commit -m "feat(invite): EmailService.sendInvite + invite.html template"
```

---

### Task 4: Create-user sends an invite (form + resource)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/UsersResource.java`
- Modify: `src/main/resources/templates/UsersResource/users.html:16-20`
- Test: `src/test/java/site/asm0dey/calit/web/UsersResourceTest.java`

**Interfaces:**
- Consumes: `PasswordResetService.issue(Long, Instant, Duration)` (Task 1); `EmailService.sendInvite(...)` (Task 3); AdminMessages `users_error_email_invalid` (Task 2); `OwnerSettings`, `AppUser.create`, `Usernames.validateNew`.
- Produces: `POST /me/users` now takes form params `username` + `email` (no `tempPassword`); private helper `String inviterEmail()`.

- [ ] **Step 1: Update the failing test**

Replace the `createUserPersistsTempUser` test in `UsersResourceTest.java` with an invite-flow test, and add email-validation + inviter tests. Add imports at the top:

```java
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.PasswordResetToken;
```

Replace the body of `createUserPersistsTempUser` (keep the `@TestSecurity` admin annotation) with:

```java
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void createUserSendsInviteAndStoresEmail() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "bob")
                .formParam("email", "bob@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200)
                .body(containsString("bob"));

        AppUser bob = reload(AppUser.findByUsername("bob").id);
        assertNull(bob.passwordHash, "invited user starts password-less (dormant)");
        assertFalse(bob.mustChangePassword);
        assertFalse(bob.settingsComplete);
        assertTrue(bob.enabled);
        assertFalse(bob.isAdmin);

        OwnerSettings s = OwnerSettings.forOwner(bob.id);
        assertNotNull(s, "settings row pre-created so the wizard can pre-fill the email");
        assertEquals("bob@example.com", s.ownerEmail);
        assertEquals(1, PasswordResetToken.count("userId", bob.id), "exactly one activation token minted");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void createUserRejectsInvalidEmail() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "carol")
                .formParam("email", "   ")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        assertNull(AppUser.findByUsername("carol"), "no user created on invalid email");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=UsersResourceTest`
Expected: FAIL — `create` still expects `tempPassword`; `bob.passwordHash` non-null / user created.

- [ ] **Step 3: Add fields + rewrite `create`**

In `UsersResource.java`, add imports and injected collaborators. Add these imports:

```java
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.user.PasswordResetService;
```

Add injected fields (near the other `@Inject`s):

```java
    @Inject
    PasswordResetService resetService;

    @Inject
    EmailService emailService;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;
```

Replace the whole `create(...)` method with:

```java
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance create(@RestForm String username, @RestForm String email) {
        var m = adminMsgs.forLocale(activeLocale.current());
        String normalized;
        try {
            normalized = Usernames.validateNew(username, AppUser::usernameTaken); // throws on invalid/reserved/taken
        } catch (IllegalArgumentException e) {
            return render(e.getMessage());
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            return render(m.users_error_email_invalid());
        }
        String inviteEmail = email.trim();
        var now = Instant.now();
        // One tx: create the dormant user + its settings row + mint the activation token together.
        String token = QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create(normalized, null, false); // null hash => cannot log in until activated
            u.mustChangePassword = false;
            u.settingsComplete = false;
            u.persist();
            // Pre-create the settings row (mirrors GoogleSignInService.provision): ownerName/timezone
            // are NOT NULL, so seed placeholders the first-login wizard overwrites; ownerEmail holds
            // the invite address so resend + the wizard's pre-fill both find it.
            OwnerSettings s = new OwnerSettings();
            s.ownerId = u.id;
            s.ownerName = "";
            s.ownerEmail = inviteEmail;
            s.timezone = "UTC";
            s.persist();
            audit.event(identity.getPrincipal().getName(), "invite-user", USER_TARGET + normalized, null);
            return resetService.issue(u.id, now, Duration.ofHours(48));
        });
        emailService.sendInvite(
                inviteEmail,
                baseUrl + "/reset-password?token=" + token,
                inviterEmail(),
                baseUrl,
                now.plus(Duration.ofHours(48)),
                activeLocale.current());
        return render(null);
    }

    /** The inviting admin's display email (their settings address), falling back to their username. */
    private String inviterEmail() {
        String adminName = identity.getPrincipal().getName();
        AppUser me = AppUser.findByUsername(adminName);
        if (me != null) {
            OwnerSettings s = OwnerSettings.forOwner(me.id);
            if (s != null && s.ownerEmail != null && !s.ownerEmail.isBlank()) {
                return s.ownerEmail;
            }
        }
        return adminName;
    }
```

- [ ] **Step 4: Update the create form**

In `src/main/resources/templates/UsersResource/users.html`, replace the temp-password label + input (lines 18-19) with an email field:

```html
    <label class="label" for="usr-email">{adm:users_label_email}</label>
    <input id="usr-email" class="input w-full" type="email" name="email" required>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=UsersResourceTest`
Expected: PASS (all methods, including the unchanged `listShowsExistingUsers` / `nonAdminIsForbidden`).

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/web/UsersResource.java src/main/resources/templates/UsersResource/users.html src/test/java/site/asm0dey/calit/web/UsersResourceTest.java
git commit -m "feat(invite): admin create-user emails a 48h activation link"
```

---

### Task 5: Resend invite + pending status in the user list

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/UsersResource.java`
- Modify: `src/main/resources/templates/UsersResource/users.html:30-42`
- Test: `src/test/java/site/asm0dey/calit/web/UsersResourceTest.java`

**Interfaces:**
- Consumes: `requireUser(Long)` (existing helper), `inviterEmail()` (Task 4), `EmailService.sendInvite` (Task 3), AdminMessages `users_error_not_pending`, `users_status_pending`, `users_btn_resend_invite` (Task 2).
- Produces: `POST /me/users/{id}/resend-invite`. Pending predicate in the template: `u.passwordHash == null && u.googleSub == null`.

- [ ] **Step 1: Write the failing test**

Add to `UsersResourceTest.java`:

```java
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void resendInviteMintsAnotherTokenForPendingUser() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "dave")
                .formParam("email", "dave@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        Long id = AppUser.findByUsername("dave").id;
        assertEquals(1, PasswordResetToken.count("userId", id));

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/users/" + id + "/resend-invite")
                .then()
                .statusCode(200);
        assertEquals(2, PasswordResetToken.count("userId", id), "resend mints a second token");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void resendInviteRejectedForActiveUser() {
        // Admin (id 1) already has a password → not pending.
        Long adminId = AppUser.findByUsername("admin").id;
        long before = PasswordResetToken.count("userId", adminId);
        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/users/" + adminId + "/resend-invite")
                .then()
                .statusCode(200);
        assertEquals(before, PasswordResetToken.count("userId", adminId), "no token minted for an active user");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=UsersResourceTest#resendInviteMintsAnotherTokenForPendingUser+resendInviteRejectedForActiveUser`
Expected: FAIL — `/resend-invite` returns 404 (no such route).

- [ ] **Step 3: Add the resend endpoint**

In `UsersResource.java`, add:

```java
    @POST
    @Path("{id}/resend-invite")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resendInvite(@PathParam("id") Long id) {
        var m = adminMsgs.forLocale(activeLocale.current());
        AppUser u = requireUser(id);
        OwnerSettings s = OwnerSettings.forOwner(u.id);
        boolean pending = u.passwordHash == null && u.googleSub == null;
        if (!pending || s == null || s.ownerEmail == null || s.ownerEmail.isBlank()) {
            return render(m.users_error_not_pending());
        }
        var now = Instant.now();
        String token = resetService.issue(u.id, now, Duration.ofHours(48));
        audit.event(identity.getPrincipal().getName(), "resend-invite", USER_TARGET + u.username, null);
        emailService.sendInvite(
                s.ownerEmail,
                baseUrl + "/reset-password?token=" + token,
                inviterEmail(),
                baseUrl,
                now.plus(Duration.ofHours(48)),
                activeLocale.current());
        return render(null);
    }
```

Add `jakarta.ws.rs.*` already imports `@Path`/`@PathParam` (wildcard import present). No new import needed.

- [ ] **Step 4: Show pending status + resend button in the list**

In `users.html`, replace the status cell (line 30) with a 3-way status:

```html
        <td>{#if u.passwordHash == null && u.googleSub == null}{adm:users_status_pending}{#else if u.enabled}{adm:users_active}{#else}{adm:users_locked}{/if}</td>
```

Then, inside the actions `<td>` (after the lock/unlock block, before the closing `</td>` at line 42), add the resend button for pending users:

```html
          {#if u.passwordHash == null && u.googleSub == null}
          <form method="post" action="/me/users/{u.id}/resend-invite"><input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}"><button class="btn btn-xs">{adm:users_btn_resend_invite}</button></form>
          {/if}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=UsersResourceTest`
Expected: PASS (all methods).

- [ ] **Step 6: Full suite sanity + commit**

Run: `mvn test`
Expected: BUILD SUCCESS (no regressions).

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/web/UsersResource.java src/main/resources/templates/UsersResource/users.html src/test/java/site/asm0dey/calit/web/UsersResourceTest.java
git commit -m "feat(invite): resend-invite action + awaiting-activation status"
```

---

### Task 6: Documentation (docs-site branch)

**Files:**
- Modify (on `docs-site` branch): the admin / user-management doc page.

Docs live on a separate `docs-site` branch (Astro Starlight). This is part of "done", not follow-up.

- [ ] **Step 1: Switch to docs and update**

On the `docs-site` branch, in the user-management page, document: creating a user now sends an **invite email** with a set-password activation link (no admin-set password); the link expires in **48 hours**; admins can **Resend invite** from the user list; pending users show "Awaiting activation". Note it uses existing `APP_BASE_URL` + `MAIL_*` config — no new env var.

- [ ] **Step 2: Commit on docs-site**

```bash
git add <edited doc page>
git commit -m "docs: invite-email flow for admin-created users"
```

(If working in a worktree off `feat/...`, handle the docs-site commit separately per repo convention — do not merge docs-site into the feature branch.)

---

## Self-Review

**Spec coverage:**
- Activation-link/no-password model → Task 4 (password-less create) + reuse of `/reset-password`.
- Email stored in `ownerEmail` → Task 4 (settings row pre-create).
- Reuse reset token machinery → Tasks 1, 3, 4.
- 48h TTL → Task 1 overload, used in Tasks 4 & 5.
- Resend button + pending status → Task 5.
- `sendInvite` + `invite.html` → Task 3.
- i18n de+he for every string → Task 2.
- Docs → Task 6.
- Edge cases (blank/invalid email, resend-on-active guard, CSRF) → Tasks 4 & 5 tests + form tokens.

**Placeholder scan:** none — every step has concrete code/commands.

**Type consistency:** `issue(Long, Instant, Duration)` (Task 1) is the exact signature called in Tasks 4/5. `sendInvite(String, String, String, String, Instant, Locale)` (Task 3) matches both call sites. Template `invite(lang, activationUrl, inviter, host)` params match `invite.html` `{@...}` declarations. Pending predicate `u.passwordHash == null && u.googleSub == null` is identical in resource guard (Task 5 Step 3) and template (Task 5 Step 4).
