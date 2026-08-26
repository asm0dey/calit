# Social Preview Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every shareable calit page a rich link preview with a rendered per-meeting-type card, and stop the capability URLs from unfurling at all.

**Architecture:** `base.html` gains an optional `og` parameter; absent means `noindex,nofollow` and no tags, so the token pages are protected by default rather than by a flag. A small AWT renderer draws a 1200×630 PNG per request (~2 ms) behind `ETag` + `Cache-Control`, with everything essential inside the central square so any client crop still shows the whole card.

**Tech Stack:** Quarkus 3.38 / Java 25, Qute `@CheckedTemplate`, JAX-RS, `java.awt` via the `quarkus-awt` extension, static TTF instances generated with `fonttools`, RestAssured + JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-26-social-preview-images-design.md` — read it before Task 1. Tracked as `calit-o89d`; spike evidence on `calit-xu12`; container hardening is out of scope in `calit-gabg`.

## Global Constraints

- **Branch + PR.** Work on `feat/social-preview-images`. Never push `main`.
- **Beans, not TODO lists.** `calit-o89d` is the bean; tick its todo items as they complete, and commit `.beans/` changes with the code.
- **`mvn test` must be fully green before the PR** — whole suite, 0 failures, 0 errors. Docker must be running.
- **Build JDK:** `export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` before any `mvn`/`./mvnw`, or you get "release 25 not supported".
- **Formatting** is enforced at `verify`: palantir-java-format via Spotless. The pre-commit hook runs `spotless:apply` on staged Java automatically.
- **Owner scoping:** every tenant query filters by owner. The card endpoints are public and read only public fields of a public meeting type — no `CurrentOwner` involvement.
- **i18n exception:** og/twitter strings and card text are **English literals**, deliberately, because `og:locale` is always `en_US` (unfurl bots send no cookie and no `Accept-Language`). Do not add message-bundle keys for them. Every *other* user-facing string still needs `de` + `he` translations.
- **Card copy is fixed English:** `Book a meeting`, `Pick a meeting type and book a time.`, `calit`.
- **Image geometry:** 1200×630. Safe square is x∈[285, 915] — nothing essential outside it.
- **Never edit an applied Flyway migration.** This feature adds none.

---

### Task 1: Suppress previews on capability URLs, and add the tags everywhere else

The leak is live today, so it lands first. `og:image` URLs point at endpoints that arrive in Task 4; nothing ships mid-branch, so the intermediate state is fine.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/OgCard.java`
- Create: `src/main/java/site/asm0dey/calit/web/OgCards.java`
- Modify: `src/main/resources/templates/base.html:1-24`
- Modify: `src/main/resources/templates/PublicResource/{index,landing,book}.html` (header + include line)
- Modify: `src/main/resources/templates/LegalResource/{privacy,terms}.html`, `LoginResource/login.html`, `SignupResource/signup.html`
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (Templates signatures + `index`, `userLanding`, `book`)
- Modify: `src/main/java/site/asm0dey/calit/web/{LegalResource,LoginResource,SignupResource}.java`
- Test: `src/test/java/site/asm0dey/calit/web/OgTagsTest.java`

**Interfaces:**
- Consumes: `SiteInfo.getBaseUrl()` (existing, `@Named("site")`), `MeetingTypeDuration.allowedDurations(MeetingType)`.
- Produces: `OgCard(String title, String description, String imageUrl, String pageUrl)`;
  `OgCards.product(String pagePath)`, `OgCards.owner(String username, String ownerName)`,
  `OgCards.meetingType(String username, MeetingType type, String ownerName)` — all return `OgCard`.

- [ ] **Step 1: Write the failing test**

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;

@QuarkusTest
class OgTagsTest {

    /** Admin is always id 1 / username "admin" (DatabaseResetCallback invariant). */
    private static void seedType(String slug, boolean secret) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coffee chat";
            t.slug = slug;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.secret = secret;
            t.persist();
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = java.time.LocalDate.now().getDayOfWeek();
            r.meetingTypeId = null;
            r.startTime = LocalTime.parse("00:00");
            r.endTime = LocalTime.parse("23:59");
            r.persist();
        });
    }

    @Test
    void bookingPageCarriesAbsoluteOgTags() {
        seedType("og-public", false);
        given().when()
                .get("/admin/og-public")
                .then()
                .statusCode(200)
                .body(containsString("property=\"og:title\" content=\"Coffee chat · Ada Lovelace\""))
                .body(containsString("property=\"og:image\" content=\"http://localhost:8081/og/admin/og-public.png\""))
                .body(containsString("property=\"og:url\" content=\"http://localhost:8081/admin/og-public\""))
                .body(containsString("name=\"twitter:card\" content=\"summary_large_image\""))
                .body(containsString("content=\"en_US\""))
                .body(not(containsString("noindex")));
    }

    @Test
    void secretTypeGetsTheGenericCard() {
        seedType("og-secret", true);
        given().when()
                .get("/admin/og-secret")
                .then()
                .statusCode(200)
                .body(not(containsString("Coffee chat · Ada Lovelace")))
                .body(containsString("property=\"og:title\" content=\"calit\""))
                .body(containsString("property=\"og:image\" content=\"http://localhost:8081/og.png\""));
    }

    @Test
    void landingAndProductPagesOptIn() {
        seedType("og-landing", false);
        given().when()
                .get("/admin")
                .then()
                .statusCode(200)
                .body(containsString("property=\"og:title\" content=\"Ada Lovelace · calit\""))
                .body(containsString("property=\"og:image\" content=\"http://localhost:8081/og/admin.png\""));
        given().when().get("/privacy").then().statusCode(200).body(containsString("property=\"og:title\""));
        given().when().get("/login").then().statusCode(200).body(containsString("property=\"og:title\""));
    }

    @Test
    void capabilityUrlsAreNoindexAndCarryNoOgTags() {
        // A bogus token renders the "not found" branch through the same base template.
        given().when()
                .get("/booking/deadbeefdeadbeefdeadbeef/manage")
                .then()
                .body(containsString("name=\"robots\" content=\"noindex,nofollow\""))
                .body(not(containsString("og:title")))
                .body(not(containsString("og:image")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=OgTagsTest
```

Expected: FAIL — no `og:` tags in the HTML, no `robots` meta.

- [ ] **Step 3: Create the OgCard record**

`src/main/java/site/asm0dey/calit/web/OgCard.java`:

```java
package site.asm0dey.calit.web;

/**
 * Link-preview metadata for one page. Passed to {@code base.html} as {@code og}; when it is absent
 * the template emits {@code noindex,nofollow} and no tags at all, which is what keeps the
 * capability URLs ({@code /booking/{manageToken}/*}, {@code /guest/{declineToken}/*}) from
 * unfurling: they simply never pass one.
 *
 * <p>All copy is English on purpose — {@code og:locale} is always {@code en_US} because unfurl bots
 * send no {@code calit_lang} cookie and usually no {@code Accept-Language}.</p>
 */
public record OgCard(String title, String description, String imageUrl, String pageUrl) {}
```

- [ ] **Step 4: Create the OgCards factory**

`src/main/java/site/asm0dey/calit/web/OgCards.java`:

```java
package site.asm0dey.calit.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;

/** Builds the per-page {@link OgCard}s. Absolute URLs come from {@code app.base-url}. */
@ApplicationScoped
public class OgCards {

    static final String PRODUCT_TITLE = "calit";

    static final String PRODUCT_DESCRIPTION = "Self-hosted scheduling. Pick a time that works for you.";

    final SiteInfo site;

    @Inject
    public OgCards(SiteInfo site) {
        this.site = site;
    }

    /** Product-level card. {@code pagePath} is an absolute path such as {@code "/privacy"}. */
    public OgCard product(String pagePath) {
        return new OgCard(PRODUCT_TITLE, PRODUCT_DESCRIPTION, url("/og.png"), url(pagePath));
    }

    public OgCard owner(String username, String ownerName) {
        String name = ownerName == null || ownerName.isBlank() ? username : ownerName;
        return new OgCard(
                name + " · calit",
                "Pick a meeting type and book a time.",
                url("/og/" + username + ".png"),
                url("/" + username));
    }

    /**
     * Meeting-type card. A secret type falls back to {@link #product} — it is hidden from the
     * owner's public list, so naming it in an unfurl would defeat the flag.
     */
    public OgCard meetingType(String username, MeetingType type, String ownerName) {
        if (type.secret) {
            return product("/" + username + "/" + type.slug);
        }
        String name = ownerName == null || ownerName.isBlank() ? username : ownerName;
        return new OgCard(
                type.name + " · " + name,
                "Book a " + durations(type) + " meeting with " + name + ".",
                url("/og/" + username + "/" + type.slug + ".png"),
                url("/" + username + "/" + type.slug));
    }

    /** "30 min", or "15, 30 or 60 min" — never claims a single length for a multi-duration type. */
    static String durations(MeetingType type) {
        List<Integer> all = MeetingTypeDuration.allowedDurations(type);
        if (all.size() == 1) {
            return all.getFirst() + " min";
        }
        String head = all.subList(0, all.size() - 1).stream().map(String::valueOf).reduce((a, b) -> a + ", " + b)
                .orElse("");
        return head + " or " + all.getLast() + " min";
    }

    String url(String path) {
        String base = site.getBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }
}
```

- [ ] **Step 5: Teach base.html the og parameter**

In `src/main/resources/templates/base.html`, replace the `{#if inject:site.googleVerification}` block's neighbourhood — insert immediately **before** `{#insert head}{/insert}`:

```html
  {#if og??}
  <meta property="og:site_name" content="calit">
  <meta property="og:type" content="website">
  <meta property="og:locale" content="en_US">
  <meta property="og:title" content="{og.title}">
  <meta property="og:description" content="{og.description}">
  <meta property="og:image" content="{og.imageUrl}">
  <meta property="og:image:width" content="1200">
  <meta property="og:image:height" content="630">
  <meta property="og:url" content="{og.pageUrl}">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="{og.title}">
  <meta name="twitter:description" content="{og.description}">
  <meta name="twitter:image" content="{og.imageUrl}">
  {#else}
  {! No og for capability URLs (/booking/{manageToken}/*, /guest/{declineToken}/*): an unfurl would
     paint the invitee's name, meeting and time into whatever chat the token was pasted into, and
     some clients prefetch previews server-side. Absent `og` is the safe default on purpose. !}
  <meta name="robots" content="noindex,nofollow">
  {/if}
```

`og??` is the same safe-access pattern the file already uses for `forceTheme??` and `ownFooter??`, so templates that pass nothing render the `{#else}` branch instead of failing under strict rendering.

- [ ] **Step 6: Opt the seven public templates in**

For each of `PublicResource/index.html`, `PublicResource/landing.html`, `PublicResource/book.html`,
`LegalResource/privacy.html`, `LegalResource/terms.html`, `LoginResource/login.html`,
`SignupResource/signup.html`:

1. Add to the parameter declarations at the top of the file:

```
{@site.asm0dey.calit.web.OgCard og}
```

2. Add `og=og` to the include, keeping any existing extras. Examples:

```
{#include base title=title og=og}
{#include base title=title og=og bodyClass="book-page"}
{#include base title=title og=og bodyClass="lp-body" forceTheme="calit-light" ownFooter=true}
```

- [ ] **Step 7: Add the parameter to the CheckedTemplate signatures and pass it**

In `PublicResource.Templates`, add `OgCard og` as the **last** parameter of `index`, `landing` and
`book`:

```java
public static native TemplateInstance index(String title, boolean authenticated, String username, OgCard og);

public static native TemplateInstance landing(
        String title, List<LandingType> types, String user, String ownerName, OgCard og);

public static native TemplateInstance book(
        String title,
        String user,
        MeetingType type,
        List<DaySlots> days,
        List<BookingField> fields,
        DurationChoice duration,
        String error,
        Chrome chrome,
        Captcha captcha,
        boolean googleConnected,
        String ownerName,
        String initialGuests,
        OgCard og);
```

Add the collaborator to `PublicResource` — a new field beside the existing ones and a new
constructor parameter:

```java
    final OgCards ogCards;
```

```java
    @Inject
    public PublicResource(
            BookingService bookingService,
            MeetingHosts meetingHosts,
            CurrentOwner currentOwner,
            ActiveLocale activeLocale,
            AppMessageResolver messages,
            CalendarPort calendarPort,
            SecurityIdentity identity,
            CaptchaProviderConfig captchaProviderConfig,
            OgCards ogCards) {
        this.bookingService = bookingService;
        this.meetingHosts = meetingHosts;
        this.currentOwner = currentOwner;
        this.activeLocale = activeLocale;
        this.messages = messages;
        this.calendarPort = calendarPort;
        this.identity = identity;
        this.captchaProviderConfig = captchaProviderConfig;
        this.ogCards = ogCards;
    }
```

Then pass a card at each call site:

- `index()` → `Templates.index(m.pub_index_title(), authenticated, username, ogCards.product("/"))`
- `userLanding()` → `Templates.landing(m.pub_user_title(), types, owner.username, settings.ownerName, ogCards.owner(owner.username, settings.ownerName))`
- `book()` → add `ogCards.meetingType(user, type, settings.ownerName)` as the final argument of the
  `Templates.book(...)` call.

Leave `notReady`, `unavailable`, `hostPending`, `manage`, `confirmation`, `cancelConfirm`,
`cancelled`, `guestDeclineConfirm` and `guestDeclined` untouched — they must keep getting the
`noindex` branch.

- [ ] **Step 8: Opt in the three small resources**

`LegalResource` has no constructor yet; add one:

```java
    final OgCards ogCards;

    @Inject
    public LegalResource(OgCards ogCards) {
        this.ogCards = ogCards;
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy() {
        return Templates.privacy("Privacy Policy", ogCards.product("/privacy"));
    }

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms() {
        return Templates.terms("Terms of Service", ogCards.product("/terms"));
    }
```

and widen its template signatures:

```java
        public static native TemplateInstance privacy(String title, OgCard og);

        public static native TemplateInstance terms(String title, OgCard og);
```

`LoginResource`: add `OgCards ogCards` to the `@Inject` constructor with a matching
`final OgCards ogCards;` field, change the signature to

```java
        public static native TemplateInstance login(
                String title, boolean error, boolean googleEnabled, boolean oidcEnabled, String notice, OgCard og);
```

and pass `ogCards.product("/login")` at every `Templates.login(...)` call site in that file.

`SignupResource`: same shape —

```java
        public static native TemplateInstance signup(String title, String error, OgCard og);
```

with `ogCards.product("/signup")` at every call site, plus the field and constructor parameter.

- [ ] **Step 9: Run the test to verify it passes**

```bash
./mvnw test -Dtest=OgTagsTest
```

Expected: PASS, 4 tests. If `og:url` mismatches, check `app.base-url` in
`src/test/resources/application.properties` and align the expected host in the test with it rather
than hardcoding a different one.

- [ ] **Step 10: Run the full suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS. Every template whose `@CheckedTemplate` signature changed is compile-checked
by Qute, so a missed call site fails the build rather than a test.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web src/main/resources/templates src/test/java/site/asm0dey/calit/web/OgTagsTest.java
git commit -m "feat(web): add link-preview tags, and none at all on capability URLs

base.html emits og:/twitter: only when a page passes an OgCard; absent means
noindex,nofollow. The token pages pass nothing, so they cannot unfurl an
invitee's name, meeting and time into a chat — by default rather than by flag.

Secret meeting types get the product card: naming a hidden meeting in a preview
would defeat the flag."
```

---

### Task 2: Ship the fonts and the fallback chain

**Files:**
- Create: `src/main/resources/fonts/*.ttf` (7 faces) + `src/main/resources/fonts/README.md` + OFL files
- Create: `src/main/java/site/asm0dey/calit/web/og/CardFonts.java`
- Create: `src/main/java/site/asm0dey/calit/web/og/TextRuns.java`
- Modify: `pom.xml` (add `quarkus-awt`)
- Modify: `src/main/resources/application.properties:47` (`quarkus.native.resources.includes`)
- Test: `src/test/java/site/asm0dey/calit/web/og/TextRunsTest.java`

**Interfaces:**
- Produces: `CardFonts.regular()`, `CardFonts.semibold()`, `CardFonts.wordmark()`, `CardFonts.chip()` → `java.awt.Font`;
  `CardFonts.chain(boolean semibold)` → `List<Font>`;
  `TextRuns.split(String text, List<Font> chain, float size)` → `List<TextRuns.Run>` where `Run` is `record Run(String text, Font font)`;
  `TextRuns.width(Graphics2D g, List<Run> runs)` → `int`;
  `TextRuns.drawCentered(Graphics2D g, List<Run> runs, int centerX, int baseline)`;
  `TextRuns.covered(String text, List<Font> chain)` → `boolean`.

- [ ] **Step 1: Generate the font assets**

`fonttools` is needed once (`pip install fonttools` or `uv tool install fonttools`). AWT ignores
variable-font axes — it loads the default instance and gives no way to ask for another weight — so
static instances must be baked now rather than derived at runtime.

```bash
cd /tmp && mkdir -p calit-fonts && cd calit-fonts
curl -sSL -o Rubik.ttf          "https://raw.githubusercontent.com/google/fonts/main/ofl/rubik/Rubik%5Bwght%5D.ttf"
curl -sSL -o NotoSans.ttf       "https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"
curl -sSL -o NotoSansHebrew.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanshebrew/NotoSansHebrew%5Bwdth%2Cwght%5D.ttf"
curl -sSL -o Hanken.ttf         "https://raw.githubusercontent.com/google/fonts/main/ofl/hankengrotesk/HankenGrotesk%5Bwght%5D.ttf"
curl -sSL -o Fraunces.ttf       "https://raw.githubusercontent.com/google/fonts/main/ofl/fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf"

python3 -m fontTools.varLib.instancer Rubik.ttf          wght=400              -o Rubik-Regular.ttf
python3 -m fontTools.varLib.instancer Rubik.ttf          wght=600              -o Rubik-SemiBold.ttf
python3 -m fontTools.varLib.instancer NotoSans.ttf       wght=400 wdth=100     -o NotoSans-Regular.ttf
python3 -m fontTools.varLib.instancer NotoSans.ttf       wght=600 wdth=100     -o NotoSans-SemiBold.ttf
python3 -m fontTools.varLib.instancer NotoSansHebrew.ttf wght=400 wdth=100     -o NotoSansHebrew-Regular.ttf
python3 -m fontTools.varLib.instancer Hanken.ttf         wght=700              -o HankenGrotesk-Bold.ttf
python3 -m fontTools.varLib.instancer Fraunces.ttf       wght=600 opsz=14 SOFT=0 WONK=0 -o Fraunces-Chip.ttf

mkdir -p ~/work_self/calit/src/main/resources/fonts
cp Rubik-Regular.ttf Rubik-SemiBold.ttf NotoSans-Regular.ttf NotoSans-SemiBold.ttf \
   NotoSansHebrew-Regular.ttf HankenGrotesk-Bold.ttf Fraunces-Chip.ttf \
   ~/work_self/calit/src/main/resources/fonts/
```

Fetch each family's `OFL.txt` from the same directory in `google/fonts` and save it as
`src/main/resources/fonts/OFL-<family>.txt`. Write `src/main/resources/fonts/README.md` containing
the exact commands above, so the assets are reproducible rather than mystery binaries.

Sanity-check the instancing worked (Fraunces at `opsz=144` is a display cut and looks wrong at chip
size; `opsz=14` matches the ~17 px the site renders it at).

- [ ] **Step 2: Add quarkus-awt and register the fonts for native**

In `pom.xml`, after the `quarkus-reactive-routes` dependency:

```xml
    <!-- java.awt in the native image: renders the social-preview card. Needs runtime .so files and
         a font on disk in the container — see Dockerfile.native. -->
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-awt</artifactId></dependency>
```

In `src/main/resources/application.properties`, extend the existing line:

```properties
quarkus.native.resources.includes=git.properties,fonts/*.ttf
```

- [ ] **Step 3: Write the failing test**

`src/test/java/site/asm0dey/calit/web/og/TextRunsTest.java`:

```java
package site.asm0dey.calit.web.og;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextRunsTest {

    static final CardFonts FONTS = new CardFonts();

    @Test
    void latinIsOneRunInThePrimaryFont() {
        List<TextRuns.Run> runs = TextRuns.split("Coffee chat", FONTS.chain(false), 40f);
        assertEquals(1, runs.size());
        assertEquals("Coffee chat", runs.getFirst().text());
        assertTrue(runs.getFirst().font().getFontName().startsWith("Rubik"));
    }

    @Test
    void cyrillicAndHebrewStayInThePrimaryFont() {
        assertEquals(1, TextRuns.split("Знакомство", FONTS.chain(false), 40f).size());
        assertEquals(1, TextRuns.split("פגישה", FONTS.chain(false), 40f).size());
    }

    @Test
    void greekFallsBackToNoto() {
        List<TextRuns.Run> runs = TextRuns.split("Συνάντηση", FONTS.chain(false), 40f);
        assertEquals(1, runs.size());
        assertTrue(runs.getFirst().font().getFontName().contains("Noto"));
    }

    @Test
    void mixedScriptsSplitIntoSeparateRuns() {
        List<TextRuns.Run> runs = TextRuns.split("Coffee Ω", FONTS.chain(false), 40f);
        assertTrue(runs.size() >= 2, "expected a Latin run and a Greek run, got " + runs.size());
    }

    @Test
    void coverageReportsWhatNoShippedFontCanDraw() {
        assertTrue(TextRuns.covered("Coffee chat", FONTS.chain(false)));
        assertTrue(TextRuns.covered("פגישת היכרות", FONTS.chain(false)));
        assertFalse(TextRuns.covered("コーヒーチャット", FONTS.chain(false)));
        assertFalse(TextRuns.covered("Coffee ☕ chat", FONTS.chain(false)));
    }

    @Test
    void semiboldChainUsesSemiboldFallback() {
        Font f = TextRuns.split("Συνάντηση", FONTS.chain(true), 40f).getFirst().font();
        assertTrue(f.getFontName().contains("Noto"));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
./mvnw test -Dtest=TextRunsTest
```

Expected: FAIL — `CardFonts` and `TextRuns` do not exist (compilation error).

- [ ] **Step 5: Implement CardFonts**

`src/main/java/site/asm0dey/calit/web/og/CardFonts.java`:

```java
package site.asm0dey.calit.web.og;

import jakarta.enterprise.context.ApplicationScoped;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The card's typefaces, loaded once off the classpath.
 *
 * <p>All faces are STATIC instances baked with {@code fonttools varLib.instancer} (see
 * {@code src/main/resources/fonts/README.md}): AWT ignores a variable font's {@code fvar} axes and
 * silently loads the default instance, so a weight cannot be chosen at runtime.</p>
 *
 * <p>Rubik carries user-supplied text because it covers Latin, Cyrillic, Hebrew and Arabic in one
 * file — the site's own Hanken Grotesk is Latin-only. Hanken is still the wordmark and Fraunces the
 * chip, matching {@code .lp-brand} on the landing page; both strings are always the literal
 * "calit".</p>
 */
@ApplicationScoped
public class CardFonts {

    final Font rubikRegular = load("Rubik-Regular.ttf");

    final Font rubikSemiBold = load("Rubik-SemiBold.ttf");

    final Font notoRegular = load("NotoSans-Regular.ttf");

    final Font notoSemiBold = load("NotoSans-SemiBold.ttf");

    final Font notoHebrew = load("NotoSansHebrew-Regular.ttf");

    final Font hankenBold = load("HankenGrotesk-Bold.ttf");

    final Font frauncesChip = load("Fraunces-Chip.ttf");

    public Font regular() {
        return rubikRegular;
    }

    public Font semibold() {
        return rubikSemiBold;
    }

    /** Hanken Grotesk Bold — the site sets the "calit" wordmark in the body sans at weight 700. */
    public Font wordmark() {
        return hankenBold;
    }

    /** Fraunces — the site sets the chip's "c" in it. */
    public Font chip() {
        return frauncesChip;
    }

    /** Ordered fallback chain: AWT does no automatic fallback for createFont-loaded fonts. */
    public List<Font> chain(boolean semibold) {
        return semibold
                ? List.of(rubikSemiBold, notoSemiBold, notoHebrew)
                : List.of(rubikRegular, notoRegular, notoHebrew);
    }

    static Font load(String name) {
        try (InputStream in = CardFonts.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("font resource missing: /fonts/" + name);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (FontFormatException e) {
            throw new IllegalStateException("unreadable font: " + name, e);
        }
    }
}
```

- [ ] **Step 6: Implement TextRuns**

`src/main/java/site/asm0dey/calit/web/og/TextRuns.java`:

```java
package site.asm0dey.calit.web.og;

import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a string into runs that each have a font able to draw them.
 *
 * <p>AWT performs no fallback for fonts loaded with {@code Font.createFont}, so a Greek meeting
 * name drawn straight through Rubik renders as boxes. Bidi *within* a run is still handled by
 * {@code drawString}, which is why Hebrew and mixed "30 דק׳ · Google Meet" need no special case.</p>
 */
public final class TextRuns {

    private TextRuns() {}

    public record Run(String text, Font font) {}

    /** Every character drawable by some font in the chain. */
    public static boolean covered(String text, List<Font> chain) {
        return text.codePoints().allMatch(cp -> chain.stream().anyMatch(f -> f.canDisplay(cp)));
    }

    public static List<Run> split(String text, List<Font> chain, float size) {
        List<Run> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Font currentFont = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Font font = chain.stream()
                    .filter(f -> f.canDisplay(cp))
                    .findFirst()
                    .orElse(chain.getFirst());
            if (currentFont == null || font == currentFont) {
                currentFont = font;
            } else {
                out.add(new Run(current.toString(), currentFont.deriveFont(size)));
                current.setLength(0);
                currentFont = font;
            }
            current.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (!current.isEmpty()) {
            out.add(new Run(current.toString(), currentFont.deriveFont(size)));
        }
        return out;
    }

    public static int width(Graphics2D g, List<Run> runs) {
        int total = 0;
        for (Run r : runs) {
            total += g.getFontMetrics(r.font()).stringWidth(r.text());
        }
        return total;
    }

    public static void drawCentered(Graphics2D g, List<Run> runs, int centerX, int baseline) {
        float x = centerX - width(g, runs) / 2f;
        for (Run r : runs) {
            g.setFont(r.font());
            g.drawString(r.text(), x, baseline);
            x += g.getFontMetrics().stringWidth(r.text());
        }
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

```bash
./mvnw test -Dtest=TextRunsTest
```

Expected: PASS, 6 tests.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/fonts src/main/resources/application.properties \
        src/main/java/site/asm0dey/calit/web/og src/test/java/site/asm0dey/calit/web/og
git commit -m "feat(og): ship card fonts and a per-run fallback chain

Rubik covers Latin, Cyrillic, Hebrew and Arabic in one file; the site's Hanken
Grotesk is Latin-only, so it stays the wordmark. AWT does no automatic fallback
for createFont-loaded fonts, hence the run splitting.

All faces are static instances baked with fonttools: AWT ignores fvar axes and
loads whatever the default instance is."
```

---

### Task 3: Render the card

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/og/CardRenderer.java`
- Test: `src/test/java/site/asm0dey/calit/web/og/CardRendererTest.java`

**Interfaces:**
- Consumes: `CardFonts`, `TextRuns` from Task 2.
- Produces: `CardRenderer.Card(String owner, String type, String meta)` (nested record);
  `CardRenderer.render(Card card)` → `byte[]` (PNG);
  `CardRenderer.product()` → `byte[]`;
  `CardRenderer.renderable(Card card)` → `boolean`.

- [ ] **Step 1: Write the failing test**

`src/test/java/site/asm0dey/calit/web/og/CardRendererTest.java`:

```java
package site.asm0dey.calit.web.og;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CardRendererTest {

    static final CardRenderer RENDERER = new CardRenderer(new CardFonts());

    static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersA1200x630Png() throws Exception {
        byte[] png = RENDERER.render(new CardRenderer.Card("Ada Lovelace", "Coffee chat", "30 min"));
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(png, 4));
        BufferedImage img = decode(png);
        assertEquals(1200, img.getWidth());
        assertEquals(630, img.getHeight());
    }

    @Test
    void drawsInkInsideTheSafeSquare() throws Exception {
        // Everything essential must survive a square centre crop: x in [285, 915].
        BufferedImage img = decode(RENDERER.render(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        int dark = 0;
        for (int x = 285; x < 915; x++) {
            for (int y = 0; y < 630; y++) {
                if ((img.getRGB(x, y) & 0xFF) < 0x60) {
                    dark++;
                }
            }
        }
        assertTrue(dark > 2000, "expected text inside the safe square, found " + dark + " dark pixels");
    }

    @Test
    void keepsDecorationOutOfTheSafeSquare() throws Exception {
        BufferedImage img = decode(RENDERER.render(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        // The indigo flanks live only outside the safe square.
        assertEquals(img.getRGB(20, 315), img.getRGB(1180, 315), "flanks should be symmetric");
        assertTrue((img.getRGB(300, 20) & 0xFFFFFF) > 0xE0E0E0, "safe square top should be background");
    }

    @Test
    void longNamesStillFit() throws Exception {
        byte[] png = RENDERER.render(new CardRenderer.Card(
                "Ada Lovelace", "Quarterly architecture review and roadmap planning session", "15, 30 or 60 min"));
        assertEquals(1200, decode(png).getWidth());
    }

    @Test
    void reportsUnrenderableText() {
        assertTrue(RENDERER.renderable(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        assertTrue(RENDERER.renderable(new CardRenderer.Card("דנה כהן", "פגישת היכרות", "30 דק׳")));
        assertFalse(RENDERER.renderable(new CardRenderer.Card("Ada", "コーヒーチャット", "30 min")));
    }

    @Test
    void productCardNeedsNoInput() throws Exception {
        assertEquals(1200, decode(RENDERER.product()).getWidth());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=CardRendererTest
```

Expected: FAIL — `CardRenderer` does not exist.

- [ ] **Step 3: Implement CardRenderer**

`src/main/java/site/asm0dey/calit/web/og/CardRenderer.java`:

```java
package site.asm0dey.calit.web.og;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the 1200x630 link-preview card.
 *
 * <p>Composition is CENTRED on purpose. Preview clients crop differently and a square centre crop is
 * the harsh case, so everything essential lives inside the central 630x630 square (x 285..915) and
 * the indigo flanks outside it are pure decoration. Centring also means RTL needs no mirroring —
 * there is no left edge to flip.</p>
 *
 * <p>Renders in about 2 ms, which is why nothing here is cached: see the endpoint's ETag and
 * Cache-Control instead.</p>
 */
@ApplicationScoped
public class CardRenderer {

    static final int W = 1200;

    static final int H = 630;

    static final int SAFE_X0 = (W - H) / 2;

    static final int SAFE_X1 = SAFE_X0 + H;

    static final int FLANK = 150;

    static final Color BG = new Color(0xFFFFFF);

    static final Color INK = new Color(0x16140F);

    static final Color INK_2 = new Color(0x565049);

    static final Color INDIGO = new Color(0x4F46E5);

    static final Color INDIGO_2 = new Color(0x6D65F0);

    static final Color MIST = new Color(0xF1F0FE);

    static final String PRODUCT_OWNER = "";

    static final String PRODUCT_TYPE = "Book a meeting";

    static final String PRODUCT_META = "Scheduling without the email thread";

    final CardFonts fonts;

    @Inject
    public CardRenderer(CardFonts fonts) {
        this.fonts = fonts;
    }

    /** Owner name, meeting-type name, and the meta line ("30 min · Google Meet"). */
    public record Card(String owner, String type, String meta) {}

    /** False when some shipped font cannot draw the text — the caller then serves the product card. */
    public boolean renderable(Card card) {
        List<Font> chain = fonts.chain(false);
        return TextRuns.covered(nullToEmpty(card.owner()), chain)
                && TextRuns.covered(nullToEmpty(card.type()), chain)
                && TextRuns.covered(nullToEmpty(card.meta()), chain);
    }

    public byte[] product() {
        return render(new Card(PRODUCT_OWNER, PRODUCT_TYPE, PRODUCT_META));
    }

    public byte[] render(Card card) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setPaint(new GradientPaint(0, 0, INDIGO, 0, H, INDIGO_2));
        g.fillRect(0, 0, FLANK, H);
        g.fillRect(W - FLANK, 0, FLANK, H);

        int cx = W / 2;
        drawLockup(g, cx);

        if (!nullToEmpty(card.owner()).isBlank()) {
            g.setColor(INK_2);
            TextRuns.drawCentered(g, TextRuns.split(card.owner(), fonts.chain(false), 32f), cx, 268);
        }

        List<List<TextRuns.Run>> lines = fitHeadline(g, nullToEmpty(card.type()));
        g.setColor(INK);
        int y = lines.size() > 1 ? 348 : 372;
        for (List<TextRuns.Run> line : lines) {
            TextRuns.drawCentered(g, line, cx, y);
            y += (int) (line.getFirst().font().getSize() * 1.1);
        }

        if (!nullToEmpty(card.meta()).isBlank()) {
            drawPill(g, cx, lines.size() > 1 ? y + 16 : 424, card.meta());
        }

        g.dispose();
        return toPng(img);
    }

    /** Chip + wordmark, matching .lp-brand: Fraunces inside the chip, the sans for "calit". */
    void drawLockup(Graphics2D g, int cx) {
        int tile = 56;
        Font wordFont = fonts.wordmark().deriveFont(34f);
        int wordWidth = trackedWidth(g, "calit", wordFont);
        int lockWidth = tile + 14 + wordWidth;
        int x = cx - lockWidth / 2;

        g.setColor(INDIGO);
        g.fill(new RoundRectangle2D.Float(x, 74, tile, tile, tile * 0.3f, tile * 0.3f));
        g.setColor(Color.WHITE);
        g.setFont(fonts.chip().deriveFont(tile * 0.6f));
        FontMetrics chipMetrics = g.getFontMetrics();
        g.drawString(
                "c",
                x + (tile - chipMetrics.stringWidth("c")) / 2f,
                74 + tile / 2f + (chipMetrics.getAscent() - chipMetrics.getDescent()) / 2f);

        g.setColor(INK);
        drawTracked(g, "calit", wordFont, x + tile + 14, 74 + tile / 2f + 12);
    }

    /** The site tracks the wordmark at -0.02em; AWT has no letter-spacing, so step per character. */
    void drawTracked(Graphics2D g, String text, Font font, float x, float baseline) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        float extra = -0.02f * font.getSize();
        for (char c : text.toCharArray()) {
            g.drawString(String.valueOf(c), x, baseline);
            x += fm.charWidth(c) + extra;
        }
    }

    int trackedWidth(Graphics2D g, String text, Font font) {
        FontMetrics fm = g.getFontMetrics(font);
        return fm.stringWidth(text) + (int) (-0.02f * font.getSize() * (text.length() - 1));
    }

    /** Shrink to fit the safe square, then wrap to two lines, then ellipsize. */
    List<List<TextRuns.Run>> fitHeadline(Graphics2D g, String text) {
        int maxWidth = SAFE_X1 - SAFE_X0 - 40;
        for (float size = 74; size >= 52; size -= 3) {
            List<TextRuns.Run> runs = TextRuns.split(text, fonts.chain(true), size);
            if (TextRuns.width(g, runs) <= maxWidth) {
                return List.of(runs);
            }
        }
        String[] words = text.split(" ");
        StringBuilder first = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String probe = first.isEmpty() ? words[i] : first + " " + words[i];
            if (!first.isEmpty() && TextRuns.width(g, TextRuns.split(probe, fonts.chain(true), 52f)) > maxWidth) {
                break;
            }
            first.setLength(0);
            first.append(probe);
        }
        List<List<TextRuns.Run>> out = new ArrayList<>();
        out.add(TextRuns.split(first.toString(), fonts.chain(true), 52f));
        String rest = String.join(" ", Arrays.copyOfRange(words, i, words.length));
        if (!rest.isBlank()) {
            out.add(TextRuns.split(ellipsize(g, rest, maxWidth), fonts.chain(true), 52f));
        }
        return out;
    }

    String ellipsize(Graphics2D g, String text, int maxWidth) {
        if (TextRuns.width(g, TextRuns.split(text, fonts.chain(true), 52f)) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 1
                && TextRuns.width(g, TextRuns.split(sb + "…", fonts.chain(true), 52f)) > maxWidth) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "…";
    }

    void drawPill(Graphics2D g, int cx, int y, String text) {
        List<TextRuns.Run> runs = TextRuns.split(text, fonts.chain(false), 28f);
        int textWidth = TextRuns.width(g, runs);
        FontMetrics fm = g.getFontMetrics(fonts.regular().deriveFont(28f));
        int padX = 24;
        int height = fm.getHeight() + 14;
        int width = textWidth + padX * 2;
        g.setColor(MIST);
        g.fill(new RoundRectangle2D.Float(cx - width / 2f, y, width, height, height, height));
        g.setColor(INDIGO);
        TextRuns.drawCentered(g, runs, cx, (int) (y + height / 2f + (fm.getAscent() - fm.getDescent()) / 2f));
    }

    static byte[] toPng(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, "png", out)) {
                throw new IllegalStateException("no PNG writer available");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -Dtest=CardRendererTest
```

Expected: PASS, 6 tests. If `drawsInkInsideTheSafeSquare` fails, the headline is being drawn outside
x∈[285,915] — check `fitHeadline`'s `maxWidth`, not the assertion.

- [ ] **Step 5: Eyeball one card before trusting the pixels**

```bash
cat > /tmp/RenderOne.java <<'EOF'
import java.nio.file.*;
import site.asm0dey.calit.web.og.*;
public class RenderOne {
    public static void main(String[] a) throws Exception {
        var r = new CardRenderer(new CardFonts());
        Files.write(Path.of("/tmp/card.png"), r.render(new CardRenderer.Card("Ada Lovelace", "Coffee chat", "30 min · Google Meet")));
        Files.write(Path.of("/tmp/card-he.png"), r.render(new CardRenderer.Card("דנה כהן", "פגישת היכרות", "30 דק׳ · Google Meet")));
        Files.write(Path.of("/tmp/card-product.png"), r.product());
    }
}
EOF
./mvnw -q compile
java -cp target/classes:/tmp /tmp/RenderOne.java
```

Open the three PNGs. Hebrew must read right-to-left with the block centred; the chip must show a
Fraunces "c"; the wordmark must be the sans, not the serif.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/og/CardRenderer.java \
        src/test/java/site/asm0dey/calit/web/og/CardRendererTest.java
git commit -m "feat(og): render the preview card

Centred composition keeps everything inside the central 630x630 square, so a
square-cropping client still shows the whole card — a left-aligned draft became
a fragment of one word with no logo. Centring also removes any need to mirror
for RTL."
```

---

### Task 4: Serve the card

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/OgImageResource.java`
- Modify: `src/main/java/site/asm0dey/calit/user/Usernames.java:16-31` (reserve `og`)
- Test: `src/test/java/site/asm0dey/calit/web/OgImageResourceTest.java`

**Interfaces:**
- Consumes: `CardRenderer.render/product/renderable`, `MeetingType.findBySlug(Long, String)`,
  `AppUser.find("username", …)`, `OwnerSettings.forOwner(Long)`,
  `MeetingTypeDuration.allowedDurations(MeetingType)`.
- Produces: routes `GET /og.png`, `GET /og/{user}.png`, `GET /og/{user}/{slug}.png`, all
  `image/png` with `ETag` and `Cache-Control: public, max-age=3600`.

- [ ] **Step 1: Write the failing test**

`src/test/java/site/asm0dey/calit/web/OgImageResourceTest.java`:

```java
package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;

@QuarkusTest
class OgImageResourceTest {

    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private static void seed(String slug, boolean secret) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coffee chat";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.secret = secret;
            t.persist();
        });
    }

    @Test
    void servesAPngForAMeetingType() {
        seed("card-public", false);
        byte[] body = given().when()
                .get("/og/admin/card-public.png")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("Cache-Control", "public, max-age=3600")
                .extract()
                .asByteArray();
        assertArrayEquals(PNG_MAGIC, Arrays.copyOf(body, 4));
    }

    @Test
    void productAndOwnerCardsRender() {
        seed("card-owner", false);
        assertArrayEquals(
                PNG_MAGIC,
                Arrays.copyOf(given().when().get("/og.png").then().statusCode(200).extract().asByteArray(), 4));
        assertArrayEquals(
                PNG_MAGIC,
                Arrays.copyOf(
                        given().when().get("/og/admin.png").then().statusCode(200).extract().asByteArray(), 4));
    }

    @Test
    void etagIsStableAndHonoursIfNoneMatch() {
        seed("card-etag", false);
        String etag = given().when()
                .get("/og/admin/card-etag.png")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        assertNotNull(etag);
        assertEquals(
                etag,
                given().when().get("/og/admin/card-etag.png").then().extract().header("ETag"),
                "same inputs must produce the same ETag");
        given().header("If-None-Match", etag)
                .when()
                .get("/og/admin/card-etag.png")
                .then()
                .statusCode(304);
    }

    @Test
    void secretTypeServesTheProductCard() {
        seed("card-secret", true);
        byte[] secret = given().when()
                .get("/og/admin/card-secret.png")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertArrayEquals(product, secret, "a secret type must not be named in its card");
    }

    @Test
    void unknownTargetsFallBackToTheProductCardNotA404() {
        byte[] product = given().when().get("/og.png").then().extract().asByteArray();
        assertArrayEquals(
                product,
                given().when().get("/og/nosuchuser.png").then().statusCode(200).extract().asByteArray());
        assertArrayEquals(
                product,
                given().when()
                        .get("/og/admin/nosuchslug.png")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asByteArray());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=OgImageResourceTest
```

Expected: FAIL — 404, the routes do not exist.

- [ ] **Step 3: Implement the resource**

`src/main/java/site/asm0dey/calit/web/OgImageResource.java`:

```java
package site.asm0dey.calit.web;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.Usernames;
import site.asm0dey.calit.web.og.CardRenderer;

/**
 * The link-preview card images. Public and unauthenticated: unfurl bots fetch them with no session.
 *
 * <p>Rendered per request (~2 ms) rather than cached server-side — the ETag changes with the inputs,
 * so a renamed meeting type needs no invalidation anywhere, and Cache-Control puts the cache in the
 * proxy/CDN layer that unfurlers already sit behind.</p>
 *
 * <p>Every miss (unknown user, unknown slug, inactive or secret type, text no shipped font can draw)
 * degrades to the product card with HTTP 200. A 404 would unfurl as a broken image; the product card
 * at least says "this is a calit link".</p>
 */
@Path("/")
public class OgImageResource {

    static final int MAX_AGE_SECONDS = 3600;

    final CardRenderer renderer;

    @Inject
    public OgImageResource(CardRenderer renderer) {
        this.renderer = renderer;
    }

    @GET
    @Path("/og.png")
    @Produces("image/png")
    public Response product(@Context Request request) {
        return png(request, "product", renderer.product());
    }

    @GET
    @Path("/og/{user}.png")
    @Produces("image/png")
    public Response owner(@Context Request request, @PathParam("user") String user) {
        AppUser owner = findOwner(user);
        if (owner == null) {
            return product(request);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        String name = settings == null || settings.ownerName == null || settings.ownerName.isBlank()
                ? owner.username
                : settings.ownerName;
        var card = new CardRenderer.Card("", name, "Book a meeting");
        if (!renderer.renderable(card)) {
            return product(request);
        }
        return png(request, "owner:" + owner.id + ":" + name, renderer.render(card));
    }

    @GET
    @Path("/og/{user}/{slug}.png")
    @Produces("image/png")
    public Response meetingType(
            @Context Request request, @PathParam("user") String user, @PathParam("slug") String slug) {
        AppUser owner = findOwner(user);
        if (owner == null) {
            return product(request);
        }
        MeetingType type = MeetingType.findBySlug(owner.id, slug);
        // Secret types are unlisted but bookable by direct link: naming one in a preview would
        // defeat the flag for anyone who glances at the chat.
        if (type == null || !type.active || type.secret) {
            return product(request);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        String name = settings == null || settings.ownerName == null || settings.ownerName.isBlank()
                ? owner.username
                : settings.ownerName;
        String meta = meta(type);
        var card = new CardRenderer.Card(name, type.name, meta);
        if (!renderer.renderable(card)) {
            return product(request);
        }
        return png(request, "type:" + type.id + ":" + name + ":" + type.name + ":" + meta, renderer.render(card));
    }

    static String meta(MeetingType type) {
        List<Integer> durations = MeetingTypeDuration.allowedDurations(type);
        String lengths = durations.stream().map(String::valueOf).reduce((a, b) -> a + " · " + b).orElse("");
        return lengths + " min · " + location(type);
    }

    static String location(MeetingType type) {
        return switch (type.locationType) {
            case GOOGLE_MEET -> "Google Meet";
            case PHONE -> "Phone";
            case IN_PERSON -> "In person";
            case CUSTOM -> "Online";
        };
    }

    static AppUser findOwner(String user) {
        String normalized = Usernames.normalize(user);
        return normalized == null ? null : AppUser.find("username", normalized).firstResult();
    }

    Response png(Request request, String cacheKey, byte[] body) {
        EntityTag etag = new EntityTag(sha256(cacheKey));
        Response.ResponseBuilder preconditionFailed = request.evaluatePreconditions(etag);
        CacheControl cc = new CacheControl();
        cc.setNoTransform(false);
        cc.setMaxAge(MAX_AGE_SECONDS);
        if (preconditionFailed != null) {
            return preconditionFailed.tag(etag).cacheControl(cc).build();
        }
        return Response.ok(body, "image/png").tag(etag).cacheControl(cc).build();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

If the `Cache-Control` header assertion fails, print what the endpoint actually returned and align
the test with JAX-RS's serialization (it may emit `no-transform` too); do not weaken the assertion
to `containsString("max-age")` without looking.

- [ ] **Step 4: Reserve the `og` username**

`/og/{user}.png` is more literal than `/{user}/{slug}`, so a user called `og` would lose their own
pages. Add it to `RESERVED` in `src/main/java/site/asm0dey/calit/user/Usernames.java`:

```java
            "calit",
            "index",
            "og",
            "privacy",
            "terms");
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./mvnw test -Dtest=OgImageResourceTest
./mvnw test -Dtest=UsernamesTest
```

Expected: PASS. (If `UsernamesTest` asserts an exact reserved-set size, update that expectation —
`og` is a deliberate addition.)

- [ ] **Step 6: Run the full suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS. `ReservedRouteTest` exercises route shadowing; if it fails, the `/og` route
is colliding with `/{user}` and needs its path checked before anything else is changed.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/OgImageResource.java \
        src/main/java/site/asm0dey/calit/user/Usernames.java \
        src/test/java/site/asm0dey/calit/web/OgImageResourceTest.java
git commit -m "feat(og): serve the preview cards

Rendered per request behind an ETag over the inputs, so a rename invalidates
nothing. Unknown, inactive and secret targets degrade to the product card with
200 — a 404 unfurls as a broken image."
```

---

### Task 5: Make both container images able to render

Neither image can render today. This is the task where "it builds" proves nothing — every failure
in the spike passed the build and died on the first request.

**Files:**
- Modify: `Dockerfile:44-60` (runtime stage)
- Modify: `Dockerfile.native:44-56` (runtime stage)
- Modify: `.github/workflows/ci.yml:237-272` (smoke test)

**Interfaces:**
- Consumes: `GET /og.png` from Task 4.
- Produces: container images whose `/og.png` returns PNG bytes.

- [ ] **Step 1: Add the font stack to the native runtime stage**

In `Dockerfile.native`, replace the runtime stage's `apk add` line and add two lines after the
binary `COPY`:

```dockerfile
# ca-certificates: outbound TLS to Google API + SMTP. zlib: the binary links libz.so.1.
# freetype + fontconfig: required by quarkus-awt for native mode, per its own guide. The font
# package goes beyond what that guide lists and is NOT decorative: with no font file anywhere the
# JDK font manager fails to initialise ("Fontconfig head is null"), and that breaks Font.createFont
# even for a TTF embedded in the binary.
RUN apk add --no-cache ca-certificates zlib freetype fontconfig font-dejavu-core && update-ca-certificates
COPY --chown=1001:1001 --from=build /build/target/*-runner /app/application
# AWT in a native image dlopen()s these JDK libraries at runtime; native-image emits them beside the
# binary instead of linking them in, so they must ship with it.
COPY --chown=1001:1001 --from=build /build/target/*-native-image-source-jar/*.so /app/lib/
ENV LD_LIBRARY_PATH=/app/lib
```

- [ ] **Step 2: Add the font stack to the JVM runtime stage**

`bellsoft/liberica-runtime-container:jre-26-musl` has no `libfreetype.so.6`, so the JVM image
currently cannot render either. Add before the `USER 1001` line in `Dockerfile`:

```dockerfile
# The JRE image ships libfontmanager.so but not libfreetype.so.6 it links against, and no font at
# all — so AWT card rendering fails at request time without these. See Dockerfile.native.
USER root
RUN apk add --no-cache freetype fontconfig font-dejavu-core
```

If this base image has no `apk` (a hardened/distroless base — see `calit-gabg`), do **not** improvise:
stop and copy the font stack from a builder stage instead, which is the approach that bean records
as verified.

Note for whoever runs these images: `/tmp` must stay writable. `Font.createFont(InputStream)` spills
to a temp file, so a read-only root filesystem needs a tmpfs mounted there or card rendering fails at
request time. Say so in the PR description; the hardening work itself is `calit-gabg`.

- [ ] **Step 3: Build the native image and prove a card renders**

```bash
docker build -f Dockerfile.native -t calit-og-check:native .
docker network create ogcheck || true
docker run -d --name og-pg --network ogcheck \
  -e POSTGRES_USER=calit -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=calit postgres:16-alpine
sleep 5
docker run -d --name og-app --network ogcheck -p 8099:8080 \
  -e DB_URL=jdbc:postgresql://og-pg:5432/calit -e DB_USER=calit -e DB_PASSWORD=pw \
  -e SESSION_ENCRYPTION_KEY=check-session-key-0123456789-abcdef \
  -e TOKEN_ENCRYPTION_KEY=1111111111111111111111111111111111111111111111111111111111111111 \
  -e APP_BASE_URL=http://localhost:8099 -e MAIL_HOST=localhost -e QUARKUS_MAILER_MOCK=true \
  -e GOOGLE_OAUTH_CLIENT_ID=dummy -e GOOGLE_OAUTH_CLIENT_SECRET=dummy \
  -e GOOGLE_OAUTH_STATE_SECRET=check-state-secret-0123456789-abcd calit-og-check:native
until curl -fs http://localhost:8099/q/health/ready >/dev/null; do sleep 2; done
curl -s -o /tmp/native-card.png -w '%{http_code} %{content_type}\n' http://localhost:8099/og.png
file /tmp/native-card.png
```

Expected: `200 image/png` and `PNG image data, 1200 x 630`. If instead you get a 500, read the
container log: `UnsatisfiedLinkError: Can't load library: awt` means Step 1's `.so` copy is wrong;
`Fontconfig head is null` means the font package is missing.

- [ ] **Step 4: Repeat for the JVM image**

```bash
docker rm -f og-app
docker build -f Dockerfile -t calit-og-check:jvm .
docker run -d --name og-app --network ogcheck -p 8099:8080 \
  -e DB_URL=jdbc:postgresql://og-pg:5432/calit -e DB_USER=calit -e DB_PASSWORD=pw \
  -e SESSION_ENCRYPTION_KEY=check-session-key-0123456789-abcdef \
  -e TOKEN_ENCRYPTION_KEY=1111111111111111111111111111111111111111111111111111111111111111 \
  -e APP_BASE_URL=http://localhost:8099 -e MAIL_HOST=localhost -e QUARKUS_MAILER_MOCK=true \
  -e GOOGLE_OAUTH_CLIENT_ID=dummy -e GOOGLE_OAUTH_CLIENT_SECRET=dummy \
  -e GOOGLE_OAUTH_STATE_SECRET=check-state-secret-0123456789-abcd calit-og-check:jvm
until curl -fs http://localhost:8099/q/health/ready >/dev/null; do sleep 2; done
curl -s -o /tmp/jvm-card.png -w '%{http_code} %{content_type}\n' http://localhost:8099/og.png
file /tmp/jvm-card.png
docker rm -f og-app og-pg && docker network rm ogcheck
```

Expected: same `200 image/png`, 1200×630.

- [ ] **Step 5: Extend the CI smoke test to request a card**

In `.github/workflows/ci.yml`, inside the smoke-test step after the `/setup` POST check, add:

```bash
          # The card endpoint is the one path that is build-green and request-red when the runtime
          # image lacks AWT's .so files or any font at all. Assert real PNG bytes, not a 200.
          curl -fs http://localhost:8080/og.png -o /tmp/og.png
          head -c 4 /tmp/og.png | grep -q 'PNG' || { echo "::error::/og.png is not a PNG"; docker logs app; exit 1; }
          echo "og card smoke test passed"
```

Also make the smoke step run for the JVM variant too, since it has the same font dependency — change

```yaml
        if: matrix.variant == 'native'
```

to

```yaml
        if: always()
```

only if the surrounding job already builds a runnable image for both variants; otherwise leave the
condition alone and note in the PR that the JVM image is covered by Step 4's manual check.

- [ ] **Step 6: Commit**

```bash
git add Dockerfile Dockerfile.native .github/workflows/ci.yml
git commit -m "build: let both images render the preview card

Neither could. The native runtime needs AWT's dlopen'd JDK .so files beside the
binary; both need freetype and at least one font on disk, because an empty
fontconfig makes the JDK font manager fail to initialise and that breaks
Font.createFont even for an embedded TTF.

CI now requests /og.png and checks PNG magic bytes: every failure of this kind
passes the build and dies on the first request."
```

---

### Task 6: Documentation

**Files:**
- Create (on the `docs-site` branch): changelog entry
- Create: `docs/adr/0009-capability-urls-never-carry-a-preview.md`
- Modify: `.beans/calit-o89d-*.md` (tick the todo items, add the summary)

- [ ] **Step 1: Write the ADR**

`docs/adr/0009-capability-urls-never-carry-a-preview.md`:

```markdown
# Capability URLs never carry a preview

A page reachable by holding an unguessable token — `/booking/{manageToken}/manage`,
`/guest/{declineToken}/decline` and their siblings — is authorised by the URL itself. Anyone the
link is forwarded to can open it, which is what makes it useful for an invitee who has no account.

Link previews turn that property into a leak. A chat client that unfurls such a URL paints the
invitee's name, the meeting and its time into the conversation for everyone in it, and some clients
prefetch previews server-side, so the endpoint is touched by machines nobody invited.

So: a capability URL emits `noindex,nofollow` and no `og:`/`twitter:` tags at all.

This is enforced by the default rather than by a flag. `base.html` renders preview tags only when a
page passes an `OgCard`; a page that passes nothing gets the suppression branch. Adding a new
token-addressed page therefore inherits the safe behaviour, and the leak can only reappear if
someone deliberately opts that page in.

## Considered options

**A `noindex` flag on the token templates** — rejected: the safe behaviour would depend on every
future page remembering to set it, and the failure is silent.

**Blocking preview bots by user agent** — rejected: unfurlers are not required to identify
themselves, the list is unbounded, and a missed one leaks exactly the data this rule protects.
```

- [ ] **Step 2: Add the changelog entry on the docs-site branch**

```bash
git worktree add /tmp/calit-docs docs-site
```

In `/tmp/calit-docs/docs-site/src/content/docs/releases/changelog.md`, add under `## Unreleased`
(create the section with its standing subtitle "Merged but not yet in a tagged release." if absent):

```markdown
- **Booking links now unfurl with a preview card.** A calit link pasted into Slack, WhatsApp,
  iMessage or a tweet used to render as a bare URL. Public pages now carry `og:`/`twitter:`
  metadata, and each meeting type gets a generated card image showing the owner, the meeting name
  and its length. Pages reached through a booking-management or guest-decline token deliberately
  carry **no** preview and are marked `noindex` — an unfurl there would paint the invitee's name and
  meeting time into whatever chat the link was pasted into. Secret meeting types show the generic
  calit card rather than naming the meeting. ([#N](https://github.com/asm0dey/calit/pull/N))

  Upgrade note: make sure `APP_BASE_URL` is the public URL of your instance — preview image and page
  URLs are absolute and are built from it. The container images grow by roughly 10 MB (font
  rendering libraries and fonts). No configuration or database changes.
```

Commit on that branch, replacing `#N` with the real PR number once it exists.

- [ ] **Step 3: Update the bean**

```bash
beans update calit-o89d --body-replace-old "[ ] noindex,nofollow" --body-replace-new "[x] noindex,nofollow"
```

Repeat for each completed todo item, then append a `## Summary of Changes` section describing what
shipped, and note explicitly which items did **not**: per-locale cards, a server-side cache, and CJK
/ Thai / Devanagari / emoji coverage were all deliberately excluded.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0009-capability-urls-never-carry-a-preview.md .beans
git commit -m "docs(adr): capability URLs never carry a preview"
```

---

### Task 7: Verify and open the PR

- [ ] **Step 1: Run the whole suite**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. A red suite blocks the PR — including a failure you
believe you did not cause. Fix it first, in its own commit, and say so in the PR.

- [ ] **Step 2: Check formatting**

```bash
./mvnw spotless:check
```

Expected: pass. If not, `./mvnw spotless:apply` and amend.

- [ ] **Step 3: Confirm a real unfurl, not just the HTML**

Deploy or tunnel the branch somewhere publicly reachable and paste a `/{user}/{slug}` link into at
least one real client (Slack, Telegram, WhatsApp or iMessage). Confirm the card renders and the text
is right. Then paste a `/booking/{manageToken}/manage` link into the same client and confirm **no**
preview appears. Reading the HTML is not the same test and does not satisfy this step.

- [ ] **Step 4: Open the PR**

```bash
git push -u origin feat/social-preview-images
gh pr create --title "Social preview images for the public pages" --body "$(cat <<'EOF'
Adds og:/twitter: metadata to every shareable page and a rendered card image per
meeting type — and, first, stops the capability URLs from unfurling at all.

## Why the token pages come first

/booking/{manageToken}/* and /guest/{declineToken}/* are reachable by anyone
holding the link. An unfurl paints the invitee's name, the meeting and the time
into whatever chat the token was pasted into, and some clients prefetch previews
server-side. They now emit noindex,nofollow and no og: tags — by default, not by
flag: base.html renders tags only for pages that pass an OgCard.

## Notes for review

- Both container images previously could NOT render: the native one lacks AWT's
  dlopen'd .so files, and both lack libfreetype plus any font on disk. Every such
  failure is build-green and request-red, so CI now requests /og.png and checks
  PNG magic bytes.
- Card text uses Rubik (Latin/Cyrillic/Hebrew/Arabic) with a per-run fallback
  chain; scripts no shipped font covers fall back to the generic card rather
  than rendering boxes.
- og:locale is always en_US, so the preview strings are English literals — a
  deliberate, documented exception to the translate-everything rule.
- Spec: docs/superpowers/specs/2026-08-26-social-preview-images-design.md
- Container hardening surfaced along the way is tracked separately in calit-gabg.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01MrnPiNwAQhe2RtmYTteNcj
EOF
)"
```

- [ ] **Step 5: Backfill the PR number**

Replace `#N` in the docs-site changelog entry with the real number and push that branch.
