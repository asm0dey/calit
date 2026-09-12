package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The routing rule: link rows for this meeting type that belong to THIS host's channels select
 * exactly those channels; no such rows means this host inherits all of their own channels. The
 * per-host scoping is what lets one host narrow a co-hosted type while their co-host keeps
 * inheriting.
 */
@QuarkusTest
class ChannelRouterTest {

    private static final long HOST_A = 1L;
    private static final long HOST_B = 2L;

    @Inject
    ChannelRouter router;

    private Long channel(long ownerId, String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = "ntfy+http://localhost:1/" + label;
            c.label = label;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    private MeetingType sharedType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(HOST_A, "Creator");
            MultiHostFixtures.settings(HOST_B, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(HOST_A, HOST_B, "routed", 30, false);
        });
    }

    @Test
    void noLinkRowsMeansInheritEveryChannelOfThatHost() {
        channel(HOST_A, "phone");
        channel(HOST_A, "slack");
        MeetingType type = sharedType();

        List<NotificationChannel> picked = router.channelsFor(HOST_A, type.id);

        assertEquals(2, picked.size());
    }

    @Test
    void linkRowsSelectExactlyThoseChannels() {
        var phone = channel(HOST_A, "phone");
        channel(HOST_A, "slack");
        MeetingType type = sharedType();
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, List.of(phone), List.of(phone)));

        List<NotificationChannel> picked = router.channelsFor(HOST_A, type.id);

        assertEquals(1, picked.size());
        assertEquals("phone", picked.getFirst().label);
    }

    @Test
    void oneHostsOverrideDoesNotNarrowTheCoHost() {
        // sharedType() seeds HOST_B (via MultiHostFixtures.enabledUser) before any HOST_B-owned
        // row can be persisted — notification_channel.owner_id carries a real FK to app_user.
        MeetingType type = sharedType();
        var aPhone = channel(HOST_A, "a-phone");
        channel(HOST_A, "a-slack");
        channel(HOST_B, "b-phone");
        channel(HOST_B, "b-slack");
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, List.of(aPhone), List.of(aPhone)));

        assertEquals(1, router.channelsFor(HOST_A, type.id).size(), "host A narrowed to their override");
        assertEquals(2, router.channelsFor(HOST_B, type.id).size(), "host B still inherits all of theirs");
    }

    @Test
    void anotherOwnersChannelIsNeverSelected() {
        // sharedType() seeds HOST_B before any HOST_B-owned row can be persisted (FK to app_user).
        MeetingType type = sharedType();
        channel(HOST_B, "b-phone");

        assertTrue(router.channelsFor(HOST_A, type.id).isEmpty());
    }

    @Test
    void nullMeetingTypeInheritsEverything() {
        channel(HOST_A, "phone");

        assertEquals(1, router.channelsFor(HOST_A, null).size());
    }
}
