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

    /**
     * Mints a signed proof-of-work challenge valid for 5 minutes.
     *
     * <p>ALTCHA verification is stateless (HMAC, hash and expiry only, with no consumed-solution
     * store), so a solved payload stays valid for replay until it expires. The always-on honeypot
     * and per-email daily cap are the abuse backstops; persist consumed challenge signatures here
     * if replay abuse is ever observed.
     */
    @GET
    @Path("/challenge")
    @Produces(MediaType.APPLICATION_JSON)
    public Altcha.Challenge challenge() throws Exception {
        var opts = new Altcha.ChallengeOptions()
                .algorithm(Altcha.Algorithm.SHA256)
                .maxNumber(maxNumber)
                .hmacKey(hmacKey.orElse(""))
                .expiresInSeconds(300);
        return Altcha.createChallenge(opts);
    }
}
