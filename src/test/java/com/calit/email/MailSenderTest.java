package com.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class MailSenderTest {

    // Spy on the ApplicationScoped bean so we can force sendNow to throw,
    // exercising the fallback-to-outbox path in send().
    @InjectSpy
    MailSender mailSender;

    @Test
    void failedSendIsParkedInOutbox() {
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender).sendNow(anyString(), anyString(), anyString(), any());

        mailSender.send("a@b.com", "Subj", "<p>hi</p>", new byte[]{9});

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.find("recipient", "a@b.com").firstResult();
            assertEquals("Subj", r.subject);
            assertEquals("smtp down", r.lastError);
            assertNull(r.sentAt);
        });
    }

    @Test
    void successfulSendLeavesOutboxEmpty() {
        doNothing().when(mailSender).sendNow(anyString(), anyString(), anyString(), any());

        mailSender.send("ok@b.com", "Subj", "<p>hi</p>", null);

        long parked = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.count("recipient", "ok@b.com"));
        assertEquals(0L, parked);
    }
}
