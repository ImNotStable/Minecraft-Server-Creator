package io.github.imnotstable.msc.core.fixture;

import java.nio.charset.StandardCharsets;

/** Prints 30 000 three-byte characters in one write, so pipe reads end in the middle of some. */
public final class Utf8PrintMain {
    private Utf8PrintMain() {
    }

    public static void main(String[] args) throws Exception {
        System.out.write("€".repeat(30_000).getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
