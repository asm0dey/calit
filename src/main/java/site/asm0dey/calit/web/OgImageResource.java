package site.asm0dey.calit.web;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.Usernames;
import site.asm0dey.calit.web.og.CardRenderer;

/**
 * The link-preview card images. Public and unauthenticated: unfurl bots fetch them with no session.
 *
 * <p>Rendered per request (~2 ms) rather than cached server-side — the ETag changes with the inputs,
 * so a renamed meeting type needs no invalidation anywhere, and Cache-Control puts the cache in the
 * proxy/CDN layer that unfurlers already sit behind.</p>
 *
 * <p>Every miss (unknown user, unknown slug, inactive or secret type, text no shipped font can draw)
 * degrades to the product card with HTTP 200. A 404 would unfurl as a broken image; the product card
 * at least says "this is a calit link".</p>
 */
@Path("/")
public class OgImageResource {

    static final int MAX_AGE_SECONDS = 3600;

    final CardRenderer renderer;

    @Inject
    public OgImageResource(CardRenderer renderer) {
        this.renderer = renderer;
    }

    @GET
    @Path("/og.png")
    @Produces("image/png")
    public Response product(@Context Request request) {
        return png(request, "product", renderer.product());
    }

    @GET
    @Path("/og/{user}.png")
    @Produces("image/png")
    public Response owner(@Context Request request, @PathParam("user") String user) {
        AppUser owner = findOwner(user);
        if (owner == null) {
            return product(request);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        String name = settings == null || settings.ownerName == null || settings.ownerName.isBlank()
                ? owner.username
                : settings.ownerName;
        var card = new CardRenderer.Card("", name, "Book a meeting");
        if (!renderer.renderable(card)) {
            return product(request);
        }
        return png(request, "owner:" + owner.id + ":" + name, renderer.render(card));
    }

    @GET
    @Path("/og/{user}/{slug}.png")
    @Produces("image/png")
    public Response meetingType(
            @Context Request request, @PathParam("user") String user, @PathParam("slug") String slug) {
        AppUser owner = findOwner(user);
        if (owner == null) {
            return product(request);
        }
        MeetingType type = MeetingType.findBySlug(owner.id, slug);
        // Secret types are unlisted but bookable by direct link: naming one in a preview would
        // defeat the flag for anyone who glances at the chat.
        if (type == null || !type.active || type.secret) {
            return product(request);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        String name = settings == null || settings.ownerName == null || settings.ownerName.isBlank()
                ? owner.username
                : settings.ownerName;
        String meta = meta(type);
        var card = new CardRenderer.Card(name, type.name, meta);
        if (!renderer.renderable(card)) {
            return product(request);
        }
        return png(request, "type:" + type.id + ":" + name + ":" + type.name + ":" + meta, renderer.render(card));
    }

    static String meta(MeetingType type) {
        List<Integer> durations = MeetingTypeDuration.allowedDurations(type);
        var lengths = durations.stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
        return lengths + " min · " + location(type);
    }

    static String location(MeetingType type) {
        return switch (type.locationType) {
            case GOOGLE_MEET -> "Google Meet";
            case PHONE -> "Phone";
            case IN_PERSON -> "In person";
            case CUSTOM -> "Online";
        };
    }

    static AppUser findOwner(String user) {
        String normalized = Usernames.normalize(user);
        return normalized == null ? null : AppUser.find("username", normalized).firstResult();
    }

    Response png(Request request, String cacheKey, byte[] body) {
        EntityTag etag = new EntityTag(sha256(cacheKey));
        Response.ResponseBuilder preconditionFailed = request.evaluatePreconditions(etag);
        // jakarta.ws.rs.core.CacheControl has no "public" directive — it only ever OMITS flags
        // (no-transform, private, ...), so building the header through it can never spell the
        // literal "public" token the spec requires. Set the header text directly instead.
        var cacheControl = "public, max-age=" + MAX_AGE_SECONDS;
        if (preconditionFailed != null) {
            return preconditionFailed
                    .tag(etag)
                    .header("Cache-Control", cacheControl)
                    .build();
        }
        return Response.ok(body, "image/png")
                .tag(etag)
                .header("Cache-Control", cacheControl)
                .build();
    }

    static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
