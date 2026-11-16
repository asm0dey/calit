package site.asm0dey.calit.web;

import module java.base;
import io.quarkus.qute.EngineConfiguration;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.ValueResolver;

/**
 * Fails any template lookup on a calit type that no build-time generated value resolver answered.
 * <p>
 * Qute ends its resolver chain with {@code ReflectionValueResolver} (priority -1). In JVM mode it quietly
 * answers whatever the generated resolvers missed; in a native image there is no reflection metadata and
 * the page 500s with "Property not found". Sitting just above it, this resolver turns every page the suite
 * renders into a check that native would render it too. quarkusio/quarkus#58441 is one way to get there:
 * a type passed to a message bundle method gets a resolver trimmed to that one member.
 */
@EngineConfiguration
public class NoReflectionFallbackResolver implements ValueResolver {
    private static final String APP_PACKAGE = "site.asm0dey.calit.";

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public boolean appliesTo(EvalContext context) {
        var base = context.getBase();
        return base != null && base.getClass().getName().startsWith(APP_PACKAGE);
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext context) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(
                        "No generated Qute value resolver for "
                        + context.getBase().getClass().getName()
                        + "."
                        + context.getName()
                        + " -- JVM falls back to reflection, the native image 500s. See NativeTemplateData."
                )
        );
    }
}
