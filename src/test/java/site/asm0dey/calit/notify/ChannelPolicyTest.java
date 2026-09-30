package site.asm0dey.calit.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChannelPolicyTest {
    @Inject
    ChannelPolicy policy;
    @InjectSpy
    NotifyConfig config;

    @Test
    void unknownSchemeIsRejected() {
        assertThat(policy.check("carrier-pigeon://nope").reason()).isEqualTo(ChannelPolicy.Reason.UNKNOWN_SCHEME);
        assertThat(policy.check("   ").reason()).isEqualTo(ChannelPolicy.Reason.UNKNOWN_SCHEME);
    }

    /**
     * The star is the DEFAULT, so a regression that parsed it into a literal one-entry allowlist would leave
     * {@link #starAllowsEveryKnownScheme} green — it only names schemes such an allowlist might contain. This
     * asks the real config about a scheme notify4j has never heard of, which only an empty (= allow-all) set
     * admits, and pins the case-insensitive lookup at the same time.
     */
    @Test
    void starMeansEveryScheme() {
        assertThat(config.schemeAllowed("carrier-pigeon")).as("* must admit a scheme the catalog does not know").isTrue();
        assertThat(config.schemeAllowed("TELEGRAM")).as("scheme matching is case-insensitive").isTrue();
    }

    @Test
    void starAllowsEveryKnownScheme() {
        assertThat(policy.check("telegram://api.telegram.org/111:AAbbCC/222333").ok()).isTrue();
        assertThat(policy.check("ntfy+http://localhost:1/topic").ok()).isTrue();
    }

    @Test
    void aBlockedSchemeIsRejectedAndTheTransportSuffixDoesNotHideIt() {
        when(config.schemeAllowed("webhook")).thenReturn(false);
        when(config.schemeAllowed("ntfy")).thenReturn(true);

        assertThat(policy.check("webhook://example.com/hook").reason()).isEqualTo(ChannelPolicy.Reason.SCHEME_BLOCKED);
        // "ntfy+http://" must match an allowlist entry of "ntfy": tryParse reports the channel
        // scheme with the transport suffix already split off.
        assertThat(policy.check("ntfy+http://localhost:1/topic").ok()).isTrue();
    }

    @Test
    void privateTargetIsRejectedOnlyWhenTheFlagIsOff() {
        assertThat(policy.check("ntfy+http://127.0.0.1:1/topic").ok()).as("default-allow").isTrue();

        when(config.allowPrivateTargets()).thenReturn(false);
        assertThat(policy.check("ntfy+http://127.0.0.1:1/topic").reason()).isEqualTo(
                ChannelPolicy.Reason.PRIVATE_TARGET
        );
    }

    @Test
    void aCredentialInTheAuthorityIsNeverResolved() {
        // pushover://<app-token>/<user-key> puts a SECRET where a host would go -- its descriptor
        // declares neither a host field nor a URL-typed one. Resolving that authority would hand
        // the token to a DNS server for nothing, so the private-target check must skip it.
        when(config.allowPrivateTargets()).thenReturn(false);
        // The discriminating input: this token DOES parse as a host ("localhost" resolves to
        // loopback), so the check passes only while the host-bearing guard excludes pushover. Drop
        // that guard and this assertion turns PRIVATE_TARGET -- which is the whole point of it.
        assertThat(policy.check("pushover://localhost/user-key").ok()).as("an app token must never be resolved").isTrue();
    }

    /**
     * telegram, ntfy and gotify put a real SERVER in the authority -- {@code api.telegram.org}, or
     * whichever box runs your ntfy. They declare it as a {@code host} field rather than a URL-typed
     * one, which used to put them outside the private-target guard: {@code ntfy://192.168.1.5/topic}
     * sailed past a server that had switched private targets off.
     */
    @Test
    void aHostFieldIsAPrivateTargetLikeAnyOther() {
        when(config.allowPrivateTargets()).thenReturn(false);
        assertThat(policy.check("ntfy://localhost/topic").reason()).isEqualTo(ChannelPolicy.Reason.PRIVATE_TARGET);
        assertThat(policy.check("gotify://localhost/AppToken").reason()).isEqualTo(ChannelPolicy.Reason.PRIVATE_TARGET);
        assertThat(policy.check("telegram://localhost/111:AAbbCC/222333").reason())
            .isEqualTo(ChannelPolicy.Reason.PRIVATE_TARGET);
    }

    /**
     * The bug behind #216: notify4j's telegram URL carries the Bot API host in the authority and
     * BOTH the token and the chat id in the path. {@code catalog.tryParse} only DECOMPOSES, so the
     * host-less form it produced -- token in the authority, one path segment left -- came back
     * "parsed" with an empty chatId, saved without complaint, and then threw in the delivery
     * parser on every send. A channel that saves and can never deliver must be refused up front.
     */
    @Test
    void aUrlMissingARequiredPartIsRejectedRatherThanSavedAndDead() {
        assertThat(policy.check("telegram://111:AAbbCC/222333").reason())
            .as("the host-less telegram URL calit used to document")
            .isEqualTo(ChannelPolicy.Reason.INCOMPLETE);
        assertThat(policy.check("telegram://api.telegram.org/111:AAbbCC").reason())
            .as("chat id missing")
            .isEqualTo(ChannelPolicy.Reason.INCOMPLETE);
        assertThat(policy.check("telegram://api.telegram.org/111:AAbbCC/222333").ok()).isTrue();
    }

    /**
     * A URL-typed SECRET (webhook's and slack's whole URL) comes back from {@code parse} as the
     * mask, which fails notify4j's {@code invalid_url} format check. Only {@code required} errors
     * may reject, or the incomplete-URL guard would refuse every webhook in existence.
     */
    @Test
    void aMaskedSecretIsNotMistakenForAMissingPart() {
        assertThat(policy.check("webhook://example.com/hook").ok()).isTrue();
        assertThat(policy.check("slack://T000/B000/xxxx").ok()).isTrue();
    }

    @Test
    void redactionHidesTheSecret() {
        String redacted = policy.redact("telegram://api.telegram.org/111:AAbbCC/222333");
        assertThat(redacted).as(redacted).doesNotContain("AAbbCC");
    }

    @Test
    void defaultLabelIsTheChannelDisplayName() {
        assertThat(policy.defaultLabel("telegram://api.telegram.org/111:AAbbCC/222333")).isEqualTo("Telegram");
    }
}
