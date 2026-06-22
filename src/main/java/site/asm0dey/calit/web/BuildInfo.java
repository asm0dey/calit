package site.asm0dey.calit.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build metadata (release version + short git commit) for display in the page footer.
 * Loaded once from the {@code /git.properties} classpath resource produced by
 * git-commit-id-maven-plugin. Exposed to Qute as {@code {inject:build.version}} /
 * {@code {inject:build.commit}}.
 */
@Named("build")
@ApplicationScoped
public class BuildInfo {

    private static final String FALLBACK = "dev";

    private final String version;
    private final String commit;

    public BuildInfo() {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/git.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException _) {
            // ponytail: git.properties is build-generated and tiny; a read failure just means "dev".
        }
        this.version = p.getProperty("git.build.version", FALLBACK);
        this.commit = p.getProperty("git.commit.id.abbrev", FALLBACK);
    }

    public String getVersion() {
        return version;
    }

    public String getCommit() {
        return commit;
    }
}
