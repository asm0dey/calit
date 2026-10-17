package site.asm0dey.calit.google;

import module java.base;
import jakarta.persistence.*;
import site.asm0dey.calit.calendar.CalendarAccount;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.crypto.EncryptedStringConverter;

@Entity
@DiscriminatorValue("GOOGLE")
public class GoogleCredential extends CalendarAccount {
    /**
     * Refresh the access token this long before its real expiry to avoid edge-of-expiry failures.
     */
    public static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);
    /**
     * Long-lived offline refresh token. Obtained once during the consent flow.
     */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String refreshToken;
    /**
     * Short-lived access token, refreshed on demand. Null until first refresh.
     */
    @Column(name = "access_token", columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String accessToken;
    /**
     * Instant the current access token stops being valid. Null when no access token is cached.
     */
    @Column(name = "access_token_expiry")
    public Instant accessTokenExpiry;
    /**
     * Google account stable subject id (id_token "sub"). Identity for dedupe within an owner.
     */
    @Column(name = "google_sub", nullable = false)
    public String googleSub;
    /**
     * The account's email (id_token "email"), shown as the human label in the UI. May be null.
     */
    @Column(name = "account_email")
    public String accountEmail;

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    public String label() {
        return accountEmail;
    }

    /**
     * This owner's credential row, or null if Google is not yet connected for them.
     */
    public static GoogleCredential forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }

    /**
     * All of this owner's connected Google accounts.
     */
    public static java.util.List<GoogleCredential> listForOwner(Long ownerId) {
        return list("ownerId", ownerId);
    }

    /**
     * This owner's credential for a specific Google account (by sub), or null.
     */
    public static GoogleCredential findByOwnerAndSub(Long ownerId, String sub) {
        return find("ownerId = ?1 and googleSub = ?2", ownerId, sub).firstResult();
    }

    /**
     * How many Google accounts this owner has connected.
     */
    public static long countForOwner(Long ownerId) {
        return count("ownerId", ownerId);
    }

    /**
     * True when this owner has a connected Google credential that needs reconnecting. False when not connected at all.
     */
    public static boolean hasPendingReconnect(Long ownerId) {
        var cred = forOwner(ownerId);
        return cred != null && cred.needsReconnect;
    }

    /**
     * True when there is no cached access token, or it expires within the safety margin of {@code now}.
     */
    public boolean isAccessTokenExpired(Instant now) {
        if (accessToken == null || accessTokenExpiry == null) {
            return true;
        }
        return !now.plus(SAFETY_MARGIN).isBefore(accessTokenExpiry);
    }
}
