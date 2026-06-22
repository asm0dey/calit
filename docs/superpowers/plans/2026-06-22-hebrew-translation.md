# Hebrew Translation (he) + RTL Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Hebrew (`he`) as a fully-translated, right-to-left UI locale to calit, including emails and a mirrored RTL layout.

**Architecture:** calit already has a complete, auto-discovering i18n layer (German `de` proves it works). A new locale needs only two `.properties` files — `AppLocaleDiscovery` enumerates the Quarkus-generated `@Localized` beans at runtime, so dropping `msg_he.properties` + `adm_he.properties` makes `he` appear in the language switcher with zero config or Java changes. The genuinely new work is **RTL**: every `<html>` element (web + email) gets a `dir` attribute derived from the page language, and a CSS sweep converts the handful of physical-direction Tailwind utilities (`text-left`, `right-3`, …) to logical ones (`text-start`, `end-3`, …) so the layout mirrors correctly.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute templates + `quarkus-qute-i18n` message bundles, Tailwind v4 + daisyUI 5 (both RTL-aware via the `dir` attribute), JUnit 5 + RestAssured.

## Global Constraints

- **UTF-8 properties.** `.properties` message files are read as UTF-8 (German file stores `ö`/`ü` literally). Write Hebrew characters directly — no `\uXXXX` escaping.
- **Preserve placeholders verbatim.** Every `{name}`, `{inviteeName}`, `{meetingTypeName}`, `{minutes}`, `{accountEmail}`, `{inviteeEmail}`, `{role}` token must appear unchanged in the Hebrew value, or Qute message formatting breaks.
- **Keys must match exactly.** A `msg_he.properties` / `adm_he.properties` key set must be a subset of the keys in `msg_de.properties` / `adm_de.properties` (which mirror the `@MessageBundle` interface methods). An unknown key fails the Quarkus build. This plan ships the full key set.
- **No new config.** `quarkus.default-locale=en` stays. Locale discovery is by filename — do **not** add `quarkus.locales`.
- **No new POST forms.** This feature adds none, so no CSRF-token work. (If you somehow add one, it must carry `{inject:csrf.token}` or it 400s in prod.)
- **Docker required** for every `mvn test` run (Dev Services Postgres). Admin user is always id 1; tests truncate+reseed per test.
- **Docs are part of done.** User-facing changes land on the `docs-site` branch in the same effort (Task 7).
- **RTL is the only new locale that is right-to-left.** Hebrew is the sole RTL language calit ships. The `dir` logic keys on `lang == 'he'` and is marked with a `ponytail:` comment naming the extension path (add `|| lang == 'ar'` when Arabic arrives).

---

### Task 1: Hebrew UI message bundle (`msg_he.properties`)

Adds the public-facing UI translations and makes `he` auto-discovered. Locale discovery enumerates `AppMessages` beans, so this file alone flips `AppLocales.supported()` from `[en, de]` to `[en, de, he]`.

**Files:**
- Create: `src/main/resources/messages/msg_he.properties`
- Modify (test): `src/test/java/site/asm0dey/calit/i18n/AppLocalesDiscoveryTest.java`

**Interfaces:**
- Consumes: the existing `AppMessages` `@MessageBundle` interface (keys = method names; English defaults live as `@Message` annotations there). The German file `src/main/resources/messages/msg_de.properties` is the structural reference — `msg_he.properties` carries the identical key set.
- Produces: `AppLocales.supported()` now returns 3 locales (`en`, `de`, `he`); `AppLocales.pick("he") == Locale.forLanguageTag("he")`; `AppLocales.labelFor("he") == "עברית"`; the footer language switcher renders an "עברית" option.

- [ ] **Step 1: Update the discovery test to expect Hebrew (failing test)**

Replace the body of `supportedContainsEnglishAndGerman` and add Hebrew assertions in `src/test/java/site/asm0dey/calit/i18n/AppLocalesDiscoveryTest.java`:

```java
    @Test
    void supportedContainsEnglishGermanAndHebrew() {
        List<Locale> supported = AppLocales.supported();
        // Default (en) must be first
        assertEquals(Locale.ENGLISH, supported.getFirst(), "Default locale must be first");
        assertTrue(supported.contains(Locale.GERMAN), "German must be discovered from msg_de.properties");
        assertTrue(supported.contains(Locale.forLanguageTag("he")), "Hebrew must be discovered from msg_he.properties");
        assertEquals(3, supported.size(), "Exactly three locales expected: en + de + he");
    }

    @Test
    void labelForHeIsHebrewEndonym() {
        assertEquals("עברית", AppLocales.labelFor("he"));
    }
```

Also update the stale `supportedMatchesBundleBeans` assertion that currently expects `he` to be unsupported — change the `isSupported("fr")` line's neighbours so `he` is now supported:

```java
    @Test
    void supportedMatchesBundleBeans() {
        assertEquals(Locale.GERMAN, AppLocales.pick("de"));
        assertTrue(AppLocales.isSupported("de"));
        assertTrue(AppLocales.isSupported("he"));
        assertFalse(AppLocales.isSupported("fr"));
    }
```

(Delete the old `supportedContainsEnglishAndGerman` method — it is replaced by `supportedContainsEnglishGermanAndHebrew`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AppLocalesDiscoveryTest`
Expected: FAIL — `supportedContainsEnglishGermanAndHebrew` asserts size 3 but discovery still returns `[en, de]` (size 2); `labelForHeIsHebrewEndonym` passes already (it is pure JDK), `isSupported("he")` fails.

- [ ] **Step 3: Create `src/main/resources/messages/msg_he.properties`**

```properties
common_cancel=ביטול
common_language=שפה

# Landing page (index.html at /)
pub_index_title=calit — תזמון הפגישות שלך
pub_landing_nav_product=מוצר
pub_landing_nav_features=תכונות
pub_landing_signed_in_as=מחובר בתור
pub_landing_nav_settings=הגדרות
pub_landing_nav_logout=התנתקות
pub_landing_nav_dashboard=לוח הבקרה שלך
pub_landing_nav_signin=התחברות
pub_landing_nav_open_instance=פתיחת המופע
pub_landing_eyebrow=אירוח עצמי · קוד פתוח · ריבוי משתמשים
pub_landing_hero_h1_pre=אפליקציית תזמון הפגישות שאתה
pub_landing_hero_h1_em=באמת הבעלים שלה
pub_landing_hero_sub_pre=calit נותנת לכל משתמש מרחב הזמנות משלו — עמוד הזמנות אישי, זמינות ו-Google Calendar — לגמרי על השרת
pub_landing_hero_sub_strong=שלך
pub_landing_hero_sub_post=. ללא SaaS, ללא תמחור לפי מושב, ללא נעילה.
pub_landing_cta_dashboard=אל לוח הבקרה שלך
pub_landing_cta_gallery=ראה את זה בפעולה
pub_landing_trust_argon=אימות argon2id
pub_landing_trust_meet=קישורי Google Meet
pub_landing_trust_approvals=תהליכי אישור
pub_landing_trust_binary=קובץ הרצה אחד + Postgres
pub_landing_gallery_eyebrow=הצצה פנימה
pub_landing_gallery_h2=אפליקציה אחת. שלושה ממשקים.
pub_landing_gallery_lede=עמוד ציבורי לאורחים, עמוד בית אישי לכל משתמש וקונסולת ניהול — כל אחד מבודד לבעליו, מוגש מאותו מופע באירוח עצמי.
pub_landing_cap_console_title=קונסולת הניהול
pub_landing_cap_console_desc_pre=נהל סוגי פגישות, זמינות, שדות הזמנה, סנכרון Google — ולמנהלים — את כל משתמשי הצוות תחת
pub_landing_cap_landing_title=עמוד הבית של כל משתמש
pub_landing_cap_landing_pre=כל משתמש מקבל
pub_landing_cap_landing_post=סוגי פגישות פעילים משלו, ותו לא.
pub_landing_cap_confirmed_title=מאושר בלחיצה אחת
pub_landing_cap_confirmed_desc=אורחים בוחרים משבצת זמן באזור הזמן שלהם; שני הצדדים מקבלים אימייל וקישור Meet.
pub_landing_features_eyebrow=למה calit
pub_landing_features_h2=נבנתה כדי להפעיל, לא כדי לשכור.
pub_landing_features_lede=כל מה שאתה מצפה מכלי תזמון — בתוספת ריבוי-דיירים אמיתי והביטחון שהנתונים שלך בבעלותך.
pub_landing_feat_isolation_k=בידוד
pub_landing_feat_isolation_h3=ריבוי-דיירים לפי משתמש
pub_landing_feat_isolation_p=לכל סוג פגישה, הזמנה והגדרה יש בעלים. משתמש לעולם אינו יכול לראות או לשנות את הנתונים של אחר.
pub_landing_feat_calendar_k=יומן
pub_landing_feat_calendar_h3=סנכרון Google ו-Meet
pub_landing_feat_calendar_p=חבר את חשבון Google של כל משתמש. הזמנות יוצרות אירועים ומפיקות קישור Meet אוטומטית — או פועלות לגמרי ללא Google.
pub_landing_feat_control_k=שליטה
pub_landing_feat_control_h3=אישורים ומגבלות
pub_landing_feat_control_p=השאר פגישות בהמתנה עד לאישור, עם מרווחים לפי סוג, זמני התראה מינימליים ואופק הזמנות.
pub_landing_feat_trust_k=אמון
pub_landing_feat_trust_h3=אימות אמיתי
pub_landing_feat_trust_p=סיסמאות מגובבות עם argon2id, עוגיות מוצפנות וחסרות מצב, נעילה מיידית — ללא סיסמת מנהל מוטמעת.
pub_landing_feat_defense_k=הגנה
pub_landing_feat_defense_h3=הגנה מפני שימוש לרעה
pub_landing_feat_defense_p=Cloudflare Turnstile, מלכודת דבש ומגבלת אימיילים יומית מגינים על כל טופס הזמנה ציבורי מההתחלה.
pub_landing_feat_ops_k=תפעול
pub_landing_feat_ops_h3=קובץ הרצה אחד + Postgres
pub_landing_feat_ops_p=אפליקציית Quarkus יחידה ומסד נתונים אחד. ארח בעצמך בכל מקום, הזמן את הצוות שלך, וזהו. הרשמה ציבורית אופציונלית כלולה גם כן.
pub_landing_close_eyebrow=בואו נתחיל
pub_landing_close_h2=הקמה תוך דקות.
pub_landing_close_p=הצבע על מסד נתונים Postgres ופתח את העמוד — הביקור הראשון יוצר את חשבון המנהל שלך. ללא רישיון, ללא רשימת המתנה, ללא חישובי מושבים.
pub_landing_close_cta_dashboard=פתח את לוח הבקרה שלך
pub_landing_close_cta_features=גלה תכונות
pub_landing_footer_meta=תזמון פגישות באירוח עצמי · הנתונים שלך לעולם לא עוזבים את השרת שלך

# User landing page (landing.html at /{username})
pub_user_title=הזמנת פגישה
pub_user_pick_type=בחר סוג פגישה כדי לראות זמנים פנויים.
pub_user_no_types=אין סוגי פגישות זמינים כרגע.
pub_user_choose_time=בחר זמן

# Booking page (book.html)
pub_book_title_prefix=הזמנה —
pub_book_back=← כל סוגי הפגישות
pub_book_select_datetime=בחר תאריך ושעה
pub_book_no_times=אין זמנים פנויים כרגע. אנא בדוק שוב מאוחר יותר.
pub_book_your_name=השם שלך
pub_book_your_email=האימייל שלך
pub_book_location_label=מיקום:
pub_book_meet_hint=Google Meet — הקישור יישלח לאחר ההזמנה
pub_book_approval_hint=פגישה זו דורשת את אישור הבעלים — אתה שולח בקשה ותקבל הודעה לאחר אישור או דחייה.
pub_book_btn_request=שלח בקשה
pub_book_btn_confirm=הזמן פגישה

# Confirmation page (confirmation.html)
pub_conf_title_pending=הבקשה נשלחה
pub_conf_title_confirmed=ההזמנה אושרה
pub_conf_h1_pending=הבקשה נשלחה — ממתינה לאישור הבעלים
pub_conf_pending_desc=תודה, {inviteeName}. הזמן שביקשת שמור בזמן שהבעלים של {meetingTypeName} בודק אותו. תקבל אימייל לאחר אישור או דחייה.
pub_conf_h1_confirmed=ההזמנה בוצעה, {inviteeName}!
pub_conf_when_label=מתי:
pub_conf_meet_label=Google Meet:
pub_conf_location_label=מיקום:
pub_conf_pending_email=אישור בקשה יישלח אל {inviteeEmail}.
pub_conf_confirmed_email=אימייל אישור יישלח אל {inviteeEmail}.
pub_conf_manage_link=לשנות או לבטל את ההזמנה?

# Cancelled page (cancelled.html)
pub_cancelled_title=ההזמנה בוטלה
pub_cancelled_h1=ההזמנה שלך בוטלה
pub_cancelled_desc=הפגישה בוטלה ורישום היומן הוסר.
pub_cancelled_btn=הזמן פגישה אחרת

# Manage booking page (manage.html)
pub_manage_title=ניהול הזמנה
pub_manage_h1=נהל את ההזמנה שלך
pub_manage_currently_label=כעת:
pub_manage_for_label=עבור:
pub_manage_h2_reschedule=שינוי מועד
pub_manage_no_alternatives=אין זמנים חלופיים זמינים.
pub_manage_btn_reschedule=שנה מועד לזמן שנבחר
pub_manage_h2_cancel=ביטול
pub_manage_btn_cancel=בטל את ההזמנה הזו

# Unavailable page (unavailable.html)
pub_unavailable_title=התזמון אינו זמין באופן זמני
pub_unavailable_h1=התזמון אינו זמין באופן זמני
pub_unavailable_desc=יומן זה אינו נגיש כרגע, ולכן הזמנות חדשות מושהות. אנא בדוק שוב בקרוב.

# Not-ready page (notReady.html)
pub_not_ready_title=עדיין לא זמין
pub_not_ready_h1=עמוד ההזמנות הזה עדיין לא מוכן
pub_not_ready_desc=הבעלים עדיין לא סיים את הקמת calit. אנא בדוק שוב בקרוב.

# Email subjects
email_requested_subject=בקשת הזמנה התקבלה: {meetingTypeName}
email_confirmed_subject=ההזמנה אושרה: {meetingTypeName}
email_approved_subject=הבקשה אושרה: {meetingTypeName}
email_declined_subject=ההזמנה נדחתה: {meetingTypeName}
email_rescheduled_subject=מועד ההזמנה שונה: {meetingTypeName}
email_cancelled_subject=ההזמנה בוטלה: {meetingTypeName}
email_reminder_subject=תזכורת: {meetingTypeName}
email_password_reset_subject=איפוס סיסמת calit
email_google_disconnected_subject=נדרשת פעולה: חבר מחדש את Google Calendar

# Email date/time pattern (Hebrew: "בשעה" for "at"; "ב" prefix before month, quoted as a literal)
email_datetime_pattern=EEEE, d 'ב'MMMM yyyy 'בשעה' HH:mm

# Email body — shared labels
email_body_greeting=שלום {name},
email_body_meeting_label=פגישה:
email_body_when_label=מתי:
email_body_duration_label=משך:
email_body_duration_minutes={minutes} דקות
email_body_meet_label=Google Meet:
email_body_location_label=מיקום:
email_body_your_answers_label=הפרטים שלך:
email_body_manage_link_text=נהל הזמנה
email_body_recipient_note=הודעה זו נשלחה אל {role}.
email_role_invitee=הנמען
email_role_owner=המארגן
email_body_requested_time_label=הזמן המבוקש:

# Email body — confirmation
email_confirmation_title=ההזמנה אושרה
email_confirmation_body=ההזמנה שלך אושרה.

# Email body — requested
email_requested_title=בקשת הזמנה התקבלה
email_requested_body=בקשת ההזמנה שלך התקבלה וממתינה לאישור.

# Email body — reminder
email_reminder_title=תזכורת להזמנה
email_reminder_body=זוהי תזכורת לפגישה הקרובה שלך.

# Email body — cancellation
email_cancellation_title=ההזמנה בוטלה
email_cancellation_body=ההזמנה שלך בוטלה.
email_cancellation_was_scheduled=היה מתוזמן ל:

# Email body — reschedule
email_reschedule_title=מועד ההזמנה שונה
email_reschedule_body=מועד ההזמנה שלך שונה.
email_reschedule_previous_time=הזמן הקודם:
email_reschedule_new_time=הזמן החדש:

# Email body — declined
email_declined_title=ההזמנה נדחתה
email_declined_body=לצערנו, בקשת ההזמנה שלך נדחתה.

# Email body — password reset
email_password_reset_title=איפוס סיסמת calit
email_password_reset_greeting=שלום,
email_password_reset_body=מישהו (כנראה אתה) ביקש לאפס את הסיסמה לחשבון ה-calit שלך.
email_password_reset_btn=אפס סיסמה
email_paste_link_hint=או הדבק קישור זה בדפדפן שלך:
email_password_reset_expiry=קישור זה תקף ל-30 דקות וניתן לשימוש פעם אחת בלבד. אם לא ביקשת זאת, התעלם מאימייל זה — הסיסמה שלך תישאר ללא שינוי.

# Email body — Google disconnected
email_google_disconnected_title=חבר מחדש את Google Calendar
email_google_disconnected_greeting=שלום,
email_google_disconnected_body=ל-calit אין עוד גישה לחשבון Google Calendar שלך {accountEmail}.
email_google_disconnected_paused=כל עוד החיבור מנותק, עמוד ההזמנות שלך מושהה — הזמנות חדשות חסומות כדי שאיש לא יזמין מעל אירועים ש-calit אינה יכולה לראות.
email_google_disconnected_btn=חבר מחדש את Google Calendar
email_google_disconnected_why=זה קורה בדרך כלל כאשר הגישה בוטלה, הסיסמה שונתה, או שהחיבור לא היה בשימוש זמן רב. החיבור מחדש אורך שניות ספורות.

# Auth / bootstrap pages

# Login page
auth_login_title=התחברות — calit
auth_login_h1=התחברות
auth_login_subtitle=גישת בעלים לניהול calit.
auth_login_invalid_credentials=פרטי התחברות שגויים — אנא נסה שוב.
auth_login_username_label=שם משתמש
auth_login_password_label=סיסמה
auth_login_remember_me=השאר אותי מחובר במכשיר זה
auth_login_submit=התחבר
auth_login_forgot_password=שכחת סיסמה?
auth_login_or_divider=או
auth_login_google_btn=התחבר עם Google
auth_login_notice_google_signup_disabled=לחשבון Google זה אין חשבון משויך, וההרשמה מושבתת.
auth_login_notice_google_ambiguous=כתובת אימייל זו של Google משויכת למספר חשבונות; אנא התחבר עם הסיסמה שלך במקום זאת.
auth_login_notice_google_generic=לא ניתן היה להשלים את ההתחברות עם Google. אנא נסה שוב.

# Signup page
auth_signup_title=הרשמה — calit
auth_signup_h1=הרשמה
auth_signup_username_label=שם משתמש
auth_signup_password_label=סיסמה
auth_signup_submit=צור חשבון
auth_signup_login_link=כבר יש לך חשבון? התחבר

# Forgot password page
auth_forgot_title=שכחת סיסמה — calit
auth_forgot_h1=שכחת סיסמה
auth_forgot_sent_notice=אם החשבון קיים, שלחנו קישור לאיפוס הסיסמה באימייל. הקישור יפוג בעוד 30 דקות.
auth_forgot_back_to_login=חזרה להתחברות
auth_forgot_instruction=הזן את שם המשתמש שלך ונשלח קישור איפוס לכתובת האימייל הרשומה.
auth_forgot_username_label=שם משתמש
auth_forgot_submit=שלח קישור איפוס

# Reset password page
auth_reset_title=איפוס סיסמה — calit
auth_reset_h1=איפוס סיסמה
auth_reset_enter_new_password=אנא הזן סיסמה חדשה.
auth_reset_new_password_label=סיסמה חדשה
auth_reset_submit=הגדר סיסמה חדשה
auth_reset_invalid_link=קישור האיפוס הזה אינו תקף או שפג תוקפו.
auth_reset_request_new_link=בקש קישור חדש

# Setup (first-run bootstrap) page
auth_setup_title=הקמת calit
auth_setup_h1=צור משתמש ראשון
auth_setup_subtitle=החשבון הראשון הזה הוא מנהל האתר.
auth_setup_error=שם המשתמש אינו תקף, שמור או תפוס — אנא בחר אחר.
auth_signup_error=לא ניתן להשתמש בשם משתמש זה – ייתכן שהוא אינו תקף, שמור או תפוס. השתמש ב-2–64 אותיות קטנות או ספרות, עם מקפים בודדים ביניהן.
auth_setup_username_label=שם משתמש
auth_setup_password_label=סיסמה
auth_setup_submit=צור מנהל

# Google bridge page
auth_bridge_title=מתחבר…
auth_bridge_progress=מתחבר…
auth_bridge_noscript_btn=המשך התחברות
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=AppLocalesDiscoveryTest`
Expected: PASS — discovery now returns `[en, de, he]` (size 3), `isSupported("he")` true, endonym `עברית`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/i18n/AppLocalesDiscoveryTest.java
git commit -m "feat(i18n): add Hebrew (he) UI message bundle"
```

---

### Task 2: Hebrew admin message bundle (`adm_he.properties`)

Translates the `/me` management UI. `AdminMessages` is a separate `@MessageBundle`; this file is required for admin pages to render in Hebrew (locale discovery already saw `he` from Task 1).

**Files:**
- Create: `src/main/resources/messages/adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/i18n/AdminMessagesHebrewTest.java`

**Interfaces:**
- Consumes: the `AdminMessages` `@MessageBundle` interface; reference file `src/main/resources/messages/adm_de.properties` (identical key set). `AdminMessageResolver#forLocale(Locale)` returns the bundle for a locale (already exists at `src/main/java/site/asm0dey/calit/i18n/AdminMessageResolver.java:20`).
- Produces: `adminMessages.forLocale(Locale.forLanguageTag("he")).adm_nav_dashboard()` returns the Hebrew string (`"לוח בקרה"`), distinct from the English default.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/i18n/AdminMessagesHebrewTest.java`:

```java
package site.asm0dey.calit.i18n;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AdminMessagesHebrewTest {

    @Inject
    AdminMessageResolver admin;

    @Test
    void hebrewAdminStringsResolveAndDifferFromEnglish() {
        String he = admin.forLocale(Locale.forLanguageTag("he")).adm_nav_dashboard();
        String en = admin.forLocale(Locale.ENGLISH).adm_nav_dashboard();
        assertFalse(he.isBlank(), "Hebrew admin nav label must not be blank");
        assertNotEquals(en, he, "Hebrew admin label must differ from English");
        assertEquals("לוח בקרה", he);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AdminMessagesHebrewTest`
Expected: FAIL — without `adm_he.properties`, `forLocale(he)` falls back to the English default, so `adm_nav_dashboard()` returns the English string and `assertNotEquals` / `assertEquals("לוח בקרה")` fail.

- [ ] **Step 3: Create `src/main/resources/messages/adm_he.properties`**

```properties
# Hebrew translations for AdminMessages (namespace: adm)
# Keys must match AdminMessages method names exactly.

# ---- Common / shared ----
adm_common_blocked=נעול

# ---- Admin nav ----
adm_nav_dashboard=לוח בקרה
adm_nav_pending=ממתין
adm_nav_meeting_types=סוגי פגישות
adm_nav_availability=זמינות
adm_nav_date_overrides=חריגות תאריך
adm_nav_booking_fields=שדות הזמנה
adm_nav_settings=הגדרות
adm_nav_google=Google
adm_nav_users=משתמשים
adm_nav_logout=התנתקות
adm_nav_create=+ צור

# ---- Page titles ----
adm_dashboard_title=ניהול — לוח בקרה
adm_meetingTypes_title=ניהול — סוגי פגישות
adm_meetingTypeDetail_title_prefix=ניהול —
adm_availability_title=ניהול — זמינות
adm_dateOverrides_title=ניהול — חריגות תאריך
adm_bookingFields_title=ניהול — שדות הזמנה
adm_settings_title=ניהול — הגדרות
adm_pending_title=ניהול — אישורים ממתינים
adm_google_title=ניהול — Google
adm_users_title=ניהול — משתמשים
mesetup_title=ברוך הבא — הקמת חשבון

# ---- Dashboard ----
adm_dashboard_h1=לוח בקרה
adm_dashboard_upcoming_stat=פגישות קרובות
adm_dashboard_pending_stat=אישורים ממתינים
adm_dashboard_h2=פגישות קרובות
adm_dashboard_no_upcoming=אין פגישות קרובות.

# ---- Meeting types list ----
adm_meetingTypes_h1=סוגי פגישות
adm_meetingTypes_badge_secret=סודי
adm_meetingTypes_badge_inactive=לא פעיל
adm_meetingTypes_badge_approval=אישור
adm_meetingTypes_min_notice=התראה מינימלית
adm_meetingTypes_horizon=אופק
adm_meetingTypes_days=ימים
adm_meetingTypes_slot_interval=מרווח זמן
adm_meetingTypes_min=דק'
adm_meetingTypes_buffer=מרווח
adm_meetingTypes_copy_link_aria=העתק קישור הזמנה
adm_meetingTypes_btn_edit=ערוך
adm_meetingTypes_btn_deactivate=השבת
adm_meetingTypes_btn_activate=הפעל
adm_meetingTypes_btn_delete=מחק
adm_meetingTypes_create_h2=צור סוג פגישה
adm_meetingTypes_section_basics=יסודות
adm_meetingTypes_label_name=שם
adm_meetingTypes_label_slug=Slug
adm_meetingTypes_slug_hint=(ריק = אוטומטי מהשם)
adm_meetingTypes_label_secret=סודי (לא בעמוד הציבורי)
adm_meetingTypes_label_approval=דורש אישור (השאר בהמתנה)
adm_meetingTypes_section_duration=משך
adm_meetingTypes_label_duration=משך (דקות)
adm_meetingTypes_label_slot_interval=מרווח זמן (דקות, ריק = ברצף)
adm_meetingTypes_label_buffer_before=מרווח לפני (דקות)
adm_meetingTypes_label_buffer_after=מרווח אחרי (דקות)
adm_meetingTypes_section_location=מיקום
adm_meetingTypes_location_hint=בחר היכן הפגישה מתקיימת. Google Meet מפיק קישור לאחר ההזמנה (דורש חשבון Google מחובר).
adm_meetingTypes_label_location_detail=פרטי מיקום (טלפון / כתובת / מותאם אישית; מתעלמים ממנו עבור Google Meet)
adm_meetingTypes_section_limits=מגבלות תזמון
adm_meetingTypes_label_min_notice=התראה מינימלית (דקות)
adm_meetingTypes_label_horizon=אופק הזמנות (ימים)
adm_meetingTypes_section_working_hours=שעות עבודה
adm_meetingTypes_working_hours_hint=זמנים שבועיים לסוג פגישה זה. השאר ריק = השתמש בברירת המחדל הגלובלית; מילוי = דרוס עבור סוג זה.
adm_meetingTypes_to=עד
adm_meetingTypes_section_date_override=חריגת תאריך
adm_meetingTypes_date_override_hint=אופציונלי. חריגה מחליפה את הזמנים הרגילים בתאריך זה. השאר ריק = יום חופשי.
adm_meetingTypes_label_date=תאריך
adm_meetingTypes_windows_legend=חלונות הזמנה (השאר את כולם ריקים = יום חופשי)
adm_meetingTypes_window_1=חלון 1
adm_meetingTypes_window_2=חלון 2
adm_meetingTypes_window_3=חלון 3
adm_meetingTypes_btn_create=צור
adm_meetingTypes_toast_copied=הקישור הועתק

# ---- Meeting type detail ----
adm_detail_back=← כל סוגי הפגישות
adm_detail_section_basics=יסודות
adm_detail_label_name=שם
adm_detail_label_slug=Slug
adm_detail_slug_hint=(ריק = אוטומטי מהשם)
adm_detail_label_duration=משך (דקות)
adm_detail_label_buffer_before=מרווח לפני (דקות)
adm_detail_label_buffer_after=מרווח אחרי (דקות)
adm_detail_label_slot_interval=מרווח זמן (דקות, ריק = ברצף)
adm_detail_label_min_notice=התראה מינימלית (דקות)
adm_detail_label_horizon=אופק הזמנות (ימים)
adm_detail_label_location=מיקום
adm_detail_label_location_detail=פרטי מיקום (טלפון / כתובת / מותאם אישית; מתעלמים ממנו עבור Google Meet)
adm_detail_label_secret=סודי (לא בעמוד הציבורי)
adm_detail_label_approval=דורש אישור
adm_detail_btn_save=שמור שינויים
adm_detail_section_fields=שדות הזמנה
adm_detail_fields_hint=שדות אלה נדרשים רק עבור סוג פגישה זה, בנוסף לשם ולאימייל.
adm_detail_badge_required=נדרש
adm_detail_label_field_label=תווית
adm_detail_label_field_key=מפתח שדה
adm_detail_label_field_type=סוג
adm_detail_label_field_required=נדרש
adm_detail_label_field_position=מיקום
adm_detail_btn_add_field=הוסף שדה
adm_detail_section_working_hours=שעות עבודה
adm_detail_working_hours_hint=זמנים שבועיים לסוג פגישה זה. אם יום מוגדר, הוא דורס את שעות ברירת המחדל הגלובליות.
adm_detail_to=עד
adm_detail_frame_add=+ טווח זמן
adm_detail_copy_all=העתק לכל הימים
adm_detail_copy_weekdays=העתק לימי חול
adm_detail_btn_save_hours=שמור שעות עבודה
adm_detail_section_overrides=חריגות תאריך
adm_detail_overrides_hint=חריגה מחליפה את הזמנים הרגילים בתאריך זה עבור סוג פגישה זה. השאר חלונות ריקים = יום חופשי.
adm_detail_badge_day_off=יום חופשי
adm_detail_label_date=תאריך
adm_detail_windows_legend=חלונות הזמנה (השאר את כולם ריקים = יום חופשי)
adm_detail_window_1=חלון 1
adm_detail_window_2=חלון 2
adm_detail_window_3=חלון 3
adm_detail_btn_save_override=שמור חריגה
adm_detail_btn_delete=מחק
adm_detail_remove_frame_aria=הסר טווח זמן

# ---- Availability ----
adm_availability_h1=זמינות (שעות עבודה)
adm_availability_hint=לוח הזמנים השבועי שלך כברירת מחדל. כל יום יכול לכלול מספר טווחים. כפתורי ההעתקה משכפלים יום על פני כל השבוע.
adm_availability_frame_add=+ טווח זמן
adm_availability_copy_all=העתק לכל הימים
adm_availability_copy_weekdays=העתק לימי חול
adm_availability_to=עד
adm_availability_remove_frame_aria=הסר טווח זמן
adm_availability_btn_save=שמור לוח זמנים

# ---- Date overrides ----
adm_dateOverrides_h1=חריגות תאריך
adm_dateOverrides_hint=חריגה מחליפה את שעות העבודה הרגילות בתאריך זה. הוסף חלונות = זמנים ניתנים להזמנה בלבד; כל החלונות ריקים = יום חופשי.
adm_dateOverrides_badge_day_off=יום חופשי
adm_dateOverrides_global=גלובלי
adm_dateOverrides_btn_delete=מחק
adm_dateOverrides_add_h2=הוסף חריגה
adm_dateOverrides_label_date=תאריך
adm_dateOverrides_label_applies_to=חל על
adm_dateOverrides_option_all_global=הכול (גלובלי)
adm_dateOverrides_windows_legend=חלונות הזמנה (השאר את כולם ריקים = יום חופשי)
adm_dateOverrides_window_1=חלון 1
adm_dateOverrides_window_2=חלון 2
adm_dateOverrides_window_3=חלון 3
adm_dateOverrides_btn_save=שמור חריגה
adm_dateOverrides_to=עד

# ---- Booking fields ----
adm_bookingFields_h1=שדות הזמנה כברירת מחדל
adm_bookingFields_hint=שם ואימייל תמיד נדרשים. שדות ברירת מחדל אלה חלים על כל סוג פגישה ללא שדות משלו.
adm_bookingFields_badge_required=נדרש
adm_bookingFields_position_prefix=מיקום
adm_bookingFields_btn_delete=מחק
adm_bookingFields_add_h2=הוסף שדה
adm_bookingFields_label_label=תווית
adm_bookingFields_label_key=מפתח שדה
adm_bookingFields_label_type=סוג
adm_bookingFields_label_required=נדרש
adm_bookingFields_label_position=מיקום
adm_bookingFields_btn_add=הוסף שדה

# ---- Pending approvals ----
adm_pending_h1=אישורים ממתינים
adm_pending_empty=אין בקשות הממתינות לאישור.
adm_pending_btn_approve=אשר
adm_pending_btn_decline=דחה

# ---- Settings ----
adm_settings_h1=הגדרות בעלים
adm_settings_language=שפה
adm_settings_label_name=שם
adm_settings_label_email=אימייל
adm_settings_label_timezone=אזור זמן
adm_settings_label_notifications=קבל התראות אימייל על הזמנות
adm_settings_btn_save=שמור
adm_settings_reminder_lead_prefix=תזכורת:
adm_settings_reminder_lead_suffix=דקות לפני הפגישה
adm_settings_reminder_lead_env=(מוגדר דרך משתנה הסביבה REMINDER_LEAD_MINUTES)

# ---- Google Calendar ----
google_h1=Google Calendar
google_hint=חבר חשבונות Google כדי ש-calit תוכל לקרוא את הזמינות שלך וליצור פגישות.
google_btn_connect=חבר חשבון Google
google_load_error=לא ניתן היה להגיע ל-Google עבור חשבון אחד או יותר. אנא חבר מחדש את החשבון המסומן.
google_no_accounts=עדיין לא חוברו חשבונות Google.
google_badge_needs_reconnect=נדרש חיבור מחדש
google_badge_load_failed=הטעינה נכשלה — טען מחדש
google_btn_disconnect=נתק
google_table_calendar=יומן
google_table_read_busy=קרא זמינות
google_table_write_events=צור פגישות כאן
google_reconnect_hint=חבר מחדש כדי לערוך. הבחירה השמורה שלך מוצגת.
google_btn_save_selection=שמור בחירת יומנים
google_disconnect_confirm=לנתק חשבון Google זה? בחירת היומנים תוסר.
google_disabled_title=בחר תחילה יעד כתיבה אחר בחשבון אחר

# ---- Users ----
users_h1=משתמשים
users_create_h2=צור משתמש
users_label_username=שם משתמש
users_label_temp_password=סיסמה זמנית
users_btn_create=צור משתמש
users_th_username=שם משתמש
users_th_admin=מנהל
users_th_status=סטטוס
users_th_actions=פעולות
users_yes=כן
users_no=לא
users_active=פעיל
users_locked=נעול
users_btn_revoke_admin=בטל הרשאת מנהל
users_btn_grant_admin=הענק הרשאת מנהל
users_btn_lock=נעל
users_btn_unlock=שחרר נעילה

# ---- Me setup wizard ----
mesetup_h1=השלם הקמה
mesetup_subtitle=כמה פרטים לפני שמתחילים.
mesetup_h2_password=בחר סיסמה
mesetup_label_new_password=סיסמה חדשה
mesetup_h2_details=הפרטים שלך
mesetup_label_name=שם
mesetup_label_email=אימייל
mesetup_label_timezone=אזור זמן
mesetup_btn_finish=סיים
mesetup_choose_new_password=אנא בחר סיסמה חדשה.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=AdminMessagesHebrewTest`
Expected: PASS — `adm_nav_dashboard()` for `he` returns `"לוח בקרה"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/messages/adm_he.properties \
        src/test/java/site/asm0dey/calit/i18n/AdminMessagesHebrewTest.java
git commit -m "feat(i18n): add Hebrew (he) admin message bundle"
```

---

### Task 3: Request-time Hebrew locale resolution test

Confirms the cookie → Accept-Language resolution chain selects Hebrew end-to-end (filter + bundle + template). Pure test addition — no production code changes; the machinery already works for any discovered locale.

**Files:**
- Modify (test): `src/test/java/site/asm0dey/calit/i18n/LocaleResolutionTest.java`

**Interfaces:**
- Consumes: the test-only probe at `src/test/java/site/asm0dey/calit/i18n/I18nProbeResource.java` (`GET /__i18n_probe`), whose template `src/test/resources/templates/i18nProbe.html` renders exactly `lang={lang}|cancel={msg:common_cancel}`. Hebrew `common_cancel` = `ביטול` (from Task 1).
- Produces: nothing for later tasks.

- [ ] **Step 1: Add the failing Hebrew resolution tests**

Append these two methods inside `LocaleResolutionTest` (before the closing brace):

```java
    @Test
    void publicCookieHeRendersHebrew() {
        given().cookie("calit_lang", "he").when().get("/__i18n_probe")
                .then().statusCode(200)
                .body(containsString("lang=he")).body(containsString("cancel=ביטול"));
    }

    @Test
    void publicAcceptLanguageHe() {
        given().header("Accept-Language", "he").when().get("/__i18n_probe")
                .then().statusCode(200)
                .body(containsString("cancel=ביטול")).body(containsString("lang=he"));
    }
```

- [ ] **Step 2: Run the tests**

Run: `mvn test -Dtest=LocaleResolutionTest`
Expected: PASS — Task 1's `msg_he.properties` makes `he` resolvable, so the probe renders `lang=he|cancel=ביטול`. (If Task 1 is not yet merged in this worktree these fail with English `cancel=Cancel`; run after Task 1.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/site/asm0dey/calit/i18n/LocaleResolutionTest.java
git commit -m "test(i18n): assert Hebrew locale resolution via cookie and Accept-Language"
```

---

### Task 4: RTL `dir` attribute on web layouts (`base.html`, `adminBase.html`)

The single change that makes the whole page mirror: a `dir` attribute on `<html>`, derived from the already-present `{lang}` data value. No Java — `{lang}` is set by `LocaleTemplateInitializer` for every web request and equals `"he"` for Hebrew.

**Files:**
- Modify: `src/main/resources/templates/base.html:3`
- Modify: `src/main/resources/templates/adminBase.html:6`
- Test: `src/test/java/site/asm0dey/calit/web/RtlDirMarkerTest.java`

**Interfaces:**
- Consumes: the `{lang}` template data value (BCP-47 tag for web: `"en"`, `"de"`, `"he"`), set by `src/main/java/site/asm0dey/calit/i18n/LocaleTemplateInitializer.java:53`.
- Produces: served HTML whose root element is `<html lang="he" dir="rtl">` for Hebrew and `dir="ltr"` for every other language.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/RtlDirMarkerTest.java`. It reuses the public booking page (renders `base.html`) with a seeded owner, exactly like `LayoutLocaleMarkerTest`:

```java
package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class RtlDirMarkerTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("rtltest");
        if (owner == null) {
            owner = AppUser.create("rtltest", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "rtl-intro");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "RTL Test Owner";
        s.ownerEmail = "rtltest@example.com";
        s.timezone = "Asia/Jerusalem";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "RTL Test Intro"; t.slug = "rtl-intro"; t.durationMinutes = 30;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    @Test
    void hebrewPageIsRtl() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        given().cookie("calit_lang", "he").when().get("/rtltest/rtl-intro")
            .then().statusCode(200)
            .body(containsString("lang=\"he\""))
            .body(containsString("dir=\"rtl\""));
    }

    @Test
    void englishPageIsLtr() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        given().cookie("calit_lang", "en").when().get("/rtltest/rtl-intro")
            .then().statusCode(200)
            .body(containsString("lang=\"en\""))
            .body(containsString("dir=\"ltr\""));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=RtlDirMarkerTest`
Expected: FAIL — `base.html` currently emits `<html lang="he">` with no `dir` attribute, so `dir="rtl"` is absent.

- [ ] **Step 3: Add `dir` to `base.html`**

In `src/main/resources/templates/base.html`, change line 3 from:

```html
<html lang="{lang}">
```

to:

```html
{! ponytail: he is calit's only RTL locale; add `|| lang == 'ar'` here + in adminBase/email heads when Arabic lands. !}
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
```

- [ ] **Step 4: Add `dir` to `adminBase.html`**

In `src/main/resources/templates/adminBase.html`, change line 6 from:

```html
<html lang="{lang}">
```

to:

```html
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=RtlDirMarkerTest`
Expected: PASS — Hebrew page now serves `dir="rtl"`, English serves `dir="ltr"`.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/base.html \
        src/main/resources/templates/adminBase.html \
        src/test/java/site/asm0dey/calit/web/RtlDirMarkerTest.java
git commit -m "feat(i18n): set <html dir> from page language for RTL layouts"
```

---

### Task 5: RTL `dir` in email templates + Hebrew email test

Emails set `{lang}` per-recipient in `EmailService` (an invitee may be Hebrew while the owner is English), so each email template's `<html>` gets the same language-derived `dir`. The 8 templates take the identical one-line edit.

**Files:**
- Modify (8 files): `src/main/resources/templates/email/confirmation.html`, `cancellation.html`, `declined.html`, `reminder.html`, `requested.html`, `reschedule.html`, `password-reset.html`, `google-disconnected.html` (each line 2: `<html lang="{lang}">`)
- Test: `src/test/java/site/asm0dey/calit/email/EmailLocaleHebrewTest.java`

**Interfaces:**
- Consumes: `{lang}` set in each email render via `.data("lang", locale.getLanguage())` in `src/main/java/site/asm0dey/calit/email/EmailService.java` (e.g. `:224`); for Hebrew this is `"he"`. The injectable `AppMessageResolver` (`messages.forTag("he")`) returns Hebrew `AppMessages`.
- Produces: Hebrew emails render `<html lang="he" dir="rtl">`; Hebrew subjects resolve non-blank and differ from English.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/EmailLocaleHebrewTest.java`:

```java
package site.asm0dey.calit.email;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.i18n.AppMessageResolver;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EmailLocaleHebrewTest {

    @Inject
    AppMessageResolver messages;

    @Test
    void hebrewSubjectResolvesAndDiffersFromEnglish() {
        String he = messages.forTag("he").email_confirmed_subject("X");
        String en = messages.forTag("en").email_confirmed_subject("X");
        assertFalse(he.isBlank(), "Hebrew confirmation subject must not be blank");
        assertNotEquals(en, he, "Hebrew subject must differ from English");
        // Placeholder is preserved verbatim
        assertTrue(he.contains("X"), "Subject must keep the {meetingTypeName} value");
    }
}
```

- [ ] **Step 2: Run the test to verify it passes (sanity) — subjects only**

Run: `mvn test -Dtest=EmailLocaleHebrewTest`
Expected: PASS already (Task 1 added the Hebrew subjects). This locks in the subject behaviour; the `dir` change below is verified by `RtlDirMarkerTest` patterns plus manual render in Task 6. (Email `dir` has no dedicated RestAssured assertion because emails are not served over HTTP; the marker is the rendered HTML string, validated visually in Task 7.)

- [ ] **Step 3: Add `dir` to each email template's `<html>` element**

In all 8 files under `src/main/resources/templates/email/`, change the line:

```html
<html lang="{lang}">
```

to:

```html
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
```

The files (verify each is changed): `confirmation.html`, `cancellation.html`, `declined.html`, `reminder.html`, `requested.html`, `reschedule.html`, `password-reset.html`, `google-disconnected.html`.

Confirm with:

```bash
grep -rL 'dir="{#if lang' src/main/resources/templates/email/
```

Expected: prints nothing (every email template now has the `dir` attribute).

- [ ] **Step 4: Run the full i18n + email suites**

Run: `mvn test -Dtest=EmailLocaleTest,EmailLocaleHebrewTest`
Expected: PASS — existing German/English email tests still green; Hebrew subject test green.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/email/ \
        src/test/java/site/asm0dey/calit/email/EmailLocaleHebrewTest.java
git commit -m "feat(i18n): set <html dir> in email templates for RTL recipients"
```

---

### Task 6: RTL CSS audit — convert physical-direction utilities to logical

daisyUI 5 + Tailwind v4 mirror automatically under `dir="rtl"` for logical properties, but any hardcoded physical-direction utility (`text-left`, `right-3`, `ml-2`, …) stays pinned to one side and breaks the mirror. This sweep finds and converts them.

**Files:**
- Modify: template files under `src/main/resources/templates/` flagged by the grep below (known: `footer.html`, `base.html`)
- Build artifact: `/calit.css` (regenerated, gitignored)

**Interfaces:**
- Consumes: nothing (CSS-only).
- Produces: a layout that mirrors fully under `dir="rtl"`.

**Conversion table** (apply to every grep hit — Tailwind v4 logical equivalents):

| Physical | Logical |
|----------|---------|
| `text-left` | `text-start` |
| `text-right` | `text-end` |
| `ml-*` | `ms-*` |
| `mr-*` | `me-*` |
| `pl-*` | `ps-*` |
| `pr-*` | `pe-*` |
| `left-*` | `start-*` |
| `right-*` | `end-*` |
| `rounded-l*` | `rounded-s*` |
| `rounded-r*` | `rounded-e*` |
| `border-l*` | `border-s*` |
| `border-r*` | `border-e*` |

Leave already-logical utilities untouched (`ms-auto`, `ps-*`, `justify-start`/`justify-end` — flex justification already follows writing direction).

- [ ] **Step 1: Find every physical-direction utility in templates**

Run:

```bash
grep -rnE 'text-left|text-right|[^a-z-](ml-|mr-|pl-|pr-)[0-9]|[^a-z-](left|right)-[0-9]|rounded-(l|r|tl|tr|bl|br)|border-(l|r)(-|[^a-z])' src/main/resources/templates/
```

Expected known hits to convert:
- `src/main/resources/templates/footer.html` — `text-left` (the dropdown `<ul>` class) → `text-start`
- `src/main/resources/templates/base.html:25` — `fixed top-3 right-3` (theme toggle) → `fixed top-3 end-3`

Review the full grep output; convert each genuine hit per the table. (SVG `path d="..."` coordinates and inline `<script>` are not Tailwind classes — ignore any hits inside those.)

- [ ] **Step 2: Apply the conversions**

In `src/main/resources/templates/footer.html`, in the dropdown `<ul>` class list change `text-left` to `text-start`.

In `src/main/resources/templates/base.html:25`, change:

```html
<div class="fixed top-3 right-3 z-50">
```

to:

```html
<div class="fixed top-3 end-3 z-50">
```

Apply the same table to any other hits Step 1 surfaced.

- [ ] **Step 3: Re-run the grep to confirm none remain**

Run the Step 1 grep again.
Expected: no Tailwind-class hits remain (only SVG/script false positives, if any).

- [ ] **Step 4: Rebuild CSS**

Run: `bun run css:build`
Expected: `/calit.css` regenerates without error. (`/calit.css` is gitignored — not committed.)

- [ ] **Step 5: Run the template test suite to confirm nothing broke**

Run: `mvn test -Dtest=RtlDirMarkerTest,LayoutLocaleMarkerTest`
Expected: PASS — class changes are cosmetic; markers still present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/footer.html \
        src/main/resources/templates/base.html
git commit -m "fix(i18n): use logical-direction CSS utilities so RTL mirrors correctly"
```

---

### Task 7: Visual verification + docs

Confirm Hebrew renders right-to-left in a real browser, then land the docs (CLAUDE.md mandates docs on the `docs-site` branch as part of "done") and a changelog entry.

**Files:**
- Modify (on `docs-site` branch): the configuration/i18n docs page that lists supported languages, and `docs-site/src/content/docs/releases/changelog.md`

**Interfaces:**
- Consumes: a running dev server.
- Produces: docs reflecting Hebrew + RTL support.

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: PASS — entire suite green (Docker running).

- [ ] **Step 2: Visually verify RTL in the browser**

Start the dev server (`bun run css:build` first if `/calit.css` is missing):

```bash
bun run css:build
mvn quarkus:dev
```

In a browser, open the landing page, use the footer language switcher to select **עברית**, and confirm:
- The page flips right-to-left (nav, footer, text alignment).
- The theme toggle moves to the top-left corner.
- The admin UI (`/me`) sidebar mirrors to the right side.
- No element is visibly clipped or pinned to the wrong edge.

Fix any stray physical-direction utility found this way using the Task 6 conversion table, rebuild CSS, and re-check.

- [ ] **Step 3: Update docs on the `docs-site` branch**

Switch to the docs branch in a separate worktree or checkout:

```bash
git worktree add ../calit-docs docs-site
```

Find the languages/i18n doc page:

```bash
grep -rl 'German\|Deutsch\|msg_de' ../calit-docs/docs-site/src/content/docs/
```

In that page, add Hebrew (`he`, עברית) to the list of supported languages and note that Hebrew renders right-to-left automatically. If the docs explain how to add a language, confirm the "drop two `.properties` files" instruction still holds (it does) and mention that an RTL language needs no extra steps — the `dir` attribute is language-derived.

- [ ] **Step 4: Add a changelog entry**

At the top of `../calit-docs/docs-site/src/content/docs/releases/changelog.md`, add a new section noting: "Added Hebrew (he) translation with full right-to-left (RTL) layout support for web pages and emails."

- [ ] **Step 5: Commit docs**

```bash
cd ../calit-docs
git add docs-site/src/content/docs/
git commit -m "docs: document Hebrew (he) + RTL support"
git push origin docs-site
```

- [ ] **Step 6: Final verification on the feature branch**

Back on the feature branch, confirm a clean full run:

Run: `mvn test`
Expected: PASS. Feature complete.

---

## Self-Review

**Spec coverage:**
- Hebrew UI strings → Task 1 (`msg_he.properties`, full key set).
- Hebrew admin strings → Task 2 (`adm_he.properties`, full key set).
- Auto-discovery / language switcher shows Hebrew → Task 1 (verified by `AppLocalesDiscoveryTest`).
- Locale resolution selects Hebrew → Task 3.
- RTL on web → Task 4 (`dir` on `base.html` + `adminBase.html`).
- RTL on email → Task 5 (8 templates).
- RTL CSS correctness → Task 6 (logical-utility sweep, user-requested audit).
- Docs + changelog → Task 7.

**Placeholder scan:** No `TODO`/`TBD`/"add error handling"; every `.properties` value and test body is concrete. Task 6's "apply to other hits" is bounded by an exact grep + a complete conversion table (the algorithm is fully specified), with the two known hits given as explicit edits.

**Type/name consistency:** `AppMessageResolver#forTag`/`forLocale` and `AdminMessageResolver#forLocale` match the real signatures (`AppMessageResolver.java:20,28`, `AdminMessageResolver.java:20`). The `dir` expression `{#if lang == 'he'}rtl{#else}ltr{/if}` is identical across `base.html`, `adminBase.html`, and all 8 email templates. `{lang}` is `"he"` for Hebrew in both web (`toLanguageTag()`) and email (`getLanguage()`) paths. Test class/method names are unique and self-consistent.
