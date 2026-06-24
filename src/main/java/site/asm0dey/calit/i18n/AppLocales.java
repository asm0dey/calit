package site.asm0dey.calit.i18n;

import io.quarkus.arc.Arc;

import java.util.List;
import java.util.Locale;

/**
 * Supported-locale list + negotiation utilities.
 *
 * <h2>Design</h2>
 * All negotiation logic lives in <em>pure static overloads</em> that take an explicit
 * {@code List<Locale>} (e.g. {@link #pick(String, List)}).  The public no-arg convenience
 * methods delegate to those overloads using {@link #supported()}, which fetches the list
 * from {@link AppLocaleDiscovery} via Arc.
 *
 * <p>This split keeps {@code AppLocalesTest} a plain JUnit class (no Arc, no CDI) that
 * exercises the pure algorithms with a fixed list, while a separate {@code @QuarkusTest}
 * asserts that {@link #supported()} auto-discovers the real bundle set.
 *
 * <h2>Adding a language</h2>
 * Drop {@code msg_XX.properties} and {@code adm_XX.properties} in
 * {@code src/main/resources/messages/}.  No config, no endonym map, no Java edits needed.
 */
public final class AppLocales {

    private AppLocales() {}

    /** The hard-coded application default — used as last-resort fallback. */
    public static final Locale DEFAULT = Locale.ENGLISH;

    // -------------------------------------------------------------------------
    // Locale discovery (delegates to the CDI bean; lazy via Arc)
    // -------------------------------------------------------------------------

    /**
     * Returns the auto-discovered supported-locale list: default locale first,
     * then each additional locale found from {@code @Localized} message-bundle beans,
     * sorted by language tag.
     *
     * <p>Requires Arc to be running (safe for any request-time or application-scoped
     * call; must NOT be called from a static initializer at class-load time).
     */
    public static List<Locale> supported() {
        return Arc.container().instance(AppLocaleDiscovery.class).get().supported();
    }

    // -------------------------------------------------------------------------
    // Endonym label
    // -------------------------------------------------------------------------

    /**
     * Returns the endonym (self-name) for the given BCP-47 language code.
     * Uses the JDK: {@code Locale.forLanguageTag(code).getDisplayLanguage(thatLocale)}.
     * Example: {@code "de"} → {@code "Deutsch"}, {@code "en"} → {@code "English"}.
     * No hardcoded map — works for any language the JDK locale data covers.
     */
    public static String labelFor(String langCode) {
        if (langCode == null || langCode.isBlank()) return langCode;
        Locale locale = Locale.forLanguageTag(langCode.trim());
        String display = locale.getDisplayLanguage(locale);
        if (display.isBlank()) return langCode;
        // Capitalize first letter (some JDK locales already do; this is a no-op for those).
        return Character.toUpperCase(display.charAt(0)) + display.substring(1);
    }

    // -------------------------------------------------------------------------
    // Pure static helpers (accept explicit list — unit-testable without CDI)
    // -------------------------------------------------------------------------

    /**
     * First day of the week for the given locale, as a JS {@code Date.getDay()} index
     * (0=Sunday … 6=Saturday) — the value the booking calendar grid uses for its first column.
     *
     * <p>Why not {@link java.time.temporal.WeekFields}? CLDR stores first-day-of-week as
     * <em>territory</em> data, but calit's locales are language-only tags (e.g. {@code "de"}),
     * for which {@code WeekFields} falls back to Sunday. So this is an explicit per-language
     * policy: Hebrew starts Sunday (Israel); every other language defaults to Monday (ISO 8601,
     * which is correct for German and most of the world). Add a case here only when a new
     * language's convention differs from Monday.
     */
    public static int firstDayOfWeekIndex(Locale locale) {
        String lang = (locale != null) ? locale.getLanguage() : DEFAULT.getLanguage();
        return "iw".equals(lang) || "he".equals(lang) ? 0 : 1; // "iw" = JDK legacy code for Hebrew
    }

    /** Whether {@code tag} matches (by language) any locale in the given list. */
    public static boolean isSupported(String tag, List<Locale> supported) {
        if (tag == null || tag.isBlank()) return false;
        Locale want = Locale.forLanguageTag(tag.trim());
        return supported.stream().anyMatch(l -> l.getLanguage().equals(want.getLanguage()));
    }

    /**
     * Returns the supported locale matching {@code tag} (by language), or {@link #DEFAULT}.
     * Null/blank/unsupported tags all fall back to {@link #DEFAULT}.
     */
    public static Locale pick(String tag, List<Locale> supported) {
        if (tag == null || tag.isBlank()) return DEFAULT;
        Locale want = Locale.forLanguageTag(tag.trim());
        return supported.stream()
                .filter(l -> l.getLanguage().equals(want.getLanguage()))
                .findFirst()
                .orElse(DEFAULT);
    }

    /**
     * Best supported match from an {@code Accept-Language} header, honoring q-weights;
     * falls back to {@link #DEFAULT} when no match or the header is absent/malformed.
     */
    public static Locale fromAcceptLanguage(String header, List<Locale> supported) {
        if (header == null || header.isBlank()) return DEFAULT;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            Locale best = Locale.lookup(ranges, supported);
            return best != null ? best : DEFAULT;
        } catch (IllegalArgumentException _) {
            return DEFAULT;
        }
    }

    // -------------------------------------------------------------------------
    // Public no-arg convenience wrappers (delegate to pure overloads + supported())
    // -------------------------------------------------------------------------

    /** @see #isSupported(String, List) */
    public static boolean isSupported(String tag) {
        return isSupported(tag, supported());
    }

    /** @see #pick(String, List) */
    public static Locale pick(String tag) {
        return pick(tag, supported());
    }

    /** @see #fromAcceptLanguage(String, List) */
    public static Locale fromAcceptLanguage(String header) {
        return fromAcceptLanguage(header, supported());
    }
}
