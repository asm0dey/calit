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
        return path.equals("/setup")
                || path.equals("/login")
                || path.equals("/j_security_check")
                || path.startsWith("/q/")
                || path.equals("/calit.css")
                || path.equals("/favicon.ico");
    }
}
