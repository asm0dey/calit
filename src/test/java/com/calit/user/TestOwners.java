package com.calit.user;

import jakarta.persistence.EntityManager;

/**
 * Test helper: ensures an {@code app_user} row exists for a specific id so tenant rows
 * (owner_id FK -> app_user(id), added by V8) can be persisted in owner-scope unit tests.
 *
 * <p>app_user.id is a BIGSERIAL (sequence default, not a strict IDENTITY column), so an explicit
 * id may be inserted. Idempotent: a no-op if the row already exists. Call inside the test's
 * transaction before persisting any owner-scoped row.
 */
public final class TestOwners {
    private TestOwners() {}

    /** Insert (id, 'owner<id>') into app_user if absent, so owner_id={@code id} satisfies the FK. */
    public static void ensure(EntityManager em, long id) {
        em.createNativeQuery(
                "insert into app_user (id, username, password_hash, roles, is_admin, enabled, "
                        + "must_change_password, settings_complete, created_at) "
                        + "values (?1, ?2, 'x', 'user', false, true, false, false, now()) "
                        + "on conflict (id) do nothing")
                .setParameter(1, id)
                .setParameter(2, "owner" + id)
                .executeUpdate();
    }
}
