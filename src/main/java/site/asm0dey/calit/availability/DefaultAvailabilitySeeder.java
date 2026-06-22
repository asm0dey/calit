package site.asm0dey.calit.availability;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import site.asm0dey.calit.domain.AvailabilityRule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2: boot-time GLOBAL availability seeding is disabled — under owner scoping a rule needs an
 * owner_id and at boot no {@code app_user} may exist yet. Default-availability seeding becomes a
 * per-user concern triggered by Phase 4's first-login wizard (which knows the owner id and stamps it),
 * reusing {@link #weekdayDefaults()}.
 */
@ApplicationScoped
@UnlessBuildProfile("test")
public class DefaultAvailabilitySeeder {

    /**
     * Phase 2: boot-time GLOBAL seeding is disabled — a rule now needs an owner_id and at boot no
     * app_user may exist. Phase 4's first-login wizard seeds each new owner's default availability
     * (it knows the owner id and stamps it), reusing {@link #weekdayDefaults()}.
     */
    void onStart(@Observes StartupEvent ev) {
        // intentionally no-op until Phase 4 wires per-owner seeding
    }

    /** Mon–Fri 09:00–18:00, global (meetingTypeId == null). */
    static List<AvailabilityRule> weekdayDefaults() {
        List<AvailabilityRule> rules = new ArrayList<>();
        for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                   DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
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
