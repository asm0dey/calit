package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/** Locale-specific AdminMessages for Java code (page titles etc). Two-locale switch. */
@ApplicationScoped
public class AdminMsgs {

    @Inject AdminMessages en;
    @Inject @Localized("de") AdminMessages de;

    public AdminMessages forLocale(Locale l) {
        // ponytail: two-locale switch; replace with a Map<lang,AdminMessages> when a 3rd lands.
        return (l != null && "de".equals(l.getLanguage())) ? de : en;
    }
}
