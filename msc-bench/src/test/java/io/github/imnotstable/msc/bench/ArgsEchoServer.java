package io.github.imnotstable.msc.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for a server in a player check: prints its arguments, a ready line, and each console line it reads
 * ({@code got: <line>}) until {@code stop}.
 */
public final class ArgsEchoServer {

    private ArgsEchoServer() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("args: " + String.join(" ", args));
        System.out.println("Done (0.1s)!");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line = in.readLine(); line != null && !line.equals("stop"); line = in.readLine()) {
            System.out.println("got: " + line);
            System.out.flush();
        }
    }
}
