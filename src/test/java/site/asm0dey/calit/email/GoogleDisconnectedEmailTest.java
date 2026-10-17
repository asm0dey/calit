package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@QuarkusTest
class GoogleDisconnectedEmailTest {
    @Inject
    EmailService emailService;
    @InjectMock
    MailSender mailSender;

    @Test
    void sendsReconnectLinkToOwner() {
        emailService.sendCalendarDisconnected(
                1L,
                "owner@example.com",
                "work@gmail.com",
                site.asm0dey.calit.calendar.CalendarProvider.GOOGLE,
                Locale.ENGLISH
        );

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // 6-arg send(fromName, to, subject, html, ics, tag) is the no-deadline overload; fromName is null for
        // system mails, and the tag names the owner so account deletion can clear a parked copy.
        Mockito
            .verify(mailSender)
            .send(
                    Mockito.isNull(),
                    to.capture(),
                    subject.capture(),
                    body.capture(),
                    Mockito.isNull(),
                    Mockito.eq(MailTag.forOwner(1L))
            );

        assertThat(to.getValue()).isEqualTo("owner@example.com");
        assertThat(subject.getValue().toLowerCase()).contains("reconnect");
        assertThat(body.getValue()).as("body must link to the calendars settings page").contains("/me/calendars");
        assertThat(body.getValue()).as("body names the affected account").contains("work@gmail.com");
    }

    @Test
    void germanLocaleProducesGermanSubject() {
        emailService.sendCalendarDisconnected(
                1L,
                "owner@example.com",
                "work@gmail.com",
                site.asm0dey.calit.calendar.CalendarProvider.GOOGLE,
                java.util.Locale.forLanguageTag("de")
        );

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        Mockito
            .verify(mailSender)
            .send(
                    Mockito.isNull(),
                    Mockito.any(),
                    subject.capture(),
                    Mockito.any(),
                    Mockito.isNull(),
                    Mockito.any(MailTag.class)
            );

        String deSubject = subject.getValue();
        assertThat(deSubject)
            .as("German google-disconnected subject must not be blank")
            .isNotBlank()
            .as("German subject must differ from hardcoded English")
            .isNotEqualTo("Action needed: reconnect your Google Calendar");
    }

    @Test
    void caldavVariantNamesPasswordFix() {
        emailService.sendCalendarDisconnected(
                1L,
                "owner@example.com",
                "alice @ dav.example.com",
                site.asm0dey.calit.calendar.CalendarProvider.CALDAV,
                Locale.ENGLISH
        );

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito
            .verify(mailSender)
            .send(
                    Mockito.isNull(),
                    Mockito.any(),
                    subject.capture(),
                    body.capture(),
                    Mockito.isNull(),
                    Mockito.any(MailTag.class)
            );

        assertThat(subject.getValue()).contains("CalDAV password");
        assertThat(body.getValue()).contains("alice @ dav.example.com", "/me/calendars", "CalDAV");
    }
}
