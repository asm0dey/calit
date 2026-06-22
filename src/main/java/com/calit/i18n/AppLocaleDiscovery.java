package com.calit.i18n;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;
import io.quarkus.qute.i18n.Localized;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Discovers the supported locale set at runtime by enumerating all Arc-managed
 * {@link AppMessages} beans and reading their {@link Localized} qualifier values.
 *
 * <p>Quarkus generates one bean per {@code msg_XX.properties} file, each qualified
 * with {@code @Localized("XX")}. The default (English) bean has no {@code @Localized}
 * qualifier and is covered by reading {@code quarkus.default-locale} (fallback: "en").
 *
 * <p>Result: supported = {default locale} ∪ {one locale per @Localized bean}.
 * Adding a new language requires only dropping in {@code msg_XX.properties} and
 * {@code adm_XX.properties} — no Java, no config, no endonym map.
 *
 * <p>The list is computed once (first call to {@link #supported()}) and cached in a
 * {@code volatile} field — safe under concurrent first-call races because the result is
 * always the same, and Arc is up by the time any request fires.
 */
@ApplicationScoped
@Unremovable // accessed via Arc.container().instance() from static AppLocales.supported()
public class AppLocaleDiscovery {

    // Volatile: safe lazy init — worst case two threads compute the same list once.
    private volatile List<Locale> cache;

    /**
     * Returns the supported locale list: default first, then additional locales sorted
     * by language tag. Computed once and cached.
     */
    public List<Locale> supported() {
        if (cache != null) return cache;
        synchronized (this) {
            if (cache != null) return cache;
            cache = discover();
        }
        return cache;
    }

    private List<Locale> discover() {
        Locale defaultLocale = defaultLocale();

        // Use a TreeSet keyed on language tag for stable, deduplicated ordering.
        TreeSet<String> extras = new TreeSet<>();

        List<InstanceHandle<AppMessages>> handles =
                Arc.container().listAll(AppMessages.class);
        for (InstanceHandle<AppMessages> h : handles) {
            for (Annotation q : h.getBean().getQualifiers()) {
                if (q instanceof Localized localized) {
                    String tag = localized.value();
                    // Skip if it matches the default locale — already included below.
                    if (!tag.equalsIgnoreCase(defaultLocale.getLanguage())) {
                        extras.add(tag);
                    }
                }
            }
        }

        List<Locale> result = new ArrayList<>(1 + extras.size());
        result.add(defaultLocale);
        for (String tag : extras) {
            result.add(Locale.forLanguageTag(tag));
        }
        return List.copyOf(result);
    }

    private static Locale defaultLocale() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.default-locale", String.class)
                .map(Locale::forLanguageTag)
                .orElse(Locale.ENGLISH);
    }
}
