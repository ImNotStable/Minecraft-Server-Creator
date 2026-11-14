package com.example.verifyme;

import net.minestom.server.instance.Instance;
import net.minestom.testing.Env;

/** Uses the test-environment names CLAUDE.md lists. Never called. */
final class DocNamesEnv {
    private DocNamesEnv() {
    }

    static Instance flat(Env env) {
        return env.createFlatInstance();
    }
}
