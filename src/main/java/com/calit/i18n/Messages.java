package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;

/**
 * Locale-specific AppMessages for Java code (e.g. email subjects).
 * Resolves dynamically via MessageBundles.get so adding a new language
 * requires only new properties files — no config, no Java edits here.
 *
 * For the default locale ("en") we call MessageBundles.get(AppMessages.class)
 * (no Localized qualifier) to match the unqualified default bean; for any other
 * supported locale we use Localized.Literal.of(langTag).
 */
@ApplicationScoped
public class Messages {

    public AppMessages forLocale(Locale locale) {
        Locale l = locale != null ? locale : AppLocales.DEFAULT;
        if (l.getLanguage().equals(AppLocales.DEFAULT.getLanguage())) {
            return MessageBundles.get(AppMessages.class);
        }
        return MessageBundles.get(AppMessages.class, Localized.Literal.of(l.getLanguage()));
    }

    public AppMessages forTag(String tag) {
        return forLocale(AppLocales.pick(tag));
    }
}
