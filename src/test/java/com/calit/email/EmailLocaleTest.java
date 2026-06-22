package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingConfirmed;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Verifies locale-aware email rendering:
 *  1. Messages accessor returns different (non-blank) German subjects.
 *  2. A booking with locale="de" produces a German date string in the email body.
 */
@QuarkusTest
class EmailLocaleTest {

    private static final String OWNER_EMAIL = "owner-locale@example.com";
    private static final String INVITEE_EMAIL = "invitee-locale@example.com";

    @Inject
    com.calit.i18n.Messages messages;

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        QuarkusTransaction.requiringNew().run(() -> Booking.deleteAll());
    }

    // ---- 1. Subject accessor: German differs from English ----

    @Test
    void germanSubjectResolves() {
        String deSubj = messages.forTag("de").email_confirmed_subject("X");
        String enSubj = messages.forTag("en").email_confirmed_subject("X");
        assertFalse(deSubj.isBlank(), "German confirmation subject must not be blank");
        assertNotEquals(enSubj, deSubj, "German subject must differ from English");
    }

    @Test
    void germanPasswordResetSubjectNonBlankAndDiffersFromEnglish() {
        String enSubj = messages.forTag("en").email_password_reset_subject();
        String deSubj = messages.forTag("de").email_password_reset_subject();
        assertFalse(deSubj.isBlank(), "German password-reset subject must not be blank");
        assertNotEquals(enSubj, deSubj, "German password-reset subject must differ from English");
    }

    @Test
    void germanGoogleDisconnectedSubjectNonBlankAndDiffersFromEnglish() {
        String enSubj = messages.forTag("en").email_google_disconnected_subject();
        String deSubj = messages.forTag("de").email_google_disconnected_subject();
        assertFalse(deSubj.isBlank(), "German Google-disconnected subject must not be blank");
        assertNotEquals(enSubj, deSubj, "German Google-disconnected subject must differ from English");
    }

    // ---- 2. End-to-end: de booking → German date string in body ----

    @Test
    void germanBookingConfirmationContainsGermanDate() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false); // invitee fallback active

        // Seed a booking with locale="de"; start on a known date so we can predict the German weekday.
        // 2026-06-08 is a Monday → "Montag" in German.
        long bookingId = QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Berlin";
            s.ownerNotificationsEnabled = true;
            s.locale = "en"; // owner locale stays English
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "DE Call";
            t.slug = "de-call-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+49 30 12345";
            t.persist();

            Instant start = Instant.parse("2026-06-08T09:00:00Z"); // Monday
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
            b.locale = "de"; // invitee's locale is German
            b.persist();
            return b.id;
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive confirmation email");

        Mail inviteeMail = toInvitee.get(0);
        String html = inviteeMail.getHtml();

        // German weekday for 2026-06-08 (Monday) = "Montag"
        assertTrue(html.contains("Montag") || html.contains("um"),
                "German date in invitee body must contain 'Montag' (Monday) or German 'um' connector; got: " + html);

        // Subject must be the German confirmation subject
        String subject = inviteeMail.getSubject();
        assertTrue(subject.contains("bestätigt") || subject.toLowerCase().contains("buchung"),
                "German invitee subject must be in German; got: " + subject);
    }

    // ---- 3. Owner-copy uses owner locale (English when owner.locale = "en") ----

    @Test
    void ownerCopyUsesOwnerLocale() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/London";
            s.ownerNotificationsEnabled = true;
            s.locale = "en"; // English owner
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Owner Locale Call";
            t.slug = "owner-locale-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+44 1234";
            t.persist();

            Instant start = Instant.parse("2026-06-08T09:00:00Z"); // Monday
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
            b.locale = "de"; // invitee German, owner English
            b.persist();
            return b.id;
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toOwner.size(), "owner must receive confirmation email");

        Mail ownerMail = toOwner.get(0);
        // English owner → English date pattern includes "at" not "um"
        String html = ownerMail.getHtml();
        assertTrue(html.contains("Monday") || html.contains("at"),
                "English owner email must use English date; got: " + html);
    }

    // ---- 4. German invitee email body contains translated body strings ----

    @Test
    void germanInviteeBodyContainsGermanBodyStrings() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction.requiringNew().call(() -> {
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

            Instant start = Instant.parse("2026-06-08T10:00:00Z");
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
            b.locale = "de"; // German invitee
            b.persist();
            return b.id;
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive one confirmation email");
        String html = toInvitee.get(0).getHtml();

        // German body strings added by task 9d — both must be present (body-specific, not just subject)
        assertTrue(html.contains("Hallo"),
                "German confirmation body must contain greeting 'Hallo'; got: " + html);
        assertTrue(html.contains("Minuten"),
                "German confirmation body must contain 'Minuten' (duration); got: " + html);
    }

    // ---- 5. English default locale email body contains English body strings ----

    @Test
    void englishDefaultBodyContainsEnglishBodyStrings() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction.requiringNew().call(() -> {
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

            Instant start = Instant.parse("2026-06-08T14:00:00Z");
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
            b.locale = "en"; // English invitee
            b.persist();
            return b.id;
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive one confirmation email");
        String html = toInvitee.get(0).getHtml();

        // English body strings from task 9d — both must be present (body-specific)
        assertTrue(html.contains("Hi "),
                "English confirmation body must contain greeting 'Hi '; got: " + html);
        assertTrue(html.contains("minutes"),
                "English confirmation body must contain 'minutes' (duration); got: " + html);
    }

    // ---- 6. German email footer uses localized role word, not raw "invitee"/"owner" ----

    @Test
    void germanEmailFooterUsesLocalizedRoleWord() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Berlin";
            s.ownerNotificationsEnabled = true;
            s.locale = "de"; // German owner
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Role Test";
            t.slug = "role-test-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+49 30 0000";
            t.persist();

            Instant start = Instant.parse("2026-06-08T09:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Karl Braun";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = java.util.UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.answers = Map.of();
            b.locale = "de"; // German invitee
            b.persist();
            return b.id;
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        // Invitee email: footer must say "Empfänger" (not raw "invitee")
        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive confirmation email");
        String inviteeHtml = toInvitee.get(0).getHtml();
        assertTrue(inviteeHtml.contains("Empfänger"),
                "German invitee footer must contain 'Empfänger'; got: " + inviteeHtml);
        assertFalse(inviteeHtml.contains(">invitee<") || inviteeHtml.contains(" invitee ") || inviteeHtml.contains(" invitee."),
                "German invitee footer must NOT contain raw 'invitee'; got: " + inviteeHtml);

        // Owner email: footer must say "Veranstalter" (not raw "owner")
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toOwner.size(), "owner must receive confirmation email");
        String ownerHtml = toOwner.get(0).getHtml();
        assertTrue(ownerHtml.contains("Veranstalter"),
                "German owner footer must contain 'Veranstalter'; got: " + ownerHtml);
        assertFalse(ownerHtml.contains(">owner<") || ownerHtml.contains(" owner ") || ownerHtml.contains(" owner."),
                "German owner footer must NOT contain raw 'owner'; got: " + ownerHtml);
    }

    // ---- 7. German email has <html lang="de" ----

    @Test
    void germanEmailHasCorrectHtmlLang() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        long bookingId = QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Berlin";
            s.ownerNotificationsEnabled = true;
            s.locale = "en"; // English owner — should get lang="en"
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Lang Test";
            t.slug = "lang-test-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+49 30 1111";
            t.persist();

            Instant start = Instant.parse("2026-06-08T09:00:00Z");
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
            b.locale = "de"; // German invitee
            b.persist();
            return b.id;
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        // German invitee email must have lang="de"
        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive confirmation email");
        String inviteeHtml = toInvitee.get(0).getHtml();
        assertTrue(inviteeHtml.contains("lang=\"de\""),
                "German invitee email must have <html lang=\"de\">; got: " + inviteeHtml);

        // English owner email must have lang="en"
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toOwner.size(), "owner must receive confirmation email");
        String ownerHtml = toOwner.get(0).getHtml();
        assertTrue(ownerHtml.contains("lang=\"en\""),
                "English owner email must have <html lang=\"en\">; got: " + ownerHtml);
    }
}
