package io.github.imnotstable.msc.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Stands in for a Minestom server in the player check's encoding test: logs non-ASCII text on System.out, as
 * slf4j-simple does, and echoes console lines it reads in the JVM's stdin encoding, as a JLine console does.
 */
public final class EchoServer {
    public static final String TEXT = "é § 你 😀";

    private EchoServer() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("log: " + TEXT);
        System.out.println("Done (0.1s)! Listening");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in,
                Charset.forName(System.getProperty("stdin.encoding"))));
        for (String line = in.readLine(); line != null && !line.equals("stop"); line = in.readLine()) {
            System.out.println("got: " + line);
            System.out.flush();
        }
    }
}
