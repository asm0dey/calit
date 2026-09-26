package site.asm0dey.calit.email;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single seam every mail goes through. {@link #send} tries a direct, synchronous SMTP send and,
 * on failure, parks the mail in {@link EmailOutbox} instead of losing it -- so an SMTP outage never
 * fails the booking flow (the observers run AFTER_SUCCESS; the booking is already committed).
 * {@link #sendNow} is the raw send used by OutboxScheduler's retry, which applies its own backoff.
 */
@ApplicationScoped
public class MailSender {
    /**
     * ponytail: the only attachment calit sends. Generalize if a second type ever appears.
     */
    private static final String ICS_FILENAME = "invite.ics";
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=UTF-8; method=REQUEST";
    final Mailer mailer;

    @Inject
    public MailSender(Mailer mailer, @ConfigProperty(name = "app.mail-from") String mailFrom) {
        this.mailer = mailer;
        this.mailFrom = mailFrom;
    }

    /**
     * Bare sending address; the per-message display name (when present) is prefixed onto this.
     */
    final String mailFrom;

    /**
     * Direct send; throws on SMTP failure. {@code fromName} null → From left to config default.
     */
    public void sendNow(String fromName, String to, String subject, String html, byte[] ics) {
        Mail mail = Mail.withHtml(to, subject, html);
        if (fromName != null) {
            mail.setFrom(fromName + " <" + mailFrom + ">");
        }
        if (ics != null) {
            mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
        }
        mailer.send(mail);
    }

    /**
     * Try direct; on any failure, durably queue to the outbox for retry (no usefulness deadline).
     */
    public void send(String fromName, String to, String subject, String html, byte[] ics) {
        send(fromName, to, subject, html, ics, null, MailTag.none());
    }

    /**
     * Try direct; on any failure, durably queue to the outbox for retry. Never throws.
     * {@code notAfter} non-null bounds retry: a queued mail is dropped undelivered once that instant
     * passes (e.g. a reset link whose token has expired — delivering it would only hand over a dead
     * link). ponytail: the outbox does not persist {@code fromName}; a retried mail sends with the
     * config-default From (cosmetic only). Add an email_outbox column only if branded From on the
     * rare retry path is ever required.
     */
    public void send(String fromName, String to, String subject, String html, byte[] ics, Instant notAfter) {
        send(fromName, to, subject, html, ics, notAfter, MailTag.none());
    }

    public void send(String fromName, String to, String subject, String html, byte[] ics, MailTag tag) {
        send(fromName, to, subject, html, ics, null, tag);
    }

    /**
     * Try direct; on any failure, durably queue to the outbox for retry. Never throws.
     * {@code notAfter} non-null bounds retry: a queued mail is dropped undelivered once that instant
     * passes (e.g. a reset link whose token has expired — delivering it would only hand over a dead
     * link). {@code tag} records the booking/owner the mail is about so erasure can purge it by key.
     * ponytail: the outbox does not persist {@code fromName}; a retried mail sends with the
     * config-default From (cosmetic only).
     */
    public void send(String fromName, String to, String subject, String html, byte[] ics, Instant notAfter, MailTag tag) {
        try {
            sendNow(fromName, to, subject, html, ics);
        } catch (Exception e) {
            QuarkusTransaction
                .requiringNew()
                .run(() -> EmailOutbox.enqueue(to, subject, html, ics, notAfter, e.getMessage(), tag));
            Log.warnf(e, "SMTP send failed, queued to outbox: to=%s subject=%s", to, subject);
        }
    }
}
