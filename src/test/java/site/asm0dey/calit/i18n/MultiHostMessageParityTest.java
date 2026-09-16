package site.asm0dey.calit.i18n;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.qute.i18n.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reflective parity sweep: every {@code @Message} method on {@link AppMessages} and
 * {@link AdminMessages} must have a matching key in EVERY locale property file, so a
 * translated string is never silently missing and falling back to the English default.
 *
 * <p>This is a plain JUnit test (no {@code @QuarkusTest}) — it only reflects over the
 * bundle interfaces and reads the {@code .properties} files from the classpath, so it needs
 * no CDI container / Postgres and runs fast.
 */
class MultiHostMessageParityTest {

    @Test
    void everyAppMessageKeyHasGermanAndHebrewTranslation() {
        assertParity(AppMessages.class, "messages/msg_de.properties", "messages/msg_he.properties");
    }

    @Test
    void everyAdminMessageKeyHasGermanAndHebrewTranslation() {
        assertParity(AdminMessages.class, "messages/adm_de.properties", "messages/adm_he.properties");
    }

    @Test
    void appPropertyFilesHaveNoOrphanKeys() {
        assertNoOrphans(AppMessages.class, "messages/msg_de.properties", "messages/msg_he.properties");
    }

    @Test
    void adminPropertyFilesHaveNoOrphanKeys() {
        assertNoOrphans(AdminMessages.class, "messages/adm_de.properties", "messages/adm_he.properties");
    }

    private static void assertParity(Class<?> bundle, String... localeResources) {
        Set<String> methodNames = messageMethodNames(bundle);
        assertTrue(!methodNames.isEmpty(), "Expected at least one @Message method on " + bundle.getSimpleName());

        var failures = new StringBuilder();
        for (String resource : localeResources) {
            var props = loadProperties(resource);
            Set<String> missing = methodNames.stream()
                    .filter(name -> !props.containsKey(name))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!missing.isEmpty()) {
                failures.append("\n  ")
                        .append(resource)
                        .append(" is missing ")
                        .append(missing.size())
                        .append(" key(s): ")
                        .append(missing);
            }
        }

        assertTrue(
                failures.isEmpty(),
                "Missing translations for " + bundle.getSimpleName() + " @Message keys:" + failures);
    }

    private static void assertNoOrphans(Class<?> bundle, String... localeResources) {
        Set<String> methodNames = messageMethodNames(bundle);

        var failures = new StringBuilder();
        for (String resource : localeResources) {
            var props = loadProperties(resource);
            Set<String> orphans = props.stringPropertyNames().stream()
                    .filter(key -> !methodNames.contains(key))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!orphans.isEmpty()) {
                failures.append("\n  ")
                        .append(resource)
                        .append(" has ")
                        .append(orphans.size())
                        .append(" orphan key(s) with no matching @Message method: ")
                        .append(orphans);
            }
        }

        assertTrue(
                failures.isEmpty(),
                "Orphan property keys for " + bundle.getSimpleName() + " (rename/remove them or add the @Message"
                        + " method):" + failures);
    }

    private static Set<String> messageMethodNames(Class<?> bundle) {
        var methods = bundle.getDeclaredMethods();
        return Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(Message.class))
                .map(Method::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Properties loadProperties(String classpathResource) {
        var props = new Properties();
        try (var in = MultiHostMessageParityTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
            assertNotNull(in, "Missing classpath resource: " + classpathResource);
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + classpathResource, e);
        }
        return props;
    }
}
