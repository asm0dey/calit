package com.calit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/** Makes a system UTC Clock injectable so time-dependent code can be frozen in tests. */
public class ClockProducer {

    @Produces
    @ApplicationScoped
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
