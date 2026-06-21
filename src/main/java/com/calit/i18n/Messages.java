package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/** Locale-specific AppMessages for Java code (e.g. email subjects). Two-locale switch. */
@ApplicationScoped
public class Messages {

    @Inject AppMessages en;
    @Inject @Localized("de") AppMessages de;

    public AppMessages forLocale(Locale l) {
        // ponytail: two-locale switch; replace with a Map<lang,AppMessages> when a 3rd lands.
        return (l != null && "de".equals(l.getLanguage())) ? de : en;
    }

    public AppMessages forTag(String tag) {
        return forLocale(AppLocales.pick(tag));
    }
}
