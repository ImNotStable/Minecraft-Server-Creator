package io.github.imnotstable.msc.core.fixture;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for {@code gradlew}. {@code assemble} (or no task) fails like javac when {@code broken.flag} exists, else
 * writes a jar; {@code check} fails like a test when {@code failing-test.flag} exists, and appends each task it ran to
 * {@code tasks.log}. With {@code slow-check.flag} or {@code -Dfake.slowCheck=true}, any run with {@code check} (also a
 * warmup's {@code mscJar check}) writes its pid to {@code check-started} and sleeps a minute. With {@code many-failing-tests.flag}, check fails 200 tests and writes their
 * results where Gradle does. With {@code thin.flag}, the jar also goes to {@code build/msc/server.jar} with one dependency in
 * {@code build/msc/libs}, as the init script's thin Minestom jar does. With {@code no-tests.flag}, check runs no test
 * ({@code NO-SOURCE}) and passes. With {@code no-jar.flag}, the build succeeds with only a sources jar. With {@code download.txt} (a jar, then a path in the Gradle user home), any run copies
 * that jar into {@code GRADLE_USER_HOME} there, as Gradle downloads a dependency into its caches.
 */
public final class FakeBuildMain {
    private FakeBuildMain() {
    }

    /**
     * What Gradle's test task writes for {@code many-failing-tests.flag}: {@code build/test-results/test/
     * TEST-DemoTest.xml}, one testcase per failed test, case0 to case{n-1}, with its failure.
     */
    private static void testResults(int n) throws java.io.IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"DemoTest\""
                + " tests=\"" + n + "\" failures=\"" + n + "\">\n");
        for (int i = 0; i < n; i++) {
            xml.append("  <testcase name=\"case").append(i).append("()\" classname=\"DemoTest\">\n")
                    .append("    <failure message=\"expected: &quot;hi").append(i).append("&quot; but was: &quot;bye")
                    .append(i).append("&quot;\" type=\"org.opentest4j.AssertionFailedError\"/>\n  </testcase>\n");
        }
        Path dir = Files.createDirectories(Path.of("build", "test-results", "test"));
        Files.writeString(dir.resolve("TEST-DemoTest.xml"), xml.append("</testsuite>\n"));
    }

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("tasks.log"), String.join(" ", args) + "\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        if (Files.exists(Path.of("download.txt"))) {
            java.util.List<String> download = Files.readAllLines(Path.of("download.txt"));
            Path to = Path.of(System.getenv("GRADLE_USER_HOME")).resolve(download.get(1));
            Files.createDirectories(to.getParent());
            Files.copy(Path.of(download.get(0)), to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (java.util.List.of(args).contains("check")
                && (Files.exists(Path.of("slow-check.flag")) || Boolean.getBoolean("fake.slowCheck"))) {
            Files.move(Files.writeString(Path.of("check-started.tmp"), String.valueOf(ProcessHandle.current().pid())),
                    Path.of("check-started"));
            Thread.sleep(60_000);
        }
        if (args.length > 0 && args[0].equals("check")) {
            // many-failing-tests.flag: 200 failed tests, far more than any cap (#473).
            if (Files.exists(Path.of("many-failing-tests.flag"))) {
                StringBuilder out = new StringBuilder("> Task :test FAILED\n");
                for (int i = 0; i < 200; i++) {
                    out.append("\nDemoTest > case").append(i).append("() FAILED\n    org.opentest4j.AssertionFailedError: ")
                            .append("expected: \"hi").append(i).append("\" but was: \"bye").append(i)
                            .append("\" in a fairly long message\n");
                }
                testResults(200);
                System.out.println(out + "200 tests completed, 200 failed\nBUILD FAILED in 1s");
                System.exit(1);
            }
            if (Files.exists(Path.of("failing-test.flag"))) {
                System.out.println("> Task :test FAILED\n\nDemoTest > greets() FAILED\n    org.opentest4j.AssertionFailedError: "
                        + "expected: \"hi\" but was: \"bye\"\n\n1 test completed, 1 failed\n\nBUILD FAILED in 1s");
                System.exit(1);
            }
            System.out.println(Files.exists(Path.of("no-tests.flag")) ? "> Task :test NO-SOURCE\nBUILD SUCCESSFUL in 1s"
                    : "> Task :test\nBUILD SUCCESSFUL in 1s");
            return;
        }
        System.out.println("> Task :compileJava" + (Files.exists(Path.of("broken.flag")) ? " FAILED" : ""));
        if (Files.exists(Path.of("broken.flag"))) {
            System.out.println("/work/src/main/java/com/example/demo/Demo.java:12: error: cannot find symbol");
            System.out.println("        player.sendMesage(\"hi\");");
            System.out.println("\nFAILURE: Build failed with an exception.\n\n* Try:\n> Run with --info.\n\nBUILD FAILED in 1s");
            System.exit(1);
        }
        // name.txt, when present, renames the project's jar.
        String name = Files.exists(Path.of("name.txt")) ? Files.readString(Path.of("name.txt")).trim() : "demo";
        Files.createDirectories(Path.of("build", "libs"));
        try (var old = Files.list(Path.of("build", "libs"))) {
            for (Path jar : old.toList()) {
                Files.delete(jar);
            }
        }
        Files.writeString(Path.of("build", "libs", name + "-1.0-sources.jar"), "sources");
        if (Files.exists(Path.of("no-jar.flag"))) {
            System.out.println("BUILD SUCCESSFUL in 1s");
            return;
        }
        Files.writeString(Path.of("build", "libs", name + "-1.0.jar"), Files.readString(Path.of("version.txt")));
        if (Files.exists(Path.of("thin.flag"))) {
            Files.createDirectories(Path.of("build", "msc", "libs"));
            Files.writeString(Path.of("build", "msc", "server.jar"), "thin " + Files.readString(Path.of("version.txt")));
            Files.writeString(Path.of("build", "msc", "libs", "dep-1.jar"), Files.readString(Path.of("thin.flag")));
        }
        System.out.println("BUILD SUCCESSFUL in 1s");
    }
}
