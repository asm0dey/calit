package site.asm0dey.calit.web;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Strips the {@code csrf-token} Set-Cookie from responses to the social-preview card endpoints
 * (calit-7hls): {@code /og.png}, {@code /og/{user}.png}, {@code /og/{user}/{slug}.png}
 * ({@link OgImageResource}).
 *
 * <p>Those endpoints are explicitly {@code Cache-Control: public, max-age=3600} -- designed to be
 * stored by a shared cache/CDN in front of unfurl crawlers, none of which ever submit a CSRF token
 * back. quarkus-rest-csrf mints its cookie on every safe GET regardless of content type, so without
 * this filter the response was simultaneously cacheable AND carrying a per-visitor cookie: a shared
 * cache could serve one visitor's csrf token to another.
 *
 * <p><b>Why not a JAX-RS {@code ContainerResponseFilter}:</b> that was the first approach considered
 * and rejected on inspection of {@code CsrfRequestResponseReactiveFilter} (decompiled from
 * quarkus-rest-csrf 3.38.3) -- its response-side method calls
 * {@code RoutingContext.response().addCookie(...)} directly against Vert.x's response CookieJar, the
 * exact mechanism {@link RememberMeFilter} already documents for the {@code quarkus-credential}
 * cookie. A cookie added that way never appears in {@code ContainerResponseContext.getHeaders()}: it
 * is serialized into a real {@code Set-Cookie} header only when Vert.x prepares the response for
 * write, which happens strictly after every {@code headersEndHandler} has run -- itself strictly
 * after the whole JAX-RS filter chain (including the CSRF filter) has completed. No JAX-RS filter
 * priority could make a {@code ContainerResponseFilter} see this cookie; the problem is a difference
 * in API surface, not filter ordering. This filter therefore works at the same layer the cookie is
 * actually added at: a Vert.x {@code @RouteFilter} that registers a {@code headersEndHandler} to
 * remove the cookie from the CookieJar right before it would be written.
 *
 * <p><b>Why not {@code quarkus.rest-csrf.create-token-path}:</b> that config is a path allow-list for
 * where the token cookie may be created, which would need every form-bearing route enumerated.
 * calit's form paths are dynamic ({@code /{username}/{slug}}, {@code /booking/{manageToken}/manage},
 * {@code /guest/{declineToken}/decline}), so they cannot be listed literally; a missed path would
 * silently disarm CSRF on that form and its POST would start 400ing in production. Denylisting the
 * three known-static card paths here is the safe direction.
 */
public class CardCsrfCookieFilter {

    private static final Pattern CARD_PATH = Pattern.compile("^/og\\.png$|^/og/[^/]+\\.png$|^/og/[^/]+/[^/]+\\.png$");

    @ConfigProperty(name = "quarkus.rest-csrf.cookie-name", defaultValue = "csrf-token")
    String csrfCookieName;

    @RouteFilter
    void stripCsrfCookieOnCardResponses(RoutingContext rc) {
        if (CARD_PATH.matcher(rc.normalizedPath()).matches()) {
            // Mirrors RememberMeFilter's documented technique: the CookieJar is only serialized to
            // Set-Cookie headers after headersEndHandlers run, so removing it here still wins even
            // though the CSRF filter (a later stage) is the one that added it.
            rc.addHeadersEndHandler(v -> rc.response().removeCookies(csrfCookieName, false));
        }
        rc.next();
    }
}
