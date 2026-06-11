package com.calit.web;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Turns on opt-in signup so /signup is reachable in SignupEnabledTest. */
public class SignupEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("calit.signup.enabled", "true");
    }
}
