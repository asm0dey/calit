package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import org.alexmond.notify4j.ChannelCatalog;
import org.alexmond.notify4j.ChannelField;
import org.alexmond.notify4j.FieldType;
import org.alexmond.notify4j.ParsedChannel;

/**
 * Admits or rejects a channel URL. Applied at SAVE time (so the owner gets a real error) and again
 * at SEND time (so a row saved before the allowlist was tightened stops delivering rather than
 * being grandfathered).
 */
@ApplicationScoped
public class ChannelPolicy {

    public enum Reason {
        OK,
        UNKNOWN_SCHEME,
        SCHEME_BLOCKED,
        PRIVATE_TARGET,
        INCOMPLETE
    }

    public record Check(Reason reason, String scheme) {
        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private final ChannelCatalog catalog = ChannelCatalog.standard();

    final NotifyConfig config;

    @Inject
    public ChannelPolicy(NotifyConfig config) {
        this.config = config;
    }

    public Check check(String url) {
        Optional<ParsedChannel> parsed = catalog.tryParse(url);
        if (parsed.isEmpty()) {
            return new Check(Reason.UNKNOWN_SCHEME, null);
        }
        String scheme = parsed.get().scheme();
        if (!config.schemeAllowed(scheme)) {
            return new Check(Reason.SCHEME_BLOCKED, scheme);
        }
        if (missingRequiredField(parsed.get())) {
            return new Check(Reason.INCOMPLETE, scheme);
        }
        if (!config.allowPrivateTargets() && hostBearing(parsed.get()) && resolvesPrivate(url)) {
            return new Check(Reason.PRIVATE_TARGET, scheme);
        }
        return new Check(Reason.OK, scheme);
    }

    /** Safe for display and logs: {@code scheme://host/…}, secrets stripped by notify4j's own redactor. */
    public String redact(String url) {
        return catalog.redact(url);
    }

    /** "Telegram", "Slack", "Gotify" … — the label a blank input falls back to at save time. */
    public String defaultLabel(String url) {
        return catalog.tryParse(url)
                .flatMap(p -> catalog.describe(p.scheme()).map(d -> d.displayName()))
                .orElseGet(
                        () -> catalog.tryParse(url).map(ParsedChannel::scheme).orElse("Channel"));
    }

    /**
     * Whether a URL names every part its channel needs. {@link ChannelCatalog#tryParse} only
     * DECOMPOSES — it splits the URL into the descriptor's fields and reports success for any known
     * scheme, however short the URL is — so it admits {@code telegram://<bot-token>/<chat-id>},
     * which leaves {@code chatId} empty and throws in {@code NotifierUrlParser} at DELIVERY time.
     * A channel that saves and can then never deliver is the worst outcome available, so the
     * required-field check runs here instead.
     *
     * <p>Only {@code required} errors count. {@link ChannelCatalog#parse} masks secret values, and a
     * URL-typed secret (webhook's and slack's whole URL) therefore comes back as {@code ********}
     * and fails notify4j's {@code invalid_url} format check — on a perfectly good channel.
     */
    private boolean missingRequiredField(ParsedChannel parsed) {
        return catalog.validate(parsed.scheme(), parsed.values()).stream().anyMatch(e -> "required".equals(e.code()));
    }

    /**
     * Whether this channel's URL authority is a real host, so that resolving it is both meaningful
     * and safe. It is for every channel whose descriptor declares a {@code host} field (telegram's
     * Bot API host, ntfy's and gotify's server) or a URL-typed one (webhook, slack, discord), and
     * for anything on the {@code +http} cleartext transport. It is NOT for a channel that puts a
     * credential in the authority — {@code pushover://<app-token>/<user-key>} — where a lookup would
     * hand the secret to a DNS resolver for nothing.
     */
    private boolean hostBearing(ParsedChannel parsed) {
        if (parsed.cleartextHttp()) {
            return true;
        }
        return catalog.describe(parsed.scheme())
                .map(d -> d.fields().stream().anyMatch(ChannelPolicy::namesAHost))
                .orElse(false);
    }

    private static boolean namesAHost(ChannelField f) {
        return f.type() == FieldType.URL || "host".equals(f.key());
    }

    private static boolean resolvesPrivate(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException _) {
            return false; // not a parseable authority; notify4j will fail it at send time
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress()
                        || a.isSiteLocalAddress()
                        || a.isLinkLocalAddress()
                        || a.isAnyLocalAddress()
                        || uniqueLocalIpv6(a)) {
                    return true;
                }
            }
        } catch (UnknownHostException _) {
            return false; // cannot resolve: not our business to block, the send will fail anyway
        }
        return false;
    }

    /** fc00::/7 — IPv6's private range, which {@code isSiteLocalAddress()} does not cover. */
    private static boolean uniqueLocalIpv6(InetAddress a) {
        var b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }
}
