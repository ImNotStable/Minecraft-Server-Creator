# ADR 0035: Source-scanning guard tests are tripwires for ordinary changes

Status: accepted, 2026-11-14. Decided by Claude under ADR 0029. Closes #409 and #410; part of #411.

## Context

msc-core's `repoChecks` task runs tests that read source text to keep a rule: `RepoInputsTest` (a test reads repository files only through its repoInputs sets, #370), `EulaWritersTest` (only the documented opt-ins write `~/.msc/eula.json`, ADR 0020) and `ServerPropertiesAccessTest` (only `ServerPropertiesFile` opens `server.properties`, ADR 0033). Four audit rounds in a row found them leaky or over-eager, and each fix added one more pattern:

- `ServerPropertiesAccessTest` failed on healthy code: any file with a text read and any `indexOf('=')` or `indexOf(':')` was reported, and eleven production files that split block states and ids were one edit away from failing the build (#409). It also kept an allowlist of every `java.util.Properties` user, so an unrelated `Properties` broke the build.
- The same test missed the old `PackProperties.set` shape it was written to replace: a key=value rewrite of a path passed in by the caller (#410).
- `EulaWritersTest` missed msc-core's own standard writer, `AtomicFile` and `OutputFile`, and copies and moves (#410).
- `RepoBypasses` missed a working-folder path kept in a variable and walked up, and `Path.of("build").toAbsolutePath().getParent().getParent()` (#410).

## Decision

A guard test that scans source text is a tripwire for ordinary, honest changes, not a proof against deliberate evasion. Each one:

1. **Never fails on healthy code.** It keys on the thing it guards (the file, the path, the call), not on generic string handling. A false alarm costs every later contributor, so a check that cannot tell is narrowed, not widened.
2. **Catches every ordinary way of doing the guarded thing** with the helpers and JDK calls this codebase uses: `Files`, the `java.io` readers, writers and streams, `java.util.Properties` (through the stream it loads or stores), msc-core's `AtomicFile` and `OutputFile` (matched by simple name, wherever the class lives), copies and moves, Kotlin's file calls in build scripts, a value held in a local, field, parameter or method result, and a helper in another class that is handed the value.
3. **Carries its reach in its own test:** a table of samples it must report and a table it must not, including the real past code it was written against where there is any (`ServerPropertiesAccessTest` keeps the readers and writers ADR 0033 replaced as test resources, as they were at 8f49c2a). A regression in either direction fails there first.
4. **Says in its failure message what to do:** the rule, the one sanctioned way, and what to change when an entry is a false alarm.

Out of scope by decision, so later audits do not refile them: reflection, method handles, names or paths built from pieces at run time (`"server" + ".properties"`, `"eula" + ".json"`), a value copied through collections, arrays or another object's instance methods, and any other deliberate evasion. Code review catches those; a text scan cannot without becoming a Java compiler.

How the three guards follow this:

- `ServerPropertiesAccessTest` (detector `ServerPropertiesAccess`) follows the file: a path is `server.properties` when it is built from a literal naming it or from `ServerPropertiesFile.NAME`, or held in a local, field, parameter or method result that provably holds such a path; parameters are followed across the scanned classes to a fixed point. Opening such a path as text or a stream is reported. Not reported: `ServerPropertiesFile`, existence and permission checks, and byte-for-byte snapshots (`Files.readAllBytes`, `Files.write` of a `byte[]`, copy, move). The allowlist of `Properties` users is gone.
- `EulaWritersTest` counts writes through `AtomicFile` and `OutputFile` (`write`, `replace`, `copy`), `Files.copy` and `Files.move` (source and target) and Kotlin `copyTo` and `renameTo` as writes of the file they name.
- `RepoBypasses` walks every path built from a literal (`Path.of`, `Paths.get`, `new File`), step by step: `getParent`, `resolveSibling` and `resolve("..")` that climb above the folder the literal names are reported, chained or on a variable that holds the path, with the Java scoping it already used for `RepoFiles` variables (#384).

The shared masking and bracket helpers live in `SourceText` in msc-core's tests.

## Consequences

- Adding an unrelated `java.util.Properties`, a text read or a key=value split no longer touches these tests.
- A new ordinary shape that slips through is a missing row in a must-flag table: the fix adds the row and the smallest change that makes it pass, without breaking a must-not-flag row.
- The server.properties detector follows static calls and constructors, not instance calls on another class's object; a path handed through one is out of reach until such code exists and the table grows.
- `DecisionsIndexTest` (also in `repoChecks`) applies the same idea to the docs: every ADR file needs a row or mention in plan.md, after ADR 0017 had none (#411).
