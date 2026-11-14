# ADR 0036: Guard an invariant by construction first, by behaviour second, by plain literal rules last

Status: accepted, 2026-11-14. Decided by Claude under ADR 0029. Amends [ADR 0035](0035-guard-tests-are-tripwires.md). Closes #424 and #425.

## Context

ADR 0035 asked each source-scanning guard to never fail on healthy code and to catch every ordinary way of doing the guarded thing. Five audit rounds in a row, including the one right after ADR 0035, found `EulaWritersTest`'s name tracking, `ServerPropertiesAccess`'s path flow tracking and `RepoBypasses` either failing on healthy edits (#424: a same-named local anywhere in the file or method, a renamed parameter) or missing ordinary shapes (#425: a helper's parameter, a wrapped assignment, `java.io.FileWriter` qualified, `p = p.getParent()` in a loop). Each fix added scoping or another pattern, and the next round found the next shape. A data-flow analyser written over source text is a small Java compiler; it cannot meet both demands at once, and every rule added to one side moved the failure to the other.

## Decision

An invariant is protected, in this order:

1. **By construction.** The code is shaped so the wrong thing is impossible or plainly awkward. The thing to guard stays private to the one class that owns it, and other code asks that class questions or calls its one sanctioned method.
2. **By a behaviour test.** The product is run and the effect is looked at, not the source.
3. **By a source-text guard only when it is a plain literal or token rule:** a file name, a string literal or a method name may appear (comments left out, via `SourceText`) only in listed files, each listed with its reason. No variable, parameter or path flow is tracked. Its must-flag and must-not-flag tables stay a few rows long.

Applied:

- **The machine EULA (ADR 0020), the invariant that matters most: installing or using MSC alone never accepts Mojang's EULA.**
  - Construction: `CoreConfig` holds a `MachineEula` object (`MachineEula.forHome(home)`, or `MachineEula.none()` in tests), not a `Path`. The file's path is a private field: `eulaFile()`, `defaultFile(...)` and the static `read/write/applyTo(Path, ...)` are gone. Other classes call `read()`, `applyTo(eulaTxt)`, `revoke()` or the one write, `recordAcceptance(...)`, and print the object to show the user where the file is. msc-bench's copy only reads: its `file(home)` is private, messages use `location(home)`, and it has no write method.
  - Behaviour: `EulaOptInBehaviourTest` (msc-core) runs every registered MCP tool (a reflection check fails when a tool has no row), `server_setup` and `server_start` for all seven platforms with `acceptEula` absent and false, the `eula` command short of a typed yes and `refresh-legacy-install`, against a temporary home and workspace with fake builds, servers and downloads. Afterwards no `eula.json` exists under the home and no `eula.txt` under the workspace says `eula=true`. Then each documented opt-in that runs in-process (`eula accept` with a typed yes, `acceptEula=true` on `server_setup` and on `server_start`) writes it. installMsc's `-Pmsc.acceptEula=true` runs in Gradle, so it is pinned by the literal rule only.
  - Literal rule: `EulaWritersTest` reports each occurrence of `eula.json`, `recordAcceptance` and `recordForMachine` outside comments; the list must equal the documented places (the two `MachineEula` classes, `msc-core/build.gradle.kts`, `EulaCommand`, `EulaPrompts`, `ServerRun`, `ServerSetup`). `recordAcceptance`'s body stays pinned.
- **server.properties (ADR 0033).** `ServerPropertiesAccess` is deleted. `ServerPropertiesAccessTest` reports any string literal that contains `server.properties` in production code outside msc-process's `ServerPropertiesFile`; everything else, messages included, writes `ServerPropertiesFile.NAME`. Exceptions go into the test's `ALLOWED` map with a reason; today there are none, because every module that names the file depends on msc-process. Template sources (`msc-templates/src/main/templates`) are scaffolded projects, not MSC code, and stay outside the scanned set. The readers and writers ADR 0033 replaced stay as must-flag samples: each is reported where its path is built.
- **RepoBypasses (#370)** stays the tripwire it is, with two cheap token additions: fully qualified `java.nio.file.Path.of`, `java.nio.file.Paths.get` and `new java.io.File`, and a reassignment hides the variable only after its new value, so `p = p.getParent()` walks the old value. It is not to grow further.

## Consequences

- A healthy edit cannot trip a guard by naming a variable, splitting an assignment or renaming a parameter: none of the three tracks names. The auditor's 31 healthy edits of rounds 14 and 15 pass all three guards.
- Ordinary violations are caught by the compiler (code that wants the EULA path has nothing to call), by the behaviour test (any tool that writes an acceptance without the owner's yes) or by the literal rules (a new place that names `eula.json` or `server.properties`).
- Known limits, by decision: code that names the file through `ServerPropertiesFile.NAME` and then opens it by hand is not reported (the constant points the reviewer at the one reader and writer); `RepoBypasses` follows one step of a loop, not every iteration, and nothing it cannot see without flow tracking. These are code review's, not a scanner's.
- ADR 0035's rule 1 (never fail on healthy code) and rule 4 (say what to do) stand. Its rule 2 (catch every ordinary way) is now met by the three layers together, not by each scanner alone; its descriptions of how the EULA and server.properties guards follow values are replaced by this ADR.
