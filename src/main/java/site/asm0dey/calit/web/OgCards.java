package site.asm0dey.calit.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;

/** Builds the per-page {@link OgCard}s. Absolute URLs come from {@code app.base-url}. */
@ApplicationScoped
public class OgCards {

    static final String PRODUCT_TITLE = "calit";

    static final String PRODUCT_DESCRIPTION = "Self-hosted scheduling. Pick a time that works for you.";

    final SiteInfo site;

    @Inject
    public OgCards(SiteInfo site) {
        this.site = site;
    }

    /** Product-level card. {@code pagePath} is an absolute path such as {@code "/privacy"}. */
    public OgCard product(String pagePath) {
        return new OgCard(PRODUCT_TITLE, PRODUCT_DESCRIPTION, url("/og.png"), url(pagePath));
    }

    public OgCard owner(String username, String ownerName) {
        var name = ownerName == null || ownerName.isBlank() ? username : ownerName;
        return new OgCard(
                name + " · calit",
                "Pick a meeting type and book a time.",
                url("/og/" + username + ".png"),
                url("/" + username));
    }

    /**
     * Meeting-type card. A secret type falls back to {@link #product} — it is hidden from the
     * owner's public list, so naming it in an unfurl would defeat the flag.
     */
    public OgCard meetingType(String username, MeetingType type, String ownerName) {
        if (type.secret) {
            return product("/" + username + "/" + type.slug);
        }
        var name = ownerName == null || ownerName.isBlank() ? username : ownerName;
        return new OgCard(
                type.name + " · " + name,
                "Book a " + durations(type) + " meeting with " + name + ".",
                url("/og/" + username + "/" + type.slug + ".png"),
                url("/" + username + "/" + type.slug));
    }

    /** "30 min", or "15, 30 or 60 min" — never claims a single length for a multi-duration type. */
    static String durations(MeetingType type) {
        List<Integer> all = MeetingTypeDuration.allowedDurations(type);
        if (all.size() == 1) {
            return all.getFirst() + " min";
        }
        var head = all.subList(0, all.size() - 1).stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return head + " or " + all.getLast() + " min";
    }

    String url(String path) {
        String base = site.getBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }
}
