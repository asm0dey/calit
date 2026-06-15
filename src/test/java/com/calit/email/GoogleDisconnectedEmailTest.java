package com.calit.email;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleDisconnectedEmailTest {

    @Inject
    EmailService emailService;

    @InjectMock
    MailSender mailSender;

    @Test
    void sendsReconnectLinkToOwner() {
        emailService.sendGoogleDisconnected("owner@example.com", "work@gmail.com");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // 4-arg send(to, subject, html, ics) is the no-deadline overload.
        Mockito.verify(mailSender).send(to.capture(), subject.capture(), body.capture(),
                Mockito.isNull());

        org.junit.jupiter.api.Assertions.assertEquals("owner@example.com", to.getValue());
        assertTrue(subject.getValue().toLowerCase().contains("reconnect"));
        assertTrue(body.getValue().contains("/me/google"), "body must link to the Google settings page");
        assertTrue(body.getValue().contains("work@gmail.com"), "body names the affected account");
    }
}
