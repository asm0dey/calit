package com.calit.i18n;

/**
 * View-model record representing one entry in the language switcher footer.
 * Built by {@link LocaleOptions} and injected into templates via the initializer.
 */
public record LocaleOption(
        /** BCP 47 language tag, e.g. "en" or "de". */
        String code,
        /** Endonym label, e.g. "English" or "Deutsch". */
        String label,
        /** True when this locale is the currently-active one (render as span, not link). */
        boolean active,
        /** URL for switching to this locale, e.g. "/lang/de?return=%2Falice%2Fintro". */
        String href
) {}
