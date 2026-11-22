#!/usr/bin/env bash
# Rebuilds the graphify graphs of msc-stdlib that the templates ship in libs/stdlib/graphify-out/ (ADR 0066).
# Run after changing msc-stdlib or an adapter; msc-templates' StdlibGraphTest fails until you do.
# Needs graphify on PATH (local AST extraction, no API key).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/msc-templates/src/main/stdlib-graphs"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
platforms="paper minestom fabric velocity neoforge forge"
# The sources jars are what the templates ship (the mod loaders' include the shared msc-stdlib-vanilla code).
tasks=":msc-stdlib:stdlibRepo"
for platform in $platforms; do tasks="$tasks :msc-stdlib-$platform:stdlibRepo"; done
(cd "$root" && ./gradlew -q $tasks)
for platform in $platforms; do
  dir="$work/$platform"
  mkdir -p "$dir/src"
  # The same layout the templates ship: libs/stdlib/src/<module>/<package path>.
  for module in msc-stdlib "msc-stdlib-$platform"; do
    mkdir -p "$dir/src/$module"
    unzip -q -o "$(ls "$root/$module"/build/repo/io/github/imnotstable/msc/"$module"/*/*-sources.jar)" '*.java' -d "$dir/src/$module"
  done
  (cd "$dir" && graphify update . > /dev/null)
  mkdir -p "$out/$platform"
  cp "$dir/graphify-out/graph.json" "$out/$platform/graph.json"
  # Drop the date line so an unchanged library gives an unchanged file.
  grep -v '^# Graph Report' "$dir/graphify-out/GRAPH_REPORT.md" > "$out/$platform/GRAPH_REPORT.md"
  # What StdlibGraphTest compares with the sources a template ships, to catch a stale graph.
  python3 - "$dir/src" > "$out/$platform/sources.sha256" <<'PY'
import hashlib, os, sys
root = sys.argv[1]
h = hashlib.sha256()
paths = sorted(os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/")
               for d, _, fs in os.walk(root) for f in fs if f.endswith(".java"))
for p in paths:
    h.update((p + "\n").encode())
    h.update(open(os.path.join(root, p), "rb").read())
print(h.hexdigest())
PY
  echo "$platform: $(python3 -c "import json,sys; g=json.load(open('$out/$platform/graph.json')); print(len(g['nodes']), 'nodes')")"
done
