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
        assertEquals(
                ChannelPolicy.Reason.UNKNOWN_SCHEME,
                policy.check("carrier-pigeon://nope").reason());
        assertEquals(ChannelPolicy.Reason.UNKNOWN_SCHEME, policy.check("   ").reason());
    }

    @Test
    void starAllowsEveryKnownScheme() {
        assertTrue(policy.check("telegram://111:AAbbCC/222333").ok());
        assertTrue(policy.check("ntfy+http://localhost:1/topic").ok());
    }

    @Test
    void aBlockedSchemeIsRejectedAndTheTransportSuffixDoesNotHideIt() {
        when(config.schemeAllowed("webhook")).thenReturn(false);
        when(config.schemeAllowed("ntfy")).thenReturn(true);

        assertEquals(
                ChannelPolicy.Reason.SCHEME_BLOCKED,
                policy.check("webhook://example.com/hook").reason());
        // "ntfy+http://" must match an allowlist entry of "ntfy": tryParse reports the channel
        // scheme with the transport suffix already split off.
        assertTrue(policy.check("ntfy+http://localhost:1/topic").ok());
    }

    @Test
    void privateTargetIsRejectedOnlyWhenTheFlagIsOff() {
        assertTrue(policy.check("ntfy+http://127.0.0.1:1/topic").ok(), "default-allow");

        when(config.allowPrivateTargets()).thenReturn(false);
        assertEquals(
                ChannelPolicy.Reason.PRIVATE_TARGET,
                policy.check("ntfy+http://127.0.0.1:1/topic").reason());
    }

    @Test
    void aCredentialInTheAuthorityIsNeverResolved() {
        // telegram://<bot-token>/<chat-id>: the authority is a SECRET, not a host. Resolving it
        // would leak the bot token to a DNS server, so the private-target check must skip it.
        when(config.allowPrivateTargets()).thenReturn(false);
        assertTrue(policy.check("telegram://111:AAbbCC/222333").ok());
        // The discriminating input: this token DOES parse as a host ("localhost" resolves to
        // loopback), so the check passes only while the host-bearing guard excludes telegram. Drop
        // that guard and this assertion turns PRIVATE_TARGET -- which is the whole point of it.
        assertTrue(policy.check("telegram://localhost/222").ok(), "a bot token must never be resolved");
    }

    @Test
    void redactionHidesTheSecret() {
        String redacted = policy.redact("telegram://111:AAbbCC/222333");
        assertFalse(redacted.contains("AAbbCC"), redacted);
    }

    @Test
    void defaultLabelIsTheChannelDisplayName() {
        assertEquals("Telegram", policy.defaultLabel("telegram://111:AAbbCC/222333"));
    }
}
