package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.i18n.AppLocales;

/**
 * Verifies locale-aware email rendering:
 *  1. AppMessageResolver accessor returns different (non-blank) German subjects.
 *  2. A booking with locale="de" produces a German date string in the email body.
 */
@QuarkusTest
class EmailLocaleTest {
    private static final String OWNER_EMAIL = "owner-locale@example.com";
    private static final String INVITEE_EMAIL = "invitee-locale@example.com";
    @Inject
    site.asm0dey.calit.i18n.AppMessageResolver messages;
    @Inject
    EmailService emailService;
    @Inject
    MockMailbox mailbox;
    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        QuarkusTransaction
            .requiringNew()
            .run(() -> Booking.deleteAll());
    }

    // ---- 1. Subject accessor: German differs from English ----
    @Test
    void germanSubjectResolves() {
        String deSubj = messages.forTag("de").email_confirmed_subject("X");
        String enSubj = messages.forTag("en").email_confirmed_subject("X");
        assertThat(deSubj).as("German confirmation subject must not be blank").isNotBlank();
        assertThat(deSubj).as("German subject must differ from English").isNotEqualTo(enSubj);
    }

    @Test
    void germanPasswordResetSubjectNonBlankAndDiffersFromEnglish() {
        String enSubj = messages.forTag("en").email_password_reset_subject();
        String deSubj = messages.forTag("de").email_password_reset_subject();
        assertThat(deSubj).as("German password-reset subject must not be blank").isNotBlank();
        assertThat(deSubj).as("German password-reset subject must differ from English").isNotEqualTo(enSubj);
    }

    @Test
    void germanGoogleDisconnectedSubjectNonBlankAndDiffersFromEnglish() {
        String enSubj = messages.forTag("en").email_google_disconnected_subject();
        String deSubj = messages.forTag("de").email_google_disconnected_subject();
        assertThat(deSubj).as("German Google-disconnected subject must not be blank").isNotBlank();
        assertThat(deSubj).as("German Google-disconnected subject must differ from English").isNotEqualTo(enSubj);
    }

    // ---- 1b. h12 pattern is a valid, renderable DateTimeFormatter pattern in every locale ----
    @Test
    void h12DatetimePatternResolvesAndFormatsForEveryLocale() {
        var instant = Instant.parse("2026-06-08T13:00:00Z");
        var zone = ZoneId.of("UTC");
        var zoned = instant.atZone(zone);

        for (String tag : List.of("en", "de", "he")) {
            Locale locale = AppLocales.pick(tag);
            String pattern = messages.forTag(tag).email_datetime_pattern_h12();
            assertThat(pattern).as("h12 pattern for '" + tag + "' must not be blank").isNotBlank();

            DateTimeFormatter formatter;
            try {
                formatter = DateTimeFormatter.ofPattern(pattern, locale);
            } catch (IllegalArgumentException e) {
                throw new AssertionError(
                        "h12 pattern for '" + tag + "' is not a valid DateTimeFormatter pattern: " + pattern,
                        e
                );
            }

            String rendered;
            try {
                rendered = formatter.format(zoned);
            } catch (RuntimeException e) {
                throw new AssertionError("h12 pattern for '" + tag + "' failed to format an instant: " + pattern, e);
            }
            assertThat(rendered).as("h12 rendering for '" + tag + "' must not be blank; pattern: " + pattern).isNotBlank();
        }
    }

    // ---- 2. End-to-end: de booking → German date string in body ----
    @Test
    void germanBookingConfirmationContainsGermanDate() {
        // invitee fallback active
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        // Seed a booking with locale="de"; start on a known date so we can predict the German weekday.
        // 2026-06-08 is a Monday → "Montag" in German.
        long bookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                if (s == null) {
                    s = new OwnerSettings();
                    s.ownerId = 1L;
                }
                s.ownerName = "Owner";
                s.ownerEmail = OWNER_EMAIL;
                s.timezone = "Europe/Berlin";
                s.ownerNotificationsEnabled = true;
                // owner locale stays English
                s.locale = "en";
                s.persist();

                MeetingType t = new MeetingType();
                t.ownerId = 1L;
                t.name = "DE Call";
                t.slug = "de-call-" + System.nanoTime();
                t.durationMinutes = 30;
                t.locationType = LocationType.PHONE;
                t.locationDetail = "+49 30 12345";
                t.persist();
                // Monday
                var start = Instant.parse("2026-06-08T09:00:00Z");
                Booking b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Hans Müller";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.manageToken = UUID.randomUUID().toString();
                b.createdAt = Instant.now();
                b.answers = Map.of();
                // invitee's locale is German
                b.locale = "de";
                b.persist();
                return b.id;
            });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertThat(toInvitee).as("invitee must receive confirmation email").hasSize(1);

        Mail inviteeMail = toInvitee.getFirst();
        String html = inviteeMail.getHtml();
        // German weekday for 2026-06-08 (Monday) = "Montag"
        assertThat(html.contains("Montag") || html.contains("um"))
            .as("German date in invitee body must contain 'Montag' (Monday) or German 'um' connector; got: " + html)
            .isTrue();
        // Subject must be the German confirmation subject
        String subject = inviteeMail.getSubject();
        assertThat(subject.contains("bestätigt") || subject.toLowerCase().contains("buchung"))
            .as("German invitee subject must be in German; got: " + subject)
            .isTrue();
    }

    // ---- 3. Owner-copy uses owner locale (English when owner.locale = "en") ----
    @Test
    void ownerCopyUsesOwnerLocale() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                if (s == null) {
                    s = new OwnerSettings();
                    s.ownerId = 1L;
                }
                s.ownerName = "Owner";
                s.ownerEmail = OWNER_EMAIL;
                s.timezone = "Europe/London";
                s.ownerNotificationsEnabled = true;
                // English owner
                s.locale = "en";
                s.persist();

                MeetingType t = new MeetingType();
                t.ownerId = 1L;
                t.name = "Owner Locale Call";
                t.slug = "owner-locale-" + System.nanoTime();
                t.durationMinutes = 30;
                t.locationType = LocationType.PHONE;
                t.locationDetail = "+44 1234";
                t.persist();
                // Monday
                var start = Instant.parse("2026-06-08T09:00:00Z");
                Booking b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Anna";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.manageToken = UUID.randomUUID().toString();
                b.createdAt = Instant.now();
                b.answers = Map.of();
                // invitee German, owner English
                b.locale = "de";
                b.persist();
                return b.id;
            });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertThat(toOwner).as("owner must receive confirmation email").hasSize(1);

        Mail ownerMail = toOwner.getFirst();
        // English owner → English date pattern includes "at" not "um"
        String html = ownerMail.getHtml();
        assertThat(html.contains("Monday") || html.contains("at"))
            .as("English owner email must use English date; got: " + html)
            .isTrue();
    }

    // ---- 4. German invitee email body contains translated body strings ----
    @Test
    void germanInviteeBodyContainsGermanBodyStrings() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                if (s == null) {
                    s = new OwnerSettings();
                    s.ownerId = 1L;
                }
                s.ownerName = "Owner";
                s.ownerEmail = OWNER_EMAIL;
                s.timezone = "Europe/Berlin";
                s.ownerNotificationsEnabled = true;
                s.locale = "en";
                s.persist();

                MeetingType t = new MeetingType();
                t.ownerId = 1L;
                t.name = "DE Body Test";
                t.slug = "de-body-" + System.nanoTime();
                t.durationMinutes = 45;
                t.locationType = LocationType.PHONE;
                t.locationDetail = "+49 30 999";
                t.persist();

                var start = Instant.parse("2026-06-08T10:00:00Z");
                Booking b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Greta Haber";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(45, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.manageToken = java.util.UUID.randomUUID().toString();
                b.createdAt = Instant.now();
                b.answers = Map.of();
                // German invitee
                b.locale = "de";
                b.persist();
                return b.id;
            });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertThat(toInvitee).as("invitee must receive one confirmation email").hasSize(1);
        String html = toInvitee.getFirst().getHtml();
        // German body strings added by task 9d — both must be present (body-specific, not just subject)
        assertThat(html).as("German confirmation body must contain greeting 'Hallo'; got: " + html).contains("Hallo");
        assertThat(html).as("German confirmation body must contain 'Minuten' (duration); got: " + html).contains(
                "Minuten"
        );
    }

    // ---- 5. English default locale email body contains English body strings ----
    @Test
    void englishDefaultBodyContainsEnglishBodyStrings() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                if (s == null) {
                    s = new OwnerSettings();
                    s.ownerId = 1L;
                }
                s.ownerName = "Owner";
                s.ownerEmail = OWNER_EMAIL;
                s.timezone = "Europe/London";
                s.ownerNotificationsEnabled = true;
                s.locale = "en";
                s.persist();

                MeetingType t = new MeetingType();
                t.ownerId = 1L;
                t.name = "EN Body Test";
                t.slug = "en-body-" + System.nanoTime();
                t.durationMinutes = 30;
                t.locationType = LocationType.PHONE;
                t.locationDetail = "+44 999";
                t.persist();

                var start = Instant.parse("2026-06-08T14:00:00Z");
                Booking b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Alice Smith";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.manageToken = java.util.UUID.randomUUID().toString();
                b.createdAt = Instant.now();
                b.answers = Map.of();
                // English invitee
                b.locale = "en";
                b.persist();
                return b.id;
            });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertThat(toInvitee).as("invitee must receive one confirmation email").hasSize(1);
        String html = toInvitee.getFirst().getHtml();
        // English body strings from task 9d — both must be present (body-specific)
        assertThat(html).as("English confirmation body must contain greeting 'Hi '; got: " + html).contains("Hi ");
        assertThat(html).as("English confirmation body must contain 'minutes' (duration); got: " + html).contains(
                "minutes"
        );
    }

    // ---- 7. German email has <html lang="de" ----
    @Test
    void germanEmailHasCorrectHtmlLang() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                if (s == null) {
                    s = new OwnerSettings();
                    s.ownerId = 1L;
                }
                s.ownerName = "Owner";
                s.ownerEmail = OWNER_EMAIL;
                s.timezone = "Europe/Berlin";
                s.ownerNotificationsEnabled = true;
                // English owner — should get lang="en"
                s.locale = "en";
                s.persist();

                MeetingType t = new MeetingType();
                t.ownerId = 1L;
                t.name = "Lang Test";
                t.slug = "lang-test-" + System.nanoTime();
                t.durationMinutes = 30;
                t.locationType = LocationType.PHONE;
                t.locationDetail = "+49 30 1111";
                t.persist();

                var start = Instant.parse("2026-06-08T09:00:00Z");
                Booking b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Luisa Meier";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.manageToken = java.util.UUID.randomUUID().toString();
                b.createdAt = Instant.now();
                b.answers = Map.of();
                // German invitee
                b.locale = "de";
                b.persist();
                return b.id;
            });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));
        // German invitee email must have lang="de"
        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertThat(toInvitee).as("invitee must receive confirmation email").hasSize(1);
        String inviteeHtml = toInvitee.getFirst().getHtml();
        assertThat(inviteeHtml)
            .as("German invitee email must have <html lang=\"de\">; got: " + inviteeHtml)
            .contains("lang=\"de\"");
        // English owner email must have lang="en"
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertThat(toOwner).as("owner must receive confirmation email").hasSize(1);
        String ownerHtml = toOwner.getFirst().getHtml();
        assertThat(ownerHtml)
            .as("English owner email must have <html lang=\"en\">; got: " + ownerHtml)
            .contains("lang=\"en\"");
    }
}
