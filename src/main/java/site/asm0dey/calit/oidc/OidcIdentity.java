package site.asm0dey.calit.oidc;

import java.util.Set;

/**
 * The claims we read from a verified OIDC id_token. {@code groups} is the id_token "groups" claim
 * (empty if the provider sends none). Signature/issuer/audience/nonce were already verified by
 * quarkus-oidc before this record is built.
 */
public record OidcIdentity(String sub, String email, boolean emailVerified, Set<String> groups) {}
