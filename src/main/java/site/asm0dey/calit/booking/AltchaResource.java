package site.asm0dey.calit.booking;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Optional;
import org.altcha.altcha.v1.Altcha;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Mints ALTCHA proof-of-work challenges for the booking-form widget. Public + unauthenticated:
 * issuing a challenge is cheap and safe to expose. The widget fetches this, solves it client-side,
 * and posts the base64 solution back in the {@code altcha} form field (verified in {@link CaptchaVerifier}).
 */
@Path("/altcha")
public class AltchaResource {

    @ConfigProperty(name = "calit.captcha.altcha.hmac-key")
    Optional<String> hmacKey;

    @ConfigProperty(name = "calit.captcha.altcha.max-number", defaultValue = "100000")
    long maxNumber;

    @GET
    @Path("/challenge")
    @Produces(MediaType.APPLICATION_JSON)
    public Altcha.Challenge challenge() throws Exception {
        var opts = new Altcha.ChallengeOptions()
                .algorithm(Altcha.Algorithm.SHA256)
                .maxNumber(maxNumber)
                .hmacKey(hmacKey.orElse(""))
                .expiresInSeconds(300); // challenge is single-use within 5 minutes
        return Altcha.createChallenge(opts);
    }
}
