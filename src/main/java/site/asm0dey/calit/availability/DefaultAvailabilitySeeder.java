package site.asm0dey.calit.availability;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.calit.domain.AvailabilityRule;

/**
 * Default availability for a brand-new owner: Mon–Fri 09:00–18:00, global (meetingTypeId == null).
 *
 * <p>Not a CDI bean and not boot-time: under owner scoping a rule needs an owner_id, and at boot no
 * {@code app_user} need exist. Seeding is a per-user concern, driven from the first-login wizard
 * ({@code MeSetupResource#submit}) — the one place every user must pass through before they can use
 * {@code /me} at all, whichever of the five creation paths made their row.</p>
 */
public final class DefaultAvailabilitySeeder {

    private DefaultAvailabilitySeeder() {}

    /**
     * Persists this owner's Mon–Fri 09:00–18:00 global defaults and returns how many rules were
     * written. Idempotent: an owner who already has ANY global rule is left alone and 0 is returned,
     * so completing the wizard twice — or a user who set hours by hand before finishing it — never
     * ends up with doubled rules. Must be called inside a transaction.
     */
    public static int seedGlobalDefaults(Long ownerId) {
        if (ownerId == null) {
            return 0;
        }
        if (AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId) > 0) {
            return 0;
        }
        List<AvailabilityRule> rules = weekdayDefaults();
        for (AvailabilityRule r : rules) {
            r.ownerId = ownerId;
            r.persist();
        }
        return rules.size();
    }

    /** Mon–Fri 09:00–18:00, global (meetingTypeId == null). Unstamped — the caller sets ownerId. */
    static List<AvailabilityRule> weekdayDefaults() {
        List<AvailabilityRule> rules = new ArrayList<>();
        for (DayOfWeek d : List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(18, 0);
            r.meetingTypeId = null;
            rules.add(r);
        }
        return rules;
    }
}
