package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.protocol.Limits;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where the core listens and the session token. Read from the environment the core sets
 * ({@code MSC_CORE_ADDRESS}, {@code MSC_TOKEN}), or from the system properties
 * {@code msc.core.address} and {@code msc.token} for servers started by hand and for tests.
 */
record CoreAddress(String host, int port, String token) {
    static final String PROPERTY_ADDRESS = "msc.core.address";
    static final String PROPERTY_TOKEN = "msc.token";

    static Optional<CoreAddress> resolve() {
        return resolve(System::getenv, System::getProperty);
    }

    static Optional<CoreAddress> resolve(UnaryOperator<String> env, UnaryOperator<String> properties) {
        String address = env.apply(Limits.ENV_CORE_ADDRESS);
        String token = env.apply(Limits.ENV_TOKEN);
        if (address == null || token == null) {
            address = properties.apply(PROPERTY_ADDRESS);
            token = properties.apply(PROPERTY_TOKEN);
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
}
