package io.github.imnotstable.msc.core.fixture;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for {@code gradlew}. {@code assemble} (or no task) fails like javac when {@code broken.flag} exists, else
 * writes a jar; {@code check} fails like a test when {@code failing-test.flag} exists, and appends each task it ran to
 * {@code tasks.log}. With {@code slow-check.flag} or {@code -Dfake.slowCheck=true}, any run with {@code check} (also a
 * warmup's {@code mscJar check}) writes its pid to {@code check-started} and sleeps a minute. With {@code thin.flag}, the jar also goes to {@code build/msc/server.jar} with one dependency in
 * {@code build/msc/libs}, as the init script's thin Minestom jar does.
 */
public final class FakeBuildMain {
    private FakeBuildMain() {
    }

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("tasks.log"), String.join(" ", args) + "\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        if (java.util.List.of(args).contains("check")
                && (Files.exists(Path.of("slow-check.flag")) || Boolean.getBoolean("fake.slowCheck"))) {
            Files.move(Files.writeString(Path.of("check-started.tmp"), String.valueOf(ProcessHandle.current().pid())),
                    Path.of("check-started"));
            Thread.sleep(60_000);
        }
        if (args.length > 0 && args[0].equals("check")) {
            if (Files.exists(Path.of("failing-test.flag"))) {
                System.out.println("> Task :test FAILED\n\nDemoTest > greets() FAILED\n    org.opentest4j.AssertionFailedError: "
                        + "expected: \"hi\" but was: \"bye\"\n\n1 test completed, 1 failed\n\nBUILD FAILED in 1s");
                System.exit(1);
            }
            System.out.println("> Task :test\nBUILD SUCCESSFUL in 1s");
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
        Files.writeString(Path.of("build", "libs", name + "-1.0.jar"), Files.readString(Path.of("version.txt")));
        if (Files.exists(Path.of("thin.flag"))) {
            Files.createDirectories(Path.of("build", "msc", "libs"));
            Files.writeString(Path.of("build", "msc", "server.jar"), "thin " + Files.readString(Path.of("version.txt")));
            Files.writeString(Path.of("build", "msc", "libs", "dep-1.jar"), Files.readString(Path.of("thin.flag")));
        }
        System.out.println("BUILD SUCCESSFUL in 1s");
    }
}
