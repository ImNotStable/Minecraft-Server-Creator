package io.github.imnotstable.msc.bench.run;

import java.util.Map;
import java.util.Set;

/**
 * The agent's environment. A bench started from inside a Claude Code session (a cloud container, Remote Control, a
 * terminal session that runs Gradle) passes that session's variables on, and a {@code claude -p} that sees them runs
 * as part of it: other tools, another system prompt, the session's own connections. The bench drops them, so every
 * attempt runs as Claude Code started from a plain shell, and keeps what signs Claude Code in (ADR 0044).
 */
final class AgentEnvironment {

    /** Variables of the parent session that stay: they choose the account or the provider, not the session. */
    private static final Set<String> KEEP = Set.of("CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR",
            "CLAUDE_SESSION_INGRESS_TOKEN_FILE");

    private AgentEnvironment() {
    }

    /** Removes the parent session's variables from {@code env}. */
    static void dropParentSession(Map<String, String> env) {
        env.keySet().removeIf(AgentEnvironment::fromParentSession);
    }

    static boolean fromParentSession(String name) {
        if (KEEP.contains(name) || name.startsWith("CLAUDE_CODE_USE_") || name.startsWith("CLAUDE_CODE_SKIP_")) {
            return false;
        }
        return name.equals("CLAUDECODE") || name.startsWith("CLAUDE_") || name.startsWith("CCR_");
    }
}
