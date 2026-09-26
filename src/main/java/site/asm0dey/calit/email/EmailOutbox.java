package site.asm0dey.calit.email;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
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
    /**
     * ponytail: hardcoded caps -- they don't vary per deployment. Make config only if ops asks.
     */
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
    /**
     * Optional single .ics attachment; null = none.
     */
    @Column(name = "ics_bytes")
    public byte[] icsBytes;
    @Column(name = "attempts", nullable = false)
    public int attempts;
    @Column(name = "last_error")
    public String lastError;
    /**
     * Usefulness deadline (e.g. reset-token expiry); null = no deadline (retry until attempt cap).
     */
    @Column(name = "not_after")
    public Instant notAfter;
    /**
     * Due time; null = dead (attempt-capped or deadline passed).
     */
    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;
    @Column(name = "sent_at")
    public Instant sentAt;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    /**
     * The booking this mail is about, when there is one. Lets erasure purge it by key, not by address.
     */
    @Column(name = "booking_id")
    public Long bookingId;
    /**
     * The owner this mail belongs to, when known. Cascades away with the account.
     */
    @Column(name = "owner_id")
    public Long ownerId;

    /**
     * Parks a failed send with no booking/owner link — see {@link #enqueue(String, String, String, byte[], Instant, String, MailTag)}.
     */
    public static Long enqueue(
            String recipient,
            String subject,
            String htmlBody,
            byte[] icsBytes,
            Instant notAfter,
            String error
    ) {
        return enqueue(recipient, subject, htmlBody, icsBytes, notAfter, error, MailTag.none());
    }

    /**
     * Parks a failed send. Must run inside a transaction (caller opens requiringNew). Returns the new id.
     * {@code notAfter} null = no usefulness deadline; non-null = stop retrying once that instant passes
     * (so a time-limited mail like a reset link isn't delivered dead). {@code tag} records what the mail
     * is about so erasure can clear it without waiting for the 30-day age purge.
     */
    public static Long enqueue(
            String recipient,
            String subject,
            String htmlBody,
            byte[] icsBytes,
            Instant notAfter,
            String error,
            MailTag tag
    ) {
        var r = new EmailOutbox();
        r.recipient = recipient;
        r.subject = subject;
        r.htmlBody = htmlBody;
        r.icsBytes = icsBytes;
        r.attempts = 0;
        r.lastError = error;
        r.notAfter = notAfter;
        // due immediately
        r.nextAttemptAt = Instant.now();
        r.sentAt = null;
        r.createdAt = Instant.now();
        r.bookingId = tag.bookingId();
        r.ownerId = tag.ownerId();
        r.persist();
        return r.id;
    }

    /**
     * Drops every parked mail about one booking. Returns the row count.
     */
    public static long deleteForBooking(Long bookingId) {
        return delete("bookingId", bookingId);
    }

    /**
     * Drops every parked mail belonging to one owner. Returns the row count.
     */
    public static long deleteForOwner(Long ownerId) {
        return delete("ownerId", ownerId);
    }

    /**
     * True once a deadlined mail is no longer worth delivering.
     */
    public boolean pastDeadline(Instant now) {
        return notAfter != null && now.isAfter(notAfter);
    }

    /**
     * Mark dead without sending: the usefulness deadline passed before we could deliver.
     */
    public void markExpired() {
        // dead: excluded by the claim predicate, kept for inspection
        nextAttemptAt = null;
        lastError = "deadline passed before delivery";
    }

    /**
     * After a failed retry: bump attempts; reschedule with exponential backoff, or mark dead at the cap.
     */
    public void deadOrBackoff(String error) {
        attempts++;
        lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            // dead: excluded by the partial index / claim predicate
            nextAttemptAt = null;
            return;
        }
        var secs = Math.min(CAP_BACKOFF.getSeconds(), BASE_BACKOFF.getSeconds() << attempts);
        nextAttemptAt = Instant.now().plusSeconds(secs);
    }
}
