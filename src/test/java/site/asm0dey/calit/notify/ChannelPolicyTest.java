package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;
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
        assertEquals(ChannelPolicy.Reason.UNKNOWN_SCHEME, policy.check("carrier-pigeon://nope").reason());
        assertEquals(ChannelPolicy.Reason.UNKNOWN_SCHEME, policy.check("   ").reason());
    }

    /**
     * The star is the DEFAULT, so a regression that parsed it into a literal one-entry allowlist would leave
     * {@link #starAllowsEveryKnownScheme} green — it only names schemes such an allowlist might contain. This
     * asks the real config about a scheme notify4j has never heard of, which only an empty (= allow-all) set
     * admits, and pins the case-insensitive lookup at the same time.
     */
    @Test
    void starMeansEveryScheme() {
        assertTrue(config.schemeAllowed("carrier-pigeon"), "* must admit a scheme the catalog does not know");
        assertTrue(config.schemeAllowed("TELEGRAM"), "scheme matching is case-insensitive");
    }

    @Test
    void starAllowsEveryKnownScheme() {
        assertTrue(policy.check("telegram://api.telegram.org/111:AAbbCC/222333").ok());
        assertTrue(policy.check("ntfy+http://localhost:1/topic").ok());
    }

    @Test
    void aBlockedSchemeIsRejectedAndTheTransportSuffixDoesNotHideIt() {
        when(config.schemeAllowed("webhook")).thenReturn(false);
        when(config.schemeAllowed("ntfy")).thenReturn(true);

        assertEquals(ChannelPolicy.Reason.SCHEME_BLOCKED, policy.check("webhook://example.com/hook").reason());
        // "ntfy+http://" must match an allowlist entry of "ntfy": tryParse reports the channel
        // scheme with the transport suffix already split off.
        assertTrue(policy.check("ntfy+http://localhost:1/topic").ok());
    }

    @Test
    void privateTargetIsRejectedOnlyWhenTheFlagIsOff() {
        assertTrue(policy.check("ntfy+http://127.0.0.1:1/topic").ok(), "default-allow");

        when(config.allowPrivateTargets()).thenReturn(false);
        assertEquals(ChannelPolicy.Reason.PRIVATE_TARGET, policy.check("ntfy+http://127.0.0.1:1/topic").reason());
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
        assertTrue(policy.check("pushover://localhost/user-key").ok(), "an app token must never be resolved");
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
        assertEquals(ChannelPolicy.Reason.PRIVATE_TARGET, policy.check("ntfy://localhost/topic").reason());
        assertEquals(ChannelPolicy.Reason.PRIVATE_TARGET, policy.check("gotify://localhost/AppToken").reason());
        assertEquals(
                ChannelPolicy.Reason.PRIVATE_TARGET,
                policy.check("telegram://localhost/111:AAbbCC/222333").reason()
        );
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
        assertEquals(
                ChannelPolicy.Reason.INCOMPLETE,
                policy.check("telegram://111:AAbbCC/222333").reason(),
                "the host-less telegram URL calit used to document"
        );
        assertEquals(
                ChannelPolicy.Reason.INCOMPLETE,
                policy.check("telegram://api.telegram.org/111:AAbbCC").reason(),
                "chat id missing"
        );
        assertTrue(policy.check("telegram://api.telegram.org/111:AAbbCC/222333").ok());
    }

    /**
     * A URL-typed SECRET (webhook's and slack's whole URL) comes back from {@code parse} as the
     * mask, which fails notify4j's {@code invalid_url} format check. Only {@code required} errors
     * may reject, or the incomplete-URL guard would refuse every webhook in existence.
     */
    @Test
    void aMaskedSecretIsNotMistakenForAMissingPart() {
        assertTrue(policy.check("webhook://example.com/hook").ok());
        assertTrue(policy.check("slack://T000/B000/xxxx").ok());
    }

    @Test
    void redactionHidesTheSecret() {
        String redacted = policy.redact("telegram://api.telegram.org/111:AAbbCC/222333");
        assertFalse(redacted.contains("AAbbCC"), redacted);
    }

    @Test
    void defaultLabelIsTheChannelDisplayName() {
        assertEquals("Telegram", policy.defaultLabel("telegram://api.telegram.org/111:AAbbCC/222333"));
    }
}
