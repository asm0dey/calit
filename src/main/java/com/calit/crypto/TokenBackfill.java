package com.calit.crypto;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * One-time, idempotent backfill: encrypt any Google token rows still stored as plaintext
 * (rows written before SEC-SECRET-02 shipped). Runs at startup on every replica but only
 * rewrites rows lacking the ciphertext marker, so it is safe to run repeatedly and converges.
 * Tokens are re-encrypted, never rotated — every already-connected calendar keeps working.
 */
@ApplicationScoped
public class TokenBackfill {

    private static final Logger LOG = Logger.getLogger(TokenBackfill.class);

    @Inject
    EntityManager em;

    @Inject
    TokenCipher cipher;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        encryptLegacy();
    }

    void encryptLegacy() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "select id, refresh_token, access_token from google_credential "
                        + "where refresh_token not like 'enc:v1:%' or access_token not like 'enc:v1:%' "
                        + "for update skip locked").getResultList();
        int migrated = 0;
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String refresh = (String) row[1];
            String access = (String) row[2];
            boolean changed = false;
            if (refresh != null && !cipher.looksEncrypted(refresh)) {
                refresh = cipher.encrypt(refresh);
                changed = true;
            }
            if (access != null && !cipher.looksEncrypted(access)) {
                access = cipher.encrypt(access);
                changed = true;
            }
            if (changed) {
                em.createNativeQuery(
                        "update google_credential set refresh_token = :r, access_token = :a where id = :id")
                        .setParameter("r", refresh)
                        .setParameter("a", access)
                        .setParameter("id", id)
                        .executeUpdate();
                migrated++;
            }
        }
        if (migrated > 0) {
            LOG.infof("Encrypted %d legacy Google token row(s) at rest (SEC-SECRET-02).", migrated);
        }
    }
}
