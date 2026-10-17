package site.asm0dey.calit.caldav;

import module java.base;

/**
 * SSRF guard: refuses non-http(s) URIs and hosts that resolve to any non-public address.
 */
final class HostGuard {
    private HostGuard() {
    }

    static void check(URI uri, boolean allowPrivate) {
        var scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new CaldavException(CaldavException.Reason.PRIVATE_HOST, "Only http and https URLs are allowed");
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new CaldavException(CaldavException.Reason.UNREACHABLE, "URL has no host");
        }
        if (allowPrivate) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "Cannot resolve " + host, e);
        }
        // every address must be public: a host with one private A record is refused
        for (var a : addresses) {
            if (isNonPublic(a)) {
                throw new CaldavException(CaldavException.Reason.PRIVATE_HOST, "Host resolves to a non-public address");
            }
        }
    }

    private static boolean isNonPublic(InetAddress a) {
        if (a.isLoopbackAddress()
                || a.isAnyLocalAddress()
                || a.isLinkLocalAddress()
                || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        var b = a.getAddress();
        if (b.length == 16) {
            // fc00::/7
            return (b[0] & 0xFE) == 0xFC;
        }
        // 100.64.0.0/10 CGNAT
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }
}
