package io.github.imnotstable.msc.core.fixture;

import java.nio.file.Files;
import java.nio.file.Path;

/** A stand-in for {@code gradlew build}: fails like javac when {@code broken.flag} exists, else writes a jar. */
public final class FakeBuildMain {
    private FakeBuildMain() {
    }

    public static void main(String[] args) throws Exception {
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
        System.out.println("BUILD SUCCESSFUL in 1s");
    }
}
