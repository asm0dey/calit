package com.calit.google;

import com.calit.crypto.EncryptedStringConverter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "google_credential")
public class GoogleCredential extends PanacheEntityBase {

    /** Refresh the access token this long before its real expiry to avoid edge-of-expiry failures. */
    public static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** Long-lived offline refresh token. Obtained once during the consent flow. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String refreshToken;

    /** Short-lived access token, refreshed on demand. Null until first refresh. */
    @Column(name = "access_token", columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String accessToken;

    /** Instant the current access token stops being valid. Null when no access token is cached. */
    @Column(name = "access_token_expiry")
    public Instant accessTokenExpiry;

    /** Google account stable subject id (id_token "sub"). Identity for dedupe within an owner. */
    @Column(name = "google_sub", nullable = false)
    public String googleSub;

    /** The account's email (id_token "email"), shown as the human label in the UI. May be null. */
    @Column(name = "account_email")
    public String accountEmail;

    /** Set true when a token refresh fails (revoked/expired); cleared on a successful reconnect. */
    @Column(name = "needs_reconnect", nullable = false)
    public boolean needsReconnect = false;

    /**
     * When the owner was last emailed about this account being disconnected. NULL = not yet
     * notified for the current outage. INVARIANT: reset to NULL whenever {@code needsReconnect}
     * is cleared (recovery), so the next outage re-notifies. See GoogleTokenService.
     */
    @Column(name = "reconnect_notified_at")
    public Instant reconnectNotifiedAt;

    /** When the hourly connection probe last attempted a refresh on this account. NULL = never. */
    @Column(name = "last_probed_at")
    public Instant lastProbedAt;

    /** This owner's credential row, or null if Google is not yet connected for them. */
    public static GoogleCredential forOwner(Long ownerId) {
        return find("ownerId", ownerId).firstResult();
    }

    /** All of this owner's connected Google accounts. */
    public static java.util.List<GoogleCredential> listForOwner(Long ownerId) {
        return list("ownerId", ownerId);
    }

    /** This owner's credential for a specific Google account (by sub), or null. */
    public static GoogleCredential findByOwnerAndSub(Long ownerId, String sub) {
        return find("ownerId = ?1 and googleSub = ?2", ownerId, sub).firstResult();
    }

    /** How many Google accounts this owner has connected. */
    public static long countForOwner(Long ownerId) {
        return count("ownerId", ownerId);
    }

    /** True when there is no cached access token, or it expires within the safety margin of {@code now}. */
    public boolean isAccessTokenExpired(Instant now) {
        if (accessToken == null || accessTokenExpiry == null) {
            return true;
        }
        return !now.plus(SAFETY_MARGIN).isBefore(accessTokenExpiry);
    }
}
