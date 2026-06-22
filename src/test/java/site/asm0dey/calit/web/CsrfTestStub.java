package site.asm0dey.calit.web;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * SEC-SECRET-04 test scaffolding. The REST CSRF extension is disabled in the {@code %test} profile so
 * the ~89 existing tokenless form-POST RestAssured sites need no token plumbing. With the extension
 * off, its real {@code @Named("csrf")} provider is absent, which would make Qute's build-time
 * validation of {@code {inject:csrf.token}} fail ("@Named bean not found for [csrf]") and crash form
 * rendering at request time. This stub stands in for it under {@code %test} only: same bean name and
 * the {@code token}/{@code parameterName} accessors the templates read, returning harmless constants.
 *
 * <p>It is never on the classpath in dev/prod (test sources only). It is further gated on
 * {@code quarkus.rest-csrf.enabled=false}, so when {@link CsrfEnforcementTest} re-enables the real
 * extension via {@code @TestProfile} (a separate app build where the property is {@code true}), this
 * stub is excluded and the genuine {@code @Named("csrf")} provider takes over — no bean ambiguity,
 * and the enforcement assertions exercise the real filter.
 */
@Named("csrf")
@ApplicationScoped
@IfBuildProfile("test")
@IfBuildProperty(name = "quarkus.rest-csrf.enabled", stringValue = "false")
public class CsrfTestStub {

    /** Mirrors the real provider's token accessor; value is irrelevant because verification is off. */
    public String getToken() {
        return "test-csrf-token";
    }

    /** Mirrors the real provider's default form-field/parameter name. */
    public String getParameterName() {
        return "csrf-token";
    }
}
