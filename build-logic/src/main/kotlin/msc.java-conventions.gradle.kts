// Shared Java setup for every module: toolchain, compiler flags, JUnit + AssertJ.
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javaVersion = providers.gradleProperty("msc.javaVersion").map(String::toInt).orElse(25)

java {
    toolchain {
        languageVersion = javaVersion.map(JavaLanguageVersion::of)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

// Every jar MSC builds carries the license and notice (ADR 0115); shaded and remapped jars are Jars too.
val licenseFiles = files(isolated.rootProject.projectDirectory.file("LICENSE"),
    isolated.rootProject.projectDirectory.file("NOTICE"))
tasks.withType<Jar>().configureEach {
    metaInf { from(licenseFiles) }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Keep test output short: only failures, with full exception details.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Windows: a child another concurrent test starts can inherit a handle to a file in this test's temp folder and
    // hold it until that test ends, so a folder that cannot go yet is logged, not failed (ADR 0061). Linux stays strict.
    if (System.getProperty("os.name").startsWith("Windows")) {
        systemProperty("junit.jupiter.tempdir.deletion.strategy.default",
            "org.junit.jupiter.api.io.TempDirDeletionStrategy\$IgnoreFailures")
    }
}
