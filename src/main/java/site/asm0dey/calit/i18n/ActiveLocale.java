package site.asm0dey.calit.i18n;

import jakarta.enterprise.context.RequestScoped;
import java.util.Locale;

/** Holds the active locale for the current request. Set by LocaleResolutionFilter,
 *  read by the template initializer and by booking/email code. */
@RequestScoped
public class ActiveLocale {
    private Locale locale;
    private String returnPath = "/";

    public void set(Locale locale) {
        this.locale = locale;
    }

    public Locale getOrNull() {
        return locale;
    }

    public Locale current() {
        return locale != null ? locale : AppLocales.DEFAULT;
    }

    public void setReturnPath(String returnPath) {
        this.returnPath = returnPath;
    }

    public String getReturnPath() {
        return returnPath;
    }
}
