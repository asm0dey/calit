package site.asm0dey.calit.privacy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.CaptchaProviderConfig;
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

    final boolean mailerMocked;

    final CaptchaProviderConfig captchaProviderConfig;

    final PrivacyConfig config;

    @Inject
    public PrivacyFacts(
            @ConfigProperty(name = "google.oauth.client-id") Optional<String> googleClientId,
            @ConfigProperty(name = "calit.oidc.enabled", defaultValue = "false") boolean oidcEnabled,
            @ConfigProperty(name = "quarkus.mailer.host") Optional<String> smtpHost,
            @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false") boolean mailerMocked,
            CaptchaProviderConfig captchaProviderConfig,
            PrivacyConfig config) {
        this.googleConfigured = googleClientId.filter(s -> !s.isBlank()).isPresent();
        this.oidcConfigured = oidcEnabled;
        this.smtpHost = smtpHost;
        this.mailerMocked = mailerMocked;
        this.captchaProviderConfig = captchaProviderConfig;
        this.config = config;
    }

    public boolean isGoogleConfigured() {
        return googleConfigured;
    }

    public boolean isOidcConfigured() {
        return oidcConfigured;
    }

    /**
     * The SMTP relay this deployment hands mail to — a sub-processor the operator must name. Null
     * (no bullet) when mail is mocked ({@code quarkus.mailer.mock=true}, the %dev/%test default):
     * {@code quarkus.mailer.host} carries a non-blank {@code @WithDefault("localhost")} even when
     * mocked, so the mock flag — not blankness — is the real signal that no mail, and therefore no
     * host, is actually reached.
     */
    public String getSmtpHost() {
        if (mailerMocked) {
            return null;
        }
        return smtpHost.filter(s -> !s.isBlank()).orElse(null);
    }

    /**
     * Whether this deployment routes booking-form submissions through Cloudflare Turnstile — the
     * only {@link CaptchaProviderConfig#provider()} value that sends invitee data to a third party.
     * {@code altcha} is self-hosted proof-of-work and {@code none} sends nothing anywhere.
     */
    public boolean isTurnstileConfigured() {
        return "turnstile".equals(captchaProviderConfig.provider());
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
