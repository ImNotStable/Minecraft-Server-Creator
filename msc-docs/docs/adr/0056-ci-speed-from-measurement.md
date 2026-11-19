# ADR 0056: CI speed comes from measurement, not a checklist

Status: accepted, 2026-11-19, decided by Claude at Jeremiah's request.

## Context

Jeremiah handed over two long lists of CI and Gradle optimizations (runner images, cache servers, Gradle flags, JVM
flags, Isolated Projects, test sharding and more) and asked that they be followed. Both were checked against this repo
and omen's runner before anything changed, and against a baseline.

Baseline, the Ubuntu `Build and test` job on omen (one `act_runner`, capacity 3, 6 CPUs per job):

- About 10 minutes on a PR (tasks 892 to 906: 491 to 627 s). Checkout, JDK setup and the Gradle cache restore take
  9 s together; configuration takes about 8 s; every module but msc-core is up to date or from cache in under a minute.
- `msc-core:test` is the other 9 minutes: one test JVM running 133 classes one at a time. Locally (16 threads) it took
  7m46s; 455 s of that is test time, and `PackLifecycleOrderTest` alone is 217 s (250 cases of about a second each,
  mostly waiting on the processes it starts).
- The e2e job (main, nightly, `full-ci`) takes 2 to 2.5 minutes.

So the setup steps the lists aim at cost seconds, and almost all of the time is one test task.

## Decision

1. **msc-core's tests run in parallel.** `maxParallelForks` is half the cores, 1 to 4 (3 on the CI container).
   JUnit parallel execution is on, but only classes marked `@Execution(CONCURRENT)` use it; today that is
   `PackLifecycleOrderTest`, whose cases share nothing (own temp dir, core and processes). Locally 7m46s became
   1m54s, green on both runs. Other modules keep one fork: their tests take seconds.
2. **Everything else on the lists is left as it is**, item by item below. An item comes back when a measurement
   shows it would save real time.

| Item from the lists | Decision |
|---|---|
| Gradle 9.9.1 wrapper, `-bin` distribution | Already so. |
| `distributionSha256Sum` | Skipped: `validateDistributionUrl` is on, and the sum must be bumped by hand on every upgrade. |
| Custom runner image with Temurin 25 and Gradle | Skipped: JDK setup is about 2 s from the `act-toolcache` volume; an image is one more thing to rebuild. |
| act_runner cache server, persistent dir, toolcache volume | Already so (`cache.enabled`, `act-toolcache` volume, actcache under jere's XDG cache). |
| Capacity and CPU limits | Already so: capacity 3, `--cpus=6` per job. |
| `--no-daemon`, `vfs.watch` | Skipped: one Gradle call per step, and the container ends with the job. |
| Move workflows to `.gitea/workflows`, Gitea release API | Gitea reads `.github/workflows`; release.yml already uses the Gitea API. |
| setup-gradle (instead of setup-java caching), `cache-provider: basic` | Already setup-gradle; its restore takes under 2 s on act_runner. |
| `GRADLE_RO_DEP_CACHE`, remote build cache node | Skipped: PRs already restore main's build cache (51 of 105 tasks from cache in task 901). |
| Loom, ModDevGradle and NeoForm caches | Already restored by setup-gradle (the logs show "Used cache of" for every NeoForm step). |
| run-paper jar cache, `runServer` in CI | Not applicable: CI starts no Paper server (no EULA). |
| `ADOPTIUM` vendor pin, no toolchain auto-download | Skipped: setup-java installs Temurin 25, so the toolchain resolves locally; a vendor pin would break other JDK 25 installs on developer machines. |
| Compact object headers, AOT cache, GC and JIT flags | Skipped: daemon heap is not a bottleneck, and startup is seconds of a 10-minute job. |
| `configuration-cache.parallel`, read-only CC on CI | Skipped: configuration takes about 8 s. |
| Isolated Projects | Skipped: Loom and ModDevGradle do not support it, and configuration is not the cost. |
| Configuration cache in the templates | Skipped: templates are user projects; their mod plugins are not all CC-safe. |
| Cacheable `packTemplates`, `repoFilesSource`, `checkToolDocs`; reproducible archives | Skipped: each runs in under a second. |
| Repository content filters, local Maven proxy, dependency locking | Skipped: dependencies come from the restored cache; no 429s seen on omen. Versions are already pinned. |
| Renovate in place of Dependabot | Out of scope for speed. |
| `check` instead of `build`, dropping distTar | Skipped: assemble takes a few seconds, and `build` also catches packaging breaks. |
| Path filters, module-scoped PR checks | Skipped: repoChecks read docs and every module, and a docs-only change is already about 45 s from cache. |
| Sharding verifyTemplates, merging the e2e steps | Skipped: the e2e job is 2 to 2.5 minutes and runs off the PR path. |
| Shallow checkout, concurrency with cancel-in-progress, failure-only report uploads | Already so. |
| Scheduled `--profile` runs, nightly `--warning-mode=all` | Skipped: the job log's task timestamps were enough to find the cost. |

## Consequences

- A test class in msc-core now shares the machine with others. A test that needs a fixed port aborts (not fails) when
  another fork holds it, as `CoreHarness.holdDefaultPort` already does for a developer's own server.
- A class marked concurrent must keep its cases independent; a new case that shares state needs its own class or a
  `@ResourceLock`.
- The next slowest classes (`LifecycleToolsTest` 56 s, `SideEffectStepsTest` 35 s) are the next candidates for the
  same marking if the job grows again.
