package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where the core listens and the session token. Read from the environment the core sets
 * ({@code MSC_CORE_ADDRESS}, {@code MSC_TOKEN}), or from the system properties
 * {@code msc.core.address} and {@code msc.token} for servers started by hand and for tests.
 *
 * <p>A system property is on the command line, which every user of the machine can read ({@code ps}); the
 * environment is not. So a server started by hand on a shared machine names a file holding the token instead,
 * readable by its owner only: {@code -Dmsc.token.file=<path>} (ADR 0043).
 */
public record CoreAddress(String host, int port, String token) {
    public static final String PROPERTY_ADDRESS = "msc.core.address";
    public static final String PROPERTY_TOKEN = "msc.token";
    public static final String PROPERTY_TOKEN_FILE = "msc.token.file";

    public static Optional<CoreAddress> resolve() {
        return resolve(System::getenv, System::getProperty);
    }

    public static Optional<CoreAddress> resolve(UnaryOperator<String> env, UnaryOperator<String> properties) {
        String address = env.apply(Limits.ENV_CORE_ADDRESS);
        String token = env.apply(Limits.ENV_TOKEN);
        if (address == null || token == null) {
            address = properties.apply(PROPERTY_ADDRESS);
            token = properties.apply(PROPERTY_TOKEN);
            String file = properties.apply(PROPERTY_TOKEN_FILE);
            if (token == null && file != null) {
                token = readToken(Path.of(file));
            }
        }
        if (address == null || token == null) {
            return Optional.empty();
        }
        int colon = address.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("core address must be host:port, got " + address);
        }
        return Optional.of(new CoreAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)), token));
    }

    /** The file's text without surrounding whitespace, so an editor's final newline is not part of the token. */
    private static String readToken(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the msc token file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Leaves the token out, so a log line never carries it. */
    @Override
    public String toString() {
        return "CoreAddress[" + host + ":" + port + "]";
    }
}
