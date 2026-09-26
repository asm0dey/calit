package site.asm0dey.calit.email;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        emailService.sendGoogleDisconnected(1L, "owner@example.com", "work@gmail.com", Locale.ENGLISH);

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

        org.junit.jupiter.api.Assertions.assertEquals("owner@example.com", to.getValue());
        assertTrue(subject.getValue().toLowerCase().contains("reconnect"));
        assertTrue(body.getValue().contains("/me/google"), "body must link to the Google settings page");
        assertTrue(body.getValue().contains("work@gmail.com"), "body names the affected account");
    }

    @Test
    void germanLocaleProducesGermanSubject() {
        emailService.sendGoogleDisconnected(
                1L,
                "owner@example.com",
                "work@gmail.com",
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
        org.junit.jupiter.api.Assertions.assertFalse(
                deSubject.isBlank(),
                "German google-disconnected subject must not be blank"
        );
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "Action needed: reconnect your Google Calendar",
                deSubject,
                "German subject must differ from hardcoded English"
        );
    }
}
