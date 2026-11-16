package io.github.imnotstable.msc.core.server;

import java.nio.file.Path;
import java.util.Map;

/**
 * The Gradle user home: where Gradle keeps its caches, so where the API jars a build downloaded are (#666). Gradle's own
 * rule: the {@code gradle.user.home} system property, else {@code GRADLE_USER_HOME}, else {@code <user.home>/.gradle}.
 * The core resolves it once ({@link io.github.imnotstable.msc.core.CoreConfig#gradleUserHome}), gives it to every
 * Gradle build it starts ({@link ProjectBuilder}) and reads the caches there, so the two always agree.
 */
public final class GradleUserHome {
    public static final String ENV = "GRADLE_USER_HOME";
    public static final String PROPERTY = "gradle.user.home";

    private GradleUserHome() {
    }

    /**
     * @param env      the environment, for {@code GRADLE_USER_HOME}
     * @param property the {@code gradle.user.home} system property, or null
     * @param userHome the {@code user.home} system property
     */
    public static Path resolve(Map<String, String> env, String property, String userHome) {
        String chosen = blank(property) ? env.get(ENV) : property;
        Path home = blank(chosen) ? Path.of(userHome, ".gradle") : Path.of(chosen.strip());
        return home.toAbsolutePath().normalize();
    }

    /** This process's Gradle user home. */
    public static Path ofThisProcess() {
        return resolve(System.getenv(), System.getProperty(PROPERTY), System.getProperty("user.home"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
