package site.asm0dey.calit.calendar;

import module java.base;
import jakarta.persistence.*;
import site.asm0dey.calit.crypto.EncryptedStringConverter;

/**
 * A Connected account on a CalDAV server, authenticated with a username and (app) password.
 */
@Entity
@DiscriminatorValue("CALDAV")
public class CaldavAccount extends CalendarAccount {
    @Column(name = "caldav_server_url", columnDefinition = "text")
    public String serverUrl;
    @Column(name = "caldav_username", columnDefinition = "text")
    public String username;
    @Column(name = "caldav_password", columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String password;
    /**
     * The discovered principal URL; with the owner, the identity for dedupe.
     */
    @Column(name = "caldav_principal_url", columnDefinition = "text")
    public String principalUrl;
    /**
     * The discovered calendar-home-set URL.
     */
    @Column(name = "caldav_home_url", columnDefinition = "text")
    public String homeUrl;

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.CALDAV;
    }

    @Override
    public String label() {
        return username + " @ " + host(serverUrl);
    }

    /**
     * All of this owner's CalDAV accounts.
     */
    public static List<CaldavAccount> listForOwner(Long ownerId) {
        return list("ownerId", ownerId);
    }

    private static String host(String url) {
        try {
            var host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException _) {
            return url;
        }
    }
}
