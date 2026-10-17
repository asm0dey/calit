package site.asm0dey.calit.caldav;

import module java.base;
import io.smallrye.config.ConfigMapping;

/**
 * Maps the {@code calit.caldav.*} keys from application.properties.
 */
@ConfigMapping(prefix = "calit.caldav")
public interface CaldavConfig {
    /**
     * Whether a CalDAV server may resolve to a private, loopback or link-local address.
     */
    boolean allowPrivateHosts();

    /**
     * Connect and request timeout for every CalDAV call.
     */
    Duration timeout();
}
