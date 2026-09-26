package site.asm0dey.calit.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A permanent tombstone of every deleted account's username, hashed (never stored in the clear —
 * this table exists only to answer "was this name ever used and deleted", not to record who).
 *
 * <p>Closes an account-takeover window: Quarkus form-auth's persistent-login cookie carries only
 * {@code expiry:username} (no user id) and renews itself on every restored request, while {@code
 * EnabledUserAugmentor} downgrades a request whose username no longer resolves to anonymous
 * WITHOUT invalidating that cookie. A deleted account's stale cookie therefore keeps renewing
 * indefinitely in the browser — and would silently authenticate as a NEW account that later
 * re-registers the same name. Rows here are never purged; see {@code PersonalData}.
 */
@Entity
@Table(name = "deleted_username")
public class DeletedUsername extends PanacheEntityBase {
    @Id
    @Column(name = "username_sha256")
    public String usernameSha256;
    @Column(name = "deleted_at", nullable = false)
    public Instant deletedAt;

    /**
     * Tombstone {@code username} (normalized internally) forever.
     */
    public static void tombstone(String username) {
        var row = new DeletedUsername();
        row.usernameSha256 = LoginTicketService.sha256Hex(Usernames.normalize(username));
        row.deletedAt = Instant.now();
        row.persist();
    }

    /**
     * True when {@code username} (normalized internally) belonged to a previously-deleted account.
     */
    public static boolean isTombstoned(String username) {
        return count("usernameSha256", LoginTicketService.sha256Hex(Usernames.normalize(username))) > 0;
    }
}
