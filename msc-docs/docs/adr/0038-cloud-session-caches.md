# ADR 0038: Cloud sessions start ready to build, and server jars are downloaded once per machine

Status: accepted, 2026-11-14. Decided by Claude under Jeremiah's hand-over of build decisions (backlog item "cache
server jars and Gradle dependencies to make cloud builds faster and cheaper").

## Context

With Jeremiah's PC out of use, every thread works in a fresh Claude Code cloud container. Each one rediscovered the
same setup before its first build: download a JDK 25 (the image has 21), tell Gradle where it is, and work around
Maven Central answering the cloud's shared address with HTTP 429 (memory notes and briefs carried an init script by
hand). Then the first `./gradlew build` downloaded every dependency and ran ModDevGradle's and ForgeGradle's Minecraft
decompile and recompile: about 5 minutes measured on 2026-11-14, before any test ran. That cost wall time and, worse,
tokens: threads spent turns on 429s and JDK paths.

Server jars had a second, smaller waste: msc-core caches downloads in each workspace's `.msc/cache`, so every new
workspace (a second project, each bench task, each e2e run) downloaded the same Paper jar again.

GitHub Actions is billing-blocked (ADR 0025 addendum), so CI caching does not help now.

## Decision

1. **A SessionStart hook prepares cloud sessions.** `.claude/settings.json` runs `.claude/hooks/session-start.sh`,
   which does nothing unless `CLAUDE_CODE_REMOTE=true` (so the PC and other machines are untouched). It installs
   Amazon Corretto 25 into `/opt/jdk25` (checked against Amazon's published sha256), adds it to
   `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`, copies
   `.claude/hooks/maven-central-mirror.gradle` into `~/.gradle/init.d`, and exports `JAVA_HOME` for the session.
2. **The mirror goes first and replaces Maven Central.** The init script puts Google's Maven Central mirror first in
   plugin management and dependency resolution management and rewrites every repository on `repo.maven.apache.org`
   to it. Rewriting alone was not enough: the Plugin Portal redirects artifacts it does not host (Loom's
   `org.cadixdev:lorenz`) to Maven Central itself. It lives outside the repository's build files because only the
   cloud needs it.
3. **The hook warms Gradle once per change of the build.** It runs `./gradlew testClasses` (every dependency, the
   Gradle distribution, Loom's and ModDevGradle's/ForgeGradle's Minecraft setup, and the build cache), then records
   the sha256 of the hook, `gradle/`, `build-logic/`, every `*.gradle.kts` and `gradle.properties` in
   `~/.gradle/msc-session-start.sha256`. While they match, the hook takes well under a second. A failed warm-up fails
   the hook and leaves no hash, so the next session tries again. The hook is synchronous: a thread never races its
   own first build against the warm-up. The environment keeps the container state after the hook, so a cached
   container skips all of it.
4. **Checksummed server jars go to a machine-wide store.** `DownloadStore` keeps Paper (sha256) and Purpur (md5) jars
   as `~/.msc/downloads/<algorithm>-<hex>.jar`. `Downloads.fetch` asks the store first and copies a jar whose bytes
   still match its name, then reports it as cached; otherwise it downloads as before and keeps a copy (best effort:
   a store that cannot be written only costs a download next time). `MSC_DOWNLOAD_STORE` moves the store, `none`
   turns it off; `CoreConfig.forWorkspace` (tests) has none. Downloads without a checksum (Fabric's launcher, Fabric
   API, loader installers) stay per workspace: without a checksum a shared file could not be trusted.

## Consequences

- A cloud thread's first build starts warm; the cold cost (about 5 minutes) is paid by the container setup, not by
  the thread, and only again when the build files change.
- Threads no longer need the JDK and mirror steps in their briefs; the memory note on the build environment points
  here.
- The store grows by one jar per Paper or Purpur build used (about 50 MB each); nothing prunes it yet. Deleting the
  folder is always safe.
- Paperclip's own downloads at first start (the vanilla jar and libraries in the server folder) are still per server
  folder; they belong to Paper.
