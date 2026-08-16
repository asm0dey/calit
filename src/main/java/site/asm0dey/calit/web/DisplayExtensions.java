package site.asm0dey.calit.web;

import io.quarkus.qute.TemplateExtension;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Qute template extensions that humanize UPPER_SNAKE_CASE enum constants for display:
 * GOOGLE_MEET -> "Google Meet", LONG_TEXT -> "Long Text", MONDAY -> "Monday".
 *
 * <p>Only the human-facing label changes; form &lt;option&gt;/radio VALUES keep the raw
 * enum constant (e.g. value="GOOGLE_MEET") so server-side {@code Enum.valueOf} still works.
 *
 * <p>Two usage styles are supported:
 * <ul>
 *   <li>Property style {@code {someEnumValue.display}} — works for enum-typed object properties
 *       (e.g. {@code {meetingType.locationType.display}}).</li>
 *   <li>Namespace style {@code {display:of(someEnumValue)}} — needed inside {@code #for} loops over
 *       enum arrays where Qute's build-time validation cannot resolve the (possibly nested) enum
 *       element type for a property-style extension match.</li>
 * </ul>
 */
@TemplateExtension
public class DisplayExtensions {

    /** Namespace used for {@code {display:of(...)}} calls in templates. */
    static final String NAMESPACE = "display";

    /** Title-cases an enum's {@code name()} into space-separated words. Null -> "". */
    public static String display(Enum<?> e) {
        if (e == null) {
            return "";
        }
        var words = e.name().toLowerCase().split("_");
        var sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Namespace form, e.g. {@code {display:of(lt)}}; delegates to {@link #display(Enum)}. */
    @TemplateExtension(namespace = NAMESPACE)
    public static String of(Enum<?> e) {
        return display(e);
    }

    /**
     * Raw enum constant name for value comparisons inside {@code #for} loops over enum arrays, e.g.
     * {@code {#if display:name(lt) == 'GOOGLE_MEET'}}. Property-style {@code lt.name} can't be used
     * there (Qute build validation can't resolve the loop element's enum type), same reason as
     * {@link #of(Enum)}. Null -> "".
     */
    @TemplateExtension(namespace = NAMESPACE)
    public static String name(Enum<?> e) {
        return e == null ? "" : e.name();
    }

    /** Mirrors AdminResource#renderManage's manage-hub date/time format for the no-JS fallback. */
    private static final DateTimeFormatter WHEN_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm (z)");

    /**
     * Formats {@code instant} in {@code zoneId} as e.g. "Thursday, 20 August 2026 at 22:00 (JST)"
     * -- the same pattern used for the manage-booking page, carrying the zone so the result is
     * intelligible without JavaScript (a bare "22:00" wouldn't say which zone it's in). Null
     * instant -> "". {@code OwnerSettings.timezone} is stored straight from the form with no
     * validation, so an unparseable/blank/null zone id falls back to UTC rather than throwing
     * {@link DateTimeException} and 500-ing the whole page.
     */
    @TemplateExtension(namespace = NAMESPACE)
    public static String when(Instant instant, String zoneId) {
        if (instant == null) {
            return "";
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (DateTimeException | NullPointerException _) {
            zone = ZoneId.of("UTC");
        }
        return WHEN_FMT.format(instant.atZone(zone));
    }
}
