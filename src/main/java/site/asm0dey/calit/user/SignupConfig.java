package site.asm0dey.calit.user;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether self-service signup is on ({@code SIGNUP_ENABLED}). Read once at startup, so toggling it
 * still needs a restart; it is a bean of its own so a test can switch signup on without a test
 * profile and the app restart that comes with one.
 */
@ApplicationScoped
public class SignupConfig {
    final boolean enabled;

    @Inject
    public SignupConfig(@ConfigProperty(name = "calit.signup.enabled", defaultValue = "false") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
