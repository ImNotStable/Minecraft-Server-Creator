#!/usr/bin/env bash
# Sets up an Ubuntu (or Debian) machine to run Minecraft Server Creator end to end and to host Claude Code sessions
# through Remote Control: system packages, JDK 25, Claude Code, this repository, msc-core and the msc plugin, and
# optionally a systemd user service that keeps `claude remote-control` running. Safe to run again: every step
# checks first and skips what is already done.
#
#   scripts/setup-ubuntu.sh [--repo DIR] [--workdir DIR] [--service] [--no-apt] [--no-build]
#
#   --repo DIR     where the repository lives or is cloned to (default: this script's checkout, else
#                  ~/Minecraft-Server-Creator)
#   --workdir DIR  folder Remote Control works in (default: the repository)
#   --service      install and start the systemd user service `msc-remote-control` (needs a signed-in `claude`)
#   --no-apt       skip system packages (no sudo needed)
#   --no-build     skip the Gradle build and installMsc
#
# It never accepts the Minecraft EULA: that stays the owner's own step (README, TERMS.md).
set -euo pipefail

REPO_URL="https://github.com/ImNotStable/Minecraft-Server-Creator.git"
JDK="${MSC_JDK_HOME:-/opt/jdk25}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo=""
workdir=""
service=false
apt=true
build=true

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) repo="$2"; shift 2 ;;
    --workdir) workdir="$2"; shift 2 ;;
    --service) service=true; shift ;;
    --no-apt) apt=false; shift ;;
    --no-build) build=false; shift ;;
    -h | --help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done

step() { printf '\n== %s\n' "$*"; }
sudo_() { if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi; }

if [ -z "$repo" ]; then
  if git -C "$script_dir/.." rev-parse --show-toplevel >/dev/null 2>&1 \
      && [ -f "$script_dir/../settings.gradle.kts" ]; then
    repo="$(cd "$script_dir/.." && pwd)"
  else
    repo="$HOME/Minecraft-Server-Creator"
  fi
fi
workdir="${workdir:-$repo}"

if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  case "${ID:-} ${ID_LIKE:-}" in
    *ubuntu* | *debian*) ;;
    *) echo "warning: written for Ubuntu/Debian, this is ${PRETTY_NAME:-unknown}; carrying on" >&2 ;;
  esac
fi

# 1. System packages: git and curl for the rest, tmux to watch sessions, fonts for the headless renderer (Java AWT).
if $apt; then
  step "system packages"
  need=()
  for pkg in git curl ca-certificates tar unzip tmux fontconfig fonts-dejavu-core; do
    dpkg -s "$pkg" >/dev/null 2>&1 || need+=("$pkg")
  done
  if [ ${#need[@]} -gt 0 ]; then
    sudo_ apt-get update -q
    sudo_ env DEBIAN_FRONTEND=noninteractive apt-get install -y -q "${need[@]}"
  else
    echo "all present"
  fi
fi

# 2. JDK 25 (Amazon Corretto), checked against Amazon's published sha256. The build would download one too; a fixed
#    one makes `java` on PATH (the plugin's launcher needs 17+) and the dev servers' Java the same.
step "JDK 25 in $JDK"
if "$JDK/bin/java" -version 2>&1 | grep -q 'version "25'; then
  echo "present"
else
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) echo "no Corretto 25 build for $(uname -m)" >&2; exit 1 ;;
  esac
  name="amazon-corretto-25-$arch-linux-jdk.tar.gz"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 -o "$tmp/$name" "https://corretto.aws/downloads/latest/$name"
  want="$(curl -fsSL --retry 4 "https://corretto.aws/downloads/latest_sha256/$name")"
  echo "$want  $tmp/$name" | sha256sum --check --status || { echo "JDK checksum mismatch" >&2; exit 1; }
  sudo_ rm -rf "$JDK.part"
  sudo_ mkdir -p "$JDK.part"
  sudo_ tar -xzf "$tmp/$name" -C "$JDK.part" --strip-components=1
  sudo_ rm -rf "$JDK"
  sudo_ mv "$JDK.part" "$JDK"
  rm -rf "$tmp"
fi
mkdir -p "$HOME/.gradle"
props="$HOME/.gradle/gradle.properties"
if ! grep -qs "^org.gradle.java.installations.paths=.*$JDK" "$props"; then
  { grep -vs '^org.gradle.java.installations.paths=' "$props" || true; \
    echo "org.gradle.java.installations.paths=$JDK"; } > "$props.part"
  mv "$props.part" "$props"
fi
profile="$HOME/.profile"
if ! grep -qs '# msc: JDK 25' "$profile"; then
  # shellcheck disable=SC2016 # written for the shell to expand later
  printf '\n# msc: JDK 25\nexport JAVA_HOME=%s\nexport PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"\n' "$JDK" >> "$profile"
fi
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$HOME/.local/bin:$PATH"

# 3. Claude Code (native installer, puts `claude` in ~/.local/bin).
step "Claude Code"
if command -v claude >/dev/null 2>&1; then
  claude --version
else
  curl -fsSL https://claude.ai/install.sh | bash
fi

# 4. The repository. It is private: git asks for a GitHub login, or set up `gh auth login` / an SSH key first.
step "repository in $repo"
if [ -f "$repo/settings.gradle.kts" ]; then
  echo "present ($(git -C "$repo" rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout'))"
else
  git clone "$REPO_URL" "$repo"
fi

# 5. msc-core and its agents into ~/.msc/core, then the plugin that starts the newest install.
if $build; then
  step "installMsc (first run downloads Gradle, libraries and Minecraft setups: several minutes)"
  (cd "$repo" && ./gradlew --console=plain :msc-core:installMsc)
fi
step "msc plugin"
if claude plugin list 2>/dev/null | grep -q 'msc@msc'; then
  echo "installed"
elif claude plugin marketplace add "$repo" && claude plugin install msc@msc; then
  echo "installed"
else
  echo "could not add the plugin from the command line; in claude run:"
  echo "  /plugin marketplace add $repo"
  echo "  /plugin install msc@msc"
fi

# 6. Optional: Remote Control as a systemd user service, kept running after logout (linger).
if $service; then
  step "systemd user service msc-remote-control"
  unit_dir="$HOME/.config/systemd/user"
  mkdir -p "$unit_dir"
  cat > "$unit_dir/msc-remote-control.service" <<EOF
[Unit]
Description=Claude Code Remote Control for Minecraft Server Creator
After=network-online.target

[Service]
WorkingDirectory=$workdir
Environment=JAVA_HOME=$JDK
Environment=PATH=$JDK/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$(command -v claude) remote-control --name msc-$(hostname -s)
Restart=on-failure
RestartSec=30

[Install]
WantedBy=default.target
EOF
  sudo_ loginctl enable-linger "$(id -un)" || echo "warning: could not enable linger; the service stops at logout" >&2
  systemctl --user daemon-reload
  systemctl --user enable --now msc-remote-control.service
  systemctl --user --no-pager status msc-remote-control.service | head -5 || true
fi

step "done"
cat <<EOF
Next:
  - Sign in once if you have not: run \`claude\` and follow /login.
  - Remote Control: $(if $service; then echo "running as \`systemctl --user status msc-remote-control\`"; \
      else echo "run \`claude remote-control\` in $workdir (or rerun this script with --service)"; fi).
  - Minecraft EULA: Paper, Purpur, Fabric, Quilt, NeoForge and Forge dev servers need your own yes
    (README, TERMS.md). This script never accepts it.
  - Open a new shell (or \`source ~/.profile\`) for JAVA_HOME and PATH.
EOF
