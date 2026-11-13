package io.github.imnotstable.msc.bench;

/** Prints "caf" and a lone 0xE9 byte (é in Cp1252, not valid UTF-8), as a Windows console tool may. */
public final class PrintNonUtf8 {

    private PrintNonUtf8() {
    }

    public static void main(String[] args) {
        System.out.write(new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n'}, 0, 5);
        System.out.flush();
    }
}
