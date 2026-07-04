package site.asm0dey.calit.scheduler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Widens the scheduler grace window to 120s for the reminder/expiry grace-window tests. */
public class Grace120Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("calit.scheduler.grace-seconds", "120");
    }
}
