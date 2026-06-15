package com.calit.email;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single seam every mail goes through. {@link #send} tries a direct, synchronous SMTP send and,
 * on failure, parks the mail in {@link EmailOutbox} instead of losing it -- so an SMTP outage never
 * fails the booking flow (the observers run AFTER_SUCCESS; the booking is already committed).
 * {@link #sendNow} is the raw send used by OutboxScheduler's retry, which applies its own backoff.
 */
@ApplicationScoped
public class MailSender {

    /** ponytail: the only attachment calit sends. Generalize if a second type ever appears. */
    private static final String ICS_FILENAME = "invite.ics";
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=UTF-8; method=REQUEST";

    @Inject
    Mailer mailer;

    /** Direct send; throws on SMTP failure. */
    public void sendNow(String to, String subject, String html, byte[] ics) {
        Mail mail = Mail.withHtml(to, subject, html);
        if (ics != null) {
            mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
        }
        mailer.send(mail);
    }

    /** Try direct; on any failure, durably queue to the outbox for retry. Never throws. */
    public void send(String to, String subject, String html, byte[] ics) {
        try {
            sendNow(to, subject, html, ics);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew().run(() ->
                    EmailOutbox.enqueue(to, subject, html, ics, e.getMessage()));
            Log.warnf(e, "SMTP send failed, queued to outbox: to=%s subject=%s", to, subject);
        }
    }
}
