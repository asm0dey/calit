package site.asm0dey.calit.web;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Build metadata (release version + short git commit) for display in the page footer.
 * {@code APP_VERSION} and {@code GIT_COMMIT} are the primary runtime sources;
 * the {@code /git.properties} classpath resource produced by git-commit-id-maven-plugin
 * is the fallback for local builds. Exposed to Qute as {@code {inject:build.version}} /
 * {@code {inject:build.commit}}.
 */
@Named("build")
@ApplicationScoped
public class BuildInfo {
    private static final String FALLBACK = "dev";
    private final String version;
    private final String commit;

    public BuildInfo() {
        this(System.getenv(), loadProperties());
    }

    BuildInfo(Map<String, String> environment, Properties properties) {
        this.version = value(environment, "APP_VERSION", properties, "git.build.version");
        this.commit = value(environment, "GIT_COMMIT", properties, "git.commit.id.abbrev");
    }

    private static Properties loadProperties() {
        var p = new Properties();
        try (var in = BuildInfo.class.getResourceAsStream("/git.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException _) {
            // ponytail: git.properties is build-generated and tiny; a read failure just means "dev".
        }
        return p;
    }

    private static String value(
            Map<String, String> environment,
            String environmentKey,
            Properties properties,
            String propertyKey
    ) {
        var runtimeValue = environment.get(environmentKey);
        if (runtimeValue != null && !runtimeValue.isBlank()) {
            return runtimeValue;
        }
        return properties.getProperty(propertyKey, FALLBACK);
    }

    public String getVersion() {
        return version;
    }

    public String getCommit() {
        return commit;
    }
}
