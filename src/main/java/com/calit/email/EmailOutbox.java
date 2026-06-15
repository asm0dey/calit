package com.calit.email;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * One mail that failed a direct SMTP send and is parked for retry. {@link #sentAt} null = unsent;
 * {@link #nextAttemptAt} null = dead (attempt-capped, kept for inspection, never re-claimed).
 * OutboxScheduler claims due unsent rows with SELECT ... FOR UPDATE SKIP LOCKED.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox extends PanacheEntityBase {

    /** ponytail: hardcoded caps -- they don't vary per deployment. Make config only if ops asks. */
    static final int MAX_ATTEMPTS = 10;
    static final Duration BASE_BACKOFF = Duration.ofMinutes(1);
    static final Duration CAP_BACKOFF = Duration.ofHours(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "recipient", nullable = false, length = 320)
    public String recipient;

    @Column(name = "subject", nullable = false)
    public String subject;

    @Column(name = "html_body", nullable = false)
    public String htmlBody;

    /** Optional single .ics attachment; null = none. */
    @Column(name = "ics_bytes")
    public byte[] icsBytes;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "last_error")
    public String lastError;

    /** Usefulness deadline (e.g. reset-token expiry); null = no deadline (retry until attempt cap). */
    @Column(name = "not_after")
    public Instant notAfter;

    /** Due time; null = dead (attempt-capped or deadline passed). */
    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;

    @Column(name = "sent_at")
    public Instant sentAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * Parks a failed send. Must run inside a transaction (caller opens requiringNew). Returns the new id.
     * {@code notAfter} null = no usefulness deadline; non-null = stop retrying once that instant passes
     * (so a time-limited mail like a reset link isn't delivered dead).
     */
    public static Long enqueue(String recipient, String subject, String htmlBody, byte[] icsBytes,
                               Instant notAfter, String error) {
        EmailOutbox r = new EmailOutbox();
        r.recipient = recipient;
        r.subject = subject;
        r.htmlBody = htmlBody;
        r.icsBytes = icsBytes;
        r.attempts = 0;
        r.lastError = error;
        r.notAfter = notAfter;
        r.nextAttemptAt = Instant.now(); // due immediately
        r.sentAt = null;
        r.createdAt = Instant.now();
        r.persist();
        return r.id;
    }

    /** True once a deadlined mail is no longer worth delivering. */
    public boolean pastDeadline(Instant now) {
        return notAfter != null && now.isAfter(notAfter);
    }

    /** Mark dead without sending: the usefulness deadline passed before we could deliver. */
    public void markExpired() {
        nextAttemptAt = null; // dead: excluded by the claim predicate, kept for inspection
        lastError = "deadline passed before delivery";
    }

    /** After a failed retry: bump attempts; reschedule with exponential backoff, or mark dead at the cap. */
    public void deadOrBackoff(String error) {
        attempts++;
        lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            nextAttemptAt = null; // dead: excluded by the partial index / claim predicate
            return;
        }
        long secs = Math.min(CAP_BACKOFF.getSeconds(), BASE_BACKOFF.getSeconds() << attempts);
        nextAttemptAt = Instant.now().plusSeconds(secs);
    }
}
