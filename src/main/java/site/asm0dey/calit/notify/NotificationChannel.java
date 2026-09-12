package site.asm0dey.calit.notify;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import site.asm0dey.calit.crypto.EncryptedStringConverter;

/**
 * One owner's outbound notification channel: an Apprise-style URL notify4j resolves to a concrete
 * channel at send time. The presence of a row IS the owner's consent — there is no enabled flag,
 * "turn it off" is "delete the row".
 */
@Entity
@Table(name = "notification_channel")
public class NotificationChannel extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** Secret-bearing (bot tokens, webhook secrets): encrypted at rest, never logged, never rendered raw. */
    @Column(nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String url;

    /** The owner's own name for this channel. Plain text — holds no secret, so the override list can
     * render without decrypting every URL. Defaulted from the channel's display name when left blank. */
    @Column(length = 64)
    public String label;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_success_at")
    public Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    public Instant lastFailureAt;

    public static List<NotificationChannel> forOwner(Long ownerId) {
        return list("ownerId = ?1 order by id", ownerId);
    }

    /** This owner's channel by id, or null — the owner-scoping guard for every /me handler. */
    public static NotificationChannel ownedBy(Long id, Long ownerId) {
        return find("id = ?1 and ownerId = ?2", id, ownerId).firstResult();
    }
}
