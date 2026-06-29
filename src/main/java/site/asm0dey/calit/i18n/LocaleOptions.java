package site.asm0dey.calit.i18n;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Request-scoped CDI bean that builds the list of {@link LocaleOption}s for the
 * language-switcher footer in base.html.
 *
 * Injected into templates via the {@link LocaleTemplateInitializer} (which sets
 * "localeOptions" in data). Adding a new language requires only
 * {@code msg_XX.properties} and {@code adm_XX.properties} — no config, no endonym
 * map, no Java edits needed here.
 */
@RequestScoped
@Unremovable
public class LocaleOptions {

    @Inject
    ActiveLocale activeLocale;

    /**
     * Returns one {@link LocaleOption} per supported locale, in config order.
     * The currently-active locale is marked {@code active = true}.
     */
    public List<LocaleOption> options() {
        Locale current = activeLocale.current();
        // returnPath is already URL-encoded by LocaleResolutionFilter (e.g. "%2Falice%2Fintro")
        String encodedReturn = activeLocale.getReturnPath();

        List<LocaleOption> result = new ArrayList<>();
        for (Locale supported : AppLocales.supported()) {
            var code = supported.getLanguage();
            String label = AppLocales.labelFor(code);
            var active = code.equals(current.getLanguage());
            var href = "/lang/" + code + "?return=" + encodedReturn;
            result.add(new LocaleOption(code, label, active, href));
        }
        return result;
    }
}
