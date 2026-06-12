package com.calit.user;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

/**
 * While no user exists, redirect every request to /setup so the instance gets bootstrapped —
 * except /setup itself, the login endpoints, static assets, and Quarkus management paths.
 * Once any user exists this filter is a no-op (the common case, one count() per request).
 *
 * Uses Vert.x @RouteFilter (priority 10000, runs before security) with executeBlocking so
 * the Panache count() call does not block the event loop. The count runs inside
 * {@link QuarkusTransaction#requiringNew()} so its Hibernate session/JDBC connection is opened
 * and released each call — without it the worker-thread Panache call leaks a pooled connection
 * per request and exhausts the datasource pool under load.
 */
public class FirstRunRedirectFilter {

    @RouteFilter(10000)
    void firstRunCheck(RoutingContext rc) {
        String path = rc.request().path();
        if (isAllowedWhileUnbootstrapped(path)) {
            rc.next();
            return;
        }
        rc.vertx().executeBlocking(
                        () -> QuarkusTransaction.requiringNew().call(() -> AppUser.count() == 0), false)
                .onSuccess(noUsers -> {
                    if (Boolean.TRUE.equals(noUsers)) {
                        rc.redirect("/setup");
                    } else {
                        rc.next();
                    }
                })
                .onFailure(err -> rc.next());
    }

    private boolean isAllowedWhileUnbootstrapped(String path) {
        // Note: /login is NOT exempt — while unbootstrapped there is nobody to log in as, so /login
        // redirects to /setup (first-user creation) like every other app path. /j_security_check stays
        // exempt so the redirect target is reachable; it no-ops with no users.
        return path.equals("/")                  // public marketing landing stays open pre-bootstrap
                || path.startsWith("/img/")       // ...and its screenshots
                || path.equals("/setup")
                || path.equals("/j_security_check")
                || path.startsWith("/q/")
                || path.equals("/calit.css")
                || path.equals("/favicon.ico");
    }
}
