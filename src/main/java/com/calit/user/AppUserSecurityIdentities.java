package com.calit.user;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;

/**
 * Builds a SecurityIdentity (principal + roles) from an AppUser. Shared by the
 * username/password login provider and the trusted (session re-establishment) provider.
 */
final class AppUserSecurityIdentities {
    private AppUserSecurityIdentities() {}

    static SecurityIdentity of(AppUser user) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> user.username);
        for (String role : user.roles.split(",")) {
            builder.addRole(role.trim());
        }
        return builder.build();
    }
}
