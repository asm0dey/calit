package site.asm0dey.calit.privacy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.notify.NotificationChannel;

/**
 * What THIS deployment actually does, exposed to Qute as {@code {inject:privacy.*}} so the shipped
 * policy describes the running software rather than the feature set. A deployment without Google
 * configured stops claiming it talks to Google; one with retention unset says so plainly instead of
 * implying a schedule it does not run.
 */
@Named("privacy")
@ApplicationScoped
public class PrivacyFacts {

    final boolean googleConfigured;

    final boolean oidcConfigured;

    final Optional<String> smtpHost;

    final boolean signupOpen;

    final PrivacyConfig config;

    @Inject
    public PrivacyFacts(
            @ConfigProperty(name = "google.oauth.client-id") Optional<String> googleClientId,
            @ConfigProperty(name = "calit.oidc.enabled", defaultValue = "false") boolean oidcEnabled,
            @ConfigProperty(name = "quarkus.mailer.host") Optional<String> smtpHost,
            @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false") boolean signupOpen,
            PrivacyConfig config) {
        this.googleConfigured = googleClientId.filter(s -> !s.isBlank()).isPresent();
        this.oidcConfigured = oidcEnabled;
        this.smtpHost = smtpHost;
        this.signupOpen = signupOpen;
        this.config = config;
    }

    public boolean isGoogleConfigured() {
        return googleConfigured;
    }

    public boolean isOidcConfigured() {
        return oidcConfigured;
    }

    /** The SMTP relay this deployment hands mail to — a sub-processor the operator must name. */
    public String getSmtpHost() {
        return smtpHost.filter(s -> !s.isBlank()).orElse(null);
    }

    public boolean isSignupOpen() {
        return signupOpen;
    }

    public boolean isInviteeErasureEnabled() {
        return config.inviteeErasureEnabled();
    }

    /** Instance retention window in days, or null when bookings are kept indefinitely. */
    public Integer getRetentionDays() {
        return config.bookingRetentionDays().orElse(null);
    }

    /**
     * Whether ANY owner on this instance has an outbound notification channel. A live count, not
     * config: channels are per-owner rows, and the policy has to disclose the category of recipient
     * as soon as one exists.
     */
    public boolean isAnyChannelConfigured() {
        return NotificationChannel.count() > 0;
    }
}
