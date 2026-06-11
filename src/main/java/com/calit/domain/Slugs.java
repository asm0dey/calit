package com.calit.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Slug helpers: turn a display name into a URL slug and guarantee meeting_type uniqueness. */
public final class Slugs {

    private Slugs() {}

    /** Lowercase, strip accents, collapse non-alphanumerics to single hyphens, trim hyphens. Null/blank -> "". */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String stripped = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    /**
     * Returns {@code base} (or "meeting" if blank) made unique against this OWNER's existing
     * meeting_type slugs by appending -2, -3, ... A row with id {@code excludeId} is ignored, so
     * re-saving a type with its own current slug is allowed.
     */
    public static String uniqueMeetingTypeSlug(Long ownerId, String base, Long excludeId) {
        String root = (base == null || base.isBlank()) ? "meeting" : base;
        String candidate = root;
        int n = 1;
        while (slugTaken(ownerId, candidate, excludeId)) {
            n++;
            candidate = root + "-" + n;
        }
        return candidate;
    }

    private static boolean slugTaken(Long ownerId, String slug, Long excludeId) {
        MeetingType existing = MeetingType.findBySlug(ownerId, slug);
        return existing != null && !existing.id.equals(excludeId);
    }
}
