package site.asm0dey.calit.booking;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BookingInviteRepository implements PanacheRepositoryBase<BookingInvite, Long> {
    /**
     * Atomically deletes the live invite for this token and meeting type. Returns 1 for the one
     * caller that wins it, 0 for everyone else (unknown, expired, other type, already consumed).
     */
    public long consume(String tokenHash, Long meetingTypeId, Instant now) {
        return delete("tokenHash = ?1 and meetingTypeId = ?2 and expiresAt > ?3", tokenHash, meetingTypeId, now);
    }
}
