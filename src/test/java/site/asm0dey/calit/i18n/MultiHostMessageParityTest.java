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

    /**
     * Keys whose Hebrew translation is deliberately deferred by the GDPR/privacy epic
     * (calit-l3fk, Task 5 onward): the copy is legal-adjacent and nobody in the review loop can
     * check Hebrew nuance, so each task ships the English {@code @Message} default plus German
     * only. Task 12 of that epic files a tracking issue ("i18n(he): Hebrew translations for the
     * GDPR/privacy copy") for this gap; until it's resolved a Hebrew viewer sees the English
     * default for these keys — never a missing/blank string. German parity is NOT exempted: every
     * key below still needs a {@code de=} line, checked the same as any other key.
     */
    private static final Set<String> HE_DEFERRED_APP_KEYS = Set.of(
            "pub_erase_confirm_title",
            "pub_erase_confirm_h1",
            "pub_erase_confirm_desc",
            "pub_erase_confirm_cancels_first",
            "pub_erase_boundary_h2",
            "pub_erase_boundary_google",
            "pub_erase_boundary_channels",
            "pub_erase_boundary_mail",
            "pub_erase_confirm_btn",
            "pub_erase_keep_btn",
            "pub_erased_title",
            "pub_erased_h1",
            "pub_erased_desc",
            "pub_erased_local_ok",
            "pub_erased_google_removed",
            "pub_erased_google_unreachable",
            "pub_erased_google_none",
            "pub_erased_channels",
            "pub_erased_mail",
            "pub_erased_contact",
            "pub_erased_btn",
            "pub_erase_disabled_notice",
            "pub_erase_disabled_notice_no_contact",
            "pub_manage_download_data");

    private static final Set<String> HE_DEFERRED_ADMIN_KEYS = Set.of(
            "adm_booking_invitee_erased",
            "adm_settings_export_link",
            "adm_delete_account_link",
            "adm_delete_account_title",
            "adm_delete_account_desc",
            "adm_delete_account_google_note",
            "adm_delete_account_password_label",
            "adm_delete_account_username_label",
            "adm_delete_account_btn",
            "adm_delete_account_cancel",
            "adm_delete_account_error_mismatch",
            "adm_delete_account_error_last_admin",
            "adm_users_delete",
            "adm_users_error_last_admin_delete",
            "adm_users_error_delete_self",
            "adm_settings_label_retention",
            "adm_settings_retention_hint_with_default",
            "adm_settings_retention_hint_forever");

    @Test
    void everyAppMessageKeyHasGermanAndHebrewTranslation() {
        assertParity(
                AppMessages.class,
                "messages/msg_de.properties",
                Set.of(),
                "messages/msg_he.properties",
                HE_DEFERRED_APP_KEYS);
    }

    @Test
    void everyAdminMessageKeyHasGermanAndHebrewTranslation() {
        assertParity(
                AdminMessages.class,
                "messages/adm_de.properties",
                Set.of(),
                "messages/adm_he.properties",
                HE_DEFERRED_ADMIN_KEYS);
    }

    @Test
    void appPropertyFilesHaveNoOrphanKeys() {
        assertNoOrphans(AppMessages.class, "messages/msg_de.properties", "messages/msg_he.properties");
    }

    @Test
    void adminPropertyFilesHaveNoOrphanKeys() {
        assertNoOrphans(AdminMessages.class, "messages/adm_de.properties", "messages/adm_he.properties");
    }

    private static void assertParity(
            Class<?> bundle, String deResource, Set<String> deExemptions, String heResource, Set<String> heExemptions) {
        Set<String> methodNames = messageMethodNames(bundle);
        assertTrue(!methodNames.isEmpty(), "Expected at least one @Message method on " + bundle.getSimpleName());

        var failures = new StringBuilder();
        appendMissing(failures, deResource, methodNames, deExemptions);
        appendMissing(failures, heResource, methodNames, heExemptions);

        assertTrue(
                failures.isEmpty(),
                "Missing translations for " + bundle.getSimpleName() + " @Message keys:" + failures);
    }

    private static void appendMissing(
            StringBuilder failures, String resource, Set<String> methodNames, Set<String> exemptions) {
        var props = loadProperties(resource);
        Set<String> missing = methodNames.stream()
                .filter(name -> !props.containsKey(name) && !exemptions.contains(name))
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
