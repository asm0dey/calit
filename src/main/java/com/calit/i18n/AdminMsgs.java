package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;

/**
 * Locale-specific AdminMessages for Java code (page titles etc).
 * Resolves dynamically via MessageBundles.get so adding a new language
 * requires only new properties files — no config, no Java edits here.
 *
 * For the default locale ("en") we call MessageBundles.get(AdminMessages.class)
 * (no Localized qualifier) to match the unqualified default bean; for any other
 * supported locale we use Localized.Literal.of(langTag).
 */
@ApplicationScoped
public class AdminMsgs {

    public AdminMessages forLocale(Locale locale) {
        Locale l = locale != null ? locale : AppLocales.DEFAULT;
        if (l.getLanguage().equals(AppLocales.DEFAULT.getLanguage())) {
            return MessageBundles.get(AdminMessages.class);
        }
        return MessageBundles.get(AdminMessages.class, Localized.Literal.of(l.getLanguage()));
    }
}
