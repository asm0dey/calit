package site.asm0dey.calit.user;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;

/**
 * Builds a SecurityIdentity (principal + roles) from an AppUser. Shared by the
 * username/password login provider and the trusted (session re-establishment) provider.
 */
final class AppUserSecurityIdentities {
    private AppUserSecurityIdentities() {}

    static SecurityIdentity of(AppUser user) {
        QuarkusSecurityIdentity.Builder builder =
                QuarkusSecurityIdentity.builder().setPrincipal(() -> user.username);
        if (user.roles != null && !user.roles.isBlank()) {
            for (String role : user.roles.split(",")) {
                var trimmed = role.trim();
                if (!trimmed.isEmpty()) {
                    builder.addRole(trimmed);
                }
            }
        }
        return builder.build();
    }
}
