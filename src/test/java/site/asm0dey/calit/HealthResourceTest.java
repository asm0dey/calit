package site.asm0dey.calit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.BeforeEach;
import site.asm0dey.calit.health.GoogleHealthCheck;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthResourceTest {
    /**
     * The real check opens a socket to oauth2.googleapis.com (the %test client id is non-blank): it
     * needs internet access and waits out a 2 s timeout without it.
     */
    @BeforeEach
    void noGoogleSocket() {
        GoogleHealthCheck stub = mock(GoogleHealthCheck.class);
        when(stub.call()).thenReturn(HealthCheckResponse
            .named("Google")
            .up()
            .withData("state", "not-configured")
            .build()
        );
        QuarkusMock.installMockForType(stub, GoogleHealthCheck.class, Readiness.Literal.INSTANCE);
    }

    @Test
    void healthReturnsOk() {
        given().when().get("/api/health").then().statusCode(200).body(is("ok"));
    }

    @Test
    void readinessProbeIsUp() {
        // Horizontal-scalability requirement: the orchestrator / load balancer polls this.
        given().when().get("/q/health/ready").then().statusCode(200);
    }
}
