package io.github.imnotstable.msc.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Prints a long multi-line log whose last line names the failure (#535). {@code exit}: the log, then exit 1 (a failing
 * command, or a server that stops before it is ready). {@code server}: says it is ready, prints the log for every
 * console command, and ends quietly on {@code stop}.
 */
public final class LongOutput {

    public static final String LAST = "LAST LINE: java.lang.IllegalStateException: the failure is here";

    private LongOutput() {
    }

    private static void log() {
        for (int i = 0; i < 60; i++) {
            System.out.println("[INFO] line " + i + " of a long log that says nothing about the failure");
        }
        System.out.println(LAST);
        System.out.flush();
    }

    public static void main(String[] args) throws IOException {
        if (args[0].equals("exit")) {
            log();
            System.exit(1);
        }
        System.out.println("Done (0.1s)! Listening");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null && !line.equals("stop")) {
            log();
        }
    }
}
