#!/usr/bin/env bash
# Caps the Gitea act_runner cache (actions/cache entries) on omen (ADR 0067). act_runner's cache server has no size
# limit and keeps unused entries for 7 days; setup-gradle saves ~1.5 GB per job on every push to main, so the cache
# filled the disk on 2026-11-22. This deletes entries older than MAX_AGE_DAYS, then the oldest until the cache is under
# MAX_GB. A deleted entry is a cache miss for the next job (it rebuilds and saves a fresh one), never a failure.
set -euo pipefail
dir="${ACTCACHE_DIR:-/home/jere/.cache/actcache/cache}"
max_gb="${MAX_GB:-30}"
max_age_days="${MAX_AGE_DAYS:-3}"
[ -d "$dir" ] || exit 0
find "$dir" -type f -mtime +"$max_age_days" -print -delete | sed 's/^/aged out: /'
limit=$((max_gb * 1024 * 1024 * 1024))
total=$(find "$dir" -type f -printf '%s\n' | awk '{s+=$1} END {print s+0}')
if [ "$total" -gt "$limit" ]; then
  # Oldest first until under the limit.
  find "$dir" -type f -printf '%T@ %s %p\n' | sort -n | while read -r _ size path; do
    [ "$total" -le "$limit" ] && break
    rm -f -- "$path" && echo "over ${max_gb} GB: $path"
    total=$((total - size))
  done
fi
find "$dir" -mindepth 1 -type d -empty -delete
echo "actcache: $(du -sh "$dir" | cut -f1)"
