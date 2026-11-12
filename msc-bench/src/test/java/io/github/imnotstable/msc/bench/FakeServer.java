package io.github.imnotstable.msc.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Stands in for a Minestom server in player-check tests: says it is ready and answers console commands. */
public final class FakeServer {

    private FakeServer() {
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Done (0.1s)! Listening");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("stop")) {
                System.out.println("stopping");
                return;
            }
            System.out.println("reply to " + line);
        }
    }
}
