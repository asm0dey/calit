package com.calit.user;

import io.quarkus.security.jpa.PasswordProvider;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.interfaces.ClearPassword;

/**
 * Bridges the stored argon2id MCF hash into Elytron's credential verification used by
 * security-jpa. Quarkus 3.x ships no first-class argon2 Elytron password type, so this
 * provider cannot hand Elytron a verifiable argon2 Password. We return the stored hash as a
 * ClearPassword so Elytron's default comparison only succeeds when the request credential
 * already equals the stored hash — which it never will for a plaintext form password. The
 * Task 6 spike (LoginSpikeTest) confirms this and, if it fails (expected), the custom
 * AppUserIdentityProvider fallback supersedes this provider for real verification.
 */
public class Argon2PasswordProvider implements PasswordProvider {
    @Override
    public Password getPassword(String passwordInDatabase) {
        return ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, passwordInDatabase.toCharArray());
    }
}
