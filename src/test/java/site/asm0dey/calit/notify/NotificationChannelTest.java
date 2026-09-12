package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The channel table holds a secret-bearing URL: it must be encrypted at rest (SEC-SECRET-02, same
 * mechanism as GoogleCredential's tokens) and every finder must be owner-scoped.
 */
@QuarkusTest
class NotificationChannelTest {

    private static final String TELEGRAM = "telegram://111:AAbbCC/222333";

    @Inject
    EntityManager em;

    // notification_channel.owner_id carries a real FK to app_user (like every other owner-scoped
    // table in this project — see V8__owner_scoping.sql); only the reseeded admin (id 1) exists by
    // default, so any test row for a second owner needs that owner seeded first.
    private void otherOwner() {
        QuarkusTransaction.requiringNew().run(() -> MultiHostFixtures.enabledUser("other"));
    }

    private Long persist(long ownerId, String url, String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = url;
            c.label = label;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    @Test
    void urlIsEncryptedAtRestAndDecryptsOnRead() {
        var id = persist(1L, TELEGRAM, "Phone");

        String raw = QuarkusTransaction.requiringNew()
                .call(() -> (String) em.createNativeQuery("select url from notification_channel where id = :id")
                        .setParameter("id", id)
                        .getSingleResult());
        assertTrue(raw.startsWith("enc:v1:"), "url column must hold ciphertext, was: " + raw);
        assertFalse(raw.contains("AAbbCC"), "the bot token must not appear in the column");

        String readBack = QuarkusTransaction.requiringNew()
                .call(() -> ((NotificationChannel) NotificationChannel.findById(id)).url);
        assertEquals(TELEGRAM, readBack);
    }

    @Test
    void forOwnerNeverReturnsAnotherOwnersChannel() {
        otherOwner();
        persist(1L, TELEGRAM, "Mine");
        persist(2L, "slack://T00/B00/xxxx", "Theirs");

        var mine = QuarkusTransaction.requiringNew().call(() -> NotificationChannel.forOwner(1L));
        assertEquals(1, mine.size());
        assertEquals("Mine", mine.getFirst().label);
    }

    @Test
    void ownedByRejectsAnotherOwnersId() {
        otherOwner();
        var theirs = persist(2L, "slack://T00/B00/xxxx", "Theirs");
        assertNull(QuarkusTransaction.requiringNew().call(() -> NotificationChannel.ownedBy(theirs, 1L)));
        assertNotNull(QuarkusTransaction.requiringNew().call(() -> NotificationChannel.ownedBy(theirs, 2L)));
    }
}
