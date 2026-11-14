#!/bin/bash
# Prepares a Claude Code cloud session for this repository (ADR 0038): JDK 25, the Maven Central mirror, and a warm
# Gradle cache. Does nothing outside the cloud (CLAUDE_CODE_REMOTE != true), so it is harmless on the PC.
#
# Each step is skipped when already done, so a cached container starts in about a second. The Gradle warm-up runs
# only when a file that decides the dependencies changed since it last succeeded.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"
jdk="${MSC_JDK_HOME:-/opt/jdk25}"
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"

# 1. JDK 25 (Corretto), checked against Amazon's published sha256.
if ! "$jdk/bin/java" -version 2>&1 | grep -q 'version "25'; then
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) echo "session-start: no Corretto 25 build for $(uname -m)" >&2; exit 1 ;;
  esac
  name="amazon-corretto-25-$arch-linux-jdk.tar.gz"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 -o "$tmp/$name" "https://corretto.aws/downloads/latest/$name"
  want="$(curl -fsSL --retry 4 "https://corretto.aws/downloads/latest_sha256/$name")"
  echo "$want  $tmp/$name" | sha256sum --check --status || { echo "session-start: JDK checksum mismatch" >&2; exit 1; }
  rm -rf "$jdk.part" && mkdir -p "$jdk.part"
  tar -xzf "$tmp/$name" -C "$jdk.part" --strip-components=1
  rm -rf "$jdk" && mv "$jdk.part" "$jdk" && rm -rf "$tmp"
fi

# 2. Gradle finds that JDK for the daemon and the toolchains (gradle/gradle-daemon-jvm.properties asks for 25).
mkdir -p "$gradle_home/init.d"
props="$gradle_home/gradle.properties"
if ! grep -qs "^org.gradle.java.installations.paths=.*$jdk" "$props"; then
  grep -vs '^org.gradle.java.installations.paths=' "$props" > "$props.part" || true
  echo "org.gradle.java.installations.paths=$jdk" >> "$props.part"
  mv "$props.part" "$props"
fi

# 3. Maven Central answers the cloud's shared address with 429s; Google's mirror of it does not.
cp .claude/hooks/maven-central-mirror.gradle "$gradle_home/init.d/msc-maven-central-mirror.gradle"

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export JAVA_HOME=$jdk" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=$jdk/bin:\$PATH" >> "$CLAUDE_ENV_FILE"
fi

# 4. Warm Gradle: every dependency, the Gradle distribution, Loom's and ModDevGradle's/ForgeGradle's Minecraft setup
#    (minutes on a cold machine) and the build cache. Skipped while the files that decide them are unchanged.
hashes="$gradle_home/msc-session-start.sha256"
inputs() {
  # Paths relative to the repository, sorted, so the hash file is the same wherever the checkout is.
  find .claude/hooks gradle build-logic -type f -not -path '*/build/*' -not -path '*/.gradle/*'
  find . -maxdepth 2 -name '*.gradle.kts' -not -path './build-logic/*'
  echo gradle.properties
}
if ! sha256sum --check --status "$hashes" 2>/dev/null; then
  rm -f "$hashes"
  JAVA_HOME="$jdk" ./gradlew --quiet --console=plain testClasses
  inputs | sort | xargs sha256sum > "$hashes"
fi
