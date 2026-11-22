# ADR 0067: A size cap on omen's CI cache

Status: accepted, 2026-11-22. Decided by Claude, at the coordinator's request after omen's disk filled.

## Context

On 2026-11-22 omen's root disk reached 100% and Gitea refused pushes ("unable to create temporary object
directory"). 46 GB of it was the act_runner cache server's store (`/home/jere/.cache/actcache`), 27 GB written that
day. act_runner's cache server has no size setting and keeps an entry until it has gone unused for 7 days;
`gradle/actions/setup-gradle` saves about 1.5 GB per job on every push to main, under keys that change with the build
files, so a busy day adds tens of gigabytes. Jeremiah grew the root volume from 98 to 148 GB (`lvextend`, 85 GB still
free in the volume group), which buys time but not a bound.

## Decision

`scripts/ci/actcache-prune.sh`, run hourly by a systemd timer on omen (`actcache-prune.timer`), deletes cache entries
older than 3 days, then the oldest entries until the store is under 30 GB, and removes empty folders. A deleted entry
is a cache miss for the next job, which rebuilds and saves a fresh one; jobs never fail because of it. Entries are
removed as files only: the cache server's index (`bolt.db`) may still list them, which the actions treat as a miss.

The limits are environment variables (`MAX_GB`, `MAX_AGE_DAYS`, `ACTCACHE_DIR`) in the service unit.

## Not done

- Caching less in CI (only main writes already; `setup-gradle` cleans the Gradle home before saving by default).
- An external cache server with its own eviction: more moving parts than a timer for one runner.
