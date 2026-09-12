package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import org.alexmond.notify4j.ChannelCatalog;
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
        PRIVATE_TARGET
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
     * Whether this channel's URL authority is a real host rather than a credential. {@code
     * telegram://<bot-token>/<chat-id>} puts a SECRET where a host would go, and resolving it would
     * hand the bot token to a DNS resolver — so only channels with a URL-typed field, or one using
     * the {@code +http} cleartext transport, are ever resolved. Those are exactly the self-hosted
     * channels {@code allow-private-targets} exists for.
     */
    private boolean hostBearing(ParsedChannel parsed) {
        if (parsed.cleartextHttp()) {
            return true;
        }
        return catalog.describe(parsed.scheme())
                .map(d -> d.fields().stream().anyMatch(f -> f.type() == FieldType.URL))
                .orElse(false);
    }

    private static boolean resolvesPrivate(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
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
        } catch (UnknownHostException e) {
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
