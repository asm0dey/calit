package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.alexmond.notify4j.ChannelCatalog;
import org.alexmond.notify4j.Notifications;
import org.alexmond.notify4j.SendResult;

/** Everything the /me/settings channel handlers do, kept out of the already-large AdminResource. */
@ApplicationScoped
public class ChannelAdmin {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int LABEL_MAX = 64;

    private final ChannelCatalog catalog = ChannelCatalog.standard();

    final ChannelPolicy policy;

    final ChannelMessageRenderer renderer;

    final NotifyConfig config;

    @Inject
    public ChannelAdmin(ChannelPolicy policy, ChannelMessageRenderer renderer, NotifyConfig config) {
        this.policy = policy;
        this.renderer = renderer;
        this.config = config;
    }

    public List<ChannelRow> rows(Long ownerId, ZoneId zone) {
        List<ChannelRow> rows = new ArrayList<>();
        for (NotificationChannel c : NotificationChannel.forOwner(ownerId)) {
            var descriptor = catalog.tryParse(c.url).flatMap(p -> catalog.describe(p.scheme()));
            rows.add(new ChannelRow(
                    c.id,
                    c.label,
                    policy.redact(c.url),
                    descriptor.map(d -> d.displayName()).orElse(""),
                    descriptor.map(d -> d.docsUrl()).orElse(null),
                    stamp(c.lastSuccessAt, zone),
                    stamp(c.lastFailureAt, zone)));
        }
        return rows;
    }

    /**
     * Repeatable inputs: the three lists are index-aligned because every rendered row emits all
     * three fields (a new row emits an empty {@code channelId}). A row with a blank URL is ignored —
     * removing a channel is the Delete button, not an emptied field.
     */
    @Transactional
    public void save(Long ownerId, List<String> ids, List<String> labels, List<String> urls) {
        for (var i = 0; i < urls.size(); i++) {
            var submitted = value(urls, i).trim();
            if (submitted.isEmpty()) {
                continue;
            }
            NotificationChannel existing = parseId(value(ids, i))
                    .map(id -> NotificationChannel.ownedBy(id, ownerId))
                    .orElse(null);
            // An unchanged redacted value means "keep the stored secret": the real URL never
            // round-trips through the browser, so it cannot come back from the form.
            String url = existing != null && submitted.equals(policy.redact(existing.url)) ? existing.url : submitted;
            var check = policy.check(url);
            if (!check.ok()) {
                throw new ChannelRejected(check.reason(), check.scheme());
            }
            NotificationChannel row = existing;
            if (row == null) {
                row = new NotificationChannel();
                row.ownerId = ownerId;
                row.createdAt = Instant.now();
            }
            row.url = url;
            var label = value(labels, i).trim();
            row.label = label.isEmpty() ? policy.defaultLabel(url) : label;
            if (row.label.length() > LABEL_MAX) {
                row.label = row.label.substring(0, LABEL_MAX);
            }
            row.persist();
        }
    }

    @Transactional
    public void delete(Long ownerId, Long channelId) {
        NotificationChannel c = NotificationChannel.ownedBy(channelId, ownerId);
        if (c != null) {
            c.delete(); // link rows cascade in the DB (ON DELETE CASCADE)
        }
    }

    /**
     * Sends a test message inline and reports the outcome immediately. This is the only way a host
     * learns a URL is wrong BEFORE a real booking — {@code last_failure_at} is by definition after
     * the fact. Runs on the request thread on purpose: the owner is waiting for the answer.
     */
    public boolean test(Long ownerId, Long channelId, Locale locale) {
        NotificationChannel c = NotificationChannel.ownedBy(channelId, ownerId);
        if (c == null || !policy.check(c.url).ok()) {
            return false;
        }
        try {
            SendResult r = Notifications.sendOnce(List.of(c.url), renderer.test(locale), config.http());
            return !r.anyFailed();
        } catch (RuntimeException e) {
            Log.warnf(e, "test delivery to channel %d threw", channelId);
            return false;
        }
    }

    private static String value(List<String> list, int i) {
        return list != null && i < list.size() && list.get(i) != null ? list.get(i) : "";
    }

    private static Optional<Long> parseId(String raw) {
        try {
            return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(Long.valueOf(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String stamp(Instant at, ZoneId zone) {
        return at == null ? null : STAMP.format(at.atZone(zone));
    }
}
