package io.github.imnotstable.msc.textures;

/** Bad input from the caller. The message is one line that says how to fix it. */
public final class TextureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TextureException(String message) {
        super(message);
    }
}
