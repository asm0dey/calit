package com.calit.i18n;

import io.quarkus.arc.Arc;
import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Locale;

/**
 * Applies the active request locale to EVERY Qute template instance: sets the message-bundle
 * locale (drives {msg:...}) and a {lang} data value (drives {@code <html lang="{lang}">}).
 * Off-request threads (scheduler/outbox emails) have no active request scope, so this defers to
 * whatever locale the caller set explicitly (Task 8) and only fills {lang} with the default.
 *
 * Registration: we observe the Quarkus {@link EngineBuilder} CDI event fired by EngineProducer
 * and call {@code addTemplateInstanceInitializer(this)} there. A plain
 * {@code @ApplicationScoped implements TemplateInstance.Initializer} bean is NOT auto-discovered
 * by Qute — the only auto-registration path is TemplateGlobalProviderBuildItem (build-time only).
 */
@ApplicationScoped
public class LocaleTemplateInitializer implements TemplateInstance.Initializer {

    /** Called once at startup by EngineProducer — registers this bean as a template initializer. */
    void onEngineBuilder(@Observes EngineBuilder builder) {
        builder.addTemplateInstanceInitializer(this);
    }

    @Override
    public void accept(TemplateInstance instance) {
        Locale locale = null;
        if (Arc.container().requestContext().isActive()) {
            ActiveLocale active = Arc.container().instance(ActiveLocale.class).get();
            if (active != null) locale = active.getOrNull();
        }
        if (locale != null) {
            instance.setLocale(locale);
            instance.data("lang", locale.toLanguageTag());
        } else {
            instance.data("lang", AppLocales.DEFAULT.toLanguageTag());
        }
    }
}
