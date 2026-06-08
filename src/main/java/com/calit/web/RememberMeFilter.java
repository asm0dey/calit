package com.calit.web;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;

/**
 * Makes the form-auth credential cookie persistent when the user ticked "remember me".
 *
 * <p>Quarkus form auth has no native remember-me. The login form posts to
 * {@code /j_security_check?remember=true} when the box is checked; this filter then sets a
 * {@code Max-Age} on the {@code quarkus-credential} Set-Cookie so it survives a browser restart.
 * Without the flag the cookie stays a session cookie.</p>
 *
 * <p>Implementation note: the credential cookie is stored in Vert.x's response CookieJar and is
 * serialized to Set-Cookie headers *after* headersEndHandlers fire (see Http1xServerResponse).
 * Therefore we manipulate the CookieJar directly: remove the session cookie, re-add it with
 * Max-Age set, so that Vert.x encodes it with the Max-Age attribute.</p>
 */
public class RememberMeFilter {

    private static final String CREDENTIAL_COOKIE = "quarkus-credential";
    private static final long REMEMBER_SECONDS = 60L * 60 * 24 * 30; // 30 days

    @RouteFilter(400)
    void rememberMe(RoutingContext rc) {
        boolean remember = "/j_security_check".equals(rc.request().path())
                && "true".equals(rc.request().getParam("remember"));
        if (remember) {
            rc.addHeadersEndHandler(v -> {
                // The credential cookie lives in the response CookieJar (added by form-auth via
                // response().addCookie()). Cookies are only serialized to Set-Cookie AFTER
                // headersEndHandlers fire, so we can still mutate the jar here.
                Set<Cookie> removed = rc.response().removeCookies(CREDENTIAL_COOKIE, false);
                if (removed != null && !removed.isEmpty()) {
                    for (Cookie c : removed) {
                        if (c.getMaxAge() < 0) { // only upgrade session cookies
                            c.setMaxAge(REMEMBER_SECONDS);
                        }
                        rc.response().addCookie(c);
                    }
                }
            });
        }
        rc.next();
    }
}
