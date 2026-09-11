package site.asm0dey.calit.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailSender;

// #195: the confirmation page must not promise an email that was never sent. The page renders in
// the SAME request as the booking commit -- BookingService is @Transactional, the mail observers
// are AFTER_SUCCESS (synchronous, on the committing thread), and MailSender parks failures in the
// outbox before PublicResource.book reaches `return confirmationPage(...)`. So the outcome is
// already knowable at render time and no polling is needed. THIS TEST IS THE PROOF OF THAT.
@QuarkusTest
class GuestConfirmationMailCopyTest {

    @InjectSpy
    MailSender mailSender;

    @BeforeEach
    void clean() {
        // Not a method reference: EmailOutbox::deleteAll breaks under Panache bytecode enhancement
        // (the enhancer rewrites call-site bytecode, not lambda-captured method handles).
        QuarkusTransaction.requiringNew().run(() -> EmailOutbox.deleteAll());
    }

    @Test
    void smtpDownMakesTheConfirmationPageSayTheMailDidNotGoOut() {
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender)
                .sendNow(any(), anyString(), anyString(), anyString(), any());

        // Qute HTML-escapes the apostrophe in "couldn't" to "&#39;", so asserting on the literal
        // straight-quote text would never match the real render -- and worse, a `not(containsString
        // ("couldn't send"))` guard would incorrectly PASS even if the failure branch rendered,
        // since the escaped text never equals that literal. Assert on an apostrophe-free substring
        // instead, plus the machine-readable marker attribute the template emits on this branch.
        GuestBookingFixture.book(this.getClass().getSimpleName() + "-down")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-undelivered"))
                .body(containsString("send the confirmation email"))
                .body(not(containsString("is on its way")));
    }

    @Test
    void workingSmtpKeepsTheOptimisticCopy() {
        doNothing().when(mailSender).sendNow(any(), anyString(), anyString(), anyString(), any());

        GuestBookingFixture.book(this.getClass().getSimpleName() + "-ok")
                .then()
                .statusCode(200)
                .body(containsString("is on its way"))
                .body(not(containsString("data-mail-undelivered")))
                .body(not(containsString("send the confirmation email")));
    }
}
