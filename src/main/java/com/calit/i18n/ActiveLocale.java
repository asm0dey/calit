package com.calit.i18n;

import jakarta.enterprise.context.RequestScoped;
import java.util.Locale;

/** Holds the active locale for the current request. Set by LocaleResolutionFilter,
 *  read by the template initializer and by booking/email code. */
@RequestScoped
public class ActiveLocale {
    private Locale locale;
    public void set(Locale locale) { this.locale = locale; }
    public Locale getOrNull() { return locale; }
    public Locale current() { return locale != null ? locale : AppLocales.DEFAULT; }
}
