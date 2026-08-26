package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Black-box packaging check for calit-o89d: {@code @QuarkusIntegrationTest} boots the packaged
 * {@code quarkus-app/quarkus-run.jar} as a SEPARATE process and this class only ever talks HTTP to
 * it. That process has no Panache / {@code QuarkusTransaction} access from here, so unlike {@link
 * OgImageResourceTest} it cannot seed a meeting type -- it deliberately does NOT extend that class.
 * It exercises only the seed-free product card, and proves the fonts are really inside the
 * packaged artifact and that AWT renders against the real build output, not the in-JVM
 * {@code @QuarkusTest} classpath.
 */
@QuarkusIntegrationTest
class OgImageResourceIT {

    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void productCardRendersFromThePackagedArtifact() throws IOException {
        byte[] body = given().when()
                .get("/og.png")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("Cache-Control", "public, max-age=3600")
                .extract()
                .asByteArray();

        assertArrayEquals(PNG_MAGIC, Arrays.copyOf(body, 4));

        var image = ImageIO.read(new ByteArrayInputStream(body));
        assertNotNull(image, "response body must decode as a real image, not just start with PNG magic bytes");
        assertEquals(1200, image.getWidth());
        assertEquals(630, image.getHeight());
    }
}
