package com.calit.i18n;

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
 * "localeOptions" in data). Adding a new language requires only a new properties
 * file, a config entry in app.supported-locales, and one endonym entry in
 * {@link AppLocales#labelFor(String)} — no edits needed here.
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
            String code = supported.getLanguage();
            String label = AppLocales.labelFor(code);
            boolean active = code.equals(current.getLanguage());
            String href = "/lang/" + code + "?return=" + encodedReturn;
            result.add(new LocaleOption(code, label, active, href));
        }
        return result;
    }
}
