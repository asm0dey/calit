package site.asm0dey.calit.booking;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import site.asm0dey.calit.user.LoginTicketService;

/**
 * Issues and looks up single-use booking invites (see {@link BookingInvite}). Consumption happens
 * inside {@link BookingService#book}, in the booking's own transaction.
 */
@ApplicationScoped
public class BookingInviteService {
    public static final Duration TTL = Duration.ofDays(30);
    // Non-static: keep SecureRandom out of the native image heap (build-time seed is rejected).
    private final SecureRandom rng = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * Mint an invite for {@code meetingTypeId}, persist its hash, and return the raw token. Also
     * sweeps this owner's expired invites.
     */
    @Transactional
    public String issue(Long ownerId, Long meetingTypeId, Instant now) {
        BookingInvite.delete("ownerId = ?1 and expiresAt <= ?2", ownerId, now);
        var raw = new byte[32];
        rng.nextBytes(raw);
        var token = B64URL.encodeToString(raw);

        var invite = new BookingInvite();
        invite.ownerId = ownerId;
        invite.meetingTypeId = meetingTypeId;
        invite.tokenHash = LoginTicketService.sha256Hex(token);
        invite.expiresAt = now.plus(TTL);
        invite.persist();
        return token;
    }

    /**
     * The live invite for this raw token under {@code ownerId}, or null when unknown, expired, or
     * another owner's.
     */
    public BookingInvite find(Long ownerId, String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return BookingInvite
            .<BookingInvite>find(
                    "ownerId = ?1 and tokenHash = ?2 and expiresAt > ?3",
                    ownerId,
                    LoginTicketService.sha256Hex(rawToken),
                    now
            )
            .firstResult();
    }
}
