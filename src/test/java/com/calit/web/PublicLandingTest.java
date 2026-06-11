package com.calit.web;

import com.calit.domain.MeetingType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class PublicLandingTest {

    @Transactional
    void seed() {
        MeetingType pub = new MeetingType();
        pub.ownerId = 1L;
        pub.name = "Public Intro Call"; pub.slug = "pub-landing"; pub.durationMinutes = 30;
        pub.persist();

        MeetingType secret = new MeetingType();
        secret.ownerId = 1L;
        secret.name = "Secret VIP Session"; secret.slug = "secret-landing";
        secret.durationMinutes = 30; secret.secret = true;
        secret.persist();
    }

    @Test
    void landingShowsPublicTypeAndHidesSecretType() {
        seed();
        given()
            .when().get("/")
            .then()
                .statusCode(200)
                .body(containsString("Public Intro Call"))
                .body(not(containsString("Secret VIP Session")));
    }
}
