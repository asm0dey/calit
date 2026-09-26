package site.asm0dey.calit;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Makes a system UTC Clock injectable so time-dependent code can be frozen in tests.
 */
public class ClockProducer {
    @Produces
    @ApplicationScoped
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
