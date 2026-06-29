package site.asm0dey.calit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthResourceTest {
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
