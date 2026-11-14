package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the bytes of the file {@code args[0]} to standard output as they are, for checks that read a child's output.
 * With a second argument {@code server} it then plays a server for a player check: prints a ready line and waits for
 * standard input to end (the check sends {@code stop}).
 */
public final class CatFile {

    private CatFile() {
    }

    public static void main(String[] args) throws IOException {
        System.out.write(Files.readAllBytes(Path.of(args[0])));
        if (args.length > 1 && args[1].equals("server")) {
            System.out.write("\nDone (0.1s)!\n".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            InputStream in = System.in;
            while (in.read() >= 0) {
                // until the check closes standard input after "stop"
            }
        }
        System.out.flush();
    }
}
