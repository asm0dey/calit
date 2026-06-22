package site.asm0dey.calit.web;

import io.quarkus.test.TestTransaction;
import site.asm0dey.calit.user.AppUser;

/** Test helper: resolves seeded AppUser ids by username for owner-scoped seeding/asserts. */
final class TestOwners {
    private TestOwners() {}

    /** The id of the AppUser FormAuth logs in as (username "admin"). */
    @TestTransaction
    static Long loginOwnerId() {
        return AppUser.findByUsername("admin").id;
    }
}
