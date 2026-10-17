package site.asm0dey.calit.calendar;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * A Connected account of any provider. Single-table: the {@code provider} column picks the subclass,
 * so a subclass's Panache queries see only its own provider's rows.
 */
@Entity
@Table(name = "calendar_account")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "provider")
public abstract class CalendarAccount extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
    /**
     * Set true when the account stops authenticating (revoked/expired); cleared on a successful reconnect.
     */
    @Column(name = "needs_reconnect", nullable = false)
    public boolean needsReconnect = false;
    /**
     * When the owner was last emailed about this account being disconnected. NULL = not yet
     * notified for the current outage. INVARIANT: reset to NULL whenever {@code needsReconnect}
     * is cleared (recovery), so the next outage re-notifies. See GoogleTokenService (Google) and the CalDAV probe/notifier for the CalDAV equivalent.
     */
    @Column(name = "reconnect_notified_at")
    public Instant reconnectNotifiedAt;
    /**
     * When the hourly connection probe last checked this account. NULL = never.
     */
    @Column(name = "last_probed_at")
    public Instant lastProbedAt;

    public abstract CalendarProvider provider();

    /**
     * The human label shown for this account in the UI.
     */
    public abstract String label();

    /**
     * This owner's account with the given id, of any provider; null when missing or another owner's.
     */
    public static CalendarAccount findOwned(Long ownerId, Long accountId) {
        if (ownerId == null || accountId == null) {
            return null;
        }
        return find("id = ?1 and ownerId = ?2", accountId, ownerId).firstResult();
    }
}
