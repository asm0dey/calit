package site.asm0dey.calit.notify;

/** A channel URL the policy refuses at save time. Carries enough to build a localized message. */
public class ChannelRejected extends RuntimeException {

    private final transient ChannelPolicy.Reason reason;

    private final String scheme;

    public ChannelRejected(ChannelPolicy.Reason reason, String scheme) {
        super("channel rejected: " + reason);
        this.reason = reason;
        this.scheme = scheme;
    }

    public ChannelPolicy.Reason reason() {
        return reason;
    }

    public String scheme() {
        return scheme;
    }
}
