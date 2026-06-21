package com.calit.i18n;

import org.eclipse.microprofile.config.ConfigProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Supported-locale list + negotiation. Pure; no CDI so it stays unit-testable. */
public final class AppLocales {
    private AppLocales() {}

    public static final Locale DEFAULT = Locale.ENGLISH;

    public static List<Locale> supported() {
        String csv = ConfigProvider.getConfig()
                .getOptionalValue("app.supported-locales", String.class).orElse("en,de");
        List<Locale> out = new ArrayList<>();
        for (String tag : csv.split(",")) {
            String t = tag.trim();
            if (!t.isEmpty()) out.add(Locale.forLanguageTag(t));
        }
        if (out.isEmpty()) out.add(DEFAULT);
        return out;
    }

    public static boolean isSupported(String tag) {
        if (tag == null || tag.isBlank()) return false;
        Locale want = Locale.forLanguageTag(tag.trim());
        return supported().stream().anyMatch(l -> l.getLanguage().equals(want.getLanguage()));
    }

    /** Exact-or-language match against supported; null/blank/unsupported → DEFAULT. */
    public static Locale pick(String tag) {
        if (tag == null || tag.isBlank()) return DEFAULT;
        Locale want = Locale.forLanguageTag(tag.trim());
        return supported().stream()
                .filter(l -> l.getLanguage().equals(want.getLanguage()))
                .findFirst().orElse(DEFAULT);
    }

    /** Best supported match from an Accept-Language header, honoring q-order; else DEFAULT. */
    public static Locale fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return DEFAULT;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            Locale best = Locale.lookup(ranges, supported());
            return best != null ? best : DEFAULT;
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
