package io.github.imnotstable.msc.bench;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A failing command for check tests (#462): prints an emoji and then {@code args[0]} zeros in UTF-8 and exits 1, so a
 * cut of the output's last {@code args[0]} chars falls between the emoji's two UTF-16 halves.
 */
public final class PrintsEmoji {

    private PrintsEmoji() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.print(new String(Character.toChars(0x1F600)) + "0".repeat(Integer.parseInt(args[0])));
        out.flush();
        System.exit(1);
    }
}
