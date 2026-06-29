package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Locale;
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
        emailService.sendGoogleDisconnected("owner@example.com", "work@gmail.com", Locale.ENGLISH);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // 4-arg send(to, subject, html, ics) is the no-deadline overload.
        Mockito.verify(mailSender).send(to.capture(), subject.capture(), body.capture(), Mockito.isNull());

        org.junit.jupiter.api.Assertions.assertEquals("owner@example.com", to.getValue());
        assertTrue(subject.getValue().toLowerCase().contains("reconnect"));
        assertTrue(body.getValue().contains("/me/google"), "body must link to the Google settings page");
        assertTrue(body.getValue().contains("work@gmail.com"), "body names the affected account");
    }

    @Test
    void germanLocaleProducesGermanSubject() {
        emailService.sendGoogleDisconnected(
                "owner@example.com", "work@gmail.com", java.util.Locale.forLanguageTag("de"));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailSender).send(Mockito.any(), subject.capture(), Mockito.any(), Mockito.isNull());

        String deSubject = subject.getValue();
        org.junit.jupiter.api.Assertions.assertFalse(
                deSubject.isBlank(), "German google-disconnected subject must not be blank");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "Action needed: reconnect your Google Calendar",
                deSubject,
                "German subject must differ from hardcoded English");
    }
}
