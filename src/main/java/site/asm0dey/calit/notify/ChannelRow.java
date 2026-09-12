package site.asm0dey.calit.notify;

/**
 * One rendered row of the channel list. {@code maskedUrl} is the redacted form — the real URL never
 * reaches the browser. {@code lastSuccess}/{@code lastFailure} are preformatted in the owner's zone,
 * or null when that has never happened. {@code defaultEnabled} drives the settings checkbox and
 * the "not by default" badge on a meeting type's override list.
 */
public record ChannelRow(
        Long id,
        String label,
        String maskedUrl,
        String displayName,
        String docsUrl,
        String lastSuccess,
        String lastFailure,
        boolean defaultEnabled) {}
