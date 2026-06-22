package site.asm0dey.calit.i18n;

import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.CurrentOwner;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.ext.Provider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Resolves the active locale for every request and stashes it in {@link ActiveLocale}.
 * Owner-scoped routes use the owner's stored locale; everyone else uses the calit_lang cookie,
 * then Accept-Language, then the default. Runs AFTER MeOwnerFilter so CurrentOwner is populated.
 *
 * Priority: Priorities.USER + 100 (5100). MeOwnerFilter has no @Priority so defaults to
 * Priorities.USER (5000). JAX-RS request filters run low→high priority value, so 5100 > 5000
 * guarantees this filter runs AFTER MeOwnerFilter. The brief suggested Priorities.AUTHORIZATION + 100
 * (1100) which would run BEFORE MeOwnerFilter (1100 < 5000), leaving CurrentOwner unset on /me routes.
 */
@Provider
@Priority(Priorities.USER + 100) // 5100 — runs AFTER MeOwnerFilter (5000)
public class LocaleResolutionFilter implements ContainerRequestFilter {

    @Inject
    CurrentOwner currentOwner;

    @Inject
    ActiveLocale activeLocale;

    @Override
    public void filter(ContainerRequestContext ctx) {
        activeLocale.set(resolve(ctx));
        activeLocale.setReturnPath(computeReturnPath(ctx));
    }

    /**
     * Computes a URL-encoded string of the current request path (+ query if present) suitable
     * for use as the {@code ?return=} query param on /lang/{code} links.
     * Returns "/" (encoded) if the current path is already the /lang switch endpoint,
     * to avoid return-to-switch loops.
     */
    private static String computeReturnPath(ContainerRequestContext ctx) {
        String rawPath = ctx.getUriInfo().getRequestUri().getRawPath();
        // Guard: avoid redirect loops back to the /lang/... switch endpoint
        if (rawPath.startsWith("/lang/")) {
            return "%2F"; // URL-encoded "/"
        }
        String rawQuery = ctx.getUriInfo().getRequestUri().getRawQuery();
        String raw = (rawQuery != null && !rawQuery.isEmpty()) ? rawPath + "?" + rawQuery : rawPath;
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private Locale resolve(ContainerRequestContext ctx) {
        if (currentOwner.isSet()) {
            OwnerSettings s = OwnerSettings.forOwner(currentOwner.id());
            return AppLocales.pick(s != null ? s.locale : null);
        }
        Cookie c = ctx.getCookies().get("calit_lang");
        if (c != null && AppLocales.isSupported(c.getValue())) return AppLocales.pick(c.getValue());
        return AppLocales.fromAcceptLanguage(ctx.getHeaderString("Accept-Language"));
    }
}
