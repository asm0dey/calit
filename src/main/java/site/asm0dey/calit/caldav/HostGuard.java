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
            throw new CaldavException(CaldavException.Reason.NOT_CALDAV, "Only http and https URLs are allowed");
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
            if ((b[0] & 0xFE) == 0xFC) {
                // fc00::/7
                return true;
            }
            // IPv4 embedded in IPv6: classify the v4 part by the v4 rules
            if (IntStream.range(0, 12).allMatch(i -> b[i] == 0)
                    || (b[0] == 0
                    && b[1] == 0x64
                    && (b[2] & 0xFF) == 0xFF
                    && (b[3] & 0xFF) == 0x9B
                    && IntStream.range(4, 12).allMatch(i -> b[i] == 0))) {
                // ::/96, 64:ff9b::/96
                return isNonPublicV4(Arrays.copyOfRange(b, 12, 16));
            }
            if ((b[0] & 0xFF) == 0x20 && b[1] == 0x02) {
                // 6to4
                return isNonPublicV4(Arrays.copyOfRange(b, 2, 6));
            }
            return false;
        }
        return isNonPublicV4(b);
    }

    private static boolean isNonPublicV4(byte[] b) {
        int first = b[0] & 0xFF;
        return first == 0 // 0.0.0.0/8
                || first >= 240 // 240.0.0.0/4 incl. broadcast
                || (first == 100 && (b[1] & 0xC0) == 64) // 100.64.0.0/10 CGNAT
                || first == 127
                || first == 10
                || (first == 172 && (b[1] & 0xF0) == 16)
                || (first == 192 && (b[1] & 0xFF) == 168)
                || (first == 169 && (b[1] & 0xFF) == 254)
                || (first >= 224 && first < 240);
    }
}
