#!/bin/bash
# Token-saving tools for Claude sessions on this repository (ADR 0116): graphify (code graph), the ponytail plugin,
# and user-level hooks for sessions that start outside the repository. Cloud only: Jeremiah's PC has its own setup.
#
# Cloud project threads start in the folder above the checkout, so Claude Code never loads this repository's
# .claude/settings.json there and its hooks never run. The cloud environment's setup script runs session-start.sh,
# which calls this script; it puts the same hooks in the user settings, behind user-hook.sh, which does nothing when
# the session already runs in the repository.
#
# Each step is skipped when already done. Failures are reported and skipped, so a missing tool never stops a session.
set -uo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
graphify_version=0.9.120
export PATH="$HOME/.local/bin:$PATH"

# 1. graphify, the AST-only code graph (no API key). PyPI package "graphifyy".
if ! graphify --version 2>/dev/null | grep -q "$graphify_version"; then
  if command -v uv >/dev/null; then
    uv tool install --quiet --force "graphifyy==$graphify_version" || echo "agent-tools: graphify install failed" >&2
  else
    python3 -m pip install --quiet --user "graphifyy==$graphify_version" || echo "agent-tools: graphify install failed" >&2
  fi
fi

# 2. The graph, rebuilt when HEAD moved since the last build (about 25 s). In the background unless asked to wait, so
#    a session does not wait for it; the guard hook stays quiet until graph.json exists.
graph() {
  local head
  head="$(git -C "$repo" rev-parse HEAD 2>/dev/null)" || return 0
  [ "$(cat "$repo/graphify-out/.msc-head" 2>/dev/null)" = "$head" ] && return 0
  (cd "$repo" && graphify update . >/dev/null 2>&1 && echo "$head" > graphify-out/.msc-head) \
    || echo "agent-tools: graphify update failed" >&2
}
if command -v graphify >/dev/null; then
  if [ "${MSC_AGENT_TOOLS_WAIT:-}" = "true" ]; then graph; else (graph &) ; fi
fi

# 3. ponytail, the "smallest complete change" plugin, at user scope so it loads wherever the session starts.
if command -v claude >/dev/null && ! claude plugin list 2>/dev/null | grep -q 'ponytail@ponytail'; then
  { claude plugin marketplace add DietrichGebert/ponytail && claude plugin install ponytail@ponytail --scope user; } \
    >/dev/null 2>&1 || echo "agent-tools: ponytail install failed" >&2
fi

# 4. Serena (the account's Claude plugin, which starts it with uvx). Two settings make its Java server read this
#    Gradle 9.9 build: the project's wrapper instead of JDTLS's own Gradle, and a Gradle home of its own without
#    parallel or configuration-cache, whose annotation-processing init script fails under them. Also a smaller answer
#    cap and no dashboard. The config is created first when Serena never ran here.
serena_config="$HOME/.serena/serena_config.yml"
if [ ! -f "$serena_config" ] && command -v uvx >/dev/null; then
  uvx --quiet --from git+https://github.com/oraios/serena serena init >/dev/null 2>&1 || echo "agent-tools: serena init failed" >&2
fi
if [ -f "$serena_config" ]; then
  serena_gradle="$HOME/.gradle-serena"
  mkdir -p "$serena_gradle"
  for d in caches wrapper; do mkdir -p "$HOME/.gradle/$d"; ln -sfn "$HOME/.gradle/$d" "$serena_gradle/$d"; done
  [ -d "$HOME/.gradle/init.d" ] && mkdir -p "$serena_gradle/init.d" && cp -r "$HOME/.gradle/init.d/." "$serena_gradle/init.d/"
  { grep -s '^org.gradle.java.installations.paths=' "$HOME/.gradle/gradle.properties"
    printf 'org.gradle.parallel=false\norg.gradle.configuration-cache=false\n'; } > "$serena_gradle/gradle.properties"
  serena_jdk="${MSC_JDK_HOME:-/opt/jdk25}"
  "$serena_jdk/bin/java" -version 2>&1 | grep -q 'version "25' || serena_jdk=""
  python3 - "$serena_config" "$serena_gradle" "$serena_jdk" <<'EOF2' || echo "agent-tools: serena config not written" >&2
import re, sys
path, home, jdk = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
java = ("ls_specific_settings:\n  java:\n    gradle_wrapper_enabled: true\n    maven_import_enabled: false\n"
        "    gradle_user_home: %s\n" % home)
if jdk:  # Fabric Loom needs Gradle itself on Java 25; JDTLS would run it on its own Java 21
    java += "    gradle_java_home: %s\n" % jdk
s = re.sub(r"^ls_specific_settings:.*?(?=^\S)", java, s, count=1, flags=re.S | re.M)
s = re.sub(r"^web_dashboard: .*$", "web_dashboard: false", s, count=1, flags=re.M)
s = re.sub(r"^default_max_tool_answer_chars: .*$", "default_max_tool_answer_chars: 20000", s, count=1, flags=re.M)
# No "interactive" mode: it tells the model to ask the user at every doubt, and Jeremiah wants threads to decide.
s = re.sub(r"^base_modes:\n(?:- .*\n)*", "base_modes:\n- editing\n", s, count=1, flags=re.M)
open(path, "w").write(s)
EOF2
fi

# 5. This repository's hooks in the user settings, for sessions that start outside it.
settings="$HOME/.claude/settings.json"
mkdir -p "$HOME/.claude"
python3 - "$settings" "$repo" <<'EOF' || echo "agent-tools: user hooks not written" >&2
import json, os, sys
path, repo = sys.argv[1], sys.argv[2]
try:
    with open(path) as f:
        settings = json.load(f)
except (OSError, ValueError):
    settings = {}
hook = lambda *args: {"type": "command", "timeout": 10,
                      "command": "bash \"%s/.claude/hooks/user-hook.sh\" %s" % (repo, " ".join(args))}
ours = {
    "SessionStart": [{"hooks": [dict(hook("session-start.sh"), timeout=1800)]}],
    "PreToolUse": [
        {"matcher": "Bash|PowerShell|Grep", "hooks": [hook("graphify-guard.sh", "search")]},
        {"matcher": "Read|Glob", "hooks": [hook("graphify-guard.sh", "read")]},
        {"matcher": "Bash", "hooks": [hook("gradle-quiet.sh")]},
    ],
}
hooks = settings.setdefault("hooks", {})
for event, entries in ours.items():
    kept = [e for e in hooks.get(event, []) if "user-hook.sh" not in json.dumps(e)]
    hooks[event] = kept + entries
tmp = path + ".part"
with open(tmp, "w") as f:
    json.dump(settings, f, indent=2)
os.replace(tmp, path)
EOF
exit 0
